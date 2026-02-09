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
import com.microsoft.playwright.Browser.NewContextOptions;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator.WaitForOptions;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Page.ScreenshotOptions;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route.FulfillOptions;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ScreenshotAnimations;
import com.microsoft.playwright.options.ScreenshotType;
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
				if (r.url().endsWith(decodeItem("L215Y2EvbG9nb24vZW1lYS9hY3Rpb24vbG9naW4=")) && "POST".equals(r.request().method()))
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
						log(Level.INFO, "Login ok: " + r.text());
					}
					else
					{
						log(Level.INFO, "Login-Status ist " + r.status() + " f\u00FCr url " + r.url());
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
				if (request.url().endsWith(decodeItem("L215Y2EvbG9nb24vZW1lYS9hY3Rpb24vbG9naW4=")) && "POST".equals(request.method()))
				{
					log(Level.INFO, "Fehler beim Login erhalten: " + request.failure());
					interceptor.errorCount++;
				}			
			}
		};

		pwPage.onRequestFailed(errorHandler); 
		pwPage.onResponse(responseHandler);
		var loadFinished = new Object() { public boolean value = false; };
		pwPage.route(decodeItem("aHR0cHM6Ly9nbG9iYWwuYW1lcmljYW5leHByZXNzLmNvbS9hY3Rpdml0eS9yZWNlbnQq"), route -> 
		{
			log(Level.INFO, "Recent called");
			if ("GET".equals(route.request().method()))
			{
				loadFinished.value = true;
				log(Level.INFO, "Recent called, load finished");
			}
			route.resume();
		});
		pwPage.route(decodeItem("aHR0cHM6Ly9mdW5jdGlvbnMuYW1lcmljYW5leHByZXNzLmNvbS9SZWFkQXV0aGVudGljYXRpb25DaGFsbGVuZ2VzLnYz"), route -> 
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

		pwPage.navigate(decodeItem("aHR0cHM6Ly93d3cuYW1lcmljYW5leHByZXNzLmNvbS9kZS1kZS9hY2NvdW50L2xvZ2luP0Rlc3RQYWdlPWh0dHBzJTNBJTJGJTJGZ2xvYmFsLmFtZXJpY2FuZXhwcmVzcy5jb20lMkZhY3Rpdml0eSUyRnJlY2VudCUzRmFwcHY1JTNEZmFsc2U="));
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

		var scOptions = new ScreenshotOptions().setTimeout(1000).setAnimations(ScreenshotAnimations.DISABLED).setFullPage(false).setOmitBackground(true).setType(ScreenshotType.JPEG);
		int timeout = 600;
		while (interceptor.Response == null && interceptor.errorCount < 5 && timeout-- > 0)
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

		if (interceptor.errorCount >= 5)
		{
			konto.setMeta(AMEXSynchronizeBackend.META_ERRCOUNT, "" + (Integer.parseInt(konto.getMeta(AMEXSynchronizeBackend.META_ERRCOUNT, "0")) + 1));
			throw new ApplicationException("Login fehlgeschlagen wegen technischer Probleme, bitte nach einigen Stunden erneut probieren");
		}
		else
		{
			konto.setMeta(AMEXSynchronizeBackend.META_ERRCOUNT, "0");
		}
		
		if (interceptor.Response == null)	
		{
			throw new ApplicationException("Timeout beim Ermitteln der CorrelationId");
		}

		pwPage.offResponse(responseHandler);
		pwPage.offRequestFailed(errorHandler);
		pwPage.waitForLoadState();
		while (!loadFinished.value && timeout-- > 0)
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
		pwPage.unroute(decodeItem("aHR0cHM6Ly9mdW5jdGlvbnMuYW1lcmljYW5leHByZXNzLmNvbS9SZWFkQXV0aGVudGljYXRpb25DaGFsbGVuZ2VzLnYz"));
		pwPage.unroute(decodeItem("aHR0cHM6Ly9nbG9iYWwuYW1lcmljYW5leHByZXNzLmNvbS9hY3Rpdml0eS9yZWNlbnQq")); 

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
			
			options1.setSlowMo(10);			
			browser = playwright.firefox().launch(options1);

			var stealthContext = Stealth4j.newStealthContext(browser, new Stealth4jConfig.Builder().navigatorWebDriver(true).chromeLoadTimes(true).chromeApp(true).chromeCsi(true).navigatorPlugins(true).mediaCodecs(true).windowOuterDimensions(true).navigatorUserAgent(true, null).navigatorLanguages(true, List.of("de-DE", "de")).build());
			stealthContext.setExtraHTTPHeaders(Map.of("DNT", "1"));

			ArrayList<Cookie> pwCookies = new ArrayList<>();
			if ("true".equals(konto.getMeta(AMEXSynchronizeBackend.META_TRUST, "true")) && Integer.parseInt(konto.getMeta(AMEXSynchronizeBackend.META_ERRCOUNT, "0")) <= 3)
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

			try
			{
				page.waitForLoadState(LoadState.DOMCONTENTLOADED);
				page.navigate(decodeItem("aHR0cHM6Ly9nbG9iYWwuYW1lcmljYW5leHByZXNzLmNvbS9hcGkvc2VydmljaW5nL3YxL2ZpbmFuY2lhbHMvdHJhbnNhY3Rpb25zP2xpbWl0PTEwMDAmc3RhdHVzPXBlbmRpbmcmZXh0ZW5kZWRfZGV0YWlscz1tZXJjaGFudA=="));
				page.waitForLoadState(LoadState.DOMCONTENTLOADED);
			}
			catch (Exception e) {}

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

				var response = doRequest(page, decodeItem("aHR0cHM6Ly9mdW5jdGlvbnMuYW1lcmljYW5leHByZXNzLmNvbS9SZWFkQXV0aGVudGljYXRpb25DaGFsbGVuZ2VzLnYz"), HttpMethod.POST, null, "application/json; charset=UTF-8", body, true);
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
				String otpOrder = konto.getMeta(AMEXSynchronizeBackend.META_OTPTYPE, "ES");
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
					/*else if (otpOrder.charAt(i) == 'A' && questionAPP != null)
					{
						question = questionAPP;
						break;
					}*/
				}

				if (question == null)
				{
					log(Level.INFO, "2FA-Verfahren: " + response.getContent());
					throw new ApplicationException("Kein verwendbares OTP-Verfahren gefunden, bitte Einstellungen \u00FCberpr\u00FCfen, empfohlen wird 'ES', aktuell '" + otpOrder + "'");
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
				response = doRequest(page, decodeItem("aHR0cHM6Ly9mdW5jdGlvbnMuYW1lcmljYW5leHByZXNzLmNvbS9DcmVhdGVPbmVUaW1lUGFzc2NvZGVEZWxpdmVyeS52Mw=="), HttpMethod.POST, null, "application/json; charset=UTF-8", json.toString(), true);
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
					response = doRequest(page, decodeItem("aHR0cHM6Ly9mdW5jdGlvbnMuYW1lcmljYW5leHByZXNzLmNvbS9VcGRhdGVBdXRoZW50aWNhdGlvblRva2VuV2l0aENoYWxsZW5nZS52Mw=="), HttpMethod.POST, null, "application/json; charset=UTF-8", json.toString(), true);
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
				response = doRequest(page, decodeItem("aHR0cHM6Ly9nbG9iYWwuYW1lcmljYW5leHByZXNzLmNvbS9teWNhL2xvZ29uL2VtZWEvYWN0aW9uL2xvZ2lu"), HttpMethod.POST, null, "application/x-www-form-urlencoded; charset=UTF-8", "request_type=login&Face=de_DE&Logon=Logon&version=4&mfaId=" + mfaId + "&b_hour=" + mfaTime.get(Calendar.HOUR_OF_DAY) + "&b_minute=" + mfaTime.get(Calendar.MINUTE) + "&b_second=" + mfaTime.get(Calendar.SECOND) + "&b_dayNumber=" + mfaTime.get(Calendar.DAY_OF_MONTH) + "&b_month=" + mfaTime.get(Calendar.MONTH) + "&b_year=" + mfaTime.get(Calendar.YEAR) + "&b_timeZone="+mfaTime.getTimeZone().getRawOffset()/3600000, true);
				if (response.getHttpStatus() != 200)
				{
					log(Level.DEBUG, "Response: " + response.getContent());
					throw new ApplicationException("Finaler Login fehlgeschlagen, Status = " + response.getHttpStatus());
				}
				monitor.setPercentComplete(13);

				needLogin = response.getJSONObject().optInt("statusCode") != 0;

				if (!needLogin && "true".equals(konto.getMeta(AMEXSynchronizeBackend.META_TRUST, "true")))
				{
					response = doRequest(page, decodeItem("aHR0cHM6Ly9mdW5jdGlvbnMuYW1lcmljYW5leHByZXNzLmNvbS9DcmVhdGVUd29GYWN0b3JBdXRoZW50aWNhdGlvbkZvclVzZXIudjE="), HttpMethod.POST, null, "application/json; charset=UTF-8", "[{\"locale\":\"de-DE\",\"trust\":true,\"deviceName\":\"SyncusGnampfus\"}]", true);
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

			var accountToken = konto.getMeta(AMEXSynchronizeBackend.META_ACCOUNTTOKEN, null);
			if (accountToken == null || accountToken.isBlank())
			{
				log(Level.INFO, "Ermittle AccountToken (neue Variante)");
				JSONObject initialStateObj = new JSONObject();
				try 
				{
					var response = doRequest(page, decodeItem("aHR0cHM6Ly9nbG9iYWwuYW1lcmljYW5leHByZXNzLmNvbS9hY3Rpdml0eS9yZWNlbnQ/YXBwdjU9ZmFsc2U="), HttpMethod.GET, null, null, null, true);
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
						initialStateObj = (JSONObject)TransformStrangeJSON(initialArray);
						log(Level.DEBUG, "InitialState: " + initialStateObj.toString());
						
						var productsList = (JSONObject)initialStateObj.query("/modules/axp-myca-root/products/details/types/CARD_PRODUCT/productsList");
						for (var key : productsList.keySet())
						{
							var item = productsList.getJSONObject(key);
							var cardNo = (String)item.query("/account/display_account_number");
							if (konto.getUnterkonto().equals(cardNo))
							{
								accountToken = item.getString("account_token");
								var supp = item.getJSONObject("_flags").optBoolean("isSupp");
								if (supp)
								{
									String mainToken = null;
									for (var mainKey : productsList.keySet())
									{
										var mainItem = productsList.getJSONObject(mainKey);
										var suppAccounts = mainItem.optJSONArray("supplementary_accounts");
										if (suppAccounts != null)
										{
											for (var subAccount : suppAccounts)
											{
												if (accountToken.equals((String)subAccount))
												{
													mainToken = mainItem.getString("account_token");
													break;
												}
											}
										}
										if (mainToken != null) break;
									}
									if (mainToken != null)
									{
										log(Level.INFO,"AccountToken: " + mainToken + ", SubToken: " + accountToken);
										accountToken = mainToken;
									}
									else 
									{
										log(Level.WARN, "Konnto AccountToken für Hauptkarte nicht ermitteln");
									}
								}
								else
								{
									log(Level.INFO,"AccountToken: " + accountToken);
								}
								konto.setMeta(AMEXSynchronizeBackend.META_ACCOUNTTOKEN, accountToken);
								break;
							}
						}
					}
				}
				catch (Exception e)
				{
					log(Level.ERROR, "Ermittlung AccountToken fehlgeschlagen: " + e + "\nInitialState: " + initialStateObj.toString());
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

			var response = doRequest(page, decodeItem("aHR0cHM6Ly9nbG9iYWwuYW1lcmljYW5leHByZXNzLmNvbS9hcGkvc2VydmljaW5nL3YxL2ZpbmFuY2lhbHMvYmFsYW5jZXM="), HttpMethod.GET, header, null, null, true);
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
				response = doRequest(page, decodeItem("aHR0cHM6Ly9nbG9iYWwuYW1lcmljYW5leHByZXNzLmNvbS9hcGkvc2VydmljaW5nL3YxL2ZpbmFuY2lhbHMvdHJhbnNhY3Rpb25zP2xpbWl0PTEwMDAmc3RhdHVzPXBlbmRpbmcmZXh0ZW5kZWRfZGV0YWlscz1tZXJjaGFudA=="), HttpMethod.GET, header, null, null, true);
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
				response = doRequest(page, decodeItem("aHR0cHM6Ly9nbG9iYWwuYW1lcmljYW5leHByZXNzLmNvbS9hcGkvc2VydmljaW5nL3YxL2ZpbmFuY2lhbHMvc3RhdGVtZW50X3BlcmlvZHM="), HttpMethod.GET, header, null, null, true);
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

					response = doRequest(page, decodeItem("aHR0cHM6Ly9nbG9iYWwuYW1lcmljYW5leHByZXNzLmNvbS9hcGkvc2VydmljaW5nL3YxL2ZpbmFuY2lhbHMvdHJhbnNhY3Rpb25zP2xpbWl0PTEwMDAmZXh0ZW5kZWRfZGV0YWlscz1tZXJjaGFudCZzdGF0ZW1lbnRfZW5kX2RhdGU9") + period.getString("statement_end_date") + "&status=posted", HttpMethod.GET, header, null, null, true);
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
				page.navigate(decodeItem("aHR0cHM6Ly9nbG9iYWwuYW1lcmljYW5leHByZXNzLmNvbS9hcGkvc2VydmljaW5nL3YxL2ZpbmFuY2lhbHMvdHJhbnNhY3Rpb25zP2xpbWl0PTEwMDAmc3RhdHVzPXBlbmRpbmcmZXh0ZW5kZWRfZGV0YWlscz1tZXJjaGFudA=="));
				doRequest(page, decodeItem("aHR0cHM6Ly9mdW5jdGlvbnMuYW1lcmljYW5leHByZXNzLmNvbS9EZWxldGVVc2VyU2Vzc2lvbi52MQ=="), HttpMethod.GET, null, null, null, true);
				page.close();
			}
			if (browser != null)
			{
				browser.close();
			}
		}
		return true;
	}
	
	private class SaldoContainer 
	{
		public Double value;
		public SaldoContainer() {}
	}

	private ArrayList<Umsatz> processTransactions(Konto konto, ArrayList<Umsatz> neueUmsaetze, DBIterator<Umsatz> vorhandeneUmsaetze, JSONArray transactions, boolean pending, SaldoContainer saldo) throws RemoteException, ParseException, ApplicationException
	{
		var kontoNr = konto.getUnterkonto();
		var duplikate = new ArrayList<Umsatz>();
		
		for (var transObj : transactions)
		{
			var transaction = (JSONObject)transObj;
			if (kontoNr.equals(transaction.optString("display_account_number")))
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
	
	public Object TransformStrangeJSON(JSONArray source)
	{
		var ret = new JSONObject();
		var rootString = source.optString(0);
		var rootArray = source.optJSONArray(0);
		if (rootArray != null)
		{
			var valueArray = source.getJSONArray(0);
			return TransformStrangeJSON(valueArray);
		} 
		else if ("^ ".equals(rootString))
		{
			for (int i = 1; i < source.length(); i+= 2)
			{
				var key = source.getString(i);
				var valueObj = source.optJSONArray(i+1);
				if (valueObj != null)
				{
					ret.put(key,  TransformStrangeJSON(valueObj));
				}
				else
				{
					ret.put(key, source.get(i+1));
				}
			}
		}
		else if ("~#iM".equals(rootString))
		{
			var valueArray = source.getJSONArray(1);
			for (int i = 0; i < valueArray.length(); i+= 2)
			{
				var key = valueArray.getString(i);
				var valueObj = valueArray.optJSONArray(i+1);
				if (valueObj != null)
				{
					ret.put(key,  TransformStrangeJSON(valueObj));
				}
				else
				{
					ret.put(key, valueArray.get(i+1));
				}
			}
		}
		else if ("~#iL".equals(rootString))
		{
			JSONArray arr = new JSONArray();
			var lArr = source.getJSONArray(1);
			for (int i = 0; i < lArr.length(); i++)
			{
				var subArr = lArr.optJSONArray(i);
				if (subArr != null)
				{
					arr.put(TransformStrangeJSON(subArr));
				}
				else
				{
					arr.put(lArr.get(i));
				}
			}
			return arr;
		}
		else if (rootString != null)
		{
			return source;
		}
			
		return ret;
	}
}
