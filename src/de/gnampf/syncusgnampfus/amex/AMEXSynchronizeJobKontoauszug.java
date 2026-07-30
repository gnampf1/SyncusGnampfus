package de.gnampf.syncusgnampfus.amex;

import java.nio.charset.StandardCharsets;
import java.rmi.RemoteException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Resource;

import org.htmlunit.HttpMethod;
import org.json.JSONArray;
import org.json.JSONObject;


import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;

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


public class AMEXSynchronizeJobKontoauszug extends SyncusGnampfusSynchronizeJobKontoauszug implements SyncusGnampfusSynchronizeJob 
{
	private static SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");

	private static final String AMEX_HEADER_1       = decodeItem("Y29tLmFtZXJpY2FuZXhwcmVzcy5hbmRyb2lkLmFjY3RzdmNzLmRl");
	private static final String AMEX_HEADER_2  = decodeItem("Ny4yOS4w");
	private static final String AMEX_HEADER_3     = decodeItem("QU1FWA==");
	private static final String AMEX_HEADER_4   = decodeItem("ZGU=");
	private static final String AMEX_HEADER_5      = decodeItem("ODU3ZjI1ODFk");
	private static final String AMEX_HEADER_6       = decodeItem("ZGUtREU=");
	private static final String AMEX_HEADER_7   = decodeItem("MTQ=");
	private static final String AMEX_HEADER_8 = decodeItem("c2Ftc3VuZw==");
	private static final String AMEX_HEADER_9 = decodeItem("U00tUzkxMUI=");
	private static final String AMEX_HEADER_10  = decodeItem("ZG0xcQ==");
	private static final String AMEX_HEADER_11   =
		"Android/" + AMEX_HEADER_7 + " " + AMEX_HEADER_1 + "/" + AMEX_HEADER_2 + " " + AMEX_HEADER_6
		+ " " + AMEX_HEADER_9.replace(' ', '_');

	@Resource
	private AMEXSynchronizeBackend backend = null;

	@Override
	protected SynchronizeBackend getBackend() { return backend; }

	private WebResult MobileLogin(Konto konto, String user, String passwort) throws Exception
	{
		log(Level.INFO, "Mobiler Login gestartet");
		mobileCookieJar.clear();

		// DeviceID und InstanceID aus gespeicherten Cookies lesen oder neu erzeugen.
		// Beide müssen über Läufe hinweg stabil bleiben, damit das Gerät wiedererkannt wird
		String deviceId = null;
		String instanceId = null;
		var cookiesJSON = new JSONArray(konto.getMeta(AMEXSynchronizeBackend.META_DEVICECOOKIES, "[]"));
		for (var c : cookiesJSON)
		{
			var cookieJSON = (JSONObject) c;
			var name = cookieJSON.optString("name");
			if ("device-id".equals(name))
			{
				deviceId = cookieJSON.optString("value");
			}
			else if ("instance-id".equals(name))
			{
				instanceId = cookieJSON.optString("value");
			}
		}
		var rnd = new java.security.SecureRandom();
		if (deviceId == null || deviceId.isBlank() || deviceId.length() != 16)
		{
			deviceId = String.format("%016x", rnd.nextLong());
			log(Level.INFO, "Neue DeviceID erzeugt");
		}
		else
		{
			log(Level.INFO, "Vorhandene DeviceID verwendet");
		}
		if (instanceId == null || instanceId.isBlank())
		{
			byte[] ib = new byte[16]; rnd.nextBytes(ib);
			instanceId = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ib);
			log(Level.INFO, "Neue InstanceID erzeugt");
		}

		final String finalDeviceId = deviceId;
		final String finalInstanceId = instanceId;
		final String processId = UUID.randomUUID().toString();

		long nowMillis = System.currentTimeMillis();
		var tz = java.util.TimeZone.getDefault();
		var isoUtc = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
		isoUtc.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
		var headers = new ArrayList<KeyValue<String, String>>();
		headers.add(new KeyValue<>(decodeItem("WC1TcGFuUmVsYXk="),                 "LOGIN_PROCESS"));
		headers.add(new KeyValue<>("Accept",                      "application/json"));
		headers.add(new KeyValue<>(decodeItem("eC1heHAtdGltZXpvbmVvZmZzZXQ="),        String.valueOf(tz.getOffset(nowMillis))));
		headers.add(new KeyValue<>(decodeItem("eC1heHAtcHVibGljLWd1aWQ="),           "UNAVAILABLE"));
		headers.add(new KeyValue<>(decodeItem("eC1heHAtb3M="),                    "Android"));
		headers.add(new KeyValue<>(decodeItem("eC1heHAtcmVxdWVzdC1pZA=="),            UUID.randomUUID().toString()));
		headers.add(new KeyValue<>(decodeItem("eC1heHAtZ2l0LXNoYQ=="),               AMEX_HEADER_5));
		headers.add(new KeyValue<>(decodeItem("eC1heHAtbWFudWZhY3R1cmVy"),          AMEX_HEADER_8));
		headers.add(new KeyValue<>(decodeItem("eC1heHAtZGV2aWNlLXRpbWU="),           isoUtc.format(new java.util.Date(nowMillis))));
		headers.add(new KeyValue<>(decodeItem("eC1heHAtZGV2aWNlLXRpbWV6b25lLW5hbWU="),  java.time.ZoneId.systemDefault().getId()));
		headers.add(new KeyValue<>(decodeItem("eC1heHAtbG9jYWxlcw=="),               decodeItem("eC1heHAtbG9jYWxlcw==") + ": " + AMEX_HEADER_6));
		headers.add(new KeyValue<>(decodeItem("eC1heHAtYXBwLWlk"),                AMEX_HEADER_1));
		headers.add(new KeyValue<>(decodeItem("eC1heHAtYXBwLXZlcnNpb24="),           AMEX_HEADER_2));
		headers.add(new KeyValue<>(decodeItem("eC1heHAtYXBwLW5hbWU="),              AMEX_HEADER_3));
		headers.add(new KeyValue<>(decodeItem("eC1heHAtaW5zdGFuY2UtaWQ="),           finalInstanceId));
		headers.add(new KeyValue<>(decodeItem("eC1heHAtZGV2aWNlLWNvZGUtbmFtZQ=="),      AMEX_HEADER_10));
		headers.add(new KeyValue<>(decodeItem("eC1heHAtZGV2aWNlLWlk"),             finalDeviceId));
		headers.add(new KeyValue<>(decodeItem("eC1heHAtZGV2aWNlLW1vZGVs"),          AMEX_HEADER_9));
		headers.add(new KeyValue<>(decodeItem("eC1heHAtb3MtdmVyc2lvbg=="),            AMEX_HEADER_7));
		headers.add(new KeyValue<>(decodeItem("eC1heHAtYXBwLW1hcmtldA=="),            AMEX_HEADER_4));
		headers.add(new KeyValue<>("User-Agent",                  AMEX_HEADER_11));
		headers.add(new KeyValue<>(decodeItem("eC1heHAtcHJvY2Vzcy1pZA=="),            processId));
		headers.add(new KeyValue<>(decodeItem("eC1heHAtcmVxdWVzdC1zZXF1ZW5jZQ=="),      "1"));

		var mobileLoginCookies = new ArrayList<String[]>();

		var cardArtRequest = new JSONArray();
		var cardArt = new JSONObject();
		cardArt.put("minimumWidth", 1490);
		cardArt.put("tag", "big-image");
		cardArtRequest.put(cardArt);

		var userIdLogin = new JSONObject();
		userIdLogin.put("userId", user);
		userIdLogin.put("password", passwort);
		userIdLogin.put("rememberMeFlag", true);
		var loginCredentials = new JSONObject();
		loginCredentials.put("userIdLogin", userIdLogin);

		var loginBody = new JSONObject();
		loginBody.put("loginCredentials", loginCredentials);
		loginBody.put("cardArtRequest", cardArtRequest);

		log(Level.INFO, "Sende mobilen Login-Request");
		var loginResult = mobileHttpRequest(
			decodeItem("aHR0cHM6Ly9tb2JpbGVvbmUuYW1lcmljYW5leHByZXNzLmNvbS9tb2JpbGVvbmUvbXNsL3NlcnZpY2VzL2FjY291bnRzZXJ2aWNpbmcvdjEvbG9naW5zdW1tYXJ5"),
			"POST", headers, "application/json; charset=UTF-8", loginBody.toString(), mobileLoginCookies, null);

		int loginStatus = Integer.parseInt(loginResult[0]);
		String loginBody2 = loginResult[1];
		log(Level.INFO, "Mobiler Login-Status: " + loginStatus);
		log(Level.INFO, "Mobiler Login-Response: " + loginBody2);

		if (loginStatus == 403)
		{
			konto.setMeta(AMEXSynchronizeBackend.META_ERRCOUNT, "" + (Integer.parseInt(konto.getMeta(AMEXSynchronizeBackend.META_ERRCOUNT, "0")) + 1));
			throw new ApplicationException("Login fehlgeschlagen wegen technischer Probleme (403), bitte nach einigen Stunden erneut probieren");
		}
		if (loginStatus != 200)
		{
			konto.setMeta(AMEXSynchronizeBackend.META_ERRCOUNT, "" + (Integer.parseInt(konto.getMeta(AMEXSynchronizeBackend.META_ERRCOUNT, "0")) + 1));
			throw new ApplicationException("Mobiler Login fehlgeschlagen, HTTP-Status: " + loginStatus + " - " + loginBody2);
		}

		var loginJson = new JSONObject(loginBody2);

		// Fehlercodes prüfen 
		var statusObj = loginJson.optJSONObject("status");
		boolean success = statusObj != null && statusObj.optBoolean("success");
		if (!success)
		{
			konto.setMeta(AMEXSynchronizeBackend.META_ERRCOUNT, "" + (Integer.parseInt(konto.getMeta(AMEXSynchronizeBackend.META_ERRCOUNT, "0")) + 1));
			String reportingCode = statusObj != null ? statusObj.optString("reportingCode", "") : "";
			String message = statusObj != null ? statusObj.optString("message", "") : "";
			log(Level.INFO, "Mobiler Login abgelehnt: reportingCode=" + reportingCode
					+ ", legacyCode=" + (statusObj != null ? statusObj.optString("legacyCode", "") : "")
					+ ", message=" + message);
			if ("LOGON1001".equals(reportingCode))
			{
				throw new ApplicationException("Login fehlgeschlagen: Benutzername oder Passwort falsch");
			}
			if (!message.isBlank())
			{
				throw new ApplicationException("Login fehlgeschlagen: " + message);
			}
			throw new ApplicationException("Login fehlgeschlagen (Code: " + reportingCode + ")");
		}
		konto.setMeta(AMEXSynchronizeBackend.META_ERRCOUNT, "0");

		var logonData = loginJson.optJSONObject("logonData");
		if (logonData != null)
		{
			String cupcake = logonData.optString("cupcake", null);
			String gatekeeperCookie = logonData.optString("gateKeeperCookie", null);
			if (cupcake != null && !cupcake.isBlank())
			{
				log(Level.INFO, "Setze blueboxvalues-Cookie");
				mobileCookieJar.put("blueboxvalues", cupcake);
			}
			if (gatekeeperCookie != null && !gatekeeperCookie.isBlank())
			{
				log(Level.INFO, "Setze gatekeeper-Cookie");
				mobileCookieJar.put("gatekeeper", gatekeeperCookie);
			}
		}
		// device-id ebenfalls in den okhttp-Jar (für den browserlosen Datenabruf)
		mobileCookieJar.put("device-id", finalDeviceId);
		mobileCookieJar.put("instance-id", finalInstanceId);

		// device-id- und instance-id-Cookie setzen, damit der nächste Login sofort als
		// bekanntes Gerät erkannt wird und dieselbe Instance-ID wiederverwendet wird.
		// (Werden am Ende von process() mit in META_DEVICECOOKIES persistiert.)

		permanentHeaders.clear();

		return new WebResult(loginStatus, loginBody2, null);
	}
	
	private String[] streamingLogin(String url, List<KeyValue<String, String>> headers,
			String body, List<String[]> outSetCookies) throws Exception
	{
		Request.Builder reqBuilder = new Request.Builder().url(url);
		if (headers != null)
		{
			for (var h : headers)
			{
				reqBuilder.header(h.getKey(), h.getValue());
			}
		}
		if (!mobileCookieJar.isEmpty())
		{
			var cookieHeader = new StringBuilder();
			for (var entry : mobileCookieJar.entrySet())
			{
				if (cookieHeader.length() > 0) cookieHeader.append("; ");
				cookieHeader.append(entry.getKey()).append("=").append(entry.getValue());
			}
			reqBuilder.header("Cookie", cookieHeader.toString());
		}
		MediaType mt = MediaType.parse("application/json; charset=UTF-8");
		reqBuilder.post(RequestBody.create(body.getBytes(StandardCharsets.UTF_8), mt));
		Request request = reqBuilder.build();

		final java.util.concurrent.atomic.AtomicReference<String> loginJson = new java.util.concurrent.atomic.AtomicReference<>();
		final java.util.concurrent.atomic.AtomicReference<String[]> httpError = new java.util.concurrent.atomic.AtomicReference<>();
		final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

		EventSource eventSource = EventSources.createFactory(mobileClient()).newEventSource(request, new EventSourceListener()
		{
			@Override
			public void onOpen(EventSource es, Response response)
			{
				for (String setCookieVal : response.headers("Set-Cookie"))
				{
					String nameValue = setCookieVal.split(";")[0].trim();
					int eq = nameValue.indexOf('=');
					if (eq > 0)
					{
						String cName  = nameValue.substring(0, eq).trim();
						String cValue = nameValue.substring(eq + 1).trim();
						mobileCookieJar.put(cName, cValue);
						if (outSetCookies != null) outSetCookies.add(new String[]{ cName, cValue });
					}
				}
			}

			@Override
			public void onEvent(EventSource es, String id, String type, String data)
			{
				log(Level.INFO, "SSE-Event type=" + type + ", len=" + (data != null ? data.length() : 0));
				if (data == null || data.isBlank()) return;
				// Login-Antwort erkennen (Erfolg: logonData; Ablehnung: reportingCode/twoStepLogin)
				boolean isLogin = data.contains("\"logonData\"")
						|| data.contains("\"reportingCode\"")
						|| data.contains("\"twoStepLogin\"");
				if (isLogin && loginJson.compareAndSet(null, data))
				{
					es.cancel();
					latch.countDown();
				}
			}

			@Override
			public void onFailure(EventSource es, Throwable t, Response response)
			{
				if (loginJson.get() == null)
				{
					int code = response != null ? response.code() : -1;
					String rbody = "";
					try { if (response != null && response.body() != null) rbody = response.body().string(); }
					catch (Exception ignore) {}
					if ((rbody == null || rbody.isBlank()) && t != null) rbody = t.toString();
					httpError.set(new String[]{ String.valueOf(code), rbody });
				}
				latch.countDown();
			}

			@Override
			public void onClosed(EventSource es)
			{
				latch.countDown();
			}
		});

		boolean completed = latch.await(45, java.util.concurrent.TimeUnit.SECONDS);
		eventSource.cancel();

		if (loginJson.get() != null) return new String[]{ "200", loginJson.get() };
		if (httpError.get() != null) return httpError.get();
		return new String[]{ "0", "Streaming-Login: keine Login-Antwort erhalten (Timeout=" + !completed + ")" };
	}

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
		
		
		try
		{
			ArrayList<Umsatz> neueUmsaetze = new ArrayList<Umsatz>();

			monitor.setPercentComplete(5);
			if (!("true".equals(konto.getMeta(AMEXSynchronizeBackend.META_TRUST, "true")) && Integer.parseInt(konto.getMeta(AMEXSynchronizeBackend.META_ERRCOUNT, "0")) <= 3))
			{
				konto.setMeta(AMEXSynchronizeBackend.META_DEVICECOOKIES, null);
			}

		var result = MobileLogin(konto, user, passwort);

		// logonData.reauth aus mobiler Login-Antwort lesen
		var loginRespJson = result.getJSONObject();
		var logonDataObj = loginRespJson.optJSONObject("logonData");
		var reAuth = logonDataObj != null ? logonDataObj.optJSONObject("reauth") : null;
		// statusCode != 0 bedeutet, dass eine erneute Authentifizierung (2FA) nötig ist
		boolean needLogin = logonDataObj != null && logonDataObj.optInt("statusCode", 0) != 0;
		// Für den weiteren Code wird json als Alias auf das logonData-Objekt gesetzt
		var json = logonDataObj != null ? logonDataObj : loginRespJson;

		if (needLogin && reAuth == null)
		{
			log(Level.DEBUG, "Response: " + result.getContent());
			throw new ApplicationException("Login fehlgeschlagen, Passwort falsch?");
		}

		var assessmentToken = reAuth != null ? reAuth.optString("assessmentToken", "") : "";
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

				var response = doRequest(decodeItem("aHR0cHM6Ly9mdW5jdGlvbnMuYW1lcmljYW5leHByZXNzLmNvbS9SZWFkQXV0aGVudGljYXRpb25DaGFsbGVuZ2VzLnYz"), HttpMethod.POST, null, "application/json; charset=UTF-8", body, true);
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
				response = doRequest(decodeItem("aHR0cHM6Ly9mdW5jdGlvbnMuYW1lcmljYW5leHByZXNzLmNvbS9DcmVhdGVPbmVUaW1lUGFzc2NvZGVEZWxpdmVyeS52Mw=="), HttpMethod.POST, null, "application/json; charset=UTF-8", json.toString(), true);
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
					response = doRequest(decodeItem("aHR0cHM6Ly9mdW5jdGlvbnMuYW1lcmljYW5leHByZXNzLmNvbS9VcGRhdGVBdXRoZW50aWNhdGlvblRva2VuV2l0aENoYWxsZW5nZS52Mw=="), HttpMethod.POST, null, "application/json; charset=UTF-8", json.toString(), true);
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
				response = doRequest(decodeItem("aHR0cHM6Ly9nbG9iYWwuYW1lcmljYW5leHByZXNzLmNvbS9teWNhL2xvZ29uL2VtZWEvYWN0aW9uL2xvZ2lu"), HttpMethod.POST, null, "application/x-www-form-urlencoded; charset=UTF-8", "request_type=login&Face=de_DE&Logon=Logon&version=4&mfaId=" + mfaId + "&b_hour=" + mfaTime.get(Calendar.HOUR_OF_DAY) + "&b_minute=" + mfaTime.get(Calendar.MINUTE) + "&b_second=" + mfaTime.get(Calendar.SECOND) + "&b_dayNumber=" + mfaTime.get(Calendar.DAY_OF_MONTH) + "&b_month=" + mfaTime.get(Calendar.MONTH) + "&b_year=" + mfaTime.get(Calendar.YEAR) + "&b_timeZone="+mfaTime.getTimeZone().getRawOffset()/3600000, true);
				if (response.getHttpStatus() != 200)
				{
					log(Level.DEBUG, "Response: " + response.getContent());
					throw new ApplicationException("Finaler Login fehlgeschlagen, Status = " + response.getHttpStatus());
				}
				monitor.setPercentComplete(13);

				needLogin = response.getJSONObject().optInt("statusCode") != 0;

				if (!needLogin && "true".equals(konto.getMeta(AMEXSynchronizeBackend.META_TRUST, "true")))
				{
					response = doRequest(decodeItem("aHR0cHM6Ly9mdW5jdGlvbnMuYW1lcmljYW5leHByZXNzLmNvbS9DcmVhdGVUd29GYWN0b3JBdXRoZW50aWNhdGlvbkZvclVzZXIudjE="), HttpMethod.POST, null, "application/json; charset=UTF-8", "[{\"locale\":\"de-DE\",\"trust\":true,\"deviceName\":\"" + decodeItem("T25saW5lQmFua2luZw==") + "\"}]", true);
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
				// device-id/instance-id im Konto persistieren, damit sie über Neustarts hinweg
				// stabil bleiben. mobileCookieJar hält beide nach dem Login.
				var cookiesJSON = new JSONArray();
				for (var name : new String[]{ "device-id", "instance-id" })
				{
					var value = mobileCookieJar.get(name);
					if (value != null && !value.isBlank())
					{
						cookiesJSON.put(new JSONObject().put("name", name).put("value", value));
					}
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
					var response = doRequest(decodeItem("aHR0cHM6Ly9nbG9iYWwuYW1lcmljYW5leHByZXNzLmNvbS9hY3Rpdml0eS9yZWNlbnQ/YXBwdjU9ZmFsc2U="), HttpMethod.GET, null, null, null, true);
					if (response.getHttpStatus() != 200)
					{
						log(Level.DEBUG, "Response: " + response.getContent());
						throw new ApplicationException("Abfrage Konten fehlgeschlagen, Status = " + response.getHttpStatus());
					}

					// AccountToken ueber die Seite ermitteln: htmlunit fuehrt die Seiten-JS aus,
					// die window.__INITIAL_STATE__ aufbaut (kein Inline-Script vorhanden).
					log(Level.INFO, "Ermittle AccountToken (JS-Ausfuehrung der Seite) - das kann einige Minuten dauern ...");
					var webClient = getWebClient(null);
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

			var response = doRequest(decodeItem("aHR0cHM6Ly9nbG9iYWwuYW1lcmljYW5leHByZXNzLmNvbS9hcGkvc2VydmljaW5nL3YxL2ZpbmFuY2lhbHMvYmFsYW5jZXM="), HttpMethod.GET, header, null, null, true);
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
				response = doRequest(decodeItem("aHR0cHM6Ly9nbG9iYWwuYW1lcmljYW5leHByZXNzLmNvbS9hcGkvc2VydmljaW5nL3YxL2ZpbmFuY2lhbHMvdHJhbnNhY3Rpb25zP2xpbWl0PTEwMDAmc3RhdHVzPXBlbmRpbmcmZXh0ZW5kZWRfZGV0YWlscz1tZXJjaGFudA=="), HttpMethod.GET, header, null, null, true);
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
				response = doRequest(decodeItem("aHR0cHM6Ly9nbG9iYWwuYW1lcmljYW5leHByZXNzLmNvbS9hcGkvc2VydmljaW5nL3YxL2ZpbmFuY2lhbHMvc3RhdGVtZW50X3BlcmlvZHM="), HttpMethod.GET, header, null, null, true);
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

					response = doRequest(decodeItem("aHR0cHM6Ly9nbG9iYWwuYW1lcmljYW5leHByZXNzLmNvbS9hcGkvc2VydmljaW5nL3YxL2ZpbmFuY2lhbHMvdHJhbnNhY3Rpb25zP2xpbWl0PTEwMDAmZXh0ZW5kZWRfZGV0YWlscz1tZXJjaGFudCZzdGF0ZW1lbnRfZW5kX2RhdGU9") + period.getString("statement_end_date") + "&status=posted", HttpMethod.GET, header, null, null, true);
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
			// Session serverseitig beenden (Logout) 
			try { doRequest(decodeItem("aHR0cHM6Ly9mdW5jdGlvbnMuYW1lcmljYW5leHByZXNzLmNvbS9EZWxldGVVc2VyU2Vzc2lvbi52MQ=="), HttpMethod.GET, null, null, null, true); }
			catch (Exception e) { log(Level.DEBUG, "Logout-Fehler (ignoriert): " + e); }
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
		var multiCard = konto.getMeta(AMEXSynchronizeBackend.META_MULTICARD, "false") == "true";
		var accountToken = konto.getMeta(AMEXSynchronizeBackend.META_ACCOUNTTOKEN, "");
		
		for (var transObj : transactions)
		{
			var transaction = (JSONObject)transObj;
			if (
					(!multiCard && kontoNr.equals(transaction.optString("display_account_number"))) ||
					(multiCard && accountToken.equals(transaction.optString("account_token")))
				)
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
