package de.gnampf.syncusgnampfus.raisin;

import com.microsoft.playwright.*;
import com.microsoft.playwright.BrowserType.LaunchOptions;

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
import de.willuhn.logging.Logger;
import de.willuhn.util.ApplicationException;
import io.github.kihdev.playwright.stealth4j.Stealth4j;
import io.github.kihdev.playwright.stealth4j.Stealth4jConfig;

import org.htmlunit.HttpMethod;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ProcessHandle.Info;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.rmi.RemoteException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import javax.annotation.Resource;

public class RaisinSynchronizeJobKontoauszug
        extends SyncusGnampfusSynchronizeJobKontoauszug {

	@Resource
	private RaisinSynchronizeBackend backend = null;

	@Override
	protected SynchronizeBackend getBackend() { return backend; }

    // -------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------
    private static final String TAMS_BASE = decodeItem("aHR0cHM6Ly9hcGkyLndlbHRzcGFyZW4uZGUvdGFtcy92MQ==");
    private static final String DEPOSIT_BASE = decodeItem("aHR0cHM6Ly9hcGkyLndlbHRzcGFyZW4uZGUvZGFzL3YxL2RlcG9zaXRz");
    private static final String LOGOUT_URL = decodeItem("aHR0cHM6Ly9hcGkyLndlbHRzcGFyZW4uZGUvdW1zL3YxL3B1YmxpYy90b2tlbi9sb2dvdXQ=");
    private static final String TOKEN_URL = decodeItem("aHR0cHM6Ly9hdXRoLnJhaXNpbi5jb20vYXV0aC9yZWFsbXMvZ2xvYmFsL3Byb3RvY29sL29wZW5pZC1jb25uZWN0L3Rva2Vu");


    // -------------------------------------------------------------------
    // State captured during login
    // -------------------------------------------------------------------
    private String accessToken;
    private String refreshToken;
    private String bacId;
    private LaunchOptions options1;


    private Page getPage(Konto konto, boolean headless) throws JSONException, RemoteException
    {
		Playwright playwright = Playwright.create(); 
		options1 = new BrowserType.LaunchOptions().setHeadless(headless);
		var proxyConfig = webClient.getOptions().getProxyConfig();
		if (proxyConfig != null && proxyConfig.getProxyHost() != null)
		{
			var proxy = proxyConfig.getProxyScheme()+"://" + proxyConfig.getProxyHost() + ":" + proxyConfig.getProxyPort();
			options1.setProxy(proxy);
		}
		
		options1.setSlowMo(10);			
		Browser browser = playwright.firefox().launch(options1);

		var stealthContext = Stealth4j.newStealthContext(browser, new Stealth4jConfig.Builder().navigatorWebDriver(true).chromeLoadTimes(true).chromeApp(true).chromeCsi(true).navigatorPlugins(true).mediaCodecs(true).windowOuterDimensions(true).navigatorUserAgent(true, "Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0").navigatorLanguages(true, List.of("de-DE", "de")).build());
		stealthContext.setExtraHTTPHeaders(Map.of("DNT", "1"));

		return stealthContext.newPage();
    }
    
	@Override
	public boolean process(Konto konto, boolean fetchSaldo, boolean fetchUmsatz, boolean forceAll, DBIterator<Umsatz> umsaetze, String user, String passwort) throws Exception
	{
		try
		{
			monitor.setPercentComplete(5);

	        loginWithPlaywright(konto, user, passwort);

	        String unterKonto = getKonto().getUnterkonto();
	        StringBuilder sb = new StringBuilder();
	        for (int i = 0; i < unterKonto.length(); i++) 
	        {
	            if (i > 0 && i % 3 == 0) sb.append("_");
	            sb.append(unterKonto.charAt(i));
	        }
	        unterKonto = sb.toString();
	        
	        boolean hasUnterkonto = unterKonto != null && !unterKonto.isBlank();
	
			ArrayList<Umsatz> neueUmsaetze = new ArrayList<Umsatz>();
			var duplikate = new ArrayList<Umsatz>();

	        if (hasUnterkonto) 
	        {
	            syncUnterkonto(konto, neueUmsaetze, duplikate, fetchSaldo, fetchUmsatz, forceAll, unterKonto.trim());
	        }
	        else 
	        {
	            syncHauptkonto(konto, neueUmsaetze, duplikate, fetchSaldo, fetchUmsatz, forceAll);
	        }

	        reverseImport(neueUmsaetze);

	        return true;
		}
		finally
		{
			if (accessToken != null)
			{
				try 
				{
					doRequest(LOGOUT_URL, HttpMethod.POST, null, "application/json", "{\"refresh_token\":\"" + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8) + "\"}");
			    } 
				catch (Exception e) 
				{
			        log(Level.WARN, "Logout fehlgeschlagen: " + e.getMessage());
			    }		
			}
		}
    }

    private void syncHauptkonto(Konto konto, ArrayList<Umsatz> neueUmsaetze, ArrayList<Umsatz> duplikate, boolean fetchSaldo, boolean fetchUmsatz, boolean forceAll) throws Exception 
    {
        String traId = resolveTraId();
        log(Level.INFO, "TRA-ID: " + traId);

        String accountUrl = TAMS_BASE + "/accounts/" + traId + "?embed=balance";
        var response = doRequest(accountUrl, HttpMethod.GET, null, null, null);
        var accountData = response.getJSONObject();

        if (fetchSaldo)
        {
	        var balanceWrapper = accountData.optJSONObject("balance");
	        if (balanceWrapper != null)
	    	{
        		konto.setSaldo(Double.parseDouble(balanceWrapper.optString("current", "0")));
            	konto.setSaldoAvailable(Double.parseDouble(balanceWrapper.optString("available", "0")));

        		konto.store();
        		Application.getMessagingFactory().sendMessage(new SaldoMessage(konto));
                log(Level.INFO, "Hauptkonto Saldo=" + konto.getSaldo() + " Verfuegbar=" + konto.getSaldoAvailable());
	    	}
        }
        
		if (fetchUmsatz)
		{
	    	LocalDate fromDate = LocalDate.of(1990, 1, 1);
	    	if (!forceAll) 
	    	{
	    		umsaetze.begin();
	    		while (umsaetze.hasNext())
	    		{
	    			Umsatz umsatz = umsaetze.next();
	    			var datum = umsatz.getDatum();
	    			if (datum != null) 
	    			{
		    			var umsatzDatum = LocalDate.parse(new java.text.SimpleDateFormat("yyyy-MM-dd").format(datum));
		    			if (fromDate.isBefore(umsatzDatum)) 
		    			{
		    				fromDate = umsatzDatum;
		    			}
	    			}
	    		}
	    		fromDate = fromDate.minusDays(7);
	    	}    	        
			
	    	JSONArray transactions;
		    do 
		    {
		        String dateFrom = fromDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
		        String txUrl = TAMS_BASE + "/accounts/" + traId + "/transactions?offset=0&limit=100&date_from=" + dateFrom;
	
		        response = doRequest(txUrl, HttpMethod.GET, null, null, null);
		        transactions = response.getJSONArray();
		        var batch = new ArrayList<Umsatz>();
		        log(Level.INFO, transactions.length() + " Transaktionen seit " + dateFrom);
	
		        for (int i = 0; i < transactions.length(); i++) 
		        {
		            JSONObject tx = transactions.getJSONObject(i);
		            storeTransaction(
		            		konto,
		            		batch,
		            		duplikate,
		                    tx.optString("id"),
		                    parseDate(tx.optString("bookingDate", "").substring(0, 10)),
		                    parseDate(tx.optString("valueDate",  "").substring(0, 10)),
		                    tx.optDouble("amount", 0.0),
		                    tx.optString("referenceText", ""),
		                    tx.optString("counterpartyName", ""),
		                    tx.optString("counterpartyIban", ""),
		                    tx.optString("counterpartyBic", ""),
		                    tx.optString("endToEndId", ""),
		                    tx.optString("status", "BOOKED").equals("BOOKED")
		            );
		            var datum = LocalDate.parse(tx.optString("bookingDate", "").substring(0, 10));
		            if (fromDate.isBefore(datum)) 
		            {
		            	fromDate = datum;
		            }
		        }
		    } 
		    while (transactions.length() == 100);
		}
    }

    private void storeTransaction(Konto konto, ArrayList<Umsatz> neueUmsaetze, ArrayList<Umsatz> duplikate, String id, Date buchungsDatum, Date valutaDatum, double betrag, String zweck,
			String gegenkontoName, String gegenkontoIban, String gegenkontoBic, String endToEndId, boolean gebucht) throws RemoteException, ApplicationException 
    {
		var newUmsatz = (Umsatz) Settings.getDBService().createObject(Umsatz.class,null);
		newUmsatz.setKonto(konto);
		if (!gebucht)
		{
			newUmsatz.setFlags(Umsatz.FLAG_NOTBOOKED);
		}
		newUmsatz.setTransactionId(id);
		newUmsatz.setZweck(zweck);
		newUmsatz.setDatum(buchungsDatum);
		newUmsatz.setValuta(valutaDatum);
		newUmsatz.setBetrag(betrag);
		newUmsatz.setEndToEndId(endToEndId);
		newUmsatz.setGegenkontoName(gegenkontoName);
		newUmsatz.setGegenkontoNummer(gegenkontoIban);
		newUmsatz.setGegenkontoBLZ(gegenkontoBic);

		Umsatz vorhandenerUmsatz = getDuplicateById(newUmsatz);
		if (vorhandenerUmsatz != null) 
		{
			if (gebucht && vorhandenerUmsatz.hasFlag(Umsatz.FLAG_NOTBOOKED))
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

	private String resolveTraId() throws Exception 
	{
        var response = doRequest(TAMS_BASE + "/accounts?filter=customerId+eq+" + bacId, HttpMethod.GET, null, null, null);
        var accounts = response.getJSONArray();

        for (int i = 0; i < accounts.length(); i++) 
        {
            JSONObject acc = accounts.getJSONObject(i);
            if ("ACTIVE".equals(acc.optString("state"))
                    && "TA_INTERNAL".equals(acc.optString("type"))) 
            {
                return acc.getString("id");
            }
        }

        if (accounts.length() > 0) 
        {
            return accounts.getJSONObject(0).getString("id");
        }
        throw new RuntimeException("Keine TRA-ID fuer BAC-ID: " + bacId);
    }

    private void syncUnterkonto(Konto konto, ArrayList<Umsatz> neueUmsaetze, ArrayList<Umsatz> duplikate, boolean fetchSaldo, boolean fetchUmsaetze,  boolean forceAll, String omaId) throws Exception 
    {
        var response = doRequest(DEPOSIT_BASE + "/" + omaId, HttpMethod.GET, null, null, null);
        var accounting = response.getJSONObject();

        if (accounting == null) return;

        if (fetchSaldo)
        {
	        var balanceObj = accounting.optJSONObject("balance");
	        if (balanceObj != null) 
	        {
	            var amount = balanceObj.optJSONObject("amount");
	            if (amount != null) 
	            {
	    	        konto.setSaldo(Double.parseDouble(amount.optString("denomination", "0")));

	    	        var availableBalanceObj = accounting.optJSONObject("available_balance");
	    	        if (availableBalanceObj != null) 
	    	        {
	    	            amount = availableBalanceObj.optJSONObject("amount");
	    	            if (amount != null) 
	    	            {
	    	            	konto.setSaldoAvailable(Double.parseDouble(amount.optString("denomination", "0")));
	    	            }
	    	        }

	    	        konto.store();
	    	        Application.getMessagingFactory().sendMessage(new SaldoMessage(konto));
	    	        log(Level.INFO, "Unterkonto " + omaId + " Saldo=" + konto.getSaldo() + " Verf\\u00FCgbar=" + konto.getSaldoAvailable());
	            }
	        }
	    }

        if (fetchUmsaetze)
        {
	        var transactions = accounting.optJSONArray("transactions");
	        if (transactions == null) 
	        {
	            log(Level.INFO, "Keine Transaktionen f\u00FCr " + omaId);
	            return;
	        }

	        log(Level.INFO,"Unterkonto " + omaId + ": " + transactions.length() + " Transaktionen gefunden.");

	        for (int i = 0; i < transactions.length(); i++) 
	        {
	            var tx = transactions.getJSONObject(i);
	
	            var rawDate = tx.optString("value_date", "");
	            if (rawDate.length() >= 10) 
            	{
	            	rawDate = rawDate.substring(0, 10);
            	}
	            var valueDate = parseDate(rawDate);
	
	            var betrag = 0.0;
	            var amountObj = tx.optJSONObject("amount");
	            if (amountObj != null) 
	            {
	                betrag = Double.parseDouble(amountObj.optString("denomination", "0"));
	            }
	
	            var type = tx.optString("type", "");
	            if ("PAY OUT".equals(type) || "WITHDRAWAL".equals(type)) 
	            {
	                betrag = -Math.abs(betrag);
	            }
	
	            var status  = tx.optString("status", "COMPLETED");
	            var booked = !"PENDING".equals(status);
	
	            var transactionId  = tx.optString("transaction_id", "");
	            var reference      = tx.optString("transaction_reference", "");
	
	            // Use type + reference as Zweck
	            var zweck = type;
	            if (!reference.isEmpty()) {
	                zweck = type + " / " + reference;
	            }
	
	            // creation_datetime as booking date (closest we have)
	            var creationRaw = tx.optString("creation_datetime", rawDate);
	            if (creationRaw.length() >= 10) 
            	{
	            	creationRaw = creationRaw.substring(0, 10);
            	}
	            Date buchungsDatum = parseDate(creationRaw);
	
	            storeTransaction(
	                    konto,
	                    neueUmsaetze,
	                    duplikate,
	                    transactionId,
	                    buchungsDatum,
	                    valueDate,
	                    betrag,
	                    zweck,
	                    "",   // gegenkontoName 
	                    "",   // gegenkontoIban 
	                    accounting.optString("partner_bank", ""),  
	                    "",   // endToEndId 
	                    booked
	            );
	        }
        }
    }
    
    private static class CaptchaData 
    {
        String username;
        String password;
        String captchaToken;
        String sardineKey;
    }
    
    private CaptchaData solveCaptcha(Konto konto, Page page, String username, String password) throws Exception 
    {
        var result = new CaptchaData();

        page.route("**/openid-connect/token", route -> 
        {
            try 
            {
                var req = route.request();

                var postData = req.postData();
                if (postData != null) {
                    for (var part : postData.split("&")) 
                    {
                        var kv = part.split("=", 2);
                        if (kv.length == 2) 
                        {
                            var key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
                            var val = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                            if ("username".equals(key)) result.username = val;
                            if ("password".equals(key)) result.password = val;
                        }
                    }
                }

                var headers = req.headers();
                result.captchaToken = headers.get("captcha-solution-token");
                result.sardineKey   = headers.get("sardine-session-key");

                log(Level.INFO, "Token-Request abgefangen, captchaToken=" + result.captchaToken);
            } 
            finally 
            {
                route.abort();
            }
        });

        page.navigate(decodeItem("aHR0cHM6Ly93d3cucmFpc2luLmNvbS9kZS1kZS9sb2dpbi8="));
        try 
        {
            page.waitForSelector("button#accept[data-action-type='accept']", new Page.WaitForSelectorOptions().setTimeout(10_000));
            page.locator("button#accept[data-action-type='accept']").click();
            log(Level.INFO, "Cookie-Banner akzeptiert.");
        }
        catch (Exception e) 
        {
            log(Level.INFO, "Kein Cookie-Banner gefunden, weiter...");
        }
        
        page.locator("input[name='email'], input[type='email']").fill(username);
        page.locator("input[name='password'], input[type='password']").fill(password);
        Thread.sleep(5_000);
        page.locator("button[type='submit']").click();

        var deadline = System.currentTimeMillis() + (options1.headless ? 30_000 : 300_000);
        while (result.captchaToken == null) 
        {
            if (System.currentTimeMillis() > deadline) 
            {
            	if (options1.headless)
            	{
        			var newPage = getPage(konto, false);
            		try 
            		{
            			return solveCaptcha(konto, newPage, username, password);
            		}
            		finally 
            		{
            			newPage.close();
            		}
            	}
                throw new RuntimeException("Timeout beim Warten auf Captcha-Token");
            }
            page.waitForTimeout(500);
        }

        page.unroute("**/openid-connect/token");

        return result;
    }

	private void loginWithPlaywright(Konto konto, String username, String password) throws Exception 
	{
        log(Level.INFO, "Starte Browser f\u00FCr Captcha-L\u00f6sung (1/2)...");

        Page page = null;
        CaptchaData captcha;
        try 
        {
			var headless = "false".equals(konto.getMeta(RaisinSynchronizeBackend.META_NOTHEADLESS,  "false"));
			page = getPage(konto, headless);
	        captcha = solveCaptcha(konto, page, username, password);

	        var formBody = "client_id=login&grant_type=password"
	                + "&username=" + URLEncoder.encode(captcha.username, StandardCharsets.UTF_8)
	                + "&password=" + URLEncoder.encode(captcha.password, StandardCharsets.UTF_8);
	
	        var headers = new ArrayList<KeyValue<String, String>>();
	        headers.add(new KeyValue<>("Captcha-Solution-Token",           captcha.captchaToken));
	        headers.add(new KeyValue<>("Sardine-Session-Key",              captcha.sardineKey));
	        headers.add(new KeyValue<>("Locale",                           "de-DE"));
	        headers.add(new KeyValue<>("Raisin-Device-Whitelist-Consent",  "true"));
	        var firstTokenResp = doRequest(TOKEN_URL, HttpMethod.POST, headers, "application/x-www-form-urlencoded", formBody);
	        
	        if (firstTokenResp.getHttpStatus() == 200) 
	        {
	            accessToken = firstTokenResp.getJSONObject().getString("access_token");
	            refreshToken = firstTokenResp.getJSONObject().getString("refresh_token");
	            log(Level.INFO, "Login ohne 2FA erfolgreich.");
	        } 
	        else if (firstTokenResp.getHttpStatus() == 202)
	        {
	        	String verificationId = null;
	            headers.clear();
	            headers.add(new KeyValue<>("Accept",        "application/json"));
	        	for (var header : firstTokenResp.getResponseHeader()) 
	        	{
	        		switch (header.getName())
	        		{
	        		case "customer-id":
	        		case "user-id":
	        			headers.add(new KeyValue<>(header.getName(), header.getValue()));
	        			break;
	        		case "verification-id":
	        			verificationId = header.getValue();
	        			break;
	        		case "guest-token":
	        			headers.add(new KeyValue<>("Authorization", "Bearer " + header.getValue()));
	        			break;
	        		}
	        	}
	            log(Level.INFO, "2FA erforderlich. Verification-ID: " + verificationId);
	
	            var smsURL = decodeItem("aHR0cHM6Ly9hcGkyLndlbHRzcGFyZW4uZGUvc2Nhcy9hcGkvdjIvdmVyaWZpY2F0aW9ucy8=") + verificationId + decodeItem("L2F0dGVtcHRzP2xvY2FsZT1kZS1ERSZjaGFubmVsPVNNUw==");
	            var smsResp = doRequest(smsURL, HttpMethod.POST, headers, null, null);
	            if (smsResp.getHttpStatus() != 202) 
	            {
	                throw new RuntimeException("SMS-Anforderung fehlgeschlagen: HTTP " + smsResp.getHttpStatus() + " – " + smsResp.getContent());
	            }
	
	            var nonce = smsResp.getJSONObject().getString("nonce");
	            log(Level.INFO, "SMS gesendet. Nonce: " + nonce);
	
	            var tan = Application.getCallback().askUser("Bitte geben Sie die per SMS erhaltenen TAN ein", "TAN:");
	            if (tan == null || tan.isBlank()) 
	            {
	                throw new RuntimeException("Keine TAN eingegeben.");
	            }
	
	            var tanBody = new JSONObject().put("verification_code", tan.trim()).toString();
	            var putUri = decodeItem("aHR0cHM6Ly9hcGkyLndlbHRzcGFyZW4uZGUvc2Nhcy9hcGkvdjIvdmVyaWZpY2F0aW9ucy8=") + verificationId + "/attempts/" + nonce;
	            var putResp = doRequest(putUri, HttpMethod.PUT, headers, "application/json", tanBody);
	            if (putResp.getHttpStatus() != 201) 
	            {
	                throw new RuntimeException("TAN-\u00FCbermittlung fehlgeschlagen: HTTP " + putResp.getHttpStatus() + " – " + putResp.getContent());
	            }
	
	            var putJson = putResp.getJSONObject();
	            if (!"VALIDATED".equals(putJson.optString("state"))) 
	            {
	                throw new RuntimeException("TAN nicht akzeptiert. State: " + putJson.optString("state"));
	            }
	            log(Level.INFO, "TAN validiert. Starte Browser f\u00FCr Captcha-L\u00f6sung (2/2)...");
	
	            captcha = solveCaptcha(konto, page, username, password);
	
	            var formBody2 = "client_id=login&grant_type=password"
	                    + "&username=" + URLEncoder.encode(captcha.username, StandardCharsets.UTF_8)
	                    + "&password=" + URLEncoder.encode(captcha.password, StandardCharsets.UTF_8);
	
	            headers.clear();            
	            headers.add(new KeyValue<>("Captcha-Solution-Token",           captcha.captchaToken));
	            headers.add(new KeyValue<>("Sardine-Session-Key",              captcha.sardineKey));
	            headers.add(new KeyValue<>("Locale",                           "de-DE"));
	            headers.add(new KeyValue<>("Verification-ID",                  verificationId));
	            headers.add(new KeyValue<>("Raisin-Device-Whitelist-Consent",  "true"));
	            var finalTokenResp = doRequest(TOKEN_URL, HttpMethod.POST, headers, "application/x-www-form-urlencoded", formBody2);
	            if (finalTokenResp.getHttpStatus() != 200) 
	            {
	                throw new RuntimeException("Finales Token fehlgeschlagen: HTTP " + finalTokenResp.getHttpStatus() + " – " + finalTokenResp.getContent());
	            }
	
	            accessToken = finalTokenResp.getJSONObject().getString("access_token");
	            refreshToken = finalTokenResp.getJSONObject().getString("refresh_token");
	            log(Level.INFO, "Login mit 2FA erfolgreich.");
	        }
	        else 
	        {
	            throw new RuntimeException("Unerwarteter HTTP-Status beim Token-Request: " + firstTokenResp.getHttpStatus() + " – " + firstTokenResp.getContent());
	        }
		}
		finally
		{
			if (page != null) 
			{
				page.close();
			}
		}

        permanentHeaders.add(new KeyValue<>("Authorization", "Bearer " + accessToken));
        permanentHeaders.add(new KeyValue<>("Origin", decodeItem("aHR0cHM6Ly93d3cucmFpc2luLmNvbQ==")));

        bacId = extractBacIdFromJwt(accessToken);
        log(Level.INFO, "BAC-ID from JWT: " + bacId);

        if (bacId == null) 
        {
            throw new RuntimeException("Keine BAC-ID gefunden.");
        }
    }

    private String extractBacIdFromJwt(String jwt) 
    {
        try 
        {
            var parts = jwt.split("\\.");
            if (parts.length < 2) 
        	{
            	return null;
        	}
            // Base64url → Base64
            var payload = parts[1]
                    .replace('-', '+')
                    .replace('_', '/');
            // Pad to multiple of 4
            while (payload.length() % 4 != 0)
        	{
            	payload += "=";
        	}
            var decoded = java.util.Base64.getDecoder().decode(payload);
            var claims = new JSONObject(new String(decoded));
            var bacNumbers = claims.optJSONArray("bac_number");
            if (bacNumbers != null && bacNumbers.length() > 0) 
            {
                return bacNumbers.getString(0);
            }
        } 
        catch (Exception e)
        {
            log(Level.ERROR, "JWT Dekodierung fehlgeschlagen: " + e);
        }
        return null;
    }

    private java.util.Date parseDate(String iso) 
    {
        try
        {
            return java.sql.Date.valueOf(LocalDate.parse(iso));
        } 
        catch (Exception e) 
        {
            return new java.util.Date();
        }
    }
}
