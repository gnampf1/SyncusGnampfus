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

import javax.annotation.Resource;

import org.htmlunit.HttpMethod;
import org.json.JSONArray;
import org.json.JSONObject;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Page.GetByLabelOptions;
import de.gnampf.syncusgnampfus.KeyValue;
import de.gnampf.syncusgnampfus.SyncusGnampfusSynchronizeJob;
import de.gnampf.syncusgnampfus.SyncusGnampfusSynchronizeJobKontoauszug;
import de.gnampf.syncusgnampfus.WebResult;
import de.willuhn.datasource.rmi.DBIterator;
import de.willuhn.jameica.hbci.Settings;
import de.willuhn.jameica.hbci.messaging.ObjectChangedMessage;
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
	
	private final static String prefix = decodeItem("bW9iaWxl");
	private final static String suffix = decodeItem("ZGU=");
	private final static String proto = decodeItem("aHR0cHM6Ly8=");
	private String url = "";

	private static class BbvaLoginResult
	{
		JSONObject json;
		String tsec;
		ArrayList<KeyValue<String, String>> headers = new ArrayList<>();
	}

	private BbvaLoginResult browserLogin(Konto konto, String user, String passwort) throws Exception
	{
		var result = new BbvaLoginResult();
		com.microsoft.playwright.Page pwPage = null;
		Browser browser = null;
		try
		{
			Playwright playwright = Playwright.create();
			var options1 = new BrowserType.LaunchOptions().setHeadless(true);
			if (proxyConfig != null && proxyConfig.getProxyHost() != null)
			{
				var proxy = proxyConfig.getProxyScheme()+"://" + proxyConfig.getProxyHost() + ":" + proxyConfig.getProxyPort();
				options1.setProxy(proxy);
			}
			browser = playwright.firefox().launch(options1);

			var stealthContext = Stealth4j.newStealthContext(browser, Stealth4jConfig.builder().navigatorLanguages(true, List.of("de-DE", "de")).build());
			stealthContext.setExtraHTTPHeaders(Map.of("DNT", "1"));
			pwPage = stealthContext.newPage();

			pwPage.navigate(proto + prefix + "." + getClass().getName().substring(25,29).toLowerCase() + "." + getClass().getName().substring(0,2).toLowerCase());

			var userUpper = user.toUpperCase();
			try
			{
				pwPage.getByLabel("Username", new GetByLabelOptions().setExact(true)).fill(userUpper);
				var passwordField = pwPage.getByLabel("Password", new GetByLabelOptions().setExact(true));
				passwordField.fill(passwort);
				pwPage.locator("[data-id='btnLogin']").focus();
			}
			catch (Exception e)
			{
				log(Level.ERROR, "Err: " + e.toString());
				log(Level.ERROR, "HTML: " + pwPage.content());
				throw e;
			}

			// WICHTIG: waitForResponse() statt onResponse()-Handler. Ein onResponse()-Handler laeuft auf
			// dem Playwright-Driver-Thread; ein synchroner Folgeaufruf wie resp.text() darin blockiert
			// diesen Thread fuer immer (Deadlock, ohne Exception/Log) - resp.text() braucht naemlich
			// genau diesen Thread, um die Antwort ueberhaupt zu empfangen. waitForResponse() blockiert
			// stattdessen den aufrufenden Thread und liefert die fertige Response zurueck, auf der man
			// dann gefahrlos text()/headers() aufrufen kann.
			final var finalPage = pwPage;
			com.microsoft.playwright.Response resp;
			try
			{
				resp = finalPage.waitForResponse(
						r ->
						{
							var req = r.request();
							var postData = req.postData();
							return "POST".equals(req.method()) && postData != null && postData.toUpperCase().contains(userUpper);
						},
						new com.microsoft.playwright.Page.WaitForResponseOptions().setTimeout(60000),
						() -> finalPage.locator("[data-id='btnLogin']").click());
			}
			catch (com.microsoft.playwright.PlaywrightException e)
			{
				throw new ApplicationException("Login im Browser fehlgeschlagen oder Zeitüberschreitung: " + e.getMessage());
			}

			log(Level.INFO, "Login-Antwort erhalten");
			try
			{
				result.json = new JSONObject(resp.text());
			}
			catch (Exception e)
			{
				log(Level.ERROR, "Login-Antwort ist kein JSON: " + e);
				log(Level.DEBUG, "Body: " + resp.text());
				throw new ApplicationException("Login-Antwort ist kein JSON");
			}
			var req = resp.request();
			url = req.url();
			result.tsec = resp.headers().get("tsec");
			var reqHeaders = req.headers();
			result.headers.add(new KeyValue<>(decodeItem("YWthbWFpLWJtLXRlbGVtZXRyeQ=="), reqHeaders.get(decodeItem("YWthbWFpLWJtLXRlbGVtZXRyeQ=="))));
			result.headers.add(new KeyValue<>(decodeItem("YmJ2YS11c2VyLWFnZW50"), reqHeaders.get(decodeItem("YmJ2YS11c2VyLWFnZW50"))));
			result.headers.add(new KeyValue<>(decodeItem("Y29udGFjdGlk"), reqHeaders.get(decodeItem("Y29udGFjdGlk"))));
			result.headers.add(new KeyValue<>(decodeItem("dGhpcmRwYXJ0eS1kZXZpY2VpZA=="), reqHeaders.get(decodeItem("dGhpcmRwYXJ0eS1kZXZpY2VpZA=="))));
			result.headers.add(new KeyValue<>("user-agent", reqHeaders.getOrDefault("User-Agent", reqHeaders.getOrDefault("user-agent", ""))));
		}
		finally
		{
			if (pwPage != null) pwPage.close();
			if (browser != null) browser.close();
		}
		return result;
	}

	/**
	 * @see org.jameica.hibiscus.sync.example.ExampleSynchronizeJob#execute()
	 */
	@Override
	public boolean process(Konto konto, boolean fetchSaldo, boolean fetchUmsatz, boolean forceAll, DBIterator<Umsatz> umsaetze, String user, String passwort) throws Exception
	{
		var loginResult = browserLogin(konto, user, passwort);

		var headers = new ArrayList<KeyValue<String, String>>();
		try
		{
			WebResult response;
			JSONObject json = loginResult.json;

			var authState = json.optString("authenticationState");

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

			if (!"OK".equals(authState) || userId == null || personId == null)
			{
				log(Level.DEBUG, "Response: " + json.toString());
				throw new ApplicationException("Login fehlgeschlagen! AuthState ist " + authState);
			}

			headers.addAll(loginResult.headers);
			if (loginResult.tsec != null)
			{
				headers.add(new KeyValue<>("tsec", loginResult.tsec));
			}

			ArrayList<KeyValue<String, String>> tsecheaders = new ArrayList<>();
			headers.forEach(c -> { if ("tsec".equals(c.getKey())) tsecheaders.add(new KeyValue<>("x-tsec-token", c.getValue())); });
			response = doRequest(decodeItem("aHR0cHM6Ly9wb3J0dW51cy1odWItZXMubGl2ZS5nbG9iYWwucGxhdGZvcm0uYmJ2YS5jb20vdjEvdHNlYw=="), HttpMethod.GET, tsecheaders, null, null);
			if (response.getHttpStatus() != 200)
			{
				log(Level.DEBUG, "Response: " + response.getContent());
				throw new ApplicationException("TSEC-Abfrage fehlgeschlagen! AuthState ist " + authState);
			}

			Logger.info("Login war erfolgreich");

			response = doRequest(decodeItem("aHR0cHM6Ly9kZS1uZXQuYmJ2YS5jb20vZmluYW5jaWFsLW92ZXJ2aWV3L3YxL2ZpbmFuY2lhbC1vdmVydmlldz9jdXN0b21lci5pZD0=") + personId + "&showSicav=false&showPending=true", HttpMethod.GET, headers, null, null);
			JSONArray contracts = response.getJSONObject().optJSONObject("data").optJSONArray("contracts");
			var contractDetails = new Object() { JSONObject ktoContract = null;  JSONObject availableBalance = null; JSONObject currentBalance = null;  };
			var myIban = konto.getIban();
			var kreditkarte = konto.getUnterkonto().replace(" ", ""); 
			var isKreditkarte = kreditkarte != null && !kreditkarte.isBlank();
			contracts.forEach(c -> 
			{
				var contract = (JSONObject)c;
				if (isKreditkarte && kreditkarte.equals(contract.optString("number")))
				{
					contractDetails.ktoContract = contract;
					return;
				}
				else 
				{
					contract.optJSONArray("formats").forEach(f -> 
					{
						var format = (JSONObject)f;
						if ("IBAN".equals(format.optJSONObject("numberType").optString("id")) && myIban.equals(format.optString("number")))
						{
							contractDetails.ktoContract = contract;
							return;
						}
					});
				}
			});

			log(Level.DEBUG, "BBVA Kontenabgleich: Unterkonto=" + konto.getUnterkonto() + ", IBAN=" + myIban + ", isKreditkarte=" + isKreditkarte
					+ ", gefundener Contract=" + (contractDetails.ktoContract != null ? contractDetails.ktoContract.toString() : "keiner"));

			if (contractDetails.ktoContract == null)
			{
				log(Level.DEBUG, "Response: " + response.getContent());
				if (isKreditkarte)
				{
					throw new ApplicationException("Kredit/Debitkarte mit Nummer " + konto.getUnterkonto() + " nicht gefunden!");
				}
				else
				{
					throw new ApplicationException("Konto mit IBAN " + konto.getIban() + " nicht gefunden!");
				}
			}

			((JSONArray)contractDetails.ktoContract.query("/detail/specificAmounts")).forEach(a ->
			{
				var amountObj = (JSONObject)a;
				log(Level.DEBUG, "BBVA specificAmount: " + amountObj.toString());
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
				case "disposedAmount":
					contractDetails.currentBalance = new JSONObject(Map.of(
							"amount", amountObj.query("/amounts/0/amount"),
							"currency", new JSONObject(Map.of("id", amountObj.query("/amounts/0/currency")))
							));
					break;
				}
			});

			var contractId = contractDetails.ktoContract.optString("id");

			if (fetchSaldo)
			{
				if (contractDetails.availableBalance != null)
				{
					konto.setSaldoAvailable(contractDetails.availableBalance.optDouble("amount"));
				}
				else
				{
					log(Level.DEBUG, "BBVA: kein availableBalance im Contract gefunden, SaldoAvailable wird nicht gesetzt");
				}

				if (contractDetails.currentBalance != null)
				{
					if (isKreditkarte) 
					{
						konto.setSaldo(-contractDetails.currentBalance.optDouble("amount"));
					}
					else
					{
						konto.setSaldo(contractDetails.currentBalance.optDouble("amount"));
						response = doRequest(decodeItem("aHR0cHM6Ly9kZS1uZXQuYmJ2YS5jb20vYWNjb3VudHMvdjAvYWNjb3VudHMv") + contractId + decodeItem("L2Rpc3Bva3JlZGl0cy92YWxpZGF0aW9ucy8="), HttpMethod.GET, headers, null, null);
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
					}
				}
	
				konto.store();
				Application.getMessagingFactory().sendMessage(new SaldoMessage(konto));
			}

			if (!isKreditkarte)
			{
				response = doRequest(decodeItem("aHR0cHM6Ly9kZS1uZXQuYmJ2YS5jb20vYWNjb3VudFRyYW5zYWN0aW9ucy9WMDIvdXBkYXRlQWNjb3VudFRyYW5zYWN0aW9ucw=="), HttpMethod.POST, headers, "application/json", "{\"contracts\":[{\"id\":\"" + contractId + "\"}]}");
			}

			var dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
			dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
			var neueUmsaetze = new ArrayList<Umsatz>();
			var duplikate = new ArrayList<Umsatz>();
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
				if (isKreditkarte)
				{
					json = new JSONObject(Map.of(
							"cards", new JSONArray(List.of(
										new JSONObject(Map.of(
												"id", contractId
										))
									)),
							"customerId", personId,
							"showContractTransaction", false,
							"sortedBy", "TRANSACTION_DATE",
							"sortedType", "DESC"
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
						var filter = new JSONObject(Map.of(
								"transactionDate", new JSONObject(Map.of(
										"from", dateFormat.format(fromDate),
										"to", dateFormat.format(extSearchUntil)
										))
								));
						json.put("searchFilters", filter);
					}
				}
				else
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
						var filter = new JSONObject(Map.of(
								"dates", new JSONObject(Map.of(
										"from", dateFormat.format(fromDate),
										"to", dateFormat.format(extSearchUntil)
										)),
								"operationType", new JSONArray(List.of("BOTH"))
								));
						json.put("filter", filter);
					}
				}
				
				if (isExtSearchPending) {
					log(Level.INFO, "Transaktionen \u00E4lter als 90Tage ben\u00f6tigen eine extra Verifizierung f\u00FCr erweiterte Suche");
					
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
					var url = isKreditkarte ? 
						"aHR0cHM6Ly9kZS1uZXQuYmJ2YS5jb20vY2FyZFRyYW5zYWN0aW9ucy9WMDEvbGlzdEludGVncmF0ZWRDYXJkVHJhbnNhY3Rpb25zP3BhZ2VTaXplPTYwJnBhZ2luYXRpb25LZXk9MA==" 
						:
						"aHR0cHM6Ly9kZS1uZXQuYmJ2YS5jb20vYWNjb3VudFRyYW5zYWN0aW9ucy9WMDIvYWNjb3VudFRyYW5zYWN0aW9uc0FkdmFuY2VkU2VhcmNoP3BhZ2VTaXplPTQwJnBhZ2luYXRpb25LZXk9MA==";
						response = doRequest(decodeItem(url), HttpMethod.POST, headers, "application/json", json.toString());
					updateTsec(response, headers);
					var tmpHeaders = new ArrayList<KeyValue<String, String>>(headers);
					var headerEntry = new KeyValue<String, String>("authenticationtype", "05"); 
					tmpHeaders.add(headerEntry);
					log(Level.DEBUG, "get authData-> expect 401");
					response = doRequest(decodeItem(url), HttpMethod.POST, tmpHeaders, "application/json", json.toString());
					updateTsec(response, headers);

					for (var header : response.getResponseHeader())
					{
						if ("authenticationdata".equals(header.getKey()))
						{
							log(Level.DEBUG, "got AuthData");
							extSearchAuthenticationData = header.getValue();
						}
						else if ("authenticationstate".equals(header.getKey()))
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
					var url = isKreditkarte ? 
							"aHR0cHM6Ly9kZS1uZXQuYmJ2YS5jb20vY2FyZFRyYW5zYWN0aW9ucy9WMDEvbGlzdEludGVncmF0ZWRDYXJkVHJhbnNhY3Rpb25zP3BhZ2VTaXplPTYwJnBhZ2luYXRpb25LZXk9"
							:
							"aHR0cHM6Ly9kZS1uZXQuYmJ2YS5jb20vYWNjb3VudFRyYW5zYWN0aW9ucy9WMDIvYWNjb3VudFRyYW5zYWN0aW9uc0FkdmFuY2VkU2VhcmNoP3BhZ2VTaXplPTQwJnBhZ2luYXRpb25LZXk9";
					response = doRequest(decodeItem(url) + "0", HttpMethod.POST, tmpHeaders, "application/json", json.toString());
				} else {
					log(Level.DEBUG, "nextPage handling");
					response = doRequest(decodeItem("aHR0cHM6Ly9kZS1uZXQuYmJ2YS5jb20=") + nextPage, HttpMethod.POST, headers, "application/json", json.toString());
				}
				
				if (response.getHttpStatus() != 200)
				{
					log(Level.DEBUG, "Response: " + response.getContent());
					throw new ApplicationException("Erweiterte Suche fehlgeschlagen");
				}
				
				updateTsec(response, headers);
				
				json = response.getJSONObject();
				var pagination = json.optJSONObject("pagination");
				if (pagination != null && pagination.has("nextPage"))
				{
					var page = pagination.optInt("page");
					nextPage = pagination.optString("nextPage");
					var numPages = 0;
					if (isKreditkarte) 
					{
						numPages = (int)Math.ceil(1.0 * json.optInt("totalResults") / pagination.optInt("pageSize"));
					}
					else 
					{
						numPages = pagination.optInt("numPages");
					}
					log(Level.INFO, "Seite " + page + " / " + numPages + (isExtSearch ? "(erweiterte Suche)" : ""));
				}
				else
				{
					log(Level.INFO, "Kein Pagination-Objekt oder keine Anzahl Seiten? Gehe von 1 aus");
					nextPage = null;
				}

				JSONArray arr = null;
				if (json.has("accountTransactions"))
				{
					arr = json.getJSONArray("accountTransactions");
				}
				else if (json.has("cardsTransactions"))
				{
					arr = json.getJSONArray("cardsTransactions");
				}
				
				if (arr != null)
				{
					arr.forEach(t -> 
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
							
							String zweck;
							if (!isKreditkarte) 
							{
								newUmsatz.setBetrag(transaction.optJSONObject("amount").optDouble("amount"));
								newUmsatz.setSaldo(transaction.optJSONObject("balance").optJSONObject("accountingBalance").optDouble("amount"));
								var vz = transaction.optString("humanExtendedConceptName");

								var detailSourceKey = transaction.optJSONObject("origin").optString("detailSourceKey");
								var detailSourceId = transaction.optJSONObject("origin").optString("detailSourceId");
								if (detailSourceKey != null && 
										!"".equals(detailSourceKey) && 
										!detailSourceKey.contains(" ") && 
										!"KPSA".equals(detailSourceId) &&
										!"PGGP".equals(detailSourceId) &&
										!"SBTF".equals(detailSourceId) && 
										!"PAAD".equals(detailSourceId) &&
										!"PGGI".equals(detailSourceId))
								{
									var detailResponse = doRequest(decodeItem("aHR0cHM6Ly9kZS1uZXQuYmJ2YS5jb20vdHJhbnNmZXJzL3YwL3RyYW5zZmVycy8=") + detailSourceKey + "-RE-" + contractId + "/", HttpMethod.GET, headers, null, null);
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
								zweck = transaction.optString("humanConceptName") + " " + vz;
							}
							else
							{
								newUmsatz.setBetrag(transaction.optJSONObject("holderAmount").optDouble("amount"));
								var foreignAmount = transaction.optJSONObject("amount").optDouble("amount");
								var foreignCurrency = transaction.optJSONObject("amount").optJSONObject("currency").optString("code");
								zweck = transaction.optJSONObject("shop").optString("name");
								if (!"EUR".equals(foreignCurrency)) {
									zweck += " (" + String.format( "%.2f", foreignAmount) + " " + foreignCurrency +")";
								}
								zweck = zweck.trim();
								
								var status = transaction.optJSONObject("status").optInt("id");
								if (status < 7) 
								{
									newUmsatz.setFlags(Umsatz.FLAG_NOTBOOKED);
								}
							}

							newUmsatz.setTransactionId(transaction.optString("id"));
							newUmsatz.setValuta(dateFormat.parse(transaction.optString("valueDate")));

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

							var duplikat = getDuplicateById(newUmsatz);
							if (duplikat != null) 
							{
								if (!newUmsatz.hasFlag(Umsatz.FLAG_NOTBOOKED) && duplikat.hasFlag(Umsatz.FLAG_NOTBOOKED))
								{
									duplikat.setFlags(Umsatz.FLAG_NONE);
									duplikat.store();
									Application.getMessagingFactory().sendMessage(new ObjectChangedMessage(duplikat));
								}
								if (duplikat.getTransactionId() == null)
								{
									duplikat.setTransactionId(newUmsatz.getTransactionId());
									duplikat.store();
									Application.getMessagingFactory().sendMessage(new ObjectChangedMessage(duplikat));
								}
								duplikate.add(duplikat);
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

				if (!isExtSearch && !isKreditkarte && (forceAll || (duplikate.size() == 0 && !neueUmsaetze.isEmpty())) && ((nextPage == null) || nextPage.isEmpty())) {
					log(Level.DEBUG, "no nextPage info found -> switch to ext search");
					isExtSearch = true;
					isExtSearchPending = true;
				}
			} while ((forceAll || duplikate.size() == 0) && (isExtSearchPending || ((nextPage != null) && !nextPage.isEmpty())));

			monitor.setPercentComplete(75); 
			log(Level.INFO, "Kontoauszug erfolgreich. Importiere Daten ...");

			reverseImport(neueUmsaetze);
						
			log(Level.INFO, "L\u00f6sche nicht mehr existierende Reservierungen");
			deleteMissingUnbooked(duplikate);
			monitor.setPercentComplete(35);
		} 
		finally
		{
			// Logout
			try 
			{
				doRequest(url, HttpMethod.DELETE, headers, null, null);
			}
			catch (Exception e) {}
		}
		return true;
	}
	
	private void updateTsec(WebResult response, ArrayList<KeyValue<String, String>> headers)
	{
		for (var respHeader : response.getResponseHeader())
		{
			if ("tsec".equals(respHeader.getKey()))
			{
					log(Level.DEBUG, "replace tsec");
					headers.removeIf(p -> p.getKey().compareTo("tsec") == 0);
					headers.add(new KeyValue<String, String>("tsec", respHeader.getValue()));
			}
		}
	}
}
