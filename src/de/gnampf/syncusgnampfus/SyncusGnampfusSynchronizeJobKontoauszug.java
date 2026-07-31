package de.gnampf.syncusgnampfus;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.Proxy.Type;
import java.nio.charset.Charset;
import java.rmi.RemoteException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Hashtable;
import java.util.List;
import java.util.function.Consumer;

import org.htmlunit.CookieManager;
import org.htmlunit.FailingHttpStatusCodeException;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.ProxyConfig;
import org.htmlunit.WebClient;
import org.htmlunit.WebRequest;
import org.htmlunit.util.NameValuePair;
import org.json.JSONObject;


import de.willuhn.datasource.rmi.DBIterator;
import de.willuhn.jameica.hbci.SynchronizeOptions;
import de.willuhn.jameica.hbci.messaging.ImportMessage;
import de.willuhn.jameica.hbci.messaging.ObjectDeletedMessage;
import de.willuhn.jameica.hbci.rmi.Konto;
import de.willuhn.jameica.hbci.rmi.Umsatz;
import de.willuhn.jameica.hbci.synchronize.SynchronizeBackend;
import de.willuhn.jameica.hbci.synchronize.jobs.SynchronizeJobKontoauszug;
import de.willuhn.jameica.system.Application;
import de.willuhn.logging.Level;
import de.willuhn.logging.Logger;
import de.willuhn.util.ApplicationException;
import de.willuhn.util.ProgressMonitor;

public abstract class SyncusGnampfusSynchronizeJobKontoauszug extends SynchronizeJobKontoauszug implements SyncusGnampfusSynchronizeJob
{
	protected static Hashtable<String,String> passwortHashtable = new Hashtable<String,String>();
	protected static Charset utf8 = Charset.forName("UTF-8");
	protected ProgressMonitor monitor;
	protected ProxyConfig proxyConfig;
	protected DBIterator<Umsatz> umsaetze;
	protected List<KeyValue<String, String>> permanentHeaders = new ArrayList<KeyValue<String, String>>();

	protected boolean skipLogout = false;
	
	public void setSkipLogout(boolean value)
	{
		skipLogout = value;
	}
	
	protected abstract SynchronizeBackend getBackend();
	
	@Override
	public void execute(ProgressMonitor monitor) throws Exception
	{
		Konto konto = (Konto) this.getContext(CTX_ENTITY); // wurde von HanseaticSynchronizeJobProviderKontoauszug dort abgelegt
		this.monitor = monitor;

		try 
		{
			monitor.setPercentComplete(0);
			log(Level.INFO, "Version " + Version.VERSION + "." + Version.BUILD + " wurde gestartet f\u00FCr " + konto.getLongName() +"...");
			monitor.setPercentComplete(0);
	
			SynchronizeOptions options = new SynchronizeOptions(konto);
			options.setAutoSaldo(false);
	
			boolean forceSaldo  = false;
			Object forceSaldoObj = this.getContext(SynchronizeJobKontoauszug.CTX_FORCE_SALDO);
			if (forceSaldoObj != null) forceSaldo = (Boolean)forceSaldoObj;
			Boolean forceUmsatz = false;
			Object forceUmsatzObj = this.getContext(SynchronizeJobKontoauszug.CTX_FORCE_UMSATZ);
			if (forceUmsatzObj != null) forceUmsatz = (Boolean)forceUmsatzObj;
	
			Boolean fetchSaldo = options.getSyncSaldo() || forceSaldo;
			Boolean fetchUmsatz = options.getSyncKontoauszuege() || forceUmsatz;
			log(Level.DEBUG, "Neue Synchronisierung wurde erkannt, mit folgenden Einstellungen: ");
			log(Level.DEBUG, "forceSaldo: " + forceSaldo + ", forceUmsatz: " + forceUmsatz + ", fetchSaldo: " + fetchSaldo + ", fetchUmsatz: " + fetchUmsatz);
	
	
			if (!fetchSaldo && !fetchUmsatz) {
				throw new ApplicationException("Neuer Sync wird nicht ausgef\u00FCrt da die Option 'Saldo aktualisieren' und 'Kontoausz\u00FCge (Ums\u00E4tze) abrufen' deaktiviert sind. Nichts zu tun");
			};
	
			log(Level.INFO, "Ums\u00E4tze von Hibiscus f\u00FCr Doppelbuchung-Checks holen ...");
			umsaetze = konto.getUmsaetze();
			monitor.setPercentComplete(1);
	
			log(Level.DEBUG, "es wird auf eine Proxy-Konfiguration gepr\u00FCft ...");
			proxyConfig = null;
	
			log(Level.INFO, "Proxy Einstellungen setzen ...");
	
			if (Application.getConfig().getUseSystemProxy())
			{
				var proxy = ProxySelector.getDefault().select(new URI("https://www.jameica.de")).get(0); 
	
				if (Type.DIRECT.equals(proxy.type()))
				{
					log(Level.WARN, "Systemproxy-Einstellungen verwenden ist in Jameica eingestellt, es ist aber kein Proxy im System eingetragen!");
					proxyConfig = new ProxyConfig();
				} 
				else 
				{
					InetSocketAddress address = (InetSocketAddress)proxy.address();
					proxyConfig = new ProxyConfig(address.getHostString(), address.getPort(), proxy.type().toString());
					log(Level.INFO, "Systemproxy " + proxyConfig.getProxyScheme()+"://" + proxyConfig.getProxyHost() + ":" + proxyConfig.getProxyPort());
				}
			} 
			else if (Application.getConfig().getHttpsProxyPort() != -1 && Application.getConfig().getHttpsProxyHost() != null && !Application.getConfig().getHttpsProxyHost().isBlank())
			{
				proxyConfig = new ProxyConfig(Application.getConfig().getHttpsProxyHost(), Application.getConfig().getHttpsProxyPort(), "https");
				log(Level.INFO, "Jameica-Proxy " + proxyConfig.getProxyScheme()+"://" + proxyConfig.getProxyHost() + ":" + proxyConfig.getProxyPort());
			} 
	
			var user = konto.getKundennummer();
	
			var wallet = de.willuhn.jameica.hbci.Settings.getWallet();
			var cachePins = de.willuhn.jameica.hbci.Settings.getCachePin();
			var storePins = de.willuhn.jameica.hbci.Settings.getStorePin();
			var walletAlias = "de.gnampf.syncusgnampfus." + getName() + "." + user;
	
			log(Level.INFO, "Login f\u00FCr " + user + " ...");
	
			var passwort = "";
			if (cachePins)
			{ 
				passwort = passwortHashtable.get(walletAlias); 
			} 
			else 
			{
				log(Level.DEBUG, "Don't cache PINs");
				passwortHashtable.remove(walletAlias);
			}
	
			if (storePins) 
			{
				log(Level.DEBUG, "Store PINs");
				passwort = (String)wallet.get(walletAlias); 
			} 
			else 
			{
				log(Level.DEBUG, "Don't store PINs");
				if (wallet.get(walletAlias) != null) 
				{ 
					wallet.set(walletAlias,null); 
				}
			}
	
			try 
			{
				if (passwort == null || passwort.isBlank()) 
				{
					log(Level.INFO, "Passwort f\u00FCr Anmeldung " + user + " wird abgefragt ...");			
	
					passwort = Application.getCallback().askPassword("Bitte geben Sie das Passwort f\u00FCr Konto " + konto.getLongName() + " und Benutzer " + user + " ein:");
				}
			} 
			catch(Exception err) 
			{
				log(Level.ERROR, "Login fehlgeschlagen! Passwort-Eingabe vom Benutzer abgebrochen");
				throw new java.lang.Exception("Login fehlgeschlagen! Passwort-Eingabe vom Benutzer abgebrochen");
			}
			
			boolean forceAll = false;
			if (konto.getSaldoDatum() == null)
			{
				forceAll = true;
				log(Level.INFO, "Kein Saldodatum, forciere Abruf aller Ums\u00E4tze!");
			}
			
			try 
			{
				if (process(konto, fetchSaldo, fetchUmsatz, forceAll, umsaetze, user, passwort))
				{
					if (cachePins) { passwortHashtable.put(walletAlias, passwort); }
					if (storePins) { wallet.set(walletAlias, passwort); }
				}
			}
			catch (ApplicationException e) 
			{
				passwortHashtable.remove(walletAlias); 
				wallet.set(walletAlias, null); 
				throw e;
			}
		}
		finally
		{
			monitor.log("******************************************************************************************************************\n\n\n");
			monitor.addPercentComplete(99);
			monitor = null;
		}
	}

	protected WebClient getWebClient(CookieManager cookieCache)
	{
		WebClient webClient = new WebClient(new org.htmlunit.BrowserVersion.BrowserVersionBuilder(org.htmlunit.BrowserVersion.FIREFOX)
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

		// WebClient mit den den Proxy-Einstellungen anlegen
 		if (proxyConfig != null)
		{
			webClient.getOptions().setProxyConfig(proxyConfig);
		}
		else
		{
			webClient.getOptions().setProxyConfig(new ProxyConfig());
		}

		if (cookieCache != null)
		{
			webClient.setCookieManager(cookieCache);
		}
		else
		{
			cookieCache = webClient.getCookieManager();
		}
		if (!cookieCache.isCookiesEnabled()) 
		{ 
			cookieCache.setCookiesEnabled(true); 
		}
		log(Level.INFO, "WebClient erstellt");
		
		return webClient;
	}

	protected void log(Level level, String msg) 
	{
		msg = "SyncusGnampfus/" + getBackend().getName() + ": " + msg;
		Logger.write(level, msg);
		if (level.getValue() >= Level.INFO.getValue())
		{
			monitor.log(msg);
		}
	}

	public abstract boolean process(Konto konto, boolean fetchSaldo, boolean fetchUmsatz, boolean forceAll, DBIterator<Umsatz> umsaetze, String user, String passwort) throws Exception;

	protected static String decodeItem(String encoded) 
	{
		try 
		{
			return new String(Base64.getDecoder().decode(encoded), "UTF-8");
		} catch (UnsupportedEncodingException e) {
			return encoded;
		}
	}
	
	protected Umsatz getDuplicateByCompare(Umsatz buchung) throws RemoteException
	{
		umsaetze.begin();
		while (umsaetze.hasNext())
		{
			Umsatz buchung2 = umsaetze.next();

			long d1 = buchung2.getDatum().getTime();
			long d2 = buchung.getDatum().getTime();
			long d3 = buchung.getValuta().getTime();
			long d4 = buchung2.getValuta().getTime();
			String z1 = buchung2.getZweck();
			String z2 = buchung.getZweck();
			Double b1 = buchung2.getBetrag();
			Double b2 = buchung.getBetrag();

			if (b1.equals(-0.0)) 
			{
				b1 = 0.0;
			}
			if (b2.equals(-0.0))
			{
				b2 = 0.0;
			}

			if (b1.equals(b2) &&
					(d1 == d2) &&
					(d3 == d4 || d4 == d1) &&
					z1.equals(z2)
					)
			{
				return buchung2;
			}
		}
		return null;
	}

	protected Umsatz getDuplicateById(Umsatz buchung) throws RemoteException 
	{
		umsaetze.begin();
		while (umsaetze.hasNext()) 
		{
			Umsatz buchung2 = umsaetze.next();

			var tID = buchung2.getTransactionId(); 
			if (tID == null)
			{
				return getDuplicateByCompare(buchung);
			}
			else if (buchung2.getTransactionId().equals(buchung.getTransactionId()))
			{
				return buchung2;
			}
		}
		return null;
	}

	protected void reverseImport(List<Umsatz> neueUmsaetze) throws ApplicationException, RemoteException
	{
		for (int i = neueUmsaetze.size() - 1; i >= 0; i--)
		{
			Umsatz umsatz = neueUmsaetze.get(i); 
			umsatz.store();
			Application.getMessagingFactory().sendMessage(new ImportMessage(umsatz));
		}
	}
	
	protected void deleteMissingUnbooked(List<Umsatz> stillExistingUnbooked) throws RemoteException, ApplicationException
	{
		umsaetze.begin();
		while (umsaetze.hasNext())
		{
			Umsatz umsatz = umsaetze.next();
			if (umsatz.hasFlag(Umsatz.FLAG_NOTBOOKED) && !stillExistingUnbooked.contains(umsatz))
			{
				var id = umsatz.getID();
				umsatz.delete();
				Application.getMessagingFactory().sendMessage(new ObjectDeletedMessage(umsatz, id));
			}
		}
	}

	protected WebResult doRequest(String url, HttpMethod method, List<KeyValue<String, String>> headers,
			String contentType, String data) throws URISyntaxException, FailingHttpStatusCodeException, IOException, ApplicationException 
	{
		return doRequest(url, method, headers, contentType, data, false);
	}
	
	protected WebResult doRequest(String url, HttpMethod method, List<KeyValue<String, String>> headers,
			String contentType, String data, boolean javascriptEnabled) throws URISyntaxException, FailingHttpStatusCodeException, IOException, ApplicationException 
	{
		ArrayList<KeyValue<String, String>> mergedHeader = new ArrayList<>();
		mergedHeader.add(new KeyValue<>("User-Agent", "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0"));
		mergedHeader.add(new KeyValue<>("Accept", "*/*"));
		mergedHeader.add(new KeyValue<>("Accept-Language", "de-DE,de;q=0.9,en-US;q=0.8,en;q=0.7"));
		for (var header : permanentHeaders)
		{
			mergedHeader.add(header);
		}
		if (headers != null)
		{
			for (var header : headers)
			{
				mergedHeader.add(header);
			}
		}

		var responseHeaders = new ArrayList<KeyValue<String, String>>();
		try
		{
			var r = mobileHttpRequest(url, method.toString(), mergedHeader, contentType, data, null, responseHeaders);
			return new WebResult(Integer.parseInt(r[0]), r[1], responseHeaders);
		}
		catch (ApplicationException e) { throw e; }
		catch (Exception e) { throw new ApplicationException(e.getMessage(), e); }
	}

	protected final java.util.Map<String, String> mobileCookieJar = new java.util.LinkedHashMap<>();

	protected String[] mobileHttpRequest(String url, String method,
			List<KeyValue<String, String>> headers, String contentType, String body,
			List<String[]> outSetCookies, List<KeyValue<String, String>> outResponseHeaders) throws Exception
	{
		okhttp3.Request.Builder reqBuilder = new okhttp3.Request.Builder().url(url);
		if (headers != null)
		{
			for (var h : headers)
			{
				if (h.getValue() != null)
				{
					reqBuilder.header(h.getKey(), h.getValue());
				}
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

		okhttp3.RequestBody requestBody = null;
		if (body != null)
		{
			okhttp3.MediaType mt = okhttp3.MediaType.parse(contentType != null ? contentType : "application/json; charset=UTF-8");
			requestBody = okhttp3.RequestBody.create(body.getBytes(utf8), mt);
		}
		if (requestBody == null && !"GET".equals(method) && !"HEAD".equals(method))
		{
			requestBody = okhttp3.RequestBody.create(new byte[0], (okhttp3.MediaType) null);
		}
		reqBuilder.method(method, requestBody);

		try (okhttp3.Response response = mobileClient().newCall(reqBuilder.build()).execute())
		{
			int statusCode = response.code();
			if (outResponseHeaders != null)
			{
				var respHdrs = response.headers();
				for (int i = 0; i < respHdrs.size(); i++)
				{
					outResponseHeaders.add(new KeyValue<String, String>(respHdrs.name(i), respHdrs.value(i)));
				}
			}
			for (String setCookieVal : response.headers("Set-Cookie"))
			{
				String nameValue = setCookieVal.split(";")[0].trim();
				int eq = nameValue.indexOf('=');
				if (eq > 0)
				{
					String cName  = nameValue.substring(0, eq).trim();
					String cValue = nameValue.substring(eq + 1).trim();
					mobileCookieJar.put(cName, cValue);
					if (outSetCookies != null)
					{
						outSetCookies.add(new String[]{ cName, cValue });
					}
				}
			}
			String responseBody = response.body() != null ? response.body().string() : "";
			return new String[]{ String.valueOf(statusCode), responseBody };
		}
	}

	private okhttp3.OkHttpClient mobileOkHttpClient;

	protected okhttp3.OkHttpClient mobileClient() throws Exception
	{
		if (mobileOkHttpClient == null)
		{
			java.security.Provider conscrypt = org.conscrypt.Conscrypt.newProvider();
			javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLS", conscrypt);
			javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory.getInstance(
					javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
			tmf.init((java.security.KeyStore) null);
			final javax.net.ssl.X509ExtendedTrustManager delegateTm = (javax.net.ssl.X509ExtendedTrustManager) tmf.getTrustManagers()[0];
			// Conscrypt reicht bei TLS 1.3 den authType "GENERIC" an den TrustManager; der
			// JDK-Default kennt den nicht ("Unknown authType: GENERIC"/"EC") und wirft. In dem Fall
			// mit authType "UNKNOWN" erneut prüfen — der JDK-Checker überspringt dann die
			// KeyUsage-Prüfung, validiert aber weiterhin die Zertifikatskette (funktioniert für
			// RSA- und EC-Zertifikate). Conscrypt nutzt die erweiterte 3-arg-API (mit Socket/
			// SSLEngine), daher müssen alle Varianten überschrieben werden.
			javax.net.ssl.X509ExtendedTrustManager trustManager = new javax.net.ssl.X509ExtendedTrustManager()
			{
				private boolean authTypeIssue(Throwable e)
				{
					return String.valueOf(e.getMessage()).toLowerCase().contains("authtype");
				}
				private void rethrow(Throwable e) throws java.security.cert.CertificateException
				{
					if (e instanceof java.security.cert.CertificateException) throw (java.security.cert.CertificateException) e;
					if (e instanceof RuntimeException) throw (RuntimeException) e;
					if (e instanceof Error) throw (Error) e;
					throw new java.security.cert.CertificateException(e);
				}
				@Override
				public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) throws java.security.cert.CertificateException
				{
					try { delegateTm.checkServerTrusted(c, a); }
					catch (Throwable e) { if (authTypeIssue(e)) delegateTm.checkServerTrusted(c, "UNKNOWN"); else rethrow(e); }
				}
				@Override
				public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a, java.net.Socket s) throws java.security.cert.CertificateException
				{
					try { delegateTm.checkServerTrusted(c, a, s); }
					catch (Throwable e) { if (authTypeIssue(e)) delegateTm.checkServerTrusted(c, "UNKNOWN", s); else rethrow(e); }
				}
				@Override
				public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a, javax.net.ssl.SSLEngine s) throws java.security.cert.CertificateException
				{
					try { delegateTm.checkServerTrusted(c, a, s); }
					catch (Throwable e) { if (authTypeIssue(e)) delegateTm.checkServerTrusted(c, "UNKNOWN", s); else rethrow(e); }
				}
				@Override
				public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) throws java.security.cert.CertificateException
				{ delegateTm.checkClientTrusted(c, a); }
				@Override
				public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a, java.net.Socket s) throws java.security.cert.CertificateException
				{ delegateTm.checkClientTrusted(c, a, s); }
				@Override
				public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a, javax.net.ssl.SSLEngine s) throws java.security.cert.CertificateException
				{ delegateTm.checkClientTrusted(c, a, s); }
				@Override
				public java.security.cert.X509Certificate[] getAcceptedIssuers()
				{ return delegateTm.getAcceptedIssuers(); }
			};
			sslContext.init(null, new javax.net.ssl.TrustManager[]{ trustManager }, null);

			var okBuilder = new okhttp3.OkHttpClient.Builder()
					.sslSocketFactory(sslContext.getSocketFactory(), trustManager)
					.followRedirects(true)
					.followSslRedirects(true)
					.connectTimeout(java.time.Duration.ofSeconds(30))
					.readTimeout(java.time.Duration.ofSeconds(30))
/*					.addNetworkInterceptor(chain -> {
						okhttp3.Request req = chain.request();
						log(Level.INFO, "TX " + req.method() + " " + req.url()
								+ " | Header: " + req.headers().toString().replace("\n", " | "));
						okhttp3.Response resp = chain.proceed(req);
						log(Level.INFO, "RX proto=" + resp.protocol() + " code=" + resp.code());
						return resp;
					})*/;
			// Proxy aus den Jameica-Einstellungen (wie zuvor der Browser) uebernehmen.
			if (proxyConfig != null && proxyConfig.getProxyHost() != null && !proxyConfig.getProxyHost().isBlank())
			{
				okBuilder.proxy(new java.net.Proxy(java.net.Proxy.Type.HTTP,
						new java.net.InetSocketAddress(proxyConfig.getProxyHost(), proxyConfig.getProxyPort())));
				log(Level.INFO, "okhttp nutzt Proxy " + proxyConfig.getProxyHost() + ":" + proxyConfig.getProxyPort());
			}
			mobileOkHttpClient = okBuilder.build();
			log(Level.INFO, "okhttp/Conscrypt-Client f\u00fcr mobilen Login initialisiert");
		}
		return mobileOkHttpClient;
	}
}