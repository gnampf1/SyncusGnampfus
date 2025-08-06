package de.gnampf.syncusgnampfus;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy.Type;
import java.nio.charset.Charset;
import java.rmi.RemoteException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

import org.htmlunit.CookieManager;
import org.htmlunit.FailingHttpStatusCodeException;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.ProxyConfig;
import org.htmlunit.WebClient;
import org.htmlunit.WebRequest;
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
	protected WebClient webClient;
	protected List<KeyValue<String, String>> permanentHeaders = new ArrayList<KeyValue<String, String>>();

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
	
			webClient = getWebClient(null);
	
			if (process(konto, fetchSaldo, fetchUmsatz, umsaetze, user, passwort))
			{
				if (cachePins) { passwortHashtable.put(walletAlias, passwort); }
				if (storePins) { wallet.set(walletAlias, passwort); }
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

	public abstract boolean process(Konto konto, boolean fetchSaldo, boolean fetchUmsatz, DBIterator<Umsatz> umsaetze, String user, String passwort) throws Exception;

	protected Umsatz getDuplicateByCompare(Umsatz buchung) throws RemoteException
	{
		umsaetze.begin();
		while (umsaetze.hasNext())
		{
			Umsatz buchung2 = umsaetze.next();

			long d1 = buchung2.getDatum().getTime();
			long d2 = buchung.getDatum().getTime();
			long d3 = buchung.getValuta().getTime();
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
					(d1 == d2 || d1 == d3) &&
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

		return doRequest(webClient, url, method, mergedHeader, contentType, data, javascriptEnabled);
	}		

	protected static WebResult doRequest(WebClient webClient, String url, HttpMethod method, List<KeyValue<String, String>> headers,
			String contentType, String data) throws URISyntaxException, FailingHttpStatusCodeException, IOException, ApplicationException
	{
		return doRequest(webClient, url, method, headers, contentType, data, false);
	}

	protected static WebResult doRequest(WebClient webClient, String url, HttpMethod method, List<KeyValue<String, String>> headers,
			String contentType, String data, boolean javascriptEnabled) throws URISyntaxException, FailingHttpStatusCodeException, IOException, ApplicationException 
	{
		WebRequest request = new WebRequest(new java.net.URI(url).toURL(), method);
		request.setAdditionalHeaders(new Hashtable<String, String>());
		request.setAdditionalHeader("Accept", "application/json");
		request.setAdditionalHeader("Accept-Language", "de-DE,de;q=0.9,en-US;q=0.8,en;q=0.7");
		if (contentType != null)
		{
			request.setAdditionalHeader("Content-Type", contentType);
		}
		if (headers != null)
		{
			for (var header : headers)
			{
				if (header.getValue() != null)
				{
					request.setAdditionalHeader(header.getKey(), header.getValue());
				}
			}
		}
		if (data != null)
		{
			request.setRequestBody(data);
		}

		webClient.getOptions().setJavaScriptEnabled(javascriptEnabled);
		Page page = webClient.getPage(request);
		if (page == null) 
		{
			return null;
		}
		else 
		{
			var response = page.getWebResponse();
			var text = response.getContentAsString(utf8);
			return new WebResult(response.getStatusCode(), text, response.getResponseHeaders(), page);
		}
	}
}