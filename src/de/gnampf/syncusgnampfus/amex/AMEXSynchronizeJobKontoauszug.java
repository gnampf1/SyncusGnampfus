package de.gnampf.syncusgnampfus.amex;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.rmi.RemoteException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.annotation.Resource;

import org.htmlunit.HttpMethod;
import org.htmlunit.util.NameValuePair;
import org.json.JSONArray;
import org.json.JSONObject;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator.WaitForOptions;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route.FulfillOptions;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;

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
import de.willuhn.util.ApplicationException;
import io.github.kihdev.playwright.stealth4j.Stealth4j;
import io.github.kihdev.playwright.stealth4j.Stealth4jConfig;


public class AMEXSynchronizeJobKontoauszug extends SyncusGnampfusSynchronizeJobKontoauszug implements SyncusGnampfusSynchronizeJob 
{
	private static SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");

	@Resource
	private AMEXSynchronizeBackend backend = null;

	@Override
	protected SynchronizeBackend getBackend() { return backend; }

	private AMEXRequestInterceptor GetCorrelationId(Page pwPage, Konto konto, String user, String passwort) throws Exception 
	{
		log(Level.INFO, "Ermittlung CorrelationId gestartet");
		var interceptor = new AMEXRequestInterceptor();

		var responseHandler = new Consumer<Response>() 
		{
			@Override
			public void accept(Response r) {
				if (r.url().endsWith("/myca/logon/emea/action/login") && "POST".equals(r.request().method()))
				{
					if (r.status() == 403)
					{
						log(Level.WARN, "403 beim Login erhalten");
						interceptor.errorCount++;
					}
					else if (r.status() == 200)
					{
						interceptor.Response = new WebResult(r.status(), r.text(), null, null);
						permanentHeaders.clear();
						
						r.request().headersArray().forEach(h -> 
						{
							var key = h.name.toLowerCase().trim();
							if ("one-data-correlation-id".equals(key))
							{
								permanentHeaders.add(new KeyValue<>(h.name, h.value));
							}
						});
					}
					else
					{
						log(Level.INFO, "Status ist " + r.status() + " f\u00FCr url " + r.url());
						interceptor.errorCount++;
					}
				}
			}
		};
		
		var errorHandler = new Consumer<Request>()
		{
			@Override
			public void accept(Request request)
			{
				if (request.url().endsWith("/myca/logon/emea/action/login") && "POST".equals(request.method()))
				{
					log(Level.INFO, "Fehler beim Login erhalten: " + request.failure());
					interceptor.errorCount++;
				}			
			}
		};

		pwPage.onRequestFailed(errorHandler); 
		pwPage.onResponse(responseHandler);
		var loadFinished = new Object() { public boolean value = false; };
		pwPage.route("https://global.americanexpress.com/activity/recent*", route -> 
		{
			log(Level.INFO, "Recent called");
			if ("GET".equals(route.request().method()))
			{
				loadFinished.value = true;
				log(Level.INFO, "Recent called, load finished");
			}
			route.resume();
		});
		pwPage.route("https://functions.americanexpress.com/ReadAuthenticationChallenges.v3", route -> 
		{
			log(Level.INFO, "Challenges called");
			if ("POST".equals(route.request().method()))
			{
				log(Level.INFO, "Challenges called, load finished");
				route.fulfill(new FulfillOptions().setBody("{\"challenge\":\"(OTP_SMS | OTP_EMAIL)\",\"challengeQuestions\":[{\"category\":\"OTP_SMS\",\"format\":\"string\",\"challengeOptions\":[{\"maskedValue\":\"dummy\",\"encryptedValue\":\"\",\"type\":\"OTP\"}]},{\"category\":\"OTP_EMAIL\",\"format\":\"string\",\"challengeOptions\":[{\"maskedValue\":\"dummy@dummy\",\"encryptedValue\":\"\",\"type\":\"OTP\"}]}]}"));
				loadFinished.value = true;
			}
			else
			{
				route.resume();
			}
		});

		pwPage.navigate("https://www.americanexpress.com/de-de/account/login?DestPage=https%3A%2F%2Fglobal.americanexpress.com%2Factivity%2Frecent%3Fappv5%3Dfalse");
		var userInput = pwPage.getByTestId("userid-input");
		try 
		{
			userInput.waitFor(new WaitForOptions().setTimeout(1000));
		} catch (Exception e)
		{
			pwPage.reload();
			userInput = pwPage.getByTestId("userid-input");
		}
		
		var cookieBanner = pwPage.getByTestId("granular-banner-button-accept-all");
		if (cookieBanner != null)
		{
			try 
			{
				cookieBanner.waitFor(new WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(5000));
				if (cookieBanner.count() > 0) 
				{
					cookieBanner.click();
				}
			} 
			catch (com.microsoft.playwright.TimeoutError e) { }
		}

		userInput.fill(user); 
		pwPage.getByTestId("password-input").fill(passwort); 
		pwPage.getByTestId("submit-button").click();

		int timeout = 600;
		while (interceptor.Response == null && interceptor.errorCount < 5 && timeout > 0)
		{
			pwPage.screenshot();
			Thread.sleep(100);
			timeout--;
		}

		if (interceptor.errorCount >= 5)
		{
			throw new ApplicationException("Login fehlgeschlagen wegen technischer Probleme");
		}
		
		if (timeout == 0)
		{
			throw new ApplicationException("Timeout beim Ermitteln der CorrelationId");
		}

		pwPage.offResponse(responseHandler);
		pwPage.offRequestFailed(errorHandler);
		pwPage.waitForLoadState();
		while (!loadFinished.value)
		{
			pwPage.screenshot();
			Thread.sleep(100);
		}
		pwPage.unroute("https://functions.americanexpress.com/ReadAuthenticationChallenges.v3");
		pwPage.unroute("https://global.americanexpress.com/activity/recent*"); 

		return interceptor;
	}
	
	/**
	 * @see org.jameica.hibiscus.sync.example.ExampleSynchronizeJob#execute()
	 */
	@Override
	public boolean process(Konto konto, boolean fetchSaldo, boolean fetchUmsatz, boolean forceAll, DBIterator<Umsatz> umsaetze, String user, String passwort) throws Exception
	{
		if (konto.getUnterkonto() == null || konto.getUnterkonto().length() != 5)
		{
			var nr = konto.getUnterkonto().replaceAll(" ",  "");
			if (nr.length() == 15)
			{
				konto.setUnterkonto(nr.substring(10));
				konto.store();
			}
		}
		
		if (konto.getUnterkonto() == null || konto.getUnterkonto().length() != 5)
		{
			throw new ApplicationException("Bitte die letzten 5 Ziffern der Kreditkartennummer im Konto bei Unterkontonummer eintragen!");
		}
		
		
		Browser browser = null;
		Page page = null;
		try
		{
			ArrayList<Umsatz> neueUmsaetze = new ArrayList<Umsatz>();

			monitor.setPercentComplete(5);
			Playwright playwright = Playwright.create(); 
			var headless = "false".equals(konto.getMeta(AMEXSynchronizeBackend.META_NOTHEADLESS,  "false"));
			var options1 = new BrowserType.LaunchOptions().setHeadless(headless);
			var proxyConfig = webClient.getOptions().getProxyConfig();
			if (proxyConfig != null && proxyConfig.getProxyHost() != null)
			{
				var proxy = proxyConfig.getProxyScheme()+"://" + proxyConfig.getProxyHost() + ":" + proxyConfig.getProxyPort();
				options1.setProxy(proxy);
			}
			
			var chromePath = konto.getMeta(AMEXSynchronizeBackend.META_CHROMEPATH,  null);
			if (chromePath != null && !chromePath.isBlank())
			{
				var chromeFile = new File(chromePath); 
				if (chromeFile.isFile())
				{
					if (chromeFile.canExecute())
					{
						log(Level.INFO, "Verwende Chrome unter " + chromePath);
						options1 = options1.setExecutablePath(Path.of(chromePath));
					}
					else
					{
						log(Level.WARN, "Chrome-Pfad auf " + chromePath + " gesetzt, aber Datei nicht ausf\u00FChrbar");
					}
				}
				else
				{
					log(Level.WARN, "Chrome-Pfad auf " + chromePath + " gesetzt, aber Datei nicht vorhanden");
				}
			}
			else
			{
				log(Level.INFO, "Verwende Chromium von Playwright");
			}
			
			browser = playwright.chromium().launch(options1);

			var stealthContext = Stealth4j.newStealthContext(browser, new Stealth4jConfig.Builder().navigatorWebDriver(true).chromeLoadTimes(true).chromeApp(true).chromeCsi(true).navigatorPlugins(false).mediaCodecs(false).windowOuterDimensions(true).navigatorUserAgent(true, null).build());
			stealthContext.setExtraHTTPHeaders(Map.of("DNT", "1"));

			ArrayList<Cookie> pwCookies = new ArrayList<>();
			if ("true".equals(konto.getMeta(AMEXSynchronizeBackend.META_TRUST, "true")))
			{
				var cookiesJSON = new JSONArray(konto.getMeta(AMEXSynchronizeBackend.META_DEVICECOOKIES, "[]"));
				for (var c : cookiesJSON)
				{
					var cookieJSON = (JSONObject)c;
					var cookie = new Cookie(cookieJSON.getString("name"), cookieJSON.optString("value"));
					cookie.domain = cookieJSON.optString("domain");
					cookie.path = cookieJSON.optString("path");
					cookie.secure = cookieJSON.optBoolean("secure");
					cookie.httpOnly =  cookieJSON.optBoolean("httpOnly");
					if (cookieJSON.has("sameSite"))
					{
						cookie.sameSite = Enum.valueOf(com.microsoft.playwright.options.SameSiteAttribute.class, cookieJSON.optString("sameSite").toUpperCase());
					}
	
					if (!cookie.name.equals("amexsessioncookie"))
					{
						pwCookies.add(cookie);
					}
				}
				stealthContext.addCookies(pwCookies);
			}
			else
			{
				konto.setMeta(AMEXSynchronizeBackend.META_DEVICECOOKIES, null);
			}
			page = stealthContext.newPage();

			var interceptor = GetCorrelationId(page, konto, user, passwort);
			var json = interceptor.Response.getJSONObject();
			boolean needLogin = interceptor.Response.getJSONObject().optInt("statusCode") != 0;
			var reAuth = json.optJSONObject("reauth");
			if (reAuth == null)
			{
				log(Level.DEBUG, "Response: " + interceptor.Response.getContent());
				throw new ApplicationException("Login fehlgeschlagen, Passwort falsch?");
			}
			var assessmentToken = reAuth.getString("assessmentToken");
			if (needLogin)
			{
				var applicationId = reAuth.optString("applicationId");
				var actionId = reAuth.optString("actionId");
				var mfaId = reAuth.optString("mfaId");
				monitor.setPercentComplete(6);

				json = new JSONObject(Map.of(
						"assessmentToken", assessmentToken,
						"meta", new JSONObject(Map.of(
								"applicationId", applicationId,
								"authenticationActionId", actionId,
								"locale", "de-DE")),
						"userJourneyIdentifier", "aexp.global:create:session"
						));
				var body = json.toString();
				monitor.setPercentComplete(7);

				var response = doRequest(page, "https://functions.americanexpress.com/ReadAuthenticationChallenges.v3", HttpMethod.POST, null, "application/json; charset=UTF-8", body, true);
				if (response.getHttpStatus() != 200)
				{
					log(Level.DEBUG, "Response: " + response.getContent());
					throw new ApplicationException("Abfrage 2FA-Verfahren fehlgeschlagen, Status = " + response.getHttpStatus());
				}
				json = response.getJSONObject();
				var challengeQuestions = json.getJSONArray("challengeQuestions");
				monitor.setPercentComplete(8);
				JSONObject questionSMS = null;
				JSONObject questionEMAIL = null;
				JSONObject questionAPP = null;
				for (var questionObj : challengeQuestions)
				{
					JSONObject question = (JSONObject) questionObj;
					String otpType = question.optString("category");
					if ("OTP_EMAIL".equals(otpType))
					{
						questionEMAIL = question;
						log(Level.INFO, "OTP-Typ EMAIL gefunden");
					}
					else if ("OTP_SMS".equals(otpType))
					{
						questionSMS = question;
						log(Level.INFO, "OTP-Typ SMS gefunden");
					}
					else if ("PUSH_NOTIFICATION".equals(otpType))
					{
						questionAPP = question;
						log(Level.INFO, "OTP-Typ PUSH gefunden");
					}
					else 
					{
						questionAPP = question;
						log(Level.INFO, "Unbekannter OTP-Typ " + otpType + " gefunden");
					}
				}

				JSONObject question = null;
				String otpOrder = konto.getMeta(AMEXSynchronizeBackend.META_OTPTYPE, "ESA");
				for (int i = 0; i < otpOrder.length(); i++)
				{
					if (otpOrder.charAt(i) == 'E' && questionEMAIL != null)
					{
						question = questionEMAIL;
						break;
					}
					else if (otpOrder.charAt(i) == 'S' && questionSMS != null)
					{
						question = questionSMS;
						break;
					}
					else if (otpOrder.charAt(i) == 'A' && questionAPP != null)
					{
						question = questionAPP;
						break;
					}
				}

				if (question == null)
				{
					throw new ApplicationException("Kein verwendbares OTP-Verfahren gefunden, bitte Einstellungen \u00FCberpr\u00FCfen, empfohlen wir 'ESA', aktuell '" + otpOrder + "'");
				}

				monitor.setPercentComplete(9);
				var challengeOptions = (JSONObject)question.getJSONArray("challengeOptions").get(0);
				var encryptedValue = challengeOptions.getString("encryptedValue");
				var address = challengeOptions.optString("maskedValue");
				var type = question.getString("category");

				json = new JSONObject(Map.of(
						"locale", "de-DE",
						"otpDeliveryRequest", new JSONObject(Map.of(
								"deliveryMethod", type.replace("OTP_", ""),
								"encryptedValue", encryptedValue
								)),
						"userJourneyIdentifier", "aexp.global:create:session"
						));
				response = doRequest(page, "https://functions.americanexpress.com/CreateOneTimePasscodeDelivery.v3", HttpMethod.POST, null, "application/json; charset=UTF-8", json.toString(), true);
				if (response.getHttpStatus() != 200)
				{
					log(Level.DEBUG, "Response: " + response.getContent());
					throw new ApplicationException("Anforderung 2. Faktor fehlgeschlagen, Status = " + response.getHttpStatus());
				}
				encryptedValue = response.getJSONObject().getString("encryptedChannelValue");
				monitor.setPercentComplete(10);
				boolean pendingChallenges;
				do
				{
					if (question.equals(questionAPP))
					{
						log(Level.INFO, "Warte auf Best\u00e4tigung in der App");
						json = new JSONObject(Map.of(
								"userJourneyIdentifier", "aexp.global:create:session",
								"assessmentToken", assessmentToken,
								"meta", Map.of(
										"authenticationActionId", "MFAOI01",
										"applicationId", "LOGON01",
										"locale", "de-DE"
										)
								));
					}
					else
					{
						var otp = Application.getCallback().askUser("Bitte geben Sie den Verifizierungscode ein" + (address != null ? ", der an " + address + " geschickt wurde": ""), "Verifizierungscode:");
						monitor.setPercentComplete(11);
	
						json = new JSONObject(Map.of(
								"assessmentToken", assessmentToken,
								"challengeAnswers", new JSONArray(new JSONObject[]{
										new JSONObject(Map.of(
												"encryptedValue", encryptedValue,
												"type", "OTP",
												"value", otp
												))
								}),
								"userJourneyIdentifier", "aexp.global:create:session"
								));
					}
					response = doRequest(page, "https://functions.americanexpress.com/UpdateAuthenticationTokenWithChallenge.v3", HttpMethod.POST, null, "application/json; charset=UTF-8", json.toString(), true);
					if (response.getHttpStatus()!= 200)
					{
						log(Level.DEBUG, "Response: " + response.getContent());
						throw new ApplicationException("Validierung 2. Faktor fehlgeschlagen, Status = " + response.getHttpStatus());
					}
					if (response.getJSONObject().optJSONArray("pendingChallenges") != null  && response.getJSONObject().optJSONArray("pendingChallenges").length() > 0)
					{
						pendingChallenges = true;
						Thread.sleep(5000);
					}
					else
					{
						pendingChallenges = false;
					}
				} while (pendingChallenges);
				monitor.setPercentComplete(12);

				var mfaTime = Calendar.getInstance();
				response = doRequest(page, "https://global.americanexpress.com/myca/logon/emea/action/login", HttpMethod.POST, null, "application/x-www-form-urlencoded; charset=UTF-8", "request_type=login&Face=de_DE&Logon=Logon&version=4&mfaId=" + mfaId + "&b_hour=" + mfaTime.get(Calendar.HOUR_OF_DAY) + "&b_minute=" + mfaTime.get(Calendar.MINUTE) + "&b_second=" + mfaTime.get(Calendar.SECOND) + "&b_dayNumber=" + mfaTime.get(Calendar.DAY_OF_MONTH) + "&b_month=" + mfaTime.get(Calendar.MONTH) + "&b_year=" + mfaTime.get(Calendar.YEAR) + "&b_timeZone="+mfaTime.getTimeZone().getRawOffset()/3600000, true);
				if (response.getHttpStatus() != 200)
				{
					log(Level.DEBUG, "Response: " + response.getContent());
					throw new ApplicationException("Finaler Login fehlgeschlagen, Status = " + response.getHttpStatus());
				}
				monitor.setPercentComplete(13);

				needLogin = response.getJSONObject().optInt("statusCode") != 0;

				if (!needLogin && "true".equals(konto.getMeta(AMEXSynchronizeBackend.META_TRUST, "true")))
				{
					response = doRequest(page, "https://functions.americanexpress.com/CreateTwoFactorAuthenticationForUser.v1", HttpMethod.POST, null, "application/json; charset=UTF-8", "[{\"locale\":\"de-DE\",\"trust\":true,\"deviceName\":\"SyncusGnampfus\"}]", true);
					if (response.getHttpStatus() != 200)
					{
						log(Level.DEBUG, "Response f\u00FCr Remember: " + response.getHttpStatus() + " - " + response.getContent());
					}
				}
			}
			monitor.setPercentComplete(14);

			if (needLogin)
			{
				throw new ApplicationException("Login fehlgeschlagen");
			}
			monitor.setPercentComplete(15);

			if ("true".equals(konto.getMeta(AMEXSynchronizeBackend.META_TRUST, "true")))
			{
				var cookiesJSON = new JSONArray();
				var allCookies = stealthContext.cookies();
				for (var cookie : allCookies)
				{
					var cookieJSON = new JSONObject();
					cookieJSON.put("domain", cookie.domain);
					cookieJSON.put("name", cookie.name);
					cookieJSON.put("value", cookie.value);
					cookieJSON.put("path", cookie.path);
					cookieJSON.put("secure", cookie.secure);
					cookieJSON.put("httpOnly",  cookie.httpOnly);
					cookieJSON.put("sameSite", cookie.sameSite);
					cookiesJSON.put(cookieJSON);
				}
				konto.setMeta(AMEXSynchronizeBackend.META_DEVICECOOKIES, cookiesJSON.toString());
			}

			try
			{
				page.waitForLoadState(LoadState.DOMCONTENTLOADED);
				page.navigate("https://global.americanexpress.com/activity/recent?appv5=false");
			}
			catch (Exception e) {}

			var accountToken = konto.getMeta(AMEXSynchronizeBackend.META_ACCOUNTTOKEN, null);
			if (accountToken == null || accountToken.isBlank())
			{
				log(Level.INFO, "Ermittle AccountToken (neue Variante)");
				try 
				{
					var response = doRequest(page, "https://global.americanexpress.com/activity/recent?appv5=false", HttpMethod.GET, null, null, null, true);
					if (response.getHttpStatus() != 200)
					{
						log(Level.DEBUG, "Response: " + response.getContent());
						throw new ApplicationException("Abfrage Konten fehlgeschlagen, Status = " + response.getHttpStatus());
					}

					webClient.getOptions().setJavaScriptEnabled(true);
					var initialState = webClient.loadHtmlCodeIntoCurrentWindow(response.getContent()).executeJavaScript("window.__INITIAL_STATE__").getJavaScriptResult();
					if (initialState != null)
					{
						var initialStateText = initialState.toString();
						var initialArray = new JSONArray(initialStateText);
						var a = initialArray.getJSONArray(1);
						for (int i = 0; i < a.length(); i++)
						{
							if ("modules".equals(a.optString(i)))
							{
								var b = a.getJSONArray(i+1).getJSONArray(1);
								for (i = 0; i < b.length(); i++)
								{
									if ("axp-myca-root".equals(b.optString(i)))
									{
										var c = b.getJSONArray(i+1).getJSONArray(1);
										for (i = 0; i < c.length(); i++)
										{
											if ("products".equals(c.optString(i)))
											{
												var d = c.getJSONArray(i+1).getJSONArray(1);
												for (i = 0; i < d.length(); i++)
												{
													if ("details".equals(d.optString(i)))
													{
														var e = d.getJSONArray(i+1).getJSONArray(1);
														for (i = 0; i < e.length(); i++)
														{
															if ("types".equals(e.optString(i))) 
															{
																var f = e.getJSONArray(i+1).getJSONArray(1);
																for (i = 0; i < f.length(); i++)
																{
																	if ("CARD_PRODUCT".equals(f.optString(i))) 
																	{
																		var g = f.getJSONArray(i+1);
																		for (i = 0; i < g.length(); i++)
																		{
																			if ("productsList".equals(g.optString(i)))
																			{
																				var h = g.getJSONArray(i+1);
																				for (i = 0; i < h.length(); i++)
																				{
																					var arr = h.optJSONArray(i); 
																					if (arr != null)
																					{
																						var token = h.getString(i - 1);
																						for (var j = 0; j < h.length(); j++)
																						{
																							if ("account".equals(arr.optString(j)))
																							{
																								var accArr = arr.getJSONArray(j+1);
																								for (j = 0; j < accArr.length(); j++) 
																								{
																									if ("display_account_number".equals(accArr.optString(j)) && konto.getUnterkonto().equals(accArr.optString(j + 1)))
																									{
																										konto.setMeta(AMEXSynchronizeBackend.META_ACCOUNTTOKEN, token);
																										accountToken = token;
																										break;
																									}
																								}
																								break;
																							}
																						}
																					}
																				}
																				break;
																			}
																		}
																		break;
																	}
																}
																break;
															}
														}
														break;
													}
												}
												break;
											}
										}
										break;
									}
								}
								
								break;
							}
						}
					}
				}
				catch (Exception e)
				{
					log(Level.ERROR, "Ermittlung AccountToken fehlgeschlagen: " + e);
					accountToken = null;
				}
			}
			monitor.setPercentComplete(20);

			if (accountToken == null || accountToken.isBlank())
			{
				throw new ApplicationException("Konnte kein AccountToken f\u00FCr Karte " + konto.getUnterkonto() + " ermitteln!");
			}
			ArrayList<KeyValue<String, String>> header = new ArrayList<>();
			header.add(new KeyValue<>("account_token", accountToken));

			var response = doRequest(page, "https://global.americanexpress.com/api/servicing/v1/financials/balances", HttpMethod.GET, header, null, null, true);
			if (response.getHttpStatus() != 200)
			{
				log(Level.DEBUG, "Response: " + response.getContent());
				throw new ApplicationException("Saldostatusabfrage fehlgeschlagen, Status = " + response.getHttpStatus());
			}
			var saldoArray = response.getJSONArray();
			var saldo = new SaldoContainer();
			for (int i = 0; i < saldoArray.length(); i++)
			{
				var saldoObj = saldoArray.getJSONObject(i);
				if (accountToken.equals(saldoObj.optString("account_token")))
				{
					saldo.value = -saldoObj.getDouble("statement_balance_amount");
					break;
				}
			}
			if (fetchSaldo)
			{
				konto.setSaldo(saldo.value); // last_statement_balance_amount, interest_saver_amount
				konto.store();
				Application.getMessagingFactory().sendMessage(new SaldoMessage(konto));
			}
			monitor.setPercentComplete(25);

			if (fetchUmsatz) 
			{
				log(Level.INFO, "Hole Reservierungen");
				response = doRequest(page, "https://global.americanexpress.com/api/servicing/v1/financials/transactions?limit=1000&status=pending&extended_details=merchant", HttpMethod.GET, header, null, null, true);
				if (response.getHttpStatus() != 200)
				{
					log(Level.DEBUG, "Response: " + response.getContent());
					throw new ApplicationException("Abruf unverbuchter Transaktionen fehlgeschlagen, Status = " + response.getHttpStatus());
				}
				var duplikate = processTransactions(konto, neueUmsaetze, umsaetze, response.getJSONObject().getJSONArray("transactions"), true, null);				
				monitor.setPercentComplete(30);
				
				log(Level.INFO, "L\u00f6sche nicht mehr existierende Reservierungen");
				deleteMissingUnbooked(duplikate);
				monitor.setPercentComplete(35);

				log(Level.INFO, "Hole Buchungsperioden");
				response = doRequest(page, "https://global.americanexpress.com/api/servicing/v1/financials/statement_periods", HttpMethod.GET, header, null, null, true);
				if (response.getHttpStatus() != 200)
				{
					log(Level.DEBUG, "Request: " + response.getContent());
					throw new ApplicationException("Abruf Buchungsperioden fehlgeschlagen, Status = " + response.getHttpStatus());
				}
				var periods = response.getJSONArray();
				monitor.setPercentComplete(40);
				int step = 50 / periods.length();
				for (var periodObj : periods)
				{
					var period = (JSONObject)periodObj;
					log(Level.INFO, "Abruf Buchungen " +period.getString("statement_start_date") + " bis " + period.getString("statement_end_date"));

					response = doRequest(page, "https://global.americanexpress.com/api/servicing/v1/financials/transactions?limit=1000&extended_details=merchant&statement_end_date=" + period.getString("statement_end_date") + "&status=posted", HttpMethod.GET, header, null, null, true);
					if (response.getHttpStatus() != 200)
					{
						log(Level.DEBUG, "Request: " + response.getContent());
						throw new ApplicationException("Abruf Buchungen " +period.getString("statement_start_date") + " bis " + period.getString("statement_end_date") + " fehlgeschlagen, Status = " + response.getHttpStatus());
					}
					if (!forceAll && !processTransactions(konto, neueUmsaetze, umsaetze, response.getJSONObject().getJSONArray("transactions"), false, saldo).isEmpty())
					{
						break;
					}
					monitor.setPercentComplete(monitor.getPercentComplete() + step);
				}

				monitor.setPercentComplete(95);
				reverseImport(neueUmsaetze);
			}
		}
		finally
		{
			if (page != null) 
			{
				doRequest(page, "https://functions.americanexpress.com/DeleteUserSession.v1", HttpMethod.GET, null, null, null, true);
				page.close();
			}
			if (browser != null)
			{
				browser.close();
			}
		}
		return true;
	}
	
	protected WebResult doRequest(Page page, String url, HttpMethod method, List<KeyValue<String,String>> headers, String contentType, String data, boolean retry) throws URISyntaxException, org.htmlunit.FailingHttpStatusCodeException, IOException, ApplicationException
	{
		var responseObj = new Object() { public List<NameValuePair> header = null; };
		var responseHandler = new Consumer<Response>() 
		{
			@Override
			public void accept(Response r) 
			{
				if (r.url().equals(url) && method.toString().equals(r.request().method()))
				{
					ArrayList<NameValuePair> headers = new ArrayList<>();
					r.headersArray().forEach(h -> 
					{
						headers.add(new NameValuePair(h.name, h.value));
					});
					responseObj.header = headers;
				}
			}
		};
		

		var js = "fetch(\""+url+"\", { method: \"" + method + "\", ";
		if (data != null)
		{
			js += "body: \"" + data.replace("\"", "\\\"") + "\", ";
		}
		js += "headers: {";
		if (headers != null)
		{
			for (var header : headers)			
			{		
				js += "\"" + header.getKey() + "\": \"" +  header.getValue().replace("\"", "\\\"") + "\", ";
			}
		}
		for (var header : permanentHeaders)			
		{		
			js += "\"" + header.getKey() + "\": \"" +  header.getValue().replace("\"", "\\\"") + "\", ";
		}
		js += "\"Accept\": \"*/*\"";
		if (contentType != null)
		{
			js += ", \"Content-Type\": \"" + contentType.replace("\"", "\\\"") + "\"";
		}
		js += "}, \"mode\": \"cors\", \"credentials\": \"include\"}).then((ret) => { window.s = ret.status; return ret.text(); }, (ret) => { window.s = 500; return \"Unhandled Exception: \" + ret; }).then((text) => { return \"{\\\"status\\\":\" + window.s + \", \\\"body\\\": \" + JSON.stringify(text) + \"}\"; })";
		
		
		page.onResponse(responseHandler);
		Object result;
		try 
		{
			result = page.evaluate(js);
		}
		catch (PlaywrightException e)
		{
			page.waitForLoadState();
			result = page.evaluate(js);
		}
		var resultJSON = new JSONObject(result.toString());
		page.offResponse(responseHandler);

		try
		{
			return new WebResult(resultJSON.getInt("status"), resultJSON.getString("body"), responseObj.header, null);
		}
		catch (Exception e)
		{
			if (retry)
			{
				return doRequest(page, url, method, headers, contentType, data, retry);
			}
			else
			{
				log(Level.ERROR, "Fehler bei URL " + url + ", Antwort: " + result.toString());			
				throw e;
			}
		}
	}

	private class SaldoContainer 
	{
		public Double value;
		public SaldoContainer() {}
	}

	private ArrayList<Umsatz> processTransactions(Konto konto, ArrayList<Umsatz> neueUmsaetze, DBIterator<Umsatz> vorhandeneUmsaetze, JSONArray transactions, boolean pending, SaldoContainer saldo) throws RemoteException, ParseException, ApplicationException
	{
		var accountToken = konto.getMeta(AMEXSynchronizeBackend.META_ACCOUNTTOKEN, "");
		var kontoNr = konto.getUnterkonto();
		var duplikate = new ArrayList<Umsatz>();
		
		for (var transObj : transactions)
		{
			var transaction = (JSONObject)transObj;
			if (accountToken.equals(transaction.optString("account_token")) && kontoNr.equals(transaction.optString("display_account_number")))
			{
				var newUmsatz = (Umsatz) Settings.getDBService().createObject(Umsatz.class,null);
				newUmsatz.setKonto(konto);
				if (pending)
				{
					newUmsatz.setFlags(Umsatz.FLAG_NOTBOOKED);
				}
				newUmsatz.setTransactionId(transaction.optString("identifier"));
				newUmsatz.setZweck(transaction.optString("description").replaceAll(" +", " "));
				newUmsatz.setDatum(dateFormat.parse(transaction.getString("charge_date")));
				newUmsatz.setValuta(dateFormat.parse(transaction.getString("charge_date")));
				newUmsatz.setBetrag(-transaction.optDouble("amount"));
				newUmsatz.setCustomerRef(transaction.optString("reference_id"));
				if (saldo != null)
				{
					newUmsatz.setSaldo(saldo.value);
					saldo.value -= newUmsatz.getBetrag();
				}
				var extended = transaction.optJSONObject("extended_details");
				if (extended != null)
				{
					var merchant = extended.optJSONArray("merchant");
					if (merchant != null)
					{
						newUmsatz.setGegenkontoName(extended.optString("display_name"));
					}
				}
	
				Umsatz vorhandenerUmsatz = getDuplicateById(newUmsatz);
				if (vorhandenerUmsatz != null) 
				{
					if (!pending && vorhandenerUmsatz.hasFlag(Umsatz.FLAG_NOTBOOKED))
					{
						vorhandenerUmsatz.setFlags(Umsatz.FLAG_NONE);
						vorhandenerUmsatz.store();
						Application.getMessagingFactory().sendMessage(new ObjectChangedMessage(vorhandenerUmsatz));
					}
					if (vorhandenerUmsatz.getTransactionId() == null)
					{
						vorhandenerUmsatz.setTransactionId(newUmsatz.getTransactionId());
						vorhandenerUmsatz.store();
						Application.getMessagingFactory().sendMessage(new ObjectChangedMessage(vorhandenerUmsatz));
					}
					duplikate.add(vorhandenerUmsatz);
				}
				else
				{
					neueUmsaetze.add(newUmsatz);
				}
			}
		}
		return duplikate;
	}
}
