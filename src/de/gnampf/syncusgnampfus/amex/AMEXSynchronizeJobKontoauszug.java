package de.gnampf.syncusgnampfus.amex;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.rmi.RemoteException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.LogManager;

import javax.annotation.Resource;

import org.htmlunit.HttpMethod;
import org.htmlunit.WebClient;
import org.htmlunit.util.Cookie;
import org.json.JSONArray;
import org.json.JSONObject;

import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator.WaitForOptions;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.SameSiteAttribute;
import com.microsoft.playwright.options.WaitForSelectorState;

import de.gnampf.syncusgnampfus.KeyValue;
import de.gnampf.syncusgnampfus.SyncusGnampfusSynchronizeJob;
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
import de.willuhn.logging.Logger;
import de.willuhn.util.ApplicationException;
import de.willuhn.util.ProgressMonitor;
import io.github.kihdev.playwright.stealth4j.Stealth4j;


public class AMEXSynchronizeJobKontoauszug extends SyncusGnampfusSynchronizeJobKontoauszug implements SyncusGnampfusSynchronizeJob 
{
	private static HashMap<String, AMEXRequestInterceptor> interceptor = new HashMap<>();
	private static SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
	private static Timer keepAliveTimer = new Timer("AMEXKeepAlive", true);
	private static HashMap<String, WebClient> permanentWebClient = new HashMap<>();;
	private static HashMap<String, Boolean> needLogin = new HashMap<>();;
	private static HashMap<String, String> accountToken = new HashMap<>();

	static  
	{
		keepAliveTimer.schedule(new TimerTask() {
			@Override
			public void run() 
			{
				for (var user : needLogin.keySet())
				{
					if (!needLogin.get(user))
					{
						try 
						{
							var updateTokenPage = doRequest(permanentWebClient.get(user), "https://functions.americanexpress.com/UpdateUserSession.v1", HttpMethod.GET, interceptor.get(user).Header, null, null); 
							needLogin.put(user, updateTokenPage.getHttpStatus() != 200 || !"VALID".equals(updateTokenPage.getJSONObject().optString("tokenStatus")));
						}
						catch (IOException | URISyntaxException | ApplicationException e) {}
					}
				}
			}
		}, 5 * 60 * 1000, 5 * 60 * 1000);
	}

	@Resource
	private AMEXSynchronizeBackend backend = null;

	@Override
	protected SynchronizeBackend getBackend() { return backend; }

	private void GetCorrelationId(boolean headless, String user, String passwort) throws Exception 
	{
		log(Level.INFO, "Ermittlung CorrelationId gestartet");
		try (Playwright playwright = Playwright.create()) 
		{
			var options1 = new BrowserType.LaunchOptions().setSlowMo(100).setHeadless(headless);
			var browser = playwright.chromium().launch(options1);
			
			var stealthContext = Stealth4j.newStealthContext(browser);
			var pwPage = stealthContext.newPage();
			pwPage.route("**/*", t -> 
			{
				var intereptor = interceptor.get(user);
				if (t.request().url().endsWith("/myca/logon/emea/action/login") && "POST".equals(t.request().method()))
				{
					intereptor.log += "Triggered\n";
					try 
					{
						intereptor.Header.clear();
						t.request().headersArray().forEach(h -> 
						{
							var key = h.name.toLowerCase().trim();
							if (!"content-type".equals(key) &&
									!"host".equals(key) &&
									!"cookie".equals(key))
							{
								intereptor.Header.add(new KeyValue<>(h.name, h.value));
								if (h.name.equals("one-data-correlation-id"))
								{
									intereptor.Body = t.request().postData();
									intereptor.Url = t.request().url();
									intereptor.log += "Detected\n";
								}
							}
						});
						
					}
					catch (Exception e) 
					{ 
						intereptor.log +="EX: " + e+"\n";
					}					
				}
				
				if (intereptor.Url != null)
				{
					t.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
							  .setStatus(200)
							  .setBody("accept"));
				}
				else 
				{
					t.resume(new com.microsoft.playwright.Route.ResumeOptions());
				}
			});
			var browserCookies = new ArrayList<com.microsoft.playwright.options.Cookie>();
			var cookieManager = permanentWebClient.get(user).getCookieManager();
			for (var c : cookieManager.getCookies())
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
			pwPage.getByTestId("userid-input").fill("BlahIdBlah");
			pwPage.getByTestId("password-input").fill("BlahWortBlah");
			pwPage.getByTestId("submit-button").click();

			int timeout = 600;
			while (interceptor.get(user).Url == null && timeout > 0)
			{
				pwPage.screenshot();
				Thread.sleep(100);
				timeout--;
			}
			
			if (timeout == 0)
			{
				interceptor.get(user).Url = null;
				throw new ApplicationException("Timeout beim Ermitteln der CorrelationId");
			}
			
			for (var c : pwPage.context().cookies())
			{
				cookieManager.addCookie(new Cookie(c.domain, c.name, c.value, c.path, new Date(c.expires.longValue()*1000), c.secure, c.httpOnly, c.sameSite.toString()));
			}

			pwPage.close();
			browser.close();
		}
		catch (Exception e)
		{
			log(Level.ERROR, "Kann CorrelationId nicht ermitteln: "+e);
			log(Level.ERROR, "Meldungen vom Interceptor: " + interceptor.get(user).log);
			throw e;
		}

		permanentHeaders.clear();
		permanentHeaders.addAll(interceptor.get(user).Header);
	}
	/**
	 * @see org.jameica.hibiscus.sync.example.ExampleSynchronizeJob#execute()
	 */
	@Override
	public boolean process(Konto konto, boolean fetchSaldo, boolean fetchUmsatz, DBIterator<Umsatz> umsaetze, String user, String passwort) throws Exception
	{
		permanentWebClient.putIfAbsent(user,  this.webClient);
		needLogin.putIfAbsent(user, true);

		this.webClient = permanentWebClient.get(user);
		ArrayList<Umsatz> neueUmsaetze = new ArrayList<Umsatz>();

		interceptor.putIfAbsent(user, new AMEXRequestInterceptor());
		if (interceptor.get(user).Url == null)
		{
			GetCorrelationId(true, user, passwort);
		}
		monitor.setPercentComplete(5);
		
		if (needLogin.get(user))
		{
			var response = doRequest(interceptor.get(user).Url, HttpMethod.POST, null, "application/x-www-form-urlencoded; charset=UTF-8", interceptor.get(user).Body.replace("BlahIdBlah", URLEncoder.encode(user, "UTF-8")).replace("BlahWortBlah", URLEncoder.encode(passwort, "UTF-8")));
			if (response.getHttpStatus() == 403)
			{
				interceptor.get(user).Url = null;
				GetCorrelationId(false, user, passwort);					
				response = doRequest(interceptor.get(user).Url, HttpMethod.POST, null, "application/x-www-form-urlencoded; charset=UTF-8", interceptor.get(user).Body.replace("BlahIdBlah", URLEncoder.encode(user, "UTF-8")).replace("BlahWortBlah", URLEncoder.encode(passwort, "UTF-8")));
			}
			
			if (response.getHttpStatus() != 200)
			{
				log(Level.DEBUG, "Response: " + response.getContent());
				throw new ApplicationException("Login fehlgeschlagen, Status = " + response.getHttpStatus());
			}
			var json = response.getJSONObject();
			var reAuth = json.optJSONObject("reauth");
			if (reAuth == null)
			{
				interceptor.get(user).Url = null;
				log(Level.DEBUG, "Response: " + response.getContent());
				throw new ApplicationException("Login fehlgeschlagen, Passwort falsch?");
			}
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
				else 
				{
					questionAPP = question;
					log(Level.INFO, "OTP-Typ " + otpType + " gefunden");
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
				log(Level.DEBUG, "Response: " + response.getContent());
				throw new ApplicationException("Anforderung 2. Faktor fehlgeschlagen, Status = " + response.getHttpStatus());
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
				log(Level.DEBUG, "Response: " + response.getContent());
				throw new ApplicationException("Validierung 2. Faktor fehlgeschlagen, Status = " + response.getHttpStatus());
			}
			monitor.setPercentComplete(12);

			var mfaTime = Calendar.getInstance();
			response = doRequest(interceptor.get(user).Url, HttpMethod.POST, null, "application/x-www-form-urlencoded; charset=UTF-8", "request_type=login&Face=de_DE&Logon=Logon&version=4&mfaId=" + mfaId + "&b_hour=" + mfaTime.get(Calendar.HOUR_OF_DAY) + "&b_minute=" + mfaTime.get(Calendar.MINUTE) + "&b_second=" + mfaTime.get(Calendar.SECOND) + "&b_dayNumber=" + mfaTime.get(Calendar.DAY_OF_MONTH) + "&b_month=" + mfaTime.get(Calendar.MONTH) + "&b_year=" + mfaTime.get(Calendar.YEAR) + "&b_timeZone=2");
			if (response.getHttpStatus() != 200)
			{
				log(Level.DEBUG, "Response: " + response.getContent());
				throw new ApplicationException("Finaler Login fehlgeschlagen, Status = " + response.getHttpStatus());
			}
			monitor.setPercentComplete(13);

			needLogin.put(user,  response.getJSONObject().optInt("statusCode") != 0);
			monitor.setPercentComplete(14);

			if (needLogin.get(user))
			{
				throw new ApplicationException("Login fehlgeschlagen");
			}
		}
		monitor.setPercentComplete(15);

		if (accountToken.getOrDefault(user, null) == null)
		{
			var response = doRequest("https://global.americanexpress.com/activity/recent?appv5=false", HttpMethod.GET, null, null, null);
			if (response.getHttpStatus() != 200)
			{
				log(Level.DEBUG, "Response: " + response.getContent());
				throw new ApplicationException("Abfrage Konten fehlgeschlagen, Status = " + response.getHttpStatus());
			}
			response.getContent().lines().forEach(line -> 
			{
				if (line.contains("\\\"accountToken\\\"")) 
				{
					accountToken.put(user, line.replace("\\","").replaceAll(".*\"accountToken\",\"([^\"]+)\".*", "$1"));
				}
			});

			if (accountToken.getOrDefault(user, null) == null)
			{
				log(Level.DEBUG, "Response: " + response.getContent());
				throw new ApplicationException("Ermittlung Konten fehlgeschlagen");
			}
			else
			{
				log(Level.INFO, "Kontendaten ermittelt");
			}
		}
		monitor.setPercentComplete(20);

		ArrayList<KeyValue<String, String>> header = new ArrayList<>();
		header.add(new KeyValue<>("account_token", accountToken.get(user)));

		var response = doRequest("https://global.americanexpress.com/api/servicing/v1/financials/balances", HttpMethod.GET, header, null, null);
		if (response.getHttpStatus() != 200)
		{
			log(Level.DEBUG, "Response: " + response.getContent());
			throw new ApplicationException("Saldostatusabfrage fehlgeschlagen, Status = " + response.getHttpStatus());
		}
		var saldo = new SaldoContainer(-response.getJSONArray().getJSONObject(0).getDouble("statement_balance_amount"));
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
				log(Level.DEBUG, "Response: " + response.getContent());
				throw new ApplicationException("Abruf unverbuchter Transaktionen fehlgeschlagen, Status = " + response.getHttpStatus());
			}
			processTransactions(konto, neueUmsaetze, umsaetze, response.getJSONObject().getJSONArray("transactions"), true, null);
			monitor.setPercentComplete(30);

			response = doRequest("https://global.americanexpress.com/api/servicing/v1/financials/statement_periods", HttpMethod.GET, header, null, null);
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

				response = doRequest("https://global.americanexpress.com/api/servicing/v1/financials/transactions?limit=1000&extended_details=merchant&statement_end_date=" + period.getString("statement_end_date") + "&status=posted", HttpMethod.GET, header, null, null);
				if (response.getHttpStatus() != 200)
				{
					log(Level.DEBUG, "Request: " + response.getContent());
					throw new ApplicationException("Abruf Buchungen " +period.getString("statement_start_date") + " bis " + period.getString("statement_end_date") + " fehlgeschlagen, Status = " + response.getHttpStatus());
				}
				if (processTransactions(konto, neueUmsaetze, umsaetze, response.getJSONObject().getJSONArray("transactions"), false, saldo))
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

	private class SaldoContainer 
	{
		public Double value;
		public SaldoContainer(Double saldo) {value = saldo; }
	}

	private boolean processTransactions(Konto konto, ArrayList<Umsatz> neueUmsaetze, DBIterator<Umsatz> vorhandeneUmsaetze, JSONArray transactions, boolean pending, SaldoContainer saldo) throws RemoteException, ParseException, ApplicationException
	{
		var duplicates = false;
		for (var transObj : transactions)
		{
			var transaction = (JSONObject)transObj;
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

			Umsatz vorhandenerUmsatz = getDuplicateById(newUmsatz);
			if (vorhandenerUmsatz != null) 
			{
				if (vorhandenerUmsatz.hasFlag(Umsatz.FLAG_NOTBOOKED))
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
