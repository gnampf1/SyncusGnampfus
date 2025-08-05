package de.gnampf.syncusgnampfus.hanseatic;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.rmi.RemoteException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Hashtable;
import javax.annotation.Resource;

import org.htmlunit.CookieManager;
import org.htmlunit.FailingHttpStatusCodeException;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.ProxyConfig;
import org.htmlunit.WebClient;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlPage;
import org.json.JSONArray;
import org.json.JSONObject;

import de.gnampf.syncusgnampfus.SyncusGnampfusSynchronizeJob;
import de.gnampf.syncusgnampfus.KeyValue;
import de.gnampf.syncusgnampfus.SyncusGnampfusSynchronizeJobKontoauszug;
import de.gnampf.syncusgnampfus.WebResult;
import de.willuhn.datasource.rmi.DBIterator;
import de.willuhn.jameica.hbci.Settings;
import de.willuhn.jameica.hbci.SynchronizeOptions;
import de.willuhn.jameica.hbci.messaging.ImportMessage;
import de.willuhn.jameica.hbci.messaging.ObjectChangedMessage;
import de.willuhn.jameica.hbci.messaging.ObjectDeletedMessage;
import de.willuhn.jameica.hbci.messaging.SaldoMessage;
import de.willuhn.jameica.hbci.rmi.Konto;
import de.willuhn.jameica.hbci.rmi.Umsatz;
import de.willuhn.jameica.hbci.synchronize.SynchronizeBackend;
import de.willuhn.jameica.hbci.synchronize.jobs.SynchronizeJobKontoauszug;
import de.willuhn.jameica.security.Wallet;
import de.willuhn.jameica.system.Application;
import de.willuhn.logging.Level;
import de.willuhn.logging.Logger;
import de.willuhn.util.ApplicationException;
import de.willuhn.util.ProgressMonitor;

// Spezifisch, eigentliche Implementierung

public class HanseaticSynchronizeJobKontoauszug extends SyncusGnampfusSynchronizeJobKontoauszug implements SyncusGnampfusSynchronizeJob 
{
	@Resource
	private HanseaticSynchronizeBackend backend = null;

	@Override
	protected SynchronizeBackend getBackend() { return backend; }

	@Override
	public boolean process(Konto konto, boolean fetchSaldo, boolean fetchUmsatz, DBIterator<Umsatz> umsaetze, String user, String passwort) throws Exception
	{
		log(Level.DEBUG, "besorge Basic-Credentials");
		var response = doRequest("https://meine.hanseaticbank.de/de/register/sign-in", HttpMethod.GET, null, null, null);
		var textContent = response.getContent().replace("\n", "").replace("\r", "");
		var basicAuth = textContent.replaceAll(".*BASIC_AUTH:\"Basic ([^\"]+)\".*", "$1");
		var baseUrl = textContent.replaceAll(".*NORTHLAYER_BASE_URL:\"([^\"]+)\".*", "$1");
		log(Level.DEBUG, "Basic-Auth-Token ist " + basicAuth + ", BaseUrl: " + baseUrl);
		permanentHeaders.clear();
		permanentHeaders.add(new KeyValue<>("authorization", "Basic " + basicAuth));

		response = doRequest(baseUrl + "/token", HttpMethod.POST, null, "application/x-www-form-urlencoded; charset=UTF-8", "grant_type=hbSCACustomPassword&password=" + URLEncoder.encode(passwort, "UTF-8") + "&loginId=" + URLEncoder.encode(user, "UTF-8"));
		if (response.getHttpStatus() != 200)
		{
			log(Level.DEBUG, "Response: " + response.getContent());
			throw new ApplicationException("Login fehlgeschlagen, Fehlercode " + response.getHttpStatus());
		}

		JSONObject json = response.getJSONObject();
		String token = json.optString("access_token");
		if (token == null || token.isBlank())
		{
			token = json.optString("id_token");
			if (token == null || token.isBlank())
			{
				log(Level.INFO, "Response: " + response.getContent());
				throw new ApplicationException("Weder ein Acess- noch ein ID-Token erhalten");
			}

			log(Level.INFO, "Anmeldung muss in der Hanseatic-App bestätigt werden!");

			permanentHeaders.clear();
			permanentHeaders.add(new KeyValue<>("authorization", "Bearer " + token));

			token = token.substring(token.indexOf(".") + 1);
			token = token.substring(0, token.lastIndexOf("."));
			var js = new String(de.willuhn.util.Base64.decode(token));
			JSONObject idt = new JSONObject(js);
			var scaId = idt.optString("sca_id");
			
			while (true) 
			{
				response = doRequest(baseUrl + "/openScaBroker/1.0/customer/" +  URLEncoder.encode(user, "UTF-8") + "/status/" + scaId, HttpMethod.GET, null, null, null);
				var status = response.getJSONObject().optString("status");
				if ("complete".equals(status))
				{
					break;
				}
				else if ("open".equals(status) || "accepted".equals(status))
				{
					log(Level.INFO, "Status ist " + status);
					Thread.sleep(5000);
				}
				else
				{
					throw new ApplicationException("Unbekannte Status " + status + ", Freigabe in der App verweigert?");
				}
			}
			
			response = doRequest(baseUrl + "/token", HttpMethod.POST, null, "application/x-www-form-urlencoded; charset=UTF-8", "grant_type=hbSCACustomPassword&password=" + URLEncoder.encode(passwort, "UTF-8") + "&loginId=" + URLEncoder.encode(user, "UTF-8"));
			if (response.getHttpStatus() != 200)
			{
				log(Level.DEBUG, "Response: " + response.getContent());
				throw new ApplicationException("Login fehlgeschlagen, Fehlercode " + response.getHttpStatus());
			}

			json = response.getJSONObject();
			token = json.optString("access_token");
			if (token == null || token.isBlank())
			{
				log(Level.INFO, "Response: " + response.getContent());

				throw new ApplicationException("Login fehlgeschlagen, kein AccessToken");
			}
		}

		var tokenType = json.optString("token_type");
		if (tokenType == null)
		{
			tokenType ="Bearer";
		}
		permanentHeaders.clear();
		permanentHeaders.add(new KeyValue<>("authorization", tokenType + " " + token));

		log(Level.INFO, "Login f\u00FCr " + user + " war erfolgreich");
		monitor.setPercentComplete(5); 

		response = doRequest(baseUrl + "/pairingSecureApp/1.0/activateCreditCards", HttpMethod.PUT, null, null, null);
		if (response.getHttpStatus() != 200)
		{
			log(Level.DEBUG, "Response: " + response.getContent());
			throw new ApplicationException("ActivateCreditCards fehlgeschlagen");
		}

		response = doRequest(baseUrl + "/customerportal/1.0/accounts?skipCache=false", HttpMethod.GET, null, null, null);
		if (response.getHttpStatus() != 200)
		{
			log(Level.DEBUG, "Response: " + response.getContent());
			throw new ApplicationException("Accountabfrage fehlgeschlagen");
		}
		Boolean found = false;
		var accountsArray = response.getJSONArray();
		Double saldo = konto.getSaldo();
		for (var obj : accountsArray) 
		{
			JSONObject account = (JSONObject)obj;
			if (user.equals(account.optString("customerNumber")) && konto.getKontonummer().equals(account.optString("accountNumber")))
			{
				saldo = account.optDouble("saldo");
				found = true;

				if (fetchSaldo)
				{
					konto.setSaldo(saldo);
					konto.setSaldoAvailable(account.optDouble("availableAmount"));
					konto.store();
					Application.getMessagingFactory().sendMessage(new SaldoMessage(konto));
				}
				break;
			}
		};

		if (!found)
		{
			log(Level.DEBUG, "Response: " + response.getContent());
			throw new ApplicationException("Konto nicht gefunden");
		}
		monitor.setPercentComplete(10);

		if (fetchUmsatz)
		{
			var arbeitsSaldo = saldo;
			var gotDuplicate = false;
			var more = false;
			var moreWithSCA = false;
			var scaDone = false;
			var pageNo = 0;
			var neueUmsaetze = new ArrayList<Umsatz>();
			var duplikate = new ArrayList<Umsatz>();
			var dateFormat = new SimpleDateFormat("dd.MM.yyyy");
			do
			{
				pageNo++;

				response = doRequest(baseUrl + "/transaction/1.0/transactionsEnriched/" + konto.getKontonummer() + "?page=" + pageNo + "&withReservations=true&withEnrichments=true", HttpMethod.GET, null, null, null);
				if (response.getHttpStatus() != 200)
				{
					log(Level.DEBUG, "Response: " + response.getContent());
					throw new ApplicationException("Umsatzabruf fehlgeschlagen");
				}
				json = response.getJSONObject();
				JSONArray transactionsArray = json.optJSONArray("transactions");
				more = json.optBoolean("more", false);
				moreWithSCA = json.optBoolean("moreWithSCA", false);

				log(Level.INFO, "lese Seite "+pageNo);
				monitor.setPercentComplete(monitor.getPercentComplete() + 1);

				for (var obj : transactionsArray)
				{
					var transaction = (JSONObject)obj;

					var betrag = transaction.getDouble("amount");
					var newUmsatz = (Umsatz) Settings.getDBService().createObject(Umsatz.class,null);
					newUmsatz.setKonto(konto);
					newUmsatz.setBetrag(betrag);
					newUmsatz.setDatum(dateFormat.parse(transaction.getString("transactionDate")));
					newUmsatz.setGegenkontoBLZ(transaction.optString("recipientBic"));
					newUmsatz.setGegenkontoName(transaction.optString("recipientName"));
					newUmsatz.setGegenkontoNummer(transaction.optString("recipientIban"));
					newUmsatz.setValuta(dateFormat.parse(transaction.getString("date") + " 00:00"));
					newUmsatz.setZweck(transaction.optString("description"));
					newUmsatz.setArt(transaction.optString("creditDebitKey"));

					ArrayList<String> details = new ArrayList<String>();

					var website = transaction.getJSONObject("merchantData").getString("website");
					if (website != null && !website.isBlank()) details.add("Web: " + website);
					var transactionTime = transaction.optString("transactionTime");
					if (transactionTime != null && !transactionTime.isBlank()) details.add("Zeit: " + transactionTime);
					var conversionRate = transaction.optString("conversionRate");
					if (conversionRate != null && !conversionRate.isBlank()) details.add("Wechselkurs: " + conversionRate);

					newUmsatz.setCreditorId(transaction.optString("creditorID"));
					newUmsatz.setMandateId(transaction.optString("mandateReference"));
					newUmsatz.setCustomerRef(transaction.optString("transactionId"));

					for (int j = 0; j < details.size(); j++)
					{
						if (details.get(j).length() > 35) 
						{
							details.add(j + 1, details.get(j).substring(35));
							details.set(j, details.get(j).substring(0,35));
						}
					}
					newUmsatz.setWeitereVerwendungszwecke(details.toArray(new String[0]));
					if (transaction.optBoolean("booked", true) == false)
					{
						newUmsatz.setFlags(Umsatz.FLAG_NOTBOOKED);
					}
					else 
					{
						newUmsatz.setSaldo(arbeitsSaldo); // Zwischensaldo
						arbeitsSaldo -= betrag;
					}

					var duplicate = getDuplicateByCompare(newUmsatz); 
					if (duplicate != null)
					{		                		
						gotDuplicate = true;
						if (duplicate.hasFlag(Umsatz.FLAG_NOTBOOKED))
						{
							duplicate.setFlags(newUmsatz.getFlags());
							duplicate.setSaldo(newUmsatz.getSaldo());
							duplicate.setWeitereVerwendungszwecke(newUmsatz.getWeitereVerwendungszwecke());

							duplicate.setGegenkontoBLZ(newUmsatz.getGegenkontoBLZ());
							duplicate.setGegenkontoName(newUmsatz.getGegenkontoName());
							duplicate.setGegenkontoNummer(newUmsatz.getGegenkontoNummer());
							duplicate.setValuta(newUmsatz.getValuta());
							duplicate.setArt(newUmsatz.getArt());
							duplicate.setCreditorId(newUmsatz.getCreditorId());
							duplicate.setMandateId(newUmsatz.getMandateId());
							duplicate.setCustomerRef(newUmsatz.getCustomerRef());
							duplicate.store();
							duplikate.add(duplicate);
							Application.getMessagingFactory().sendMessage(new ObjectChangedMessage(duplicate));
						}
					}
					else
					{
						neueUmsaetze.add(newUmsatz);
					}
				}

				if (!gotDuplicate && !more && moreWithSCA && !scaDone)
				{
					log(Level.INFO, "Ben\u00f6tige zweiten Faktor f\u00FCr weitere Ums\u00e4tze...");

					String status = null;
					var resultCode = 0;

					do 
					{
						response = doRequest(baseUrl + "/scaBroker/1.0/session", HttpMethod.POST, null, "application/json", "{\"initiator\":\"ton-sca-fe\",\"lang\":\"de\",\"session\":\"" + token + "\"}");
						if (response.getHttpStatus() != 200)
						{
							log(Level.DEBUG, "Response: " + response.getContent());
							throw new ApplicationException("2FA-Abruf fehlgeschlagen");
						}
						json = response.getJSONObject();	    	                
						var type = json.optString("scaType", "Unbekannt");
						var uniqueId = json.optString("scaUniqueId");

						var requestText = "Bitte geben Sie die TAN ein, das sie per " + type + " erhalten haben.";
						if (status != null)
						{
							requestText += "\nDer letzte Code wurde nicht akzeptiert, Status " + resultCode + " / " + status;
						}

						var sca = Application.getCallback().askUser(requestText, "TAN:");
						if (sca == null || sca.isBlank())
						{
							more = false;
							moreWithSCA = false;
							log(Level.WARN, "TAN-Eingabe abgebrochen");
							break;
						}
						else
						{
							response = doRequest(baseUrl + "/scaBroker/1.0/status/" + uniqueId, HttpMethod.PUT, null, "application/json", "{\"otp\":\"" + sca + "\"}");
							if (response.getHttpStatus() != 200)
							{
								log(Level.DEBUG, "Response: " + response.getContent());
								throw new ApplicationException("2FA-Validierung fehlgeschlagen");
							}
							json = response.getJSONObject();
							status = json.optString("status");
							resultCode = json.optInt("resultCode");

							if (!"complete".equals(status) || resultCode != 200)
							{
								log(Level.DEBUG, "Response: " + response.getContent());
								throw new ApplicationException("2FA-Validierung fehlgeschlagen, Status = " + status + ", Result = " + resultCode);
							}
							else
							{
								scaDone = true;
								neueUmsaetze.clear();
								arbeitsSaldo = saldo;
								pageNo = 0;
								log(Level.INFO, "Starte Umsatzabfrage neu nach Eingabe zweiter Faktor");
								break;
							}
						}
					}
					while (true);
				}	                
			} while (!gotDuplicate && (more || moreWithSCA));

			monitor.setPercentComplete(75); 
			log(Level.INFO, "Kontoauszug erfolgreich. Importiere Daten ...");

			reverseImport(neueUmsaetze);

			log(Level.INFO, "Import erfolgreich. Pr\u00FCfe Reservierungen ...");
			monitor.setPercentComplete(95); 

			deleteMissingUnbooked(duplikate);
		}

		return true;
	}
}
