package de.gnampf.syncusgnampfus.raisin;


import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import io.github.kihdev.playwright.stealth4j.Stealth4j;
import io.github.kihdev.playwright.stealth4j.Stealth4jConfig;

import de.gnampf.syncusgnampfus.FCSolver;
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

import org.htmlunit.HttpMethod;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.rmi.RemoteException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
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
    private static final String REFRESH_URL = decodeItem("aHR0cHM6Ly9hcGkyLndlbHRzcGFyZW4uZGUvdW1zL3YxL3B1YmxpYy90b2tlbi9yZWZyZXNo");
    private static final String FRC_PUZZLE = decodeItem("aHR0cHM6Ly9hcGkuZnJpZW5kbHljYXB0Y2hhLmNvbS9hcGkvdjEvcHV6emxl");
    private static final String RAISIN_BASE = decodeItem("aHR0cHM6Ly93d3cucmFpc2luLmNvbQ==");
    private static final String RAISIN_LOGIN = decodeItem("aHR0cHM6Ly93d3cucmFpc2luLmNvbS9kZS1kZS9sb2dpbi8=");

    // -------------------------------------------------------------------
    // State captured during login
    // -------------------------------------------------------------------
    private static HashMap<String, String> accessToken = new HashMap<>();
    private static HashMap<String, String> refreshToken = new HashMap<>();
    private String bacId;
    private String trustKey;
    
	@Override
	public boolean process(Konto konto, boolean fetchSaldo, boolean fetchUmsatz, boolean forceAll, DBIterator<Umsatz> umsaetze, String user, String passwort) throws Exception
	{
		try
		{
			monitor.setPercentComplete(5);

	        login(konto, user, passwort);

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
/*			if (!this.skipLogout) {
				if (accessToken.getOrDefault(user, null) != null)
				{
					try 
					{
						doRequest(LOGOUT_URL, HttpMethod.POST, null, "application/json", "{\"refresh_token\":\"" + URLEncoder.encode(refreshToken.get(user), StandardCharsets.UTF_8) + "\"}");
				    } 
					catch (Exception e) 
					{
				        log(Level.WARN, "Logout fehlgeschlagen: " + e.getMessage());
				    }		
				}
				accessToken.remove(user);
				refreshToken.remove(user);
			}*/
		}
    }

    private void syncHauptkonto(Konto konto, ArrayList<Umsatz> neueUmsaetze, ArrayList<Umsatz> duplikate, boolean fetchSaldo, boolean fetchUmsatz, boolean forceAll) throws Exception 
    {
        String traId = resolveTraId();
        log(Level.INFO, "TRA-ID: " + traId);

        String accountUrl = TAMS_BASE + "/accounts/" + traId + "?embed=balance";
        var response = doRequest(accountUrl, HttpMethod.GET, null, "application/json", null);
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
	
		        response = doRequest(txUrl, HttpMethod.GET, null, "application/json", null);
		        transactions = response.getJSONArray();
		        log(Level.INFO, transactions.length() + " Transaktionen seit " + dateFrom);
	
		        for (int i = 0; i < transactions.length(); i++) 
		        {
		            JSONObject tx = transactions.getJSONObject(i);
		            storeTransaction(
		            		konto,
		            		neueUmsaetze,
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
        var response = doRequest(TAMS_BASE + "/accounts?filter=customerId+eq+" + bacId, HttpMethod.GET, null, "application/json", null);
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
        var response = doRequest(DEPOSIT_BASE + "/" + omaId, HttpMethod.GET, null, "application/json", null);
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
    }
    
    private CaptchaData solveCaptcha(Konto konto, String username, String password) throws Exception
    {
        var result = new CaptchaData();
        result.username = username;
        result.password = password;

        String sitekey = getSitekey(konto);
        var frcHeaders = new ArrayList<KeyValue<String, String>>();
        frcHeaders.add(new KeyValue<>("x-frc-client", "js-0.9.19"));
        var puzzleResp = doRequest(FRC_PUZZLE + "?sitekey=" + sitekey, HttpMethod.GET, frcHeaders, null, null);
        var puzzleJson = puzzleResp.getJSONObject();
        if (!puzzleJson.optBoolean("success") || puzzleJson.optJSONObject("data") == null)
        {
            konto.setMeta(RaisinSynchronizeBackend.META_FRCSITEKEY, "");
            throw new ApplicationException("Captcha-Puzzle konnte nicht geladen werden: " + puzzleResp.getContent());
        }
        String puzzle = puzzleJson.getJSONObject("data").getString("puzzle");

        long t0 = System.currentTimeMillis();
        result.captchaToken = FCSolver.solve(puzzle);
        log(Level.INFO, "Captcha gel\u00f6st in " + (System.currentTimeMillis() - t0) + " ms");
        return result;
    }

    /**
     * Liefert den Session-Schluessel fuer den Login. Ist "als vertrauenswuerdiges Geraet
     * hinterlegen" aktiv, wird er ueber einen kurzen Browser-Aufruf ermittelt (siehe
     * {@link #captureTrustSession}); andernfalls eine Zufalls-ID (dann ist pro Login eine
     * TAN noetig, aber ohne Browser).
     */
    private String getTrustSessionKey(Konto konto) throws Exception
    {
        if ("true".equals(konto.getMeta(RaisinSynchronizeBackend.META_TRUST, "true")))
        {
            return captureTrustSession(konto);
        }
        return java.util.UUID.randomUUID().toString();
    }

    /**
     * Ruft kurz die Login-Seite in einem Browser auf: das dort laufende Skript registriert das
     * Geraet serverseitig und erzeugt dabei den Session-Schluessel, den wir aus dem ausgeloesten
     * Beacon-Aufruf auslesen. Damit wird das Geraet nach der ersten TAN wiedererkannt (gleiche
     * Browser-Umgebung -> gleiche serverseitige Geraetekennung). Es wird nichts gespeichert.
     */
    private String captureTrustSession(Konto konto) throws Exception
    {
        boolean headless = !"true".equals(konto.getMeta(RaisinSynchronizeBackend.META_NOTHEADLESS, "false"));
        log(Level.INFO, "Ermittle Geraete-Session (kurzer Browser-Aufruf) ...");
        try (Playwright playwright = Playwright.create())
        {
            var options = new BrowserType.LaunchOptions().setHeadless(headless);
            if (proxyConfig != null && proxyConfig.getProxyHost() != null && !proxyConfig.getProxyHost().isBlank())
            {
                options.setProxy(proxyConfig.getProxyScheme() + "://" + proxyConfig.getProxyHost() + ":" + proxyConfig.getProxyPort());
            }
            Browser browser = playwright.firefox().launch(options);
            try
            {
                var context = Stealth4j.newStealthContext(browser, new Stealth4jConfig.Builder()
                        .navigatorWebDriver(true).chromeLoadTimes(true).chromeApp(true).chromeCsi(true)
                        .navigatorPlugins(true).mediaCodecs(true).windowOuterDimensions(true)
                        .navigatorUserAgent(true, "Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0")
                        .navigatorLanguages(true, List.of("de-DE", "de")).build());
                var page = context.newPage();
                var captured = new Object() { String key = null; boolean registered = false; };
                final String beacon   = decodeItem("Yi5wbmc=");           
                final String keyParam = decodeItem("c2Vzc2lvbktleT0=");   
                final String evtPath  = decodeItem("L3YxL2V2ZW50cw==");   
                final String devTok   = decodeItem("ZGV2aWNlVG9rZW4=");   
                final String h1 = decodeItem("YWZwcm9k");                 
                final String h2 = decodeItem("Y29sbGVjdG9y");            
                final String h3 = decodeItem("LmFpLw==");                
                var seen = java.util.Collections.synchronizedList(new ArrayList<String>());
                page.onResponse((Response resp) ->
                {
                    var url = resp.request().url();
                    if (!(url.contains(h1) || url.contains(h2) || url.contains(h3))) return;
                    if (url.contains(beacon))
                    {
                        // Session-Schluessel aus der Beacon-URL uebernehmen (unabhaengig vom Merkmal).
                        if (url.contains(keyParam)) { int i = url.indexOf(keyParam) + keyParam.length(); int j = url.indexOf('&', i); captured.key = java.net.URLDecoder.decode(j > 0 ? url.substring(i, j) : url.substring(i), StandardCharsets.UTF_8); }
                        seen.add(resp.request().method() + " " + resp.status() + " " + url);
                    }
                    else if (url.contains(evtPath) && !resp.request().method().equals("GET"))
                    {
                        // Der Merkmal-Upload wird serverseitig beantwortet; die Antwort enthaelt das
                        // erzeugte Merkmal (Beleg fuer eine erfolgreiche Geraete-Registrierung).
                        String body = "";
                        try { body = resp.text(); } catch (Exception e) { body = "<nicht lesbar: " + e.getMessage() + ">"; }
                        boolean ok = body.contains(devTok);
                        if (ok) captured.registered = true;
                        // Bei Erfolg nichts Sensibles loggen; bei Misserfolg die Antwort zur Analyse.
                        seen.add(resp.request().method() + " " + resp.status() + " " + evtPath + "  [merkmal="
                                + (ok ? "ja" : "nein" + " antwort=" + body.substring(0, Math.min(200, body.length()))) + "]");
                    }
                    else
                    {
                        int q = url.indexOf('?');
                        String p = (q > 0 ? url.substring(0, q) : url).replaceFirst("https?://", "");
                        seen.add(resp.request().method() + " " + resp.status() + " " + p);
                    }
                });
                page.navigate(RAISIN_LOGIN);
                long deadline = System.currentTimeMillis() + 40000;
                while ((captured.key == null || !captured.registered) && System.currentTimeMillis() < deadline)
                {
                    page.waitForTimeout(200);
                }
                if (captured.key == null || !captured.registered)
                {
                    log(Level.INFO, "Registrierungs-Ablauf (" + seen.size() + " Requests):");
                    for (String s : seen) log(Level.INFO, "  " + s);
                    throw new ApplicationException("Keine vollstaendige Geraete-Registrierung erhalten (Session=" + captured.key + ", Merkmal=" + captured.registered + ")");
                }
                log(Level.INFO, "Geraete-Session bestaetigt.");
                return captured.key;
            }
            finally
            {
                browser.close();
            }
        }
    }

    /** Ermittelt den (env-/laenderabhaengigen) Captcha-Sitekey aus der Login-Seite, gecacht im Konto. */
    private String getSitekey(Konto konto) throws Exception
    {
        String cached = konto.getMeta(RaisinSynchronizeBackend.META_FRCSITEKEY, "");
        if (cached != null && !cached.isBlank()) return cached;

        String html = doRequest(RAISIN_LOGIN, HttpMethod.GET, null, null, null).getContent();
        var chunkPat = java.util.regex.Pattern.compile("/login/_next/static/[A-Za-z0-9/._-]+\\.js");
        var chunks = new java.util.LinkedHashSet<String>();
        var cm = chunkPat.matcher(html);
        while (cm.find()) chunks.add(cm.group());

        var skPat = java.util.regex.Pattern.compile("DEU[\"']?\\s*[,:]\\s*[\"'](FC[A-Z0-9]{8,})[\"']");
        var anyPat = java.util.regex.Pattern.compile("[\"'](FC[A-Z0-9]{12,})[\"']");
        String fallback = null;
        for (String chunk : chunks)
        {
            String js = doRequest(RAISIN_BASE + chunk, HttpMethod.GET, null, null, null).getContent();
            var m = skPat.matcher(js);
            if (m.find())
            {
                konto.setMeta(RaisinSynchronizeBackend.META_FRCSITEKEY, m.group(1));
                log(Level.INFO, "Captcha-Sitekey ermittelt");
                return m.group(1);
            }
            if (fallback == null)
            {
                var am = anyPat.matcher(js);
                if (am.find()) fallback = am.group(1);
            }
        }
        if (fallback != null)
        {
            konto.setMeta(RaisinSynchronizeBackend.META_FRCSITEKEY, fallback);
            return fallback;
        }
        throw new ApplicationException("Captcha-Sitekey nicht gefunden");
    }

	private void login(Konto konto, String username, String password) throws Exception 
	{
		if (accessToken.getOrDefault(username, null) != null) 
		{
			permanentHeaders.clear();
	        permanentHeaders.add(new KeyValue<>("Authorization", "Bearer " + accessToken.get(username)));
	        permanentHeaders.add(new KeyValue<>("Origin", decodeItem("aHR0cHM6Ly93d3cucmFpc2luLmNvbQ==")));
	        var response = doRequest(REFRESH_URL, HttpMethod.POST, null, "application/json", "{\"access_token\":\"" + accessToken.get(username) + "\",\"refresh_token\":\"" + refreshToken.get(username) + "\"}");
	        if (response.getHttpStatus() == 200)
	        {
	        	var json = response.getJSONObject();
	        	accessToken.replace(username, json.getString("access_token"));
	        	refreshToken.replace(username, json.getString("refresh_token"));
	        	log(Level.INFO, "Bestehendes Accesstoken refreshed");
	        }
	        else
	        {
	        	accessToken.remove(username);
	        	refreshToken.remove(username);
	        	log(Level.INFO, "Bestehendes Accesstoken ung\u00FCltig, erneuere Login");
	        }
		}

		if (accessToken.get(username) == null)
		{
			trustKey = getTrustSessionKey(konto);
			log(Level.INFO, "L\u00f6se Captcha (1/2)...");
	
	        CaptchaData captcha = solveCaptcha(konto, username, password);

	        var formBody = "client_id=login&grant_type=password"
	                + "&username=" + URLEncoder.encode(captcha.username, StandardCharsets.UTF_8)
	                + "&password=" + URLEncoder.encode(captcha.password, StandardCharsets.UTF_8);
	
	        var headers = new ArrayList<KeyValue<String, String>>();
	        headers.add(new KeyValue<>("Captcha-Solution-Token",           captcha.captchaToken));
	        headers.add(new KeyValue<>(decodeItem("U2FyZGluZS1TZXNzaW9uLUtleQ=="), trustKey));
	        headers.add(new KeyValue<>("Locale",                           "de-DE"));
	        headers.add(new KeyValue<>("Raisin-Device-Whitelist-Consent",  "true"));
	        var firstTokenResp = doRequest(TOKEN_URL, HttpMethod.POST, headers, "application/x-www-form-urlencoded", formBody);
	        
	        if (firstTokenResp.getHttpStatus() == 200) 
	        {
	            accessToken.put(username, firstTokenResp.getJSONObject().getString("access_token"));
	            refreshToken.put(username, firstTokenResp.getJSONObject().getString("refresh_token"));
	            log(Level.INFO, "Login ohne 2FA erfolgreich.");
	        } 
	        else if (firstTokenResp.getHttpStatus() == 202)
	        {
	        	String verificationId = null;
	            headers.clear();
	            headers.add(new KeyValue<>("Accept",        "application/json"));
	        	for (var header : firstTokenResp.getResponseHeader()) 
	        	{
	        		switch (header.getKey())
	        		{
	        		case "customer-id":
	        		case "user-id":
	        			headers.add(new KeyValue<>(header.getKey(), header.getValue()));
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
	            var smsResp = doRequest(smsURL, HttpMethod.POST, headers, "application/json", null);
	            if (smsResp.getHttpStatus() != 202) 
	            {
	                throw new RuntimeException("SMS-Anforderung fehlgeschlagen: HTTP " + smsResp.getHttpStatus() + " \u2013 " + smsResp.getContent());
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
	                throw new RuntimeException("TAN-\u00FCbermittlung fehlgeschlagen: HTTP " + putResp.getHttpStatus() + " \u2013 " + putResp.getContent());
	            }
	
	            var putJson = putResp.getJSONObject();
	            if (!"VALIDATED".equals(putJson.optString("state"))) 
	            {
	                throw new RuntimeException("TAN nicht akzeptiert. State: " + putJson.optString("state"));
	            }
	            log(Level.INFO, "TAN validiert. L\u00f6se Captcha (2/2)...");
	
	            captcha = solveCaptcha(konto, username, password);
	
	            var formBody2 = "client_id=login&grant_type=password"
	                    + "&username=" + URLEncoder.encode(captcha.username, StandardCharsets.UTF_8)
	                    + "&password=" + URLEncoder.encode(captcha.password, StandardCharsets.UTF_8);
	
	            headers.clear();            
	            headers.add(new KeyValue<>("Captcha-Solution-Token",           captcha.captchaToken));
	            headers.add(new KeyValue<>(decodeItem("U2FyZGluZS1TZXNzaW9uLUtleQ=="), trustKey));
	            headers.add(new KeyValue<>("Locale",                           "de-DE"));
	            headers.add(new KeyValue<>("Verification-ID",                  verificationId));
	            headers.add(new KeyValue<>("Raisin-Device-Whitelist-Consent",  "true"));
	            var finalTokenResp = doRequest(TOKEN_URL, HttpMethod.POST, headers, "application/x-www-form-urlencoded", formBody2);
	            if (finalTokenResp.getHttpStatus() != 200) 
	            {
	                throw new RuntimeException("Finales Token fehlgeschlagen: HTTP " + finalTokenResp.getHttpStatus() + " \u2013 " + finalTokenResp.getContent());
	            }
	
	            accessToken.put(username, finalTokenResp.getJSONObject().getString("access_token"));
	            refreshToken.put(username, finalTokenResp.getJSONObject().getString("refresh_token"));
	            log(Level.INFO, "Login mit 2FA erfolgreich.");
	        }
	        else 
	        {
	            throw new RuntimeException("Unerwarteter HTTP-Status beim Token-Request: " + firstTokenResp.getHttpStatus() + " \u2013 " + firstTokenResp.getContent());
	        }
		}
		
		permanentHeaders.clear();
        permanentHeaders.add(new KeyValue<>("Authorization", "Bearer " + accessToken.get(username)));
        permanentHeaders.add(new KeyValue<>("Origin", decodeItem("aHR0cHM6Ly93d3cucmFpc2luLmNvbQ==")));

        bacId = extractBacIdFromJwt(accessToken.get(username));
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
