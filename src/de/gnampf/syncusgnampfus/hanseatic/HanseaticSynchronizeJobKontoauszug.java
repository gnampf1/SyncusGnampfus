package de.gnampf.syncusgnampfus.hanseatic;

import java.net.URLEncoder;
import java.rmi.RemoteException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import javax.annotation.Resource;

import org.htmlunit.HttpMethod;
import org.json.JSONArray;
import org.json.JSONObject;

import de.gnampf.syncusgnampfus.SyncusGnampfusSynchronizeJob;
import de.gnampf.syncusgnampfus.KeyValue;
import de.gnampf.syncusgnampfus.SyncusGnampfusSynchronizeJobKontoauszug;
import de.willuhn.datasource.rmi.DBIterator;
import de.willuhn.jameica.hbci.Settings;
import de.willuhn.jameica.hbci.messaging.ObjectChangedMessage;
import de.willuhn.jameica.hbci.messaging.SaldoMessage;
import de.willuhn.jameica.hbci.rmi.Konto;
import de.willuhn.jameica.hbci.rmi.Umsatz;
import de.willuhn.jameica.hbci.synchronize.SynchronizeBackend;
import de.willuhn.jameica.system.Application;
import de.willuhn.logging.Level;
import de.willuhn.util.ApplicationException;

public class HanseaticSynchronizeJobKontoauszug extends SyncusGnampfusSynchronizeJobKontoauszug implements SyncusGnampfusSynchronizeJob 
{
	@Resource
	private HanseaticSynchronizeBackend backend = null;

	@Override
	protected SynchronizeBackend getBackend() { return backend; }

	@Override
	public boolean process(Konto konto, boolean fetchSaldo, boolean fetchUmsatz, boolean forceAll, DBIterator<Umsatz> umsaetze, String user, String passwort) throws Exception
	{
		umsaetze.begin();
		while (umsaetze.hasNext())
		{
			var umsatz = umsaetze.next();
			var tID = umsatz.getTransactionId();
			if (tID != null && !tID.contains(":"))
			{
				tID = tID + ":" + umsatz.getBetrag();
				umsatz.store();
			}
		}
		
		log(Level.DEBUG, "besorge Basic-Credentials");
		var response = doRequest(decodeItem("aHR0cHM6Ly9tZWluZS5oYW5zZWF0aWNiYW5rLmRlL2RlL3JlZ2lzdGVyL3NpZ24taW4="), HttpMethod.GET, null, null, null);
		var textContent = response.getContent().replace("\n", "").replace("\r", "");
		var basicAuth = textContent.replaceAll(".*BASIC_AUTH:\"Basic ([^\"]+)\".*", "$1");
		var baseUrl = textContent.replaceAll(".*NORTHLAYER_BASE_URL:\"([^\"]+)\".*", "$1");
		var clientKey = textContent.replaceAll(".*NORTHLAYER_CLIENT_KEY:\"([^\"]+)\".*", "$1");
		var clientSecret = textContent.replaceAll(".*NORTHLAYER_CLIENT_SECRET:\"([^\"]+)\".*", "$1");
		var deviceToken = konto.getMeta(HanseaticSynchronizeBackend.META_DEVICETOKEN, null);
		log(Level.DEBUG, "Basic-Auth-Token = " + basicAuth + ", BaseUrl: " + baseUrl + ", ClientKey = " + clientKey + ", ClientSecret = " + clientSecret);

		log(Level.INFO, "Hole allgemeines Token");
		permanentHeaders.clear();
		permanentHeaders.add(new KeyValue<>("authorization", "Bearer undefined"));

		response = doRequest(baseUrl + "/token", HttpMethod.POST, null, "application/x-www-form-urlencoded; charset=UTF-8", "grant_type=client_credentials&client_id=" + clientKey + "&client_secret=" + clientSecret);
		if (response.getHttpStatus() != 200 || response.getJSONObject() == null)
		{
			log(Level.DEBUG, "Response: " + response.getContent());
			throw new ApplicationException("Login fehlgeschlagen, Fehlercode " + response.getHttpStatus());
		}

		JSONObject json = response.getJSONObject();
		String genericToken = json.optString("access_token");
		if (genericToken == null)
		{
			log(Level.DEBUG, "Response: " + response.getContent());
			throw new ApplicationException("Kein allgemeines Token erhalten?");
		}
		
		permanentHeaders.clear();
		permanentHeaders.add(new KeyValue<>("authorization", "Basic " + basicAuth));
		if (deviceToken != null)
		{
			permanentHeaders.add(new KeyValue<>("DEVICETOKEN", deviceToken));
		}

		response = doRequest(baseUrl + "/token", HttpMethod.POST, null, "application/x-www-form-urlencoded; charset=UTF-8", "grant_type=hbSCACustomPassword&password=" + URLEncoder.encode(passwort, "UTF-8") + "&loginId=" + URLEncoder.encode(user, "UTF-8"));
		if (response.getHttpStatus() != 200)
		{
			log(Level.DEBUG, "Response: " + response.getContent());
			throw new ApplicationException("Login fehlgeschlagen, Fehlercode " + response.getHttpStatus());
		}

		json = response.getJSONObject();
		String token = json.optString("access_token");
		if (token == null || token.isBlank())
		{
			token = json.optString("id_token");
			if (token == null || token.isBlank())
			{
				log(Level.INFO, "Response: " + response.getContent());
				throw new ApplicationException("Weder ein Acess- noch ein ID-Token erhalten");
			}

			permanentHeaders.clear();
			permanentHeaders.add(new KeyValue<>("authorization", "Bearer " + genericToken));

			token = token.substring(token.indexOf(".") + 1);
			token = token.substring(0, token.lastIndexOf("."));
			var js = new String(de.willuhn.util.Base64.decode(token));
			JSONObject idt = new JSONObject(js);
			var scaId = idt.optString("sca_id");
			
			String sca = null;
			while (sca == null) 
			{
				response = doRequest(baseUrl + decodeItem("L29wZW5TY2FCcm9rZXIvMS4wL2N1c3RvbWVyLw==") +  URLEncoder.encode(user, "UTF-8") + "/status/" + scaId, HttpMethod.GET, null, null, null);
				var status = response.getJSONObject().optString("status");
				if ("complete".equals(status))
				{
					var resultData = response.getJSONObject().optJSONObject("resultData");
					if (resultData != null)
					{
						deviceToken = resultData.optString("DEVICETOKEN");
						konto.setMeta(HanseaticSynchronizeBackend.META_DEVICETOKEN, deviceToken);
					}
					break;
				}
				else if ("open".equals(status) || "accepted".equals(status))
				{
					if ("SMS".equals(response.getJSONObject().optString("scaType")))
					{
						sca = Application.getCallback().askUser("Bitte geben Sie die TAN ein, das sie per SMS erhalten haben.", "TAN:");
						if (sca == null || sca.isBlank())
						{
							throw new ApplicationException("TAN-Eingabe abgebrochen");
						}
					}
					else 
					{
						log(Level.INFO, "Anmeldung muss in der Hanseatic-App best\u00E4tigt werden!");
					}
					log(Level.INFO, "Status ist " + status);
					if (sca == null) Thread.sleep(5000);
				}
				else
				{
					throw new ApplicationException("Unbekannte Status " + status + ", Freigabe in der App verweigert?");
				}
			}
			
			permanentHeaders.clear();
			permanentHeaders.add(new KeyValue<>("authorization", "Basic " + basicAuth));
			if (deviceToken != null)
			{
				permanentHeaders.add(new KeyValue<>("DEVICETOKEN", deviceToken));
			}

			if (sca == null)
			{
				response = doRequest(baseUrl + "/token", HttpMethod.POST, null, "application/x-www-form-urlencoded; charset=UTF-8", "grant_type=hbSCACustomPassword&password=" + URLEncoder.encode(passwort, "UTF-8") + "&loginId=" + URLEncoder.encode(user, "UTF-8"));
			}
			else
			{
				response = doRequest(baseUrl + "/token", HttpMethod.POST, null, "application/x-www-form-urlencoded; charset=UTF-8", "grant_type=hbSCACustomPassword&loginId=" + URLEncoder.encode(user, "UTF-8") + "&otp=" + sca + "&scaId=" + scaId);
			}
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

		response = doRequest(baseUrl + decodeItem("L3BhaXJpbmdTZWN1cmVBcHAvMS4wL2FjdGl2YXRlQ3JlZGl0Q2FyZHM="), HttpMethod.PUT, null, null, null);
		if (response.getHttpStatus() != 200)
		{
			log(Level.DEBUG, "Response: " + response.getContent());
			throw new ApplicationException("ActivateCreditCards fehlgeschlagen");
		}

		response = doRequest(baseUrl + decodeItem("L2N1c3RvbWVycG9ydGFsLzEuMC9hY2NvdW50cz9za2lwQ2FjaGU9ZmFsc2U="), HttpMethod.GET, null, null, null);
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

				response = doRequest(baseUrl + decodeItem("L3RyYW5zYWN0aW9uLzEuMC90cmFuc2FjdGlvbnNFbnJpY2hlZC8=") + konto.getKontonummer() + "?page=" + pageNo + "&withReservations=true&withEnrichments=true", HttpMethod.GET, null, null, null);
				if (response.getHttpStatus() != 200)
				{
					log(Level.DEBUG, "Response: " + response.getContent());
					throw new ApplicationException("Umsatzabruf fehlgeschlagen");
				}
				json = response.getJSONObject();
				JSONArray transactionsArray = json.optJSONArray("transactions");
				more = json.optBoolean("more", false);
				moreWithSCA = json.optBoolean("moreWithSCA", false);
				
				if (moreWithSCA && scaDone)
				{
					log(Level.ERROR, "Erfolgreiche Zwei-Faktor-Anmeldung, aber trotzdem wird ein zweiter Faktor angefordert?");
				}

				log(Level.INFO, "lese Seite "+pageNo);
				monitor.setPercentComplete(monitor.getPercentComplete() + 1);

				for (var obj : transactionsArray)
				{
					var transaction = (JSONObject)obj;

					var betrag = transaction.getDouble("amount");
					var neuerUmsatz = (Umsatz) Settings.getDBService().createObject(Umsatz.class,null);
					neuerUmsatz.setKonto(konto);
					neuerUmsatz.setBetrag(betrag);
					neuerUmsatz.setValuta(dateFormat.parse(transaction.getString("transactionDate")));
					neuerUmsatz.setGegenkontoBLZ(transaction.optString("recipientBic"));
					neuerUmsatz.setGegenkontoName(transaction.optString("recipientName"));
					neuerUmsatz.setGegenkontoNummer(transaction.optString("recipientIban"));
					neuerUmsatz.setDatum(dateFormat.parse(transaction.getString("date") + " 00:00"));
					neuerUmsatz.setZweck(transaction.optString("description"));
					neuerUmsatz.setArt(transaction.optString("creditDebitKey"));

					ArrayList<String> details = new ArrayList<String>();

					var website = transaction.getJSONObject("merchantData").getString("website");
					if (website != null && !website.isBlank()) details.add("Web: " + website);
					var transactionTime = transaction.optString("transactionTime");
					if (transactionTime != null && !transactionTime.isBlank()) details.add("Zeit: " + transactionTime);
					var conversionRate = transaction.optString("conversionRate");
					if (conversionRate != null && !conversionRate.isBlank()) details.add("Wechselkurs: " + conversionRate);

					neuerUmsatz.setCreditorId(transaction.optString("creditorID"));
					neuerUmsatz.setMandateId(transaction.optString("mandateReference"));
					var transactionId = transaction.optString("transactionId");
					neuerUmsatz.setCustomerRef(transactionId);
					if (transactionId == null || transactionId.isBlank())
					{
						transactionId = neuerUmsatz.getDatum().getTime() + "/" + neuerUmsatz.getValuta().getTime() + "/" + transactionTime + neuerUmsatz.getZweck().hashCode();
					}
					neuerUmsatz.setTransactionId(transactionId + ":" + betrag);

					for (int j = 0; j < details.size(); j++)
					{
						if (details.get(j).length() > 35) 
						{
							details.add(j + 1, details.get(j).substring(35));
							details.set(j, details.get(j).substring(0,35));
						}
					}
					neuerUmsatz.setWeitereVerwendungszwecke(details.toArray(new String[0]));
					if (transaction.optBoolean("booked", true) == false)
					{
						neuerUmsatz.setFlags(Umsatz.FLAG_NOTBOOKED);
					}
					else 
					{
						neuerUmsatz.setSaldo(arbeitsSaldo); // Zwischensaldo
						arbeitsSaldo -= betrag;
					}

					var bekannterUmsatz = getDuplicateById(neuerUmsatz); 
					if (bekannterUmsatz != null)
					{		                		
						gotDuplicate = true;
						BekanntenUmsatzBehandeln(bekannterUmsatz, neuerUmsatz, duplikate);
					}
					else
					{
						var valuta = neuerUmsatz.getValuta();
						var datum = neuerUmsatz.getDatum();
						neuerUmsatz.setDatum(valuta);
						neuerUmsatz.setValuta(datum);
						bekannterUmsatz = getDuplicateByCompare(neuerUmsatz);
						neuerUmsatz.setDatum(datum);
						neuerUmsatz.setValuta(valuta);

						if (bekannterUmsatz != null)
						{
							BekanntenUmsatzBehandeln(bekannterUmsatz, neuerUmsatz, duplikate);
						}
						else
						{
							neueUmsaetze.add(neuerUmsatz);
						}
					}
				}

				if ((forceAll || !gotDuplicate) && !more && moreWithSCA && !scaDone)
				{
					log(Level.INFO, "Ben\u00f6tige zweiten Faktor f\u00FCr weitere Ums\u00e4tze...");

					String status = null;
					var resultCode = 0;

					do 
					{
						response = doRequest(baseUrl + decodeItem("L3NjYUJyb2tlci8xLjAvc2Vzc2lvbg=="), HttpMethod.POST, null, "application/json", "{\"initiator\":\"ton-sca-fe\",\"lang\":\"de\",\"session\":\"" + tokenType + " " + token + "\"}");
						if (response.getHttpStatus() != 200)
						{
							log(Level.DEBUG, "Response: " + response.getContent());
							throw new ApplicationException("2FA-Abruf fehlgeschlagen");
						}
						json = response.getJSONObject();	    	                
						var type = json.optString("scaType", "Unbekannt");
						var uniqueId = json.optString("scaUniqueId");

						String sca = null;
						if ("SMS".equals(type))
						{
							var requestText = "Bitte geben Sie die TAN ein, das sie per " + type + " erhalten haben.";
							if (status != null)
							{
								requestText += "\nDer letzte Code wurde nicht akzeptiert, Status " + resultCode + " / " + status;
							}

							 sca = Application.getCallback().askUser(requestText, "TAN:");
							if (sca == null || sca.isBlank())
							{
								more = false;
								moreWithSCA = false;
								log(Level.WARN, "TAN-Eingabe abgebrochen");
								break;
							}
						}
						else
						{
							log(Level.INFO, "Abruf \u00E4lterer Ums\u00E4tze muss in der App best\u00E4tigt werden!");
						}

						while (true)
						{
							if (sca != null)
							{
								response = doRequest(baseUrl + decodeItem("L3NjYUJyb2tlci8xLjAvc3RhdHVzLw==") + uniqueId, HttpMethod.PUT, null, "application/json", "{\"otp\":\"" + sca + "\"}");
							}
							else
							{
								response = doRequest(baseUrl + decodeItem("L3NjYUJyb2tlci8xLjAvc3RhdHVzLw==") + uniqueId, HttpMethod.GET, null, null, null);
							}
							
							if (response.getHttpStatus() != 200)
							{
								log(Level.DEBUG, "Response: " + response.getContent());
								throw new ApplicationException("2FA-Validierung fehlgeschlagen");
							}
							json = response.getJSONObject();
							status = json.optString("status");
							resultCode = json.optInt("resultCode");
	
							if ("open".equals(status) || "accepted".equals(status))
							{
								log(Level.INFO, "Warte auf Best\u00E4tigung in der App");
								Thread.sleep(5000);
							}
							else if ("complete".equals(status) && resultCode == 200)
							{
								scaDone = true;
								neueUmsaetze.clear();
								arbeitsSaldo = saldo;
								pageNo = 0;
								log(Level.INFO, "Starte Umsatzabfrage neu nach Eingabe zweiter Faktor");
								break;
							}
							else
							{
								log(Level.DEBUG, "Response: " + response.getContent());
								log(Level.WARN, "2FA-Validierung fehlgeschlagen, Status = " + status + ", Result = " + resultCode);
								scaDone = false;
							}
						}
					}
					while (!scaDone);
				}	                
			} while ((forceAll || !gotDuplicate) && (more || moreWithSCA));

			monitor.setPercentComplete(75); 
			log(Level.INFO, "Kontoauszug erfolgreich. Importiere Daten ...");

			reverseImport(neueUmsaetze);

			log(Level.INFO, "Import erfolgreich. Pr\u00FCfe Reservierungen ...");
			monitor.setPercentComplete(95); 

			deleteMissingUnbooked(duplikate);
		}

		return true;
	}
	
	void BekanntenUmsatzBehandeln(Umsatz bekannterUmsatz, Umsatz neuerUmsatz, ArrayList<Umsatz> duplikate) throws RemoteException, ApplicationException
	{
		if (bekannterUmsatz.hasFlag(Umsatz.FLAG_NOTBOOKED))
		{
			bekannterUmsatz.setFlags(neuerUmsatz.getFlags());
			duplikate.add(bekannterUmsatz);
		}
		bekannterUmsatz.setTransactionId(neuerUmsatz.getTransactionId());
		bekannterUmsatz.setSaldo(neuerUmsatz.getSaldo());
		bekannterUmsatz.setWeitereVerwendungszwecke(neuerUmsatz.getWeitereVerwendungszwecke());
		bekannterUmsatz.setGegenkontoBLZ(neuerUmsatz.getGegenkontoBLZ());
		bekannterUmsatz.setGegenkontoName(neuerUmsatz.getGegenkontoName());
		bekannterUmsatz.setGegenkontoNummer(neuerUmsatz.getGegenkontoNummer());
		bekannterUmsatz.setValuta(neuerUmsatz.getValuta());
		bekannterUmsatz.setDatum(neuerUmsatz.getDatum());
		bekannterUmsatz.setArt(neuerUmsatz.getArt());
		bekannterUmsatz.setCreditorId(neuerUmsatz.getCreditorId());
		bekannterUmsatz.setMandateId(neuerUmsatz.getMandateId());
		bekannterUmsatz.setCustomerRef(neuerUmsatz.getCustomerRef());
		
		bekannterUmsatz.store();
		Application.getMessagingFactory().sendMessage(new ObjectChangedMessage(bekannterUmsatz));
	}
}
