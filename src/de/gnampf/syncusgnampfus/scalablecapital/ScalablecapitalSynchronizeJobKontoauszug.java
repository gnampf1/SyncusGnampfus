package de.gnampf.syncusgnampfus.scalablecapital;


// aktion bei umsätzen

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.rmi.RemoteException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import javax.annotation.Resource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Playwright;

import de.gnampf.syncusgnampfus.SyncusGnampfusSynchronizeJob;
import de.gnampf.syncusgnampfus.SyncusGnampfusSynchronizeJobKontoauszug;
import de.willuhn.datasource.rmi.DBIterator;
import de.willuhn.jameica.hbci.Settings;
import de.willuhn.jameica.hbci.messaging.ImportMessage;
import de.willuhn.jameica.hbci.messaging.SaldoMessage;
import de.willuhn.jameica.hbci.rmi.Konto;
import de.willuhn.jameica.hbci.rmi.KontoType;
import de.willuhn.jameica.hbci.rmi.Umsatz;
import de.willuhn.jameica.hbci.synchronize.SynchronizeBackend;
import de.willuhn.jameica.messaging.MessageBus;
import de.willuhn.jameica.system.Application;
import de.willuhn.util.ApplicationException;
import de.willuhn.logging.Level;

import com.microsoft.playwright.options.Cookie;

public class ScalablecapitalSynchronizeJobKontoauszug extends SyncusGnampfusSynchronizeJobKontoauszug
		implements SyncusGnampfusSynchronizeJob {

	// Zwischenspeicherung der Session, damit nicht jedesmal ein neuer Login noetig
	// wird
	static HashMap<String, Session> sessions = new HashMap<String, Session>();

	@Resource
	private ScalablecapitalSynchronizeBackend backend = null;

	@Override
	protected SynchronizeBackend getBackend() {
		return backend;
	}

	private final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	@Override
	public boolean process(Konto konto, boolean fetchSaldo, boolean fetchUmsatz, boolean forceAll,
			DBIterator<Umsatz> umsaetze, String user, String passwort) throws Exception {
		if (konto.getAccountType() == null || (KontoType.SPAR.getValue() != konto.getAccountType()
				&& KontoType.GIRO.getValue() != konto.getAccountType() && KontoType.WERTPAPIERDEPOT.getValue() != konto.getAccountType())) {
			log(Level.ERROR,
					"Kontotyp muss entweder Girokonto (=Verrechnungskonto), Sparkonto (=Tagesgeldkonto) oder Wertpapierdepot sein.");
			throw new ApplicationException(
					"Kontotyp muss entweder Girokonto (=Verrechnungskonto), Sparkonto (=Tagesgeldkonto) oder Wertpapierdepot sein.");
		}

		Session session = null;
		if (sessions.containsKey(user + passwort)) {
			session = sessions.get(user + passwort);
			// Pruefen, ob die Session noch gueltig ist
			try {
				PortfolioInventoryClient portfolioInventoryClient = new PortfolioInventoryClient();
				GraphqlEnvelope portfolioInventory = portfolioInventoryClient.fetchPortfolioInventory(session);
			} catch (Exception e) {
				// Session moeglicherweise ungueltig
				log(Level.INFO, "Session möglichweise ungülig. Erzwinge neuen Login.");
				session = null;
			}
		}
		// Login, falls keine Session vorhanden ist
		if (session == null) {
			try (Playwright playwright = Playwright.create()) {

				ScalableCapitalLogin login = new ScalableCapitalLogin();
				session = login.runLogin(playwright, user, passwort);
			}
		}
		sessions.put(user + passwort, session);
		// Save Debug-Information
		if (konto.getMeta(ScalablecapitalSynchronizeBackend.JSONDATA, "").equals("true")) {
			saveJsonResponses(session);
		}
		// Je nach Kontotyp, führe die passende Unteraktion aus
		if (KontoType.SPAR.getValue() == konto.getAccountType()) {
			return sparkonto(session, konto, fetchSaldo, fetchUmsatz);
		}
		if (KontoType.GIRO.getValue() == konto.getAccountType()) {
			return verrechnungsKonto(session, konto, fetchSaldo, fetchUmsatz);
		}
		if (KontoType.WERTPAPIERDEPOT.getValue() == konto.getAccountType()) {
			return depot(session, konto, fetchSaldo, fetchUmsatz);
		}
		return false;
	}

	/**
	 * Speichert für Debug-Zwecke die angeforderten Daten als JSON-Dateien ab.
	 * 
	 * @param session
	 * @throws IOException
	 * @throws InterruptedException
	 */
	private void saveJsonResponses(Session session) throws IOException, InterruptedException {
		String path = Application.getConfig().getWorkDir();

		BrokerTransactionsClient transactionsClient = new BrokerTransactionsClient();
		GraphqlEnvelope transactions = transactionsClient.fetchBrokerTransactions(session);
		saveJsonFile(path, "Scalablecapital-broker-transactions.json", transactions);
		saveRawJsonFile(path, "Scalablecapital-broker-transactions.raw.json", transactions);

		PortfolioInventoryClient portfolioInventoryClient = new PortfolioInventoryClient();
		GraphqlEnvelope portfolioInventory = portfolioInventoryClient.fetchPortfolioInventory(session);
		saveJsonFile(path, "Scalablecapital-portfolio-inventory.json", portfolioInventory);
		saveRawJsonFile(path, "Scalablecapital-portfolio-inventory.raw.json", portfolioInventory);

		PortfolioCashClient portfolioCashClient = new PortfolioCashClient();
		GraphqlEnvelope portfolioCash = portfolioCashClient.fetchPortfolioCash(session);
		saveJsonFile(path, "Scalablecapital-portfolio-cash.json", portfolioCash);
		saveRawJsonFile(path, "Scalablecapital-portfolio-cash.raw.json", portfolioCash);

		OvernightSavingsTransactionsClient overnightSavingsTransactionsClient = new OvernightSavingsTransactionsClient();
		GraphqlEnvelope overnightSavingsTransactions = overnightSavingsTransactionsClient
				.fetchOvernightSavingsTransactions(session);
		saveJsonFile(path, "Scalablecapital-overnight-savings-transactions.json", overnightSavingsTransactions);
		saveRawJsonFile(path, "Scalablecapital-overnight-savings-transactions.raw.json", overnightSavingsTransactions);
	}

	/**
	 * Auswertung des Verrechnungskonto
	 * 
	 * @param session     Session
	 * @param konto       Konto
	 * @param fetchSaldo  Saldo abrufen?
	 * @param fetchUmsatz Umsï¿½tze abrufen?
	 * @return erfolgreich?
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws ApplicationException
	 * @throws ParseException
	 */
	public boolean verrechnungsKonto(Session session, Konto konto, boolean fetchSaldo, boolean fetchUmsatz)
			throws IOException, InterruptedException, ApplicationException, ParseException {
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX");
		sdf.setTimeZone(TimeZone.getTimeZone("UTC"));

		if (fetchSaldo) {
			PortfolioCashClient portfolioCashClient = new PortfolioCashClient();
			GraphqlEnvelope portfolioCash = portfolioCashClient.fetchPortfolioCash(session);
			konto.setSaldo(portfolioCash.data.account.brokerPortfolio.payments.buyingPower.cashBalance);
			konto.setSaldoAvailable(0);
			konto.store();
			Application.getMessagingFactory().sendMessage(new SaldoMessage(konto));

		}
		if (fetchUmsatz) {
			ArrayList<Map<String, Object>> transactionen = new ArrayList<>();

			entferneVorgemerkteBuchungen(konto.getUmsaetze());
			BrokerTransactionsClient transactionsClient = new BrokerTransactionsClient();
			GraphqlEnvelope transactions = transactionsClient.fetchBrokerTransactions(session);
			for (TransactionSummary x : transactions.data.account.brokerPortfolio.moreTransactions.transactions) {
				DBIterator<Umsatz> bekannterUmsatz = konto.getUmsaetze();
				bekannterUmsatz.addFilter("txid = ?", x.id);
				if (bekannterUmsatz.hasNext()) {
					continue;
				}
				Umsatz neuerUmsatz = (Umsatz) Settings.getDBService().createObject(Umsatz.class, null);
				neuerUmsatz.setKonto(konto);
				neuerUmsatz.setTransactionId(x.id);
				if (!x.currency.equals("EUR")) {
					log(Level.ERROR, "Buchung mit einem nicht Euro-Betrag!");
				}
				if (!(x.status.equals("SETTLED") || x.status.equals("FILLED"))) {
					log(Level.ERROR, "Unbekannter Status: " + x.status + "! Bitte als Bug melden!");
				}
				Date d = sdf.parse(x.lastEventDateTime);
				neuerUmsatz.setDatum(d);
				neuerUmsatz.setValuta(d);
				neuerUmsatz.setZweck(x.description);
				neuerUmsatz.setBetrag(x.amount);
				neuerUmsatz.setArt(x.cashTransactionType);
				if (x.status.equals("FILLED")) {
					neuerUmsatz.setFlags(Umsatz.FLAG_NOTBOOKED);
				}
				neuerUmsatz.store();
				Application.getMessagingFactory().sendMessage(new ImportMessage(neuerUmsatz));
			}
		}

		return true;

	}


	/**
	 * Auswertung des Tagesgeldkontos
	 * 
	 * @param session     Session
	 * @param konto       Konto
	 * @param fetchSaldo  Saldo abrufen?
	 * @param fetchUmsatz Umsï¿½tze abrufen?
	 * @return erfolgreich?
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws ApplicationException
	 * @throws ParseException
	 */
	public boolean sparkonto(Session session, Konto konto, boolean fetchSaldo, boolean fetchUmsatz)
			throws IOException, InterruptedException, ApplicationException, ParseException {
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX");
		sdf.setTimeZone(TimeZone.getTimeZone("UTC"));

		OvernightSavingsTransactionsClient overnightSavingsTransactionsClient = new OvernightSavingsTransactionsClient();
		GraphqlEnvelope overnightSavingsTransactions = overnightSavingsTransactionsClient
				.fetchOvernightSavingsTransactions(session);

		if (fetchSaldo) {
			konto.setSaldo(overnightSavingsTransactions.data.account.savingsAccount.totalAmount);
			konto.setSaldoAvailable(0);
			konto.store();
			Application.getMessagingFactory().sendMessage(new SaldoMessage(konto));
		}
		if (fetchUmsatz) {
			entferneVorgemerkteBuchungen(konto.getUmsaetze());
			for (TransactionSummary x : overnightSavingsTransactions.data.account.savingsAccount.moreTransactions.transactions) {
				entferneVorgemerkteBuchungen(konto.getUmsaetze());
				DBIterator<Umsatz> bekannterUmsatz = konto.getUmsaetze();
				bekannterUmsatz.addFilter("txid = ?", x.id);
				if (bekannterUmsatz.hasNext()) {
					continue;
				}
				Umsatz neuerUmsatz = (Umsatz) Settings.getDBService().createObject(Umsatz.class, null);
				neuerUmsatz.setKonto(konto);
				neuerUmsatz.setTransactionId(x.id);
				if (!x.currency.equals("EUR")) {
					log(Level.ERROR, "Buchung mit einem nicht Euro-Betrag!");
				}
				if (!(x.status.equals("SETTLED") || x.status.equals("FILLED"))) {
					log(Level.ERROR, "Unbekannter Status: " + x.status + "! Bitte als Bug melden!");
				}
				Date d = sdf.parse(x.lastEventDateTime);
				neuerUmsatz.setDatum(d);
				neuerUmsatz.setValuta(d);
				neuerUmsatz.setZweck(x.description);
				neuerUmsatz.setBetrag(x.amount);
				neuerUmsatz.setArt(x.cashTransactionType);
				if (x.status.equals("FILLED")) {
					neuerUmsatz.setFlags(Umsatz.FLAG_NOTBOOKED);
				}
				neuerUmsatz.store();
				Application.getMessagingFactory().sendMessage(new ImportMessage(neuerUmsatz));
			}
		}

		return true;

	}

	
	
	/**
	 * 
	 * @param session
	 * @param konto
	 * @param fetchSaldo
	 * @param fetchUmsatz
	 * @return
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws ApplicationException
	 * @throws ParseException
	 */
	public boolean depot(Session session, Konto konto, boolean fetchSaldo, boolean fetchUmsatz)
			throws IOException, InterruptedException, ApplicationException, ParseException {
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX");
		sdf.setTimeZone(TimeZone.getTimeZone("UTC"));

		if (fetchSaldo) {
			// Alle Aktien sammeln und verschicken
			PortfolioInventoryClient portfolioInventoryClient = new PortfolioInventoryClient();
			GraphqlEnvelope portfolioInventory = portfolioInventoryClient.fetchPortfolioInventory(session);
			Double saldo = 0.0;
			ArrayList<Map<String, Object>> bestand = new ArrayList<>();
			for (Security y : portfolioInventory.data.account.brokerPortfolio.inventory.ungroupedInventoryItems.items) {
				HashMap<String, Object> b = security2HashMap(konto, y);
				bestand.add(b);
				saldo = saldo + (Double) b.get("wert");
			}
			for (PortfolioGroup x : portfolioInventory.data.account.brokerPortfolio.inventory.portfolioGroups.items) {
				for (Security y : x.items) {
					HashMap<String, Object> b = security2HashMap(konto, y);
					bestand.add(b);
					saldo = saldo + (Double) b.get("wert");
				}
			}
			Map<String, Object> portfolio = new HashMap<>();
			portfolio.put("konto", konto);
			portfolio.put("portfolio", bestand);
			MessageBus.sendSync("depotviewer.portfolio", portfolio);

			konto.setSaldo(saldo);
			konto.setSaldoAvailable(0);
			konto.store();
			Application.getMessagingFactory().sendMessage(new SaldoMessage(konto));

		}

		if (fetchUmsatz) {
			ArrayList<Map<String, Object>> transactionen = new ArrayList<>();

			BrokerTransactionsClient transactionsClient = new BrokerTransactionsClient();
			GraphqlEnvelope transactions = transactionsClient.fetchBrokerTransactions(session);
			for (TransactionSummary x : transactions.data.account.brokerPortfolio.moreTransactions.transactions) {
				if (!(x.type.equals("SECURITY_TRANSACTION") && x.status.equals("SETTLED"))) {
					continue;
				}

				// Datenpaket für Depotviewer zusammenstellen
				HashMap<String, Object> transaction = new HashMap<>();
				Date d = sdf.parse(x.lastEventDateTime);
				transaction.put("datetime", d);
				transaction.put("orderid", x.id);
				transaction.put("konto", konto);
				transaction.put("isin", x.isin);
				transaction.put("name", x.description);
				transaction.put("kosten", x.amount);
				transaction.put("kostenw", x.currency);
				transaction.put("anzahl", x.quantity);
				transaction.put("kursw", x.currency);
				transaction.put("gebuehren", 0.0);
				transaction.put("gebuehrenw", "EUR");
				transaction.put("steuern", 0.0);
				transaction.put("steuernw", "EUR");
				Double kurs = Math.abs(x.amount / x.quantity);
				transaction.put("kurs", kurs);
				String action = x.side;
				switch (x.side) {
					case "BUY": action = "Kauf"; break;
					case "SELL": action = "Verkauf"; break;
				}
				transaction.put("aktion", action);
				transactionen.add(transaction);
			}
			// Datenpaket an Depotviewer verschicken
			Map<String, Object> depotTransactionen = new HashMap<>();
			depotTransactionen.put("konto", konto);
			depotTransactionen.put("transactions", transactionen);
			MessageBus.sendSync("depotviewer.transaction", depotTransactionen);
		}

		return true;

	}

	private HashMap<String, Object> security2HashMap(Konto konto, Security y) throws ParseException, RemoteException {
		HashMap<String, Object> b = new HashMap<>();
		SimpleDateFormat parse = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
		b.put("konto", konto);
		b.put("isin", y.isin);
		b.put("wkn", y.wkn);
		b.put("name", y.name);
		b.put("anzahl", y.inventory.position.filled);
		b.put("kurs", y.quoteTick.midPrice);
		b.put("kursw", y.quoteTick.currency);
		b.put("wert", y.quoteTick.midPrice * y.inventory.position.filled);
		b.put("wertw", y.quoteTick.currency);
		b.put("datum", new Date());
		b.put("bewertungszeitpunkt", parse.parse(y.quoteTick.time));
		return b;
	}
	
	private void saveJsonFile(String path, String fileName, Object value) {
		if (value == null) {
			return;
		}
		try {
			Path jsonDir = Path.of(path);
			Files.writeString(jsonDir.resolve(fileName), toPrettyJson(value));
			log(Level.INFO, "Speichere " + jsonDir.resolve(fileName).toString());
		} catch (IOException e) {
			throw new RuntimeException("Failed to write JSON file " + fileName, e);
		}
	}

	private void saveRawJsonFile(String path, String fileName, GraphqlEnvelope envelope) {
		if (envelope == null || envelope.rawJson == null || envelope.rawJson.isBlank()) {
			return;
		}
		try {
			Path jsonDir = Path.of(path);
			Files.createDirectories(jsonDir);
			Files.writeString(jsonDir.resolve(fileName), envelope.rawJson);
			log(Level.INFO, "Speichere " + jsonDir.resolve(fileName).toString());
		} catch (IOException e) {
			throw new RuntimeException("Failed to write raw JSON file " + fileName, e);
		}
	}

	private String toPrettyJson(Object value) {
		if (value == null) {
			return "";
		}
		try {
			return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
		} catch (JsonProcessingException e) {
			throw new RuntimeException("Failed to serialize value", e);
		}
	}

	private void entferneVorgemerkteBuchungen(DBIterator existing) throws RemoteException, ApplicationException {
		List<Umsatz> ungebuchteUmsaetze = new ArrayList<Umsatz>();
		while (existing.hasNext()) {
			Umsatz x = (Umsatz) existing.next();
			if ((x.getFlags() & Umsatz.FLAG_NOTBOOKED) > 0) {
				ungebuchteUmsaetze.add(x);
			}
		}
		for (Umsatz u : ungebuchteUmsaetze) {
			u.delete();
		}
	}

}
