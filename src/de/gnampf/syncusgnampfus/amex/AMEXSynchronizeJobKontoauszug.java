package de.gnampf.syncusgnampfus.amex;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.rmi.RemoteException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Hashtable;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import javax.annotation.Resource;
import org.htmlunit.CookieManager;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebClient;
import org.htmlunit.WebRequest;
import org.htmlunit.javascript.host.media.PeriodicSyncManager;
import org.htmlunit.util.Cookie;
import org.json.JSONArray;
import org.json.JSONObject;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.SameSiteAttribute;

import de.gnampf.syncusgnampfus.KeyValue;
import de.gnampf.syncusgnampfus.SyncusGnampfusSynchronizeJob;
import de.gnampf.syncusgnampfus.SyncusGnampfusSynchronizeJobKontoauszug;
import de.willuhn.datasource.rmi.DBIterator;
import de.willuhn.jameica.hbci.Settings;
import de.willuhn.jameica.hbci.SynchronizeOptions;
import de.willuhn.jameica.hbci.messaging.ImportMessage;
import de.willuhn.jameica.hbci.messaging.SaldoMessage;
import de.willuhn.jameica.hbci.rmi.Konto;
import de.willuhn.jameica.hbci.rmi.Umsatz;
import de.willuhn.jameica.hbci.synchronize.SynchronizeBackend;
import de.willuhn.jameica.hbci.synchronize.jobs.SynchronizeJobKontoauszug;
import de.willuhn.jameica.system.Application;
import de.willuhn.logging.Level;
import de.willuhn.logging.Logger;
import de.willuhn.util.ApplicationException;
import de.willuhn.util.ProgressMonitor;
import io.github.kihdev.playwright.stealth4j.Stealth4j;
//import io.github.wasabithumb.playwright.PlaywrightExtra;
//import io.github.wasabithumb.playwright.PlaywrightExtraOptions;
import website.magyar.mitm.proxy.ProxyServer;


public class AMEXSynchronizeJobKontoauszug extends SyncusGnampfusSynchronizeJobKontoauszug implements SyncusGnampfusSynchronizeJob 
{
	private static CookieManager cookieContainer = null;
	private static AMEXRequestInterceptor interceptor = new AMEXRequestInterceptor();
	private static SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
	private static Timer keepAliveTimer = new Timer("AMEXKeepAlive", true);
	private static WebClient webClient;
	private static boolean needLogin = true;
	private static String accountToken = null;

	@Override
	protected String getVersion() { return "0.1"; }
	
	static  
	{
		webClient = new WebClient(new org.htmlunit.BrowserVersion.BrowserVersionBuilder(org.htmlunit.BrowserVersion.FIREFOX)
				.setAcceptLanguageHeader("de-DE")
				.setSecClientHintUserAgentHeader(null)
				.setSecClientHintUserAgentPlatformHeader(null)
				.setApplicationCodeName(null)
				.setCssAcceptHeader(null)
				.setHtmlAcceptHeader(null)
				.setImgAcceptHeader(null)
				.setScriptAcceptHeader(null)
				.setXmlHttpRequestAcceptHeader(null)
				.build());
		webClient.getOptions().setUseInsecureSSL(false);
		webClient.getOptions().setRedirectEnabled(true);
		webClient.getOptions().setJavaScriptEnabled(false);
		webClient.getOptions().setThrowExceptionOnScriptError(false);
		webClient.getOptions().setThrowExceptionOnFailingStatusCode(false);
		webClient.getOptions().setCssEnabled(false);

		cookieContainer = webClient.getCookieManager();
		if (!cookieContainer.isCookiesEnabled()) 
		{ 
			cookieContainer.setCookiesEnabled(true); 
		}

		keepAliveTimer.schedule(new TimerTask() {
			@Override
			public void run() 
			{
				if (!needLogin)
				{
					try 
					{
						var updateTokenPage = doRequest(webClient, "https://functions.americanexpress.com/UpdateUserSession.v1", HttpMethod.GET, interceptor.Header, null, null); 
						needLogin = updateTokenPage.getHttpStatus() != 200 || !"VALID".equals(updateTokenPage.getJSONObject().optString("tokenStatus"));
					}
					catch (IOException | URISyntaxException | ApplicationException e) {}
				}
			}
		}, 5 * 60 * 1000, 5 * 60 * 1000);
	}


	@Resource
	private AMEXSynchronizeBackend backend = null;
	
	@Override
	protected SynchronizeBackend getBackend() { return backend; }

	/**
	 * @see org.jameica.hibiscus.sync.example.ExampleSynchronizeJob#execute()
	 */
	@Override
	public boolean process(Konto konto, boolean fetchSaldo, boolean fetchUmsatz, DBIterator<Umsatz> umsaetze, String user, String passwort) 
	{
		try 
		{
			ArrayList<Umsatz> neueUmsaetze = new ArrayList<Umsatz>();

			boolean needLogin = true;
			if (interceptor.Url == null)
			{
				ProxyServer proxyServer = new ProxyServer(0); //0 means random port
				proxyServer.start(60000);
				proxyServer.addRequestInterceptor(interceptor);
				proxyServer.setShouldKeepSslConnectionAlive(false);

				try (Playwright playwright = Playwright.create()) 
				{
					var options1 = new BrowserType.LaunchOptions().setHeadless(true).setProxy("http://localhost:" + proxyServer.getPort());
					options1.args = new ArrayList<String>();
					options1.args.add("--ignore-certificate-errors");
					var browser = playwright.chromium().launch(options1);

					var stealthContext = Stealth4j.newStealthContext(browser);
					var pwPage = stealthContext.newPage();
					var browserCookies = new ArrayList<com.microsoft.playwright.options.Cookie>();
					for (var c : cookieContainer.getCookies())
					{
						try 
						{
							var bc = new com.microsoft.playwright.options.Cookie(c.getName(), c.getValue());
							bc.setDomain(c.getDomain());
							if (c.getExpires() != null)
							{
								bc.setExpires((double)(c.getExpires().getTime() / 1000));
							}
							bc.setHttpOnly(c.isHttpOnly());
							bc.setPath(c.getPath());
							if (c.getSameSite() != null)
							{
								bc.setSameSite(SameSiteAttribute.valueOf(c.getSameSite().toUpperCase()));
							}
							bc.setSecure(c.isSecure());
							browserCookies.add(bc);
						}
						catch (Exception ex) { }
					}
					pwPage.context().addCookies(browserCookies);
					pwPage.navigate("https://www.americanexpress.com/de-de/account/login?DestPage=https%3A%2F%2Fglobal.americanexpress.com%2Factivity%2Frecent%3Fappv5%3Dfalse");
					var cookieBanner = pwPage.getByTestId("granular-banner-button-accept-all");
					if (cookieBanner != null) 
					{
						cookieBanner.click();
					}
					pwPage.getByTestId("userid-input").fill("BlahIdBlah");
					pwPage.getByTestId("password-input").fill("BlahWortBlah");
					pwPage.getByTestId("submit-button").click();

					int timeout = 600;
					while (interceptor.Url == null && timeout > 0)
					{
						Thread.sleep(100);
						timeout--;
					}

					for (var c : pwPage.context().cookies())
					{
						cookieContainer.addCookie(new Cookie(c.domain, c.name, c.value, c.path, new Date(c.expires.longValue()*1000), c.secure, c.httpOnly, c.sameSite.toString()));
					}

					permanentHeaders.clear();
					for (var header : interceptor.Header)
					{
						if (!"content-type".equals(header.getKey().toLowerCase()) &&
								!"host".equals(header.getKey().toLowerCase()) &&
								!"cookie".equals(header.getKey().toLowerCase()))
						{
							permanentHeaders.add(new KeyValue(header.getKey(), header.getValue()));
						}
					}

					pwPage.close();
					browser.close();
				}
				finally 
				{
					proxyServer.stop();
					proxyServer = null;
				}
			}
			monitor.setPercentComplete(5);

			if (needLogin)
			{
				var response = doRequest(interceptor.Url, HttpMethod.POST, null, "application/x-www-form-urlencoded; charset=UTF-8", interceptor.Body.replace("BlahIdBlah", URLEncoder.encode(user, "UTF-8")).replace("BlahWortBlah", URLEncoder.encode(passwort, "UTF-8")));
				if (response.getHttpStatus() != 200)
				{
					log(Level.ERROR, "Login fehlgeschlagen, Status = " + response.getHttpStatus());
					log(Level.DEBUG, "Response: " + response.getContent());
					monitor.setPercentComplete(100);
					monitor.setStatus(ProgressMonitor.STATUS_ERROR);
					return false;
				}
				var json = response.getJSONObject();
				var reAuth = json.optJSONObject("reauth");
				var assessmentToken = reAuth.getString("assessmentToken");
				var applicationId = reAuth.getString("applicationId");
				var actionId = reAuth.getString("actionId");
				var mfaId = reAuth.getString("mfaId");
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

				response = doRequest("https://functions.americanexpress.com/ReadAuthenticationChallenges.v3", HttpMethod.POST, null, "application/json", body);
				if (response.getHttpStatus() != 200)
				{
					log(Level.ERROR, "Abfrage 2FA-Verfahren fehlgeschlagen, Status = " + response.getHttpStatus());
					log(Level.DEBUG, "Response: " + response.getContent());
					monitor.setPercentComplete(100);
					monitor.setStatus(ProgressMonitor.STATUS_ERROR);
					return false;
				}
				json = response.getJSONObject();
				var challengeQuestions = json.getJSONArray("challengeQuestions");
				monitor.setPercentComplete(8);
				for (var questionObj : challengeQuestions)
				{
					JSONObject question = (JSONObject) questionObj;
					if ("OTP_EMAIL".equals(question.optString("category")))
					{
						monitor.setPercentComplete(9);
						var challengeOptions = (JSONObject)question.getJSONArray("challengeOptions").get(0);
						var encryptedValue = challengeOptions.getString("encryptedValue");
						var address = challengeOptions.getString("maskedValue");
						var type = question.getString("category");

						json = new JSONObject(Map.of(
								"locale", "de-DE",
								"otpDeliveryRequest", new JSONObject(Map.of(
										"deliveryMethod", type.replace("OTP_", ""),
										"encryptedValue", encryptedValue
										)),
								"userJourneyIdentifier", "aexp.global:create:session"
								));
						response = doRequest("https://functions.americanexpress.com/CreateOneTimePasscodeDelivery.v3", HttpMethod.POST, null, "application/json", json.toString());
						if (response.getHttpStatus() != 200)
						{
							log(Level.ERROR, "Anforderung 2. Faktor fehlgeschlagen, Status = " + response.getHttpStatus());
							log(Level.DEBUG, "Response: " + response.getContent());
							monitor.setPercentComplete(100);
							monitor.setStatus(ProgressMonitor.STATUS_ERROR);
							return false;
						}
						encryptedValue = response.getJSONObject().getString("encryptedChannelValue");
						monitor.setPercentComplete(10);
						var otp = Application.getCallback().askUser("Bitte geben Sie den Verifizierungscode ein, der an " + address + " geschickt wurde", "Verifizierungscode:");
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
						response = doRequest("https://functions.americanexpress.com/UpdateAuthenticationTokenWithChallenge.v3", HttpMethod.POST, null, "application/json", json.toString());
						if (response.getHttpStatus()!= 200)
						{
							log(Level.ERROR, "Validierung 2. Faktor fehlgeschlagen, Status = " + response.getHttpStatus());
							log(Level.DEBUG, "Response: " + response.getContent());
							monitor.setPercentComplete(100);
							monitor.setStatus(ProgressMonitor.STATUS_ERROR);
							return false;
						}
						monitor.setPercentComplete(12);

						var mfaTime = Calendar.getInstance();
						response = doRequest(interceptor.Url, HttpMethod.POST, null, "application/x-www-form-urlencoded; charset=UTF-8", "request_type=login&Face=de_DE&Logon=Logon&version=4&mfaId=" + mfaId + "&b_hour=" + mfaTime.get(Calendar.HOUR_OF_DAY) + "&b_minute=" + mfaTime.get(Calendar.MINUTE) + "&b_second=" + mfaTime.get(Calendar.SECOND) + "&b_dayNumber=" + mfaTime.get(Calendar.DAY_OF_MONTH) + "&b_month=" + mfaTime.get(Calendar.MONTH) + "&b_year=" + mfaTime.get(Calendar.YEAR) + "&b_timeZone=2");
						if (response.getHttpStatus() != 200)
						{
							log(Level.ERROR, "Finaler Login fehlgeschlagen, Status = " + response.getHttpStatus());
							log(Level.DEBUG, "Response: " + response.getContent());
							monitor.setPercentComplete(100);
							monitor.setStatus(ProgressMonitor.STATUS_ERROR);
							return false;
						}
						monitor.setPercentComplete(13);

						needLogin = response.getJSONObject().optInt("statusCode") != 0;
						monitor.setPercentComplete(14);
						break;
					}
				}

				if (needLogin)
				{
					log(Level.ERROR, "Login fehlgeschlagen");
					monitor.setPercentComplete(100);
					monitor.setStatus(ProgressMonitor.STATUS_ERROR);
					return false;
				}
			}
			monitor.setPercentComplete(15);

			if (accountToken == null)
			{
				var response = doRequest("https://global.americanexpress.com/activity/recent?appv5=false", HttpMethod.GET, null, null, null);
				if (response.getHttpStatus() != 200)
				{
					log(Level.ERROR, "Abfrage Konten fehlgeschlagen, Status = " + response.getHttpStatus());
					log(Level.DEBUG, "Response: " + response.getContent());
					monitor.setPercentComplete(100);
					monitor.setStatus(ProgressMonitor.STATUS_ERROR);
					return false;
				}
				var accountTokenObj = new Object() { public String value = null; };
				response.getContent().lines().forEach(line -> 
				{
					if (line.contains("\\\"accountToken\\\"")) 
					{
						accountTokenObj.value = line.replace("\\","").replaceAll(".*\"accountToken\",\"([^\"]+)\".*", "$1");
					}
				});

				if (accountTokenObj.value == null)
				{
					log(Level.ERROR, "Ermittlung Konten fehlgeschlagen");
					log(Level.DEBUG, "Response: " + response.getContent());
					monitor.setPercentComplete(100);
					monitor.setStatus(ProgressMonitor.STATUS_ERROR);
					return false;
				}
				else
				{
					log(Level.INFO, "Kontendaten ermittelt");
				}
				accountToken = accountTokenObj.value;
			}
			monitor.setPercentComplete(20);

			ArrayList<KeyValue<String, String>> header = new ArrayList<>();
			header.add(new KeyValue<>("account_token", accountToken));

			var response = doRequest("https://global.americanexpress.com/api/servicing/v1/financials/balances", HttpMethod.GET, header, null, null);
			if (response.getHttpStatus() != 200)
			{
				log(Level.ERROR, "Saldostatusabfrage fehlgeschlagen, Status = " + response.getHttpStatus());
				log(Level.DEBUG, "Response: " + response.getContent());
				monitor.setPercentComplete(100);
				monitor.setStatus(ProgressMonitor.STATUS_ERROR);
				return true;
			}
			var saldo = new SaldoContainer(-response.getJSONObject().getDouble("statement_balance_amount"));
			if (fetchSaldo)
			{
				konto.setSaldo(saldo.value); // last_statement_balance_amount, interest_saver_amount
				konto.store();
				Application.getMessagingFactory().sendMessage(new SaldoMessage(konto));
			}
			monitor.setPercentComplete(25);

			if (fetchUmsatz) 
			{
				response = doRequest("https://global.americanexpress.com/api/servicing/v1/financials/transactions?limit=10000&extended_details=merchant&start_date=2000-01-01&end_date=2025-07-12&status=pending", HttpMethod.GET, header, null, null);
				if (response.getHttpStatus() != 200)
				{
					log(Level.ERROR, "Abruf unverbuchter Transaktionen fehlgeschlagen, Status = " + response.getHttpStatus());
					log(Level.DEBUG, "Response: " + response.getContent());
					monitor.setPercentComplete(100);
					monitor.setStatus(ProgressMonitor.STATUS_ERROR);
					return true;
				}
				processTransactions(neueUmsaetze, umsaetze, response.getJSONObject().getJSONArray("transactions"), true, null);
				monitor.setPercentComplete(30);

				response = doRequest("https://global.americanexpress.com/api/servicing/v1/financials/statement_periods", HttpMethod.GET, header, null, null);
				if (response.getHttpStatus() != 200)
				{
					log(Level.ERROR, "Abruf Buchungsperioden fehlgeschlagen, Status = " + response.getHttpStatus());
					log(Level.DEBUG, "Request: " + response.getContent());
					monitor.setPercentComplete(100);
					monitor.setStatus(ProgressMonitor.STATUS_ERROR);
					return false;
				}
				var periods = new JSONArray(response.getContent());
				monitor.setPercentComplete(40);
				int step = 50 / periods.length();
				for (var periodObj : periods)
				{
					var period = (JSONObject)periodObj;

					response = doRequest("https://global.americanexpress.com/api/servicing/v1/financials/transactions?limit=1000&extended_details=merchant&start_date=" + period.getString("statement_start_date") + "&end_date=" + period.getString("statement_end_date") + "&status=posted", HttpMethod.GET, header, null, null);
					if (response.getHttpStatus() != 200)
					{
						log(Level.ERROR, "Abruf Buchungen " +period.getString("statement_start_date") + " bis " + period.getString("statement_end_date") + " fehlgeschlagen, Status = " + response.getHttpStatus());
						log(Level.DEBUG, "Request: " + response.getContent());
						monitor.setPercentComplete(100);
						monitor.setStatus(ProgressMonitor.STATUS_ERROR);
						return false;
					}
					if (processTransactions(neueUmsaetze, umsaetze, response.getJSONObject().getJSONArray("transactions"), false, saldo))
					{
						break;
					}
					monitor.setPercentComplete(monitor.getPercentComplete() + step);
				}

				monitor.setPercentComplete(95);
				reverseImport(neueUmsaetze);
			}

			return true;
		}
		catch (Exception ex)
		{
			log(Level.ERROR, "Unbekannter Fehler " + ex.toString());
			monitor.setPercentComplete(100);
			monitor.setStatus(ProgressMonitor.STATUS_ERROR);
			return false;
		}
	}

	private class SaldoContainer 
	{
		public Double value;
		public SaldoContainer(Double saldo) {value = saldo; }
	}

	private boolean processTransactions(ArrayList<Umsatz> neueUmsaetze, DBIterator<Umsatz> vorhandeneUmsaetze, JSONArray transactions, boolean pending, SaldoContainer saldo) throws RemoteException, ParseException
	{
		var duplicates = false;
		for (var transObj : transactions)
		{
			var transaction = (JSONObject)transObj;
			var newUmsatz = (Umsatz) Settings.getDBService().createObject(Umsatz.class,null);
			if (pending)
			{
				newUmsatz.setFlags(Umsatz.FLAG_NOTBOOKED);
			}
			newUmsatz.setTransactionId(transaction.optString("identifier"));
			newUmsatz.setZweck(transaction.optString("description"));
			newUmsatz.setDatum(dateFormat.parse(transaction.getString("post_date")));
			newUmsatz.setValuta(dateFormat.parse(transaction.getString("charge_date")));
			newUmsatz.setBetrag(-transaction.optDouble("amount"));
			newUmsatz.setCustomerRef(transaction.optString("reference_id"));
			newUmsatz.setSaldo(saldo.value);
			saldo.value -= newUmsatz.getBetrag();
			var extended = transaction.optJSONObject("extended_details");
			if (extended != null)
			{
				var merchant = extended.optJSONArray("merchant");
				if (merchant != null)
				{
					newUmsatz.setGegenkontoName(extended.optString("display_name"));
				}
			}
			var vorhandenerUmsatz = getDuplicateById(newUmsatz);
			if (vorhandenerUmsatz != null) 
			{
				if (vorhandenerUmsatz.hasFlag(Umsatz.FLAG_NOTBOOKED))
				{
					vorhandenerUmsatz.setFlags(Umsatz.FLAG_NONE);
				}
				duplicates = true;
			}
			else
			{
				neueUmsaetze.add(newUmsatz);
			}
		}
		return duplicates;
	}
}
