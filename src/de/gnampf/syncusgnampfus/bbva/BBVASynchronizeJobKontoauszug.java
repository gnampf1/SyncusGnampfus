package de.gnampf.syncusgnampfus.bbva;

import java.io.IOException;
import java.net.URISyntaxException;
import java.rmi.RemoteException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Hashtable;
import java.util.TimeZone;

import javax.annotation.Resource;

import org.htmlunit.CookieManager;
import org.htmlunit.FailingHttpStatusCodeException;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.ProxyConfig;
import org.htmlunit.WebClient;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.json.JSONArray;
import org.json.JSONObject;

import de.gnampf.syncusgnampfus.KeyValue;
import de.gnampf.syncusgnampfus.SyncusGnampfusSynchronizeJob;
import de.gnampf.syncusgnampfus.SyncusGnampfusSynchronizeJobKontoauszug;
import de.gnampf.syncusgnampfus.WebResult;
import de.willuhn.datasource.rmi.DBIterator;
import de.willuhn.jameica.hbci.Settings;
import de.willuhn.jameica.hbci.SynchronizeOptions;
import de.willuhn.jameica.hbci.messaging.ImportMessage;
import de.willuhn.jameica.hbci.messaging.SaldoMessage;
import de.willuhn.jameica.hbci.rmi.Konto;
import de.willuhn.jameica.hbci.rmi.Umsatz;
import de.willuhn.jameica.hbci.synchronize.SynchronizeBackend;
import de.willuhn.jameica.hbci.synchronize.jobs.SynchronizeJobKontoauszug;
import de.willuhn.jameica.security.Wallet;
import de.willuhn.jameica.system.Application;
import de.willuhn.logging.Level;
import de.willuhn.logging.Logger;
import de.willuhn.util.ProgressMonitor;

public class BBVASynchronizeJobKontoauszug extends SyncusGnampfusSynchronizeJobKontoauszug implements SyncusGnampfusSynchronizeJob 
{
	@Override
	protected String getVersion() { return "0.1"; }

	@Resource
	private BBVASynchronizeBackend backend = null;

	protected SynchronizeBackend getBackend() { return backend; }
	
	/**
	 * @see org.jameica.hibiscus.sync.example.ExampleSynchronizeJob#execute()
	 */
	@Override
	public boolean process(Konto konto, boolean fetchSaldo, boolean fetchUmsatz, DBIterator<Umsatz> umsaetze, String user, String passwort)
	{
		ArrayList<KeyValue<String, String>> headers = new ArrayList<>();
		try 
		{
			WebResult response = doRequest("https://de-net.bbva.com/TechArchitecture/grantingTickets/V02", HttpMethod.POST, null, "application/json", "{\"authentication\":{\"consumerID\":\"00000366\",\"authenticationType\":\"121\",\"userID\":\"" + user +"\",\"authenticationData\":[{\"authenticationData\":[\"" + passwort + "\"],\"idAuthenticationData\":\"password\"}]}}");
			var json = response.getJSONObject();
			var authState = json.optString("authenticationState");
			if ("GO_ON".equals(authState))
			{
				var multistepProcessId = json.optString("multistepProcessId");
				response = doRequest("https://de-net.bbva.com/TechArchitecture/grantingTickets/V02", HttpMethod.POST, null, "application/json", "{\"authentication\":{\"consumerID\":\"00000366\",\"authenticationType\":\"121\",\"userID\":\"" + user +"\",\"multistepProcessId\":\""+ multistepProcessId + "\"}}");
				json = response.getJSONObject();
				authState = json.optString("authenticationState");
				if ("GO_ON".equals(authState))
				{
					multistepProcessId = json.optString("multistepProcessId");

					String otp = null;
					do 
					{
						otp = Application.getCallback().askUser("Bitte geben Sie den SMS-Code ein.", "SMS-Code");
						if (otp == null || otp.isEmpty()) 
						{
							log(Level.ERROR, "TAN-Abfrage abgebrochen");
							monitor.setPercentComplete(100);
							monitor.setStatus(ProgressMonitor.STATUS_ERROR);
							return false;
						}
					}
					while (otp.length() != 6);

					headers.add(new KeyValue<>("authenticationstate", multistepProcessId));
					response = doRequest("https://de-net.bbva.com/TechArchitecture/grantingTickets/V02", HttpMethod.POST, headers, "application/json", "{\"authentication\":{\"consumerID\":\"00000366\",\"authenticationType\":\"121\",\"userID\":\"" + user +"\",\"multistepProcessId\":\"" + multistepProcessId + "\",\"authenticationData\":[{\"authenticationData\":[\"" + otp + "\"],\"idAuthenticationData\":\"otp\"}]}}");
					authState = response.getJSONObject().optString("authenticationState");
				}
			}

			String userId = null;
			String personId = null;
			var userObj = json.optJSONObject("user");
			if (userObj != null)
			{
				userId = userObj.optString("id");
				var personObj = userObj.optJSONObject("person");
				if (personObj != null)
				{
					personId = personObj.optString("id");
				}
			}

			if (!"OK".equals(authState) && userId != null && personId != null)
			{
				log(Level.ERROR, "Login fehlgeschlagen! AuthState ist " + authState);
				log(Level.DEBUG, "Response: " + response.getContent());
				monitor.setPercentComplete(100);
				monitor.setStatus(ProgressMonitor.STATUS_ERROR);
				return false;
			}

			headers.clear();
			String tsec = null;
			for (var header : response.getResponseHeader())
			{
				if (header.getName() == "tsec")
				{
					tsec = header.getValue();
				}
			}
			headers.add(new KeyValue<>("tsec", tsec));

			ArrayList<KeyValue<String, String>> tsecheaders = new ArrayList<>();
			tsecheaders.add(new KeyValue<>("x-tsec-token", tsec));
			response = doRequest("https://portunus-hub-es.live.global.platform.bbva.com/v1/tsec", HttpMethod.GET, tsecheaders, null, null);
			if (response.getHttpStatus() != 200)
			{
				log(Level.ERROR, "TSEC-Abfrage fehlgeschlagen! AuthState ist " + authState);
				log(Level.DEBUG, "Response: " + response.getContent());
				monitor.setPercentComplete(100);
				monitor.setStatus(ProgressMonitor.STATUS_ERROR);
				return false;
			}

			Logger.info("Login war erfolgreich");

			response = doRequest("https://de-net.bbva.com/financial-overview/v1/financial-overview?customer.id=" + personId + "&showSicav=false&showPending=true", HttpMethod.GET, headers, null, null);
			JSONArray contracts = response.getJSONObject().getJSONArray("contracts");
			var ktoContract = new Object() { JSONObject value = null; };
			var myIban = konto.getIban();
			contracts.forEach(c -> 
			{
				var contract = (JSONObject)c;
				contract.optJSONArray("formats").forEach(f -> 
				{
					var format = (JSONObject)f;
					if ("IBAN".equals(format.optJSONObject("numberType").optString("id")) && myIban.equals(format.optString("number")))
					{
						ktoContract.value = contract;
					}
				});
			});

			if (ktoContract.value == null)
			{
				log(Level.ERROR, "Konto mit IBAN " + konto.getIban() + " nicht gefunden!");
				log(Level.DEBUG, "Response: " + response.getContent());
				monitor.setPercentComplete(100);
				monitor.setStatus(ProgressMonitor.STATUS_ERROR);
				return true;
			}

			var contractId = ktoContract.value.optString("id");

			if (fetchSaldo)
			{
				ktoContract.value.optJSONObject("detail").optJSONArray("specificAmounts").forEach(sa ->
				{
					var specificAmounts = (JSONObject)sa;
					double saldo = specificAmounts.getJSONArray("amounts").getJSONObject(0).optDouble("amount");
					try 
					{
						if ("availableBalance".equals(specificAmounts.optString("id")))
						{
							konto.setSaldoAvailable(saldo);
						}
						else if ("currentBalance".equals(specificAmounts.optString("id")))
						{
							konto.setSaldo(saldo);
						}
					}
					catch (RemoteException ex) 
					{            	
						Logger.error("BBVA: Fehler beim Setzen vom Saldo: " + ex.toString());
						monitor.log("Fehler beim Setzen vom Saldo");
					}
				});

				response = doRequest("https://de-net.bbva.com/accounts/v0/accounts/" + contractId + "/dispokredits/validations/", HttpMethod.GET, headers, null, null);
				JSONObject dispo = response.getJSONObject();
				if (dispo != null)
				{
					dispo.optJSONArray("dispokreditAmounts").forEach(d ->
					{
						JSONObject dispoAmount = (JSONObject)d;
						if ("stdAuthDispoAmount".equals(dispoAmount.getString("id")))
						{
							try 
							{
								konto.setSaldoAvailable(konto.getSaldoAvailable() + dispoAmount.getJSONObject("amount").getDouble("amount"));
							} catch (RemoteException ex)
							{
								Logger.error("BBVA: Fehler beim Setzen vom Dispo-Saldo: " + ex.toString());
								monitor.log("Fehler beim Setzen vom Dispo-Saldo");
							}
						}
					});
				}
	
				konto.store();
				Application.getMessagingFactory().sendMessage(new SaldoMessage(konto));
			}

			response = doRequest("https://de-net.bbva.com/accountTransactions/V02/updateAccountTransactions", HttpMethod.POST, headers, "application/json", "{\"contracts\":[{\"id\":\"" + contractId + "\"}]}");

			var page = 0;
			var numPages = 0;
			var dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
			dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
			var neueUmsaetze = new ArrayList<Umsatz>();
			var duplikatGefunden = new Object() { boolean value = false; };
			do 
			{
				response = doRequest(webClient, "https://de-net.bbva.com/accountTransactions/V02/accountTransactionsAdvancedSearch?pageSize=40&paginationKey=" + page, HttpMethod.POST, headers, "application/json", "{\"accountContracts\":[{\"contract\":{\"id\":\"" + contractId + "\"}}],\"customer\":{\"id\":\"" + personId + "\"},\"filter\":{\"dates\":{\"from\":\"2025-05-01T00:00:00.000Z\",\"to\":\"" + dateFormat.format(new Date()) + "\"},\"operationType\":[\"BOTH\"]},\"orderField\":\"DATE_FIELD\",\"orderType\":\"DESC_ORDER\",\"searchType\":\"SEARCH\"}");
				json = response.getJSONObject();
				numPages = json.optJSONObject("pagination").optInt("numPages");

				json.optJSONArray("accountTransactions").forEach(t -> 
				{
					var transaction = (JSONObject)t;

					try 
					{
						var newUmsatz = (Umsatz) Settings.getDBService().createObject(Umsatz.class,null);
						newUmsatz.setKonto(konto);
						newUmsatz.setArt(transaction.optJSONObject("concept").optString("name"));
						newUmsatz.setBetrag(transaction.optJSONObject("amount").optDouble("amount"));
						newUmsatz.setDatum(dateFormat.parse(transaction.optString("transactionDate")));
						newUmsatz.setSaldo(transaction.optJSONObject("balance").optJSONObject("accountingBalance").optDouble("amount"));
						newUmsatz.setTransactionId(transaction.optString("id"));
						newUmsatz.setValuta(dateFormat.parse(transaction.optString("valueDate")));
						newUmsatz.setZweck(transaction.optString("humanConceptName"));
						newUmsatz.setZweck2(transaction.optString("humanExtendedConceptName"));

						var detailSourceKey = transaction.optJSONObject("origin").optString("detailSourceKey");
						var detailSourceId = transaction.optJSONObject("origin").optString("detailSourceId");
						if (detailSourceKey != null && !"".equals(detailSourceKey) && !detailSourceKey.contains(" ") && !"KPSA".equals(detailSourceId))
						{
							var detailResponse = doRequest("https://de-net.bbva.com/transfers/v0/transfers/" + detailSourceKey + "-RE-" + contractId + "/", HttpMethod.GET, headers, null, null);
							if (detailResponse != null)
							{
								var details = detailResponse.getJSONObject().getJSONObject("data");

								var gegenkto = details.getJSONObject("sender");
								var eigenkto = details.getJSONObject("receiver");
								if ("BBVADEFFXXX".equals(gegenkto.getJSONObject("bank").getString("BICCode")))
								{
									eigenkto = gegenkto;
									gegenkto = details.optJSONObject("receiver");
								}

								newUmsatz.setCustomerRef(eigenkto.optString("reference"));
								newUmsatz.setGegenkontoBLZ(gegenkto.optJSONObject("bank").optString("BICCode"));
								var name = gegenkto.optString("fullName"); 
								if (name != null && !"".equals(name))
								{
									newUmsatz.setGegenkontoName(name);
								}
								else
								{
									newUmsatz.setGegenkontoName(gegenkto.optString("alias"));
								}
								newUmsatz.setGegenkontoNummer(gegenkto.optJSONObject("contract").optString("number"));
							}
						}

						if (getDuplicateById(newUmsatz) != null)
						{
							duplikatGefunden.value = true;
						}
						else
						{
							neueUmsaetze.add(newUmsatz);
						}
					}
					catch (Exception ex)
					{
						log(Level.ERROR, "Fehler beim Anlegen vom Umsatz: " + ex.toString());
					}
				});

				page++;
			} while (!duplikatGefunden.value && page < numPages);

			monitor.setPercentComplete(75); 
			log(Level.INFO, "Kontoauszug erfolgreich. Importiere Daten ...");

			reverseImport(neueUmsaetze);
		} 
		catch (Exception ex) 
		{
			log(Level.ERROR, "Unbekannter Fehler " + ex.toString());
			monitor.setPercentComplete(100);
			monitor.setStatus(ProgressMonitor.STATUS_ERROR);
			return false;
		}
		finally
		{
			// Logout
			try 
			{
				doRequest("https://de-net.bbva.com/TechArchitecture/grantingTickets/V02", HttpMethod.DELETE, headers, null, null);
			}
			catch (Exception e) {}
		}
		return true;
	}
}
