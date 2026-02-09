package de.gnampf.syncusgnampfus.bbva;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.rmi.RemoteException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import javax.annotation.Resource;

import org.htmlunit.HttpMethod;
import org.json.JSONArray;
import org.json.JSONObject;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Browser.NewContextOptions;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Page.ScreenshotOptions;
import com.microsoft.playwright.Route.FulfillOptions;
import com.microsoft.playwright.options.ScreenshotAnimations;
import com.microsoft.playwright.options.ScreenshotType;
import de.gnampf.syncusgnampfus.KeyValue;
import de.gnampf.syncusgnampfus.SyncusGnampfusSynchronizeJob;
import de.gnampf.syncusgnampfus.SyncusGnampfusSynchronizeJobKontoauszug;
import de.gnampf.syncusgnampfus.WebResult;
import de.gnampf.syncusgnampfus.amex.AMEXSynchronizeBackend;
import de.willuhn.datasource.rmi.DBIterator;
import de.willuhn.jameica.hbci.Settings;
import de.willuhn.jameica.hbci.messaging.SaldoMessage;
import de.willuhn.jameica.hbci.rmi.Konto;
import de.willuhn.jameica.hbci.rmi.Umsatz;
import de.willuhn.jameica.hbci.synchronize.SynchronizeBackend;
import de.willuhn.jameica.system.Application;
import de.willuhn.logging.Level;
import de.willuhn.logging.Logger;
import de.willuhn.util.ApplicationException;
import io.github.kihdev.playwright.stealth4j.Stealth4j;
import io.github.kihdev.playwright.stealth4j.Stealth4jConfig;

public class BBVASynchronizeJobKontoauszug extends SyncusGnampfusSynchronizeJobKontoauszug implements SyncusGnampfusSynchronizeJob 
{
	@Resource
	private BBVASynchronizeBackend backend = null;

	protected SynchronizeBackend getBackend() { return backend; }
	
	protected void fetchPermanentHeaders(Konto konto) throws RemoteException, InterruptedException
	{
		var headerText = new Object() { public String value = konto.getMeta(BBVASynchronizeBackend.META_HEADERS, ""); };
		if ("".equals(headerText.value))
		{
			com.microsoft.playwright.Page pwPage = null;
			Browser browser = null;
			try 
			{
				Playwright playwright = Playwright.create(); 
				var headless = "false".equals(konto.getMeta(BBVASynchronizeBackend.META_NOTHEADLESS, "false"));
				var options1 = new BrowserType.LaunchOptions().setHeadless(headless);
				var proxyConfig = webClient.getOptions().getProxyConfig();
				if (proxyConfig != null && proxyConfig.getProxyHost() != null)
				{
					var proxy = proxyConfig.getProxyScheme()+"://" + proxyConfig.getProxyHost() + ":" + proxyConfig.getProxyPort();
					options1.setProxy(proxy);
				}
				
				var ffPath = konto.getMeta(AMEXSynchronizeBackend.META_FIREFOXPATH,  null);
				if (ffPath != null && !ffPath.isBlank())
				{
					var ffFile = new File(ffPath); 
					if (ffFile.isFile())
					{
						if (ffFile.canExecute())
						{
							log(Level.INFO, "Verwende Firefox unter " + ffPath);
							options1 = options1.setExecutablePath(Path.of(ffPath));
						}
						else
						{
							log(Level.WARN, "Firefox-Pfad auf " + ffPath + " gesetzt, aber Datei nicht ausf\u00FChrbar");
						}
					}
					else
					{
						log(Level.WARN, "Firefox-Pfad auf " + ffPath + " gesetzt, aber Datei nicht vorhanden");
					}
				}
				else
				{
					log(Level.INFO, "Verwende Firefox von Playwright");
				}
				
				browser = playwright.firefox().launch(options1);

				var stealthContext = Stealth4j.newStealthContext(browser, Stealth4jConfig.builder().navigatorLanguages(true, List.of("de-DE", "de")).build());
				stealthContext.setExtraHTTPHeaders(Map.of("DNT", "1"));
				pwPage = stealthContext.newPage();

				pwPage.navigate("https://mobile.bbva.de");

				var loadFinished = new Object() 
				{
					public boolean found = false; 				
				};
				pwPage.route("https://de-mobi.bbva.com/TechArchitecture/grantingTickets/V02", route -> 
				{
					log(Level.INFO, "V02 called");
					if ("POST".equals(route.request().method()))
					{
						loadFinished.found = true;
						log(Level.INFO, "V02 called, load finished");
						
						var header = route.request().headers();
						var m = Map.of(
								"akamai-bm-telemetry", header.get("akamai-bm-telemetry"),
								"bbva-user-agent", header.get("bbva-user-agent"),
								"contactid", header.get("contactid"),
								"thirdparty-deviceid", header.get("thirdparty-deviceid"),
								"user-agent", header.getOrDefault("User-Agent", header.getOrDefault("user-agent", ""))
								);
						var j = new JSONObject(m);
						headerText.value = j.toString();
						try 
						{
							konto.setMeta(BBVASynchronizeBackend.META_HEADERS, headerText.value);
						}
						catch (RemoteException e) 
						{
						}
						
						log(Level.INFO, "Header: " + header.get("akamai-bm-telemetry"));
					}
					
					if (loadFinished.found)
					{
						route.fulfill(new FulfillOptions().setBody("Ende").setStatus(200));
					}
					else
					{
						route.resume();
					}
				});

				pwPage.locator("xpath=//input[@type='text']").fill(UUID.randomUUID().toString());
				pwPage.locator("xpath=//input[@type='password']").fill(UUID.randomUUID().toString());
				pwPage.locator("xpath=//button[@type='submit']").click();
				
				var scOptions = new ScreenshotOptions().setTimeout(1000).setAnimations(ScreenshotAnimations.DISABLED).setFullPage(false).setOmitBackground(true).setType(ScreenshotType.JPEG);
				int timeout = 600;
				while (loadFinished.found == false && timeout-- > 0)
				{
					try 
					{
						pwPage.screenshot(scOptions);
					}
					catch (Exception e)
					{
						log(Level.DEBUG, "Screenshot meldet " + e);
					}
					Thread.sleep(100);
				}
			}
			finally
			{
				if (pwPage != null) pwPage.close();
				if (browser != null) browser.close();
			}
		}
		var headerObj = new JSONObject(headerText.value);
		permanentHeaders.clear();
		headerObj.toMap().forEach((x,y) -> { permanentHeaders.add(new KeyValue<>(x,y.toString())); });
	}

	/**
	 * @see org.jameica.hibiscus.sync.example.ExampleSynchronizeJob#execute()
	 */
	@Override
	public boolean process(Konto konto, boolean fetchSaldo, boolean fetchUmsatz, boolean forceAll, DBIterator<Umsatz> umsaetze, String user, String passwort) throws Exception
	{
		fetchPermanentHeaders(konto);
		
		var headers = new ArrayList<KeyValue<String, String>>();
		try
		{
			WebResult response;
			JSONObject json;
			try 
			{
				response = doRequest("https://de-mobi.bbva.com/TechArchitecture/grantingTickets/V02", HttpMethod.POST, null, "application/json", "{\"authentication\":{\"consumerID\":\"00000363\",\"authenticationType\":\"02\",\"userID\":\"" + user.toUpperCase() +"\",\"authenticationData\":[{\"authenticationData\":[\"" + passwort + "\"],\"idAuthenticationData\":\"password\"}]}}");
				json = response.getJSONObject();
				if (response.getHttpStatus() == 403 || "{}".equals(json.toString()))
				{
					throw new Exception();
				}
			}
			catch (Exception e)
			{
				konto.setMeta(BBVASynchronizeBackend.META_HEADERS, "");
				fetchPermanentHeaders(konto);
				response = doRequest("https://de-mobi.bbva.com/TechArchitecture/grantingTickets/V02", HttpMethod.POST, null, "application/json", "{\"authentication\":{\"consumerID\":\"00000363\",\"authenticationType\":\"02\",\"userID\":\"" + user.toUpperCase() +"\",\"authenticationData\":[{\"authenticationData\":[\"" + passwort + "\"],\"idAuthenticationData\":\"password\"}]}}");
				json = response.getJSONObject();
			}

			var authState = json.optString("authenticationState");
			if ("GO_ON".equals(authState))
			{
				var multistepProcessId = json.optString("multistepProcessId");
				response = doRequest("https://de-mobi.bbva.com/TechArchitecture/grantingTickets/V02", HttpMethod.POST, null, "application/json", "{\"authentication\":{\"consumerID\":\"00000363\",\"authenticationType\":\"02\",\"userID\":\"" + user.toUpperCase() +"\",\"multistepProcessId\":\""+ multistepProcessId + "\"}}");
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
							throw new ApplicationException("TAN-Abfrage abgebrochen");
						}
					}
					while (otp.length() != 6);

					headers.add(new KeyValue<>("authenticationstate", multistepProcessId));
					response = doRequest("https://de-mobi.bbva.com/TechArchitecture/grantingTickets/V02", HttpMethod.POST, headers, "application/json", "{\"authentication\":{\"consumerID\":\"00000363\",\"authenticationType\":\"02\",\"userID\":\"" + user.toUpperCase() +"\",\"multistepProcessId\":\"" + multistepProcessId + "\",\"authenticationData\":[{\"authenticationData\":[\"" + otp + "\"],\"idAuthenticationData\":\"otp\"}]}}");
					json = response.getJSONObject();
					authState = json.optString("authenticationState");
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
				log(Level.DEBUG, "Response: " + response.getContent());
				throw new ApplicationException("Login fehlgeschlagen! AuthState ist " + authState);
			}

			headers.clear();
			String tsec = null;
			for (var header : response.getResponseHeader())
			{
				if ("tsec".equals(header.getName()))
				{
					tsec = header.getValue();
					break;
				}
			}
			headers.add(new KeyValue<>("tsec", tsec));

			ArrayList<KeyValue<String, String>> tsecheaders = new ArrayList<>();
			tsecheaders.add(new KeyValue<>("x-tsec-token", tsec));
			response = doRequest("https://portunus-hub-es.live.global.platform.bbva.com/v1/tsec", HttpMethod.GET, tsecheaders, null, null);
			if (response.getHttpStatus() != 200)
			{
				log(Level.DEBUG, "Response: " + response.getContent());
				throw new ApplicationException("TSEC-Abfrage fehlgeschlagen! AuthState ist " + authState);
			}

			Logger.info("Login war erfolgreich");

			response = doRequest("https://de-net.bbva.com/financial-overview/v1/financial-overview?customer.id=" + personId + "&showSicav=false&showPending=true", HttpMethod.GET, headers, null, null);
			JSONArray contracts = response.getJSONObject().optJSONObject("data").optJSONArray("contracts");
			var contractDetails = new Object() { JSONObject ktoContract = null;  JSONObject availableBalance = null; JSONObject currentBalance = null;  };
			var myIban = konto.getIban();
			contracts.forEach(c -> 
			{
				var contract = (JSONObject)c;
				contract.optJSONArray("formats").forEach(f -> 
				{
					var format = (JSONObject)f;
					if ("IBAN".equals(format.optJSONObject("numberType").optString("id")) && myIban.equals(format.optString("number")))
					{
						contractDetails.ktoContract = contract;
						((JSONArray)contract.query("/detail/specificAmounts")).forEach(a ->
						{
							var amountObj = (JSONObject)a;
							switch (amountObj.optString("id"))
							{
							case "availableBalance":
								contractDetails.availableBalance = new JSONObject(Map.of(
										"amount", amountObj.query("/amounts/0/amount"),
										"currency", new JSONObject(Map.of("id", amountObj.query("/amounts/0/currency")))
										));
								break;
							case "currentBalance":
								contractDetails.currentBalance = new JSONObject(Map.of(
										"amount", amountObj.query("/amounts/0/amount"),
										"currency", new JSONObject(Map.of("id", amountObj.query("/amounts/0/currency")))
										));
								break;
							}
						});
					}
				});
			});

			if (contractDetails.ktoContract == null)
			{
				log(Level.DEBUG, "Response: " + response.getContent());
				throw new ApplicationException("Konto mit IBAN " + konto.getIban() + " nicht gefunden!");
			}

			var contractId = contractDetails.ktoContract.optString("id");

			if (fetchSaldo)
			{
				konto.setSaldoAvailable(contractDetails.availableBalance.optDouble("amount"));
				konto.setSaldo(contractDetails.currentBalance.optDouble("amount"));

				response = doRequest("https://de-net.bbva.com/accounts/v0/accounts/" + contractId + "/dispokredits/validations/", HttpMethod.GET, headers, null, null);
				JSONObject dispo = response.getJSONObject().optJSONObject("data");
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
								log(Level.ERROR, "Fehler beim Setzen vom Dispo-Saldo: " + ex.toString());								monitor.log("Fehler beim Setzen vom Dispo-Saldo");
							}
						}
					});
				}
	
				konto.store();
				Application.getMessagingFactory().sendMessage(new SaldoMessage(konto));
			}

			response = doRequest("https://de-net.bbva.com/accountTransactions/V02/updateAccountTransactions", HttpMethod.POST, headers, "application/json", "{\"contracts\":[{\"id\":\"" + contractId + "\"}]}");

			//var page = 0;
			//var numPages = 0;
			var dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
			dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
			var neueUmsaetze = new ArrayList<Umsatz>();
			var duplikatGefunden = new Object() { boolean value = false; };
			String nextPage = null;
			
			boolean isExtSearch = false;
			boolean isExtSearchPending = false;
			Date extSearchUntil = null;
			Date transactionsMostEarliestDate[] = {Date.from(
			        LocalDate.now()
	                .atTime(LocalTime.MAX)
	                .atZone(ZoneId.systemDefault())
	                .toInstant())} ;
			String extSearchOtp = null;
			String extSearchAuthenticationData = null;
			String extSearchAuthenticationState = null;
			do 
			{
				json = new JSONObject(Map.of(
						"customer", new JSONObject(Map.of("id", personId)),
						"searchType", "SEARCH",
						"accountContracts", new JSONArray(List.of(new JSONObject(Map.of(
								"contract", new JSONObject(Map.of("id", contractId)),
								"account", new JSONObject(Map.of(
										"currentBalance", contractDetails.currentBalance,
										"availableBalance", contractDetails.availableBalance
										))
						))))
					));
				
				// adv search may be activated and is performed from this time until end of loop
				if (isExtSearch) {
					Date fromDate = Date.from(
					        LocalDate.now()
						        .minusYears(5)
				                .atTime(LocalTime.MAX)
				                .atZone(ZoneId.systemDefault())
				                .toInstant()
							);
					if (isExtSearchPending) {
						// we need a until data is smallest resolution less than the last transaction received - so substract 1 millisecond
						extSearchUntil = new Date(transactionsMostEarliestDate[0].getTime() - 1);
					}
					//Date todayEndDate = Date.from(
					//        LocalDate.now()
				    //            .atTime(LocalTime.MAX)
				    //            .atZone(ZoneId.systemDefault())
				    //            .toInstant()
					//		);
					var filter = new JSONObject(Map.of(
							"dates", new JSONObject(Map.of(
									"from", dateFormat.format(fromDate),
									"to", dateFormat.format(extSearchUntil)
									)),
							"operationType", new JSONArray(List.of("BOTH"))
							));
					json.put("filter", filter);
				}
				
				if (isExtSearchPending) {
					log(Level.INFO, "Transaktionen älter als 90Tage benötigen eine extra Verifizierung für erweiterte Suche");
					
					// 1) we need to perform additional AdvancedFilterRequest with Filter which fails but leads to
					// transmitting a TAN to user 
					// 2) ask for the TAN
					// 3) continue with normal request 
					//	  - first request is extended with header field "authenticationdata" and "otp-sms=<TAN>"
					//    - filter is added due to advSearch-Flag
					//    - restart with page 0, after that continue with nextPage...
					nextPage = null;

					// TODO ist der call nötig? - wird von der webseite auch ausgeführt
					log(Level.DEBUG, "get authType -> expect 401");
					response = doRequest("https://de-net.bbva.com/accountTransactions/V02/accountTransactionsAdvancedSearch?pageSize=40&paginationKey=0", HttpMethod.POST, headers, "application/json", json.toString());
					// just in case the tsec has changed
					for (var respHeader : response.getResponseHeader())
					{
						if ("tsec".equals(respHeader.getName()))
						{
							log(Level.DEBUG, "replace tsec");
							headers.removeIf(p -> p.getKey().compareTo("tsec") == 0);
							headers.add(new KeyValue<String, String>("tsec", respHeader.getValue()));
						}
					}
					var tmpHeaders = new ArrayList<KeyValue<String, String>>(headers);
					var headerEntry = new KeyValue<String, String>("authenticationtype", "05"); 
					tmpHeaders.add(headerEntry);
					log(Level.DEBUG, "get authData-> expect 401");
					response = doRequest("https://de-net.bbva.com/accountTransactions/V02/accountTransactionsAdvancedSearch?pageSize=40&paginationKey=0", HttpMethod.POST, tmpHeaders, "application/json", json.toString());

					// just in case the tsec has changed
					for (var respHeader : response.getResponseHeader())
					{
						if ("tsec".equals(respHeader.getName()))
						{
							log(Level.DEBUG, "replace tsec");
							headers.removeIf(p -> p.getKey().compareTo("tsec") == 0);
							headers.add(new KeyValue<String, String>("tsec", respHeader.getValue()));
						}
					}
					for (var header : response.getResponseHeader())
					{
						if ("authenticationdata".equals(header.getName()))
						{
							log(Level.DEBUG, "got AuthData");
							extSearchAuthenticationData = header.getValue();
						}
						else if ("authenticationstate".equals(header.getName()))
						{
							log(Level.DEBUG, "got AuthState");
							extSearchAuthenticationState = header.getValue();
						}
					}
					// response should be 403 error - ignore it
					
					// this triggered sending a TAN
					var requestText = "Gib den Bestaetigungscode ein, den du per SMS erhalten hast (fuer 'Alle Transaktionen abrufen')";	// TBD evtl. versch. Wege !?

					extSearchOtp = Application.getCallback().askUser(requestText, "Bestaetigungscode:");
					if (extSearchOtp == null || extSearchOtp.isBlank())
					{
						log(Level.WARN, "TAN-Eingabe 'Alle Transaktionen abrufen' abgebrochen");
						break;
					} else {
						// continue with normal requests, while the advSearchPending Flag leads to adding the OTP once
					}
				}

				if ((nextPage == null) || (nextPage.isEmpty())) {
					log(Level.DEBUG, "nextPage null handling");
					var tmpHeaders = new ArrayList<KeyValue<String, String>>(headers);
					if (isExtSearchPending) {
						log(Level.DEBUG, "isExtSerchPending == true -> first nextPage null handling with additional headers for authType, -data, -state");
						// this happens only first time when nextPage is reset for advanced Search
						var headerEntry = new KeyValue<String, String>("authenticationdata", extSearchAuthenticationData + "=" + extSearchOtp); 
						tmpHeaders.add(headerEntry);
						headerEntry = new KeyValue<String, String>("authenticationstate", extSearchAuthenticationState);
						tmpHeaders.add(headerEntry);
						headerEntry = new KeyValue<String, String>("authenticationtype", "05"); 
						tmpHeaders.add(headerEntry);
						// change to advanced searching done, continue in advSearching-Mode
						isExtSearchPending = false;
					}
					response = doRequest("https://de-net.bbva.com/accountTransactions/V02/accountTransactionsAdvancedSearch?pageSize=40&paginationKey=0", HttpMethod.POST, tmpHeaders, "application/json", json.toString());
				} else {
					log(Level.DEBUG, "nextPage handling");
					response = doRequest("https://de-net.bbva.com" + nextPage, HttpMethod.POST, headers, "application/json", json.toString());
				}
				
				if (response.getHttpStatus() != 200)
				{
					log(Level.DEBUG, "Response: " + response.getContent());
					throw new ApplicationException("Erweiterte Suche fehlgeschlagen");
				}
				
				// just in case the tsec has changed
				for (var respHeader : response.getResponseHeader())
				{
					if ("tsec".equals(respHeader.getName()))
					{
						log(Level.DEBUG, "replace tsec");
						headers.removeIf(p -> p.getKey().compareTo("tsec") == 0);
						headers.add(new KeyValue<String, String>("tsec", respHeader.getValue()));
					}
				}
				
				json = response.getJSONObject();
				var pagination = json.optJSONObject("pagination");
				if (pagination != null && pagination.has("numPages"))
				{
					var page = pagination.optInt("page");
					var numPages = pagination.optInt("numPages");
					nextPage = pagination.optString("nextPage");
					log(Level.INFO, "Seite " + page + " / " + numPages + (isExtSearch ? "(erweiterte Suche)" : ""));
				}
				else
				{
					log(Level.INFO, "Kein Pagination-Objekt oder keine Anzahl Seiten? Gehe von 1 aus");
					nextPage = null;
				}

				if (json.has("accountTransactions"))
				{
					json.getJSONArray("accountTransactions").forEach(t -> 
					{
						var transaction = (JSONObject)t;
	
						try 
						{
							var newUmsatz = (Umsatz) Settings.getDBService().createObject(Umsatz.class,null);
							newUmsatz.setKonto(konto);
							newUmsatz.setArt(transaction.optJSONObject("concept").optString("name"));
							newUmsatz.setBetrag(transaction.optJSONObject("amount").optDouble("amount"));
							var d = dateFormat.parse(transaction.optString("transactionDate"));
							newUmsatz.setDatum(d);
							if (d.before(transactionsMostEarliestDate[0]))  {
								transactionsMostEarliestDate[0] = d;
							}
							newUmsatz.setSaldo(transaction.optJSONObject("balance").optJSONObject("accountingBalance").optDouble("amount"));
							newUmsatz.setTransactionId(transaction.optString("id"));
							newUmsatz.setValuta(dateFormat.parse(transaction.optString("valueDate")));
	
							var vz = transaction.optString("humanExtendedConceptName");
							
							var detailSourceKey = transaction.optJSONObject("origin").optString("detailSourceKey");
							var detailSourceId = transaction.optJSONObject("origin").optString("detailSourceId");
							if (detailSourceKey != null && 
									!"".equals(detailSourceKey) && 
									!detailSourceKey.contains(" ") && 
									!"KPSA".equals(detailSourceId) && 
									!"PGGI".equals(detailSourceId))
							{
								var detailResponse = doRequest("https://de-net.bbva.com/transfers/v0/transfers/" + detailSourceKey + "-RE-" + contractId + "/", HttpMethod.GET, headers, null, null);
								var detailJSON = detailResponse.getJSONObject();
								if (detailJSON != null && detailJSON.has("data"))
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
									
									if (details.has("concept"))
									{
										vz = details.getString("concept");
									}
								}
								else
								{
									log(Level.WARN, "Keine Umsatzdetails, obwohl erwartet. Bitte DetailSourceId = " + detailSourceId + " an gnampf melden");
								}
							}

							String zweck = transaction.optString("humanConceptName") + " " + vz;
							int len = Math.min(35, zweck.length());
							newUmsatz.setZweck(zweck.substring(0, len));
							zweck = zweck.substring(len);
							len = Math.min(35, zweck.length());
							newUmsatz.setZweck2(zweck.substring(0, len));
							zweck = zweck.substring(len);
							ArrayList<String> zwecke = new ArrayList<>();
							while (zweck.length() > 0)
							{
								len = Math.min(35, zweck.length());
								zwecke.add(zweck.substring(0, len));
								zweck = zweck.substring(len);
							}
							newUmsatz.setWeitereVerwendungszwecke(zwecke.toArray(new String[0]));

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
				}

				//page++;
				if (!isExtSearch && (forceAll || !duplikatGefunden.value) && ((nextPage == null) || nextPage.isEmpty())) {
					log(Level.DEBUG, "no nextPage info found -> switch to ext search");
					isExtSearch = true;
					isExtSearchPending = true;
				}
			} while ((forceAll || !duplikatGefunden.value) && (isExtSearchPending || ((nextPage != null) && !nextPage.isEmpty())));

			monitor.setPercentComplete(75); 
			log(Level.INFO, "Kontoauszug erfolgreich. Importiere Daten ...");

			reverseImport(neueUmsaetze);
		} 
		finally
		{
			// Logout
			try 
			{
				doRequest("https://de-mobi.bbva.com/TechArchitecture/grantingTickets/V02", HttpMethod.DELETE, headers, null, null);
			}
			catch (Exception e) {}
		}
		return true;
	}
}
