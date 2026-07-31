package de.gnampf.syncusgnampfus.scalablecapital;

import com.microsoft.playwright.options.Cookie;
import de.gnampf.syncusgnampfus.FCSolver;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.CookieJar;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Browserloser Login für Scalable Capital über den Auth0-OIDC-Flow (Authorization Code + PKCE),
 * repliziert mit okhttp. Der Server-seitige BFF von de.scalable.capital erzeugt state/nonce/PKCE
 * selbst und tauscht am Ende den Code gegen die Session-Cookies — wir müssen nur den Redirect-
 * Fluss mit einem domain-fähigen Cookie-Jar durchlaufen und die Zugangsdaten posten.
 *
 * Empirisch verifiziert (ohne gültige Zugangsdaten): der Credential-POST liefert bei falschen
 * Daten ein sauberes "Falsche E-Mail-Adresse oder falsches Passwort" — also KEIN erzwungenes
 * Captcha. Sollte Auth0/Cloudflare doch einmal challengen, wirft diese Methode eine
 * FallbackNeededException und der Aufrufer fällt auf den Browser-Login (Playwright) zurück.
 *
 * Diagnose: Es wird ausführlich geloggt (Struktur/Marker, NIE Zugangsdaten oder Cookie-Werte),
 * damit sich der noch nicht mit echten Daten testbare Erfolgspfad anhand eines Test-Logs
 * nachvollziehen lässt.
 */
final class ScalableCapitalOkLogin {

  private static final String UA =
      "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0";
  private static final String INIT_URL =
      "https://scalable.capital/auth/login?iss=https%3A%2F%2Fsecure.scalable.capital%2F";
  private static final String LOGIN_HOST = "secure.scalable.capital";
  private static final String APP_HOST = "de.scalable.capital";
  private static final String TRANSACTIONS_URL = "https://de.scalable.capital/broker/transactions";

  /** Wird geworfen, wenn der okhttp-Login nicht durchläuft (Captcha/Challenge/unerwartet) -> Fallback. */
  static final class FallbackNeededException extends Exception {
    FallbackNeededException(String m) { super(m); }
  }

  private ScalableCapitalOkLogin() {}

  static Session login(String username, String password, java.net.Proxy proxy, Consumer<String> log)
      throws Exception {
    if (log == null) log = m -> {};
    final List<okhttp3.Cookie> cookieStore = new ArrayList<>();
    CookieJar jar = new CookieJar() {
      @Override public synchronized void saveFromResponse(HttpUrl url, List<okhttp3.Cookie> cookies) {
        for (okhttp3.Cookie c : cookies) {
          cookieStore.removeIf(e -> e.name().equals(c.name())
              && e.domain().equals(c.domain()) && e.path().equals(c.path()));
          cookieStore.add(c);
        }
      }
      @Override public synchronized List<okhttp3.Cookie> loadForRequest(HttpUrl url) {
        List<okhttp3.Cookie> out = new ArrayList<>();
        for (okhttp3.Cookie c : cookieStore) if (c.matches(url)) out.add(c);
        return out;
      }
    };
    OkHttpClient.Builder b = new OkHttpClient.Builder()
        .cookieJar(jar)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(java.time.Duration.ofSeconds(30))
        .readTimeout(java.time.Duration.ofSeconds(30));
    if (proxy != null) { b.proxy(proxy); log.accept("[SC-Login] nutze Proxy " + proxy); }
    OkHttpClient client = b.build();

    // 1) OIDC-Flow starten -> landet auf der Auth0-Loginseite /u/login?state=...
    log.accept("[SC-Login] 1/4 OIDC-Flow start: " + INIT_URL);
    String loginPage;
    String loginUrl;
    Request initReq = new Request.Builder().url(INIT_URL)
        .header("User-Agent", UA)
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .header("Accept-Language", "de-DE,de;q=0.9").build();
    try (Response r = client.newCall(initReq).execute()) {
      loginUrl = r.request().url().toString();
      loginPage = r.body() != null ? r.body().string() : "";
      log.accept("[SC-Login]   -> HTTP " + r.code() + ", gelandet auf host=" + r.request().url().host()
          + " path=" + r.request().url().encodedPath() + ", Seite " + loginPage.length() + " Bytes");
    }
    if (!loginUrl.contains(LOGIN_HOST) || !loginUrl.contains("state=")) {
      log.accept("[SC-Login]   Marker auf Seite: " + markers(loginPage));
      throw new FallbackNeededException("OIDC-Flow endete nicht auf der Auth0-Loginseite (" + loginUrl + ")");
    }
    String state = firstGroup(Pattern.compile("name=\"state\"\\s+value=\"([^\"]+)\""), loginPage);
    log.accept("[SC-Login]   state gefunden=" + (state != null) + (state != null ? " (len=" + state.length() + ")" : ""));
    if (state == null) throw new FallbackNeededException("state-Feld nicht gefunden");

    // 2) Zugangsdaten posten (ohne Captcha)
    log.accept("[SC-Login] 2/4 poste Zugangsdaten an /u/login");
    String[] pr = doLoginPost(client, state, username, password, null, loginUrl);
    log.accept("[SC-Login]   -> host=" + pr[0] + ", Body " + pr[1].length() + " Bytes");

    if (!APP_HOST.equals(pr[0])) {
      // Nicht direkt durchgelaufen: Captcha/Challenge? Auth0 nutzt bei SC FriendlyCaptcha (PoW),
      // die wir selbst lösen können -> Captcha lösen und erneut posten.
      log.accept("[SC-Login]   nicht direkt durchgelaufen. Marker: " + markers(pr[1]));
      String prov = firstGroup(Pattern.compile("data-captcha-provider=\"([^\"]+)\""), pr[1]);
      String skey = firstGroup(Pattern.compile("data-captcha-sitekey=\"([^\"]+)\""), pr[1]);
      if (prov != null) {
        log.accept("[SC-Login]   aktiver Captcha-Provider: " + prov
            + ("friendly_captcha".equals(prov) ? " (l\u00f6sbar)" : " (nicht ohne Browser l\u00f6sbar)"));
      }
      if ("friendly_captcha".equals(prov) && skey != null) {
        String token = solveFrc(client, skey, log);
        String state2 = firstNonNull(
            firstGroup(Pattern.compile("name=\"state\"\\s+value=\"([^\"]+)\""), pr[1]), state);
        pr = doLoginPost(client, state2, username, password, token, loginUrl);
        log.accept("[SC-Login]   nach Captcha-L\u00f6sung -> host=" + pr[0] + ", Marker: " + markers(pr[1]));
      }
      if (!APP_HOST.equals(pr[0])) {
        if (pr[1].contains("wrong-email-credentials")) {
          throw new de.willuhn.util.ApplicationException("Scalable Capital: Benutzername oder Passwort falsch");
        }
        throw new FallbackNeededException("Login nicht abgeschlossen auf host=" + pr[0]
            + " (Captcha/Challenge nicht aufl\u00f6sbar, siehe Marker im Log)");
      }
    }
    log.accept("[SC-Login]   Login erfolgreich durchgelaufen (Redirect auf App-Host).");

    // 3) IDs aus __NEXT_DATA__ der Transaktionsseite lesen (statisch, kein JS nötig)
    log.accept("[SC-Login] 3/4 lade Transaktionsseite f\u00fcr portfolioId/personId");
    String txPage;
    String txUrl;
    try (Response r = client.newCall(new Request.Builder().url(TRANSACTIONS_URL)
        .header("User-Agent", UA)
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .header("Accept-Language", "de-DE,de;q=0.9").build()).execute()) {
      txUrl = r.request().url().toString();
      txPage = r.body() != null ? r.body().string() : "";
      log.accept("[SC-Login]   -> HTTP " + r.code() + ", host=" + r.request().url().host()
          + " path=" + r.request().url().encodedPath() + " query=" + r.request().url().encodedQuery()
          + ", Seite " + txPage.length() + " Bytes");
    }
    String nextData = extractNextData(txPage);
    log.accept("[SC-Login]   __NEXT_DATA__ gefunden=" + (nextData != txPage) + " (" + nextData.length() + " Bytes)");
    String portfolioId = firstNonNull(
        firstGroup(Pattern.compile("[?&]portfolioId=([A-Za-z0-9_-]+)"), txUrl),
        firstGroup(Pattern.compile("BrokerValuation:([A-Za-z0-9_-]+)"), nextData),
        firstGroup(Pattern.compile("\"portfolioId\":\"([^\"]+)\""), nextData));
    String personId = firstNonNull(
        firstGroup(Pattern.compile("\"personId\":\"([^\"]+)\""), nextData),
        firstGroup(Pattern.compile("Account:([A-Za-z0-9_-]+)"), nextData));
    log.accept("[SC-Login]   portfolioId gefunden=" + (portfolioId != null) + ", personId gefunden=" + (personId != null));
    if (portfolioId == null || personId == null) {
      diagnoseIds(nextData, log);
      throw new FallbackNeededException("portfolioId/personId nicht aus __NEXT_DATA__ ermittelbar");
    }

    // 4) Session-Cookies von de.scalable.capital einsammeln
    List<Cookie> cookies = new ArrayList<>();
    Set<String> cookieNames = new LinkedHashSet<>();
    for (okhttp3.Cookie c : cookieStore) {
      if (c.domain().contains("scalable.capital")) {
        cookies.add(new Cookie(c.name(), c.value()));
        cookieNames.add(c.domain() + "/" + c.name());
      }
    }
    log.accept("[SC-Login] 4/4 " + cookies.size() + " Session-Cookies (Namen): " + cookieNames);
    if (cookies.isEmpty()) throw new FallbackNeededException("keine Session-Cookies erhalten");
    return new Session(cookies, personId, portfolioId);
  }

  /** Postet Zugangsdaten (optional mit gelöstem Captcha) an /u/login. Liefert [finalHost, body]. */
  private static String[] doLoginPost(OkHttpClient client, String state, String username,
      String password, String captcha, String referer) throws Exception {
    FormBody.Builder fb = new FormBody.Builder()
        .add("state", state).add("username", username).add("password", password);
    if (captcha != null) fb.add("captcha", captcha);
    Request req = new Request.Builder()
        .url("https://" + LOGIN_HOST + "/u/login?state=" + state)
        .header("User-Agent", UA).header("Origin", "https://" + LOGIN_HOST).header("Referer", referer)
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .header("Accept-Language", "de-DE,de;q=0.9")
        .post(fb.build()).build();
    try (Response r = client.newCall(req).execute()) {
      return new String[]{ r.request().url().host(), r.body() != null ? r.body().string() : "" };
    }
  }

  /** Holt ein FriendlyCaptcha-Puzzle für den Sitekey und löst es (Proof-of-Work) in Java. */
  private static String solveFrc(OkHttpClient client, String sitekey, Consumer<String> log) throws Exception {
    log.accept("[SC-Login]   l\u00f6se FriendlyCaptcha (sitekey=" + sitekey + ") ...");
    Request req = new Request.Builder()
        .url("https://api.friendlycaptcha.com/api/v1/puzzle?sitekey=" + sitekey)
        .header("User-Agent", UA).header("x-frc-client", "js-0.9.19").build();
    String body;
    try (Response r = client.newCall(req).execute()) {
      body = r.body() != null ? r.body().string() : "";
    }
    String puzzle = firstGroup(Pattern.compile("\"puzzle\":\"([^\"]+)\""), body);
    if (puzzle == null) throw new FallbackNeededException("FriendlyCaptcha-Puzzle nicht erhalten");
    long t0 = System.currentTimeMillis();
    String token = FCSolver.solve(puzzle);
    log.accept("[SC-Login]   FriendlyCaptcha gel\u00f6st in " + (System.currentTimeMillis() - t0) + " ms");
    return token;
  }

  /** Loggt, welche bekannten Marker (Captcha/Challenge/Cloudflare/Fehler) im Body vorkommen. */
  private static String markers(String body) {
    String[] keys = {"wrong-email-credentials", "ulp-captcha", "data-captcha-provider",
        "captcha_v2", "hcaptcha", "recaptcha", "friendly_captcha", "arkose", "turnstile",
        "cf-mitigated", "challenge-platform", "Just a moment", "Attention Required",
        "data-sitekey=\""};
    StringBuilder sb = new StringBuilder();
    String low = body.toLowerCase();
    for (String k : keys) {
      int n = countOccur(low, k.toLowerCase());
      if (n > 0) sb.append(k).append("=").append(n).append(" ");
    }
    return sb.length() == 0 ? "(keine bekannten Marker)" : sb.toString().trim();
  }

  /** Bei fehlenden IDs: Struktur der __NEXT_DATA__ ausgeben (Feldnamen/Kandidaten, minimale PII). */
  private static void diagnoseIds(String nextData, Consumer<String> log) {
    Set<String> interesting = new LinkedHashSet<>();
    Matcher km = Pattern.compile(
        "\"([A-Za-z]*[Vv]aluation|[A-Za-z]*[Pp]ortfolio[A-Za-z]*|[A-Za-z]*[Pp]erson[A-Za-z]*|Account[A-Za-z]*|BrokerAccount[A-Za-z]*)\"\\s*:")
        .matcher(nextData);
    while (km.find() && interesting.size() < 40) interesting.add(km.group(1));
    log.accept("[SC-Login]   Kandidaten-Felder in __NEXT_DATA__: " + interesting);
    log.accept("[SC-Login]   enth\u00e4lt 'personId'=" + nextData.contains("personId")
        + ", 'portfolioId'=" + nextData.contains("portfolioId")
        + ", 'initialQueryResult'=" + nextData.contains("initialQueryResult"));
    for (String needle : new String[]{"person", "portfolio"}) {
      int i = nextData.toLowerCase().indexOf(needle);
      if (i >= 0) {
        int a = Math.max(0, i - 30), z = Math.min(nextData.length(), i + 120);
        log.accept("[SC-Login]   Kontext um '" + needle + "': \u2026"
            + nextData.substring(a, z).replaceAll("\\s+", " ") + "\u2026");
      }
    }
  }

  private static int countOccur(String hay, String needle) {
    int n = 0, i = 0;
    while ((i = hay.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
    return n;
  }

  private static String extractNextData(String html) {
    Matcher m = Pattern.compile(
        "<script[^>]*id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", Pattern.DOTALL).matcher(html);
    return m.find() ? m.group(1) : html;
  }

  private static String firstGroup(Pattern p, String s) {
    if (s == null) return null;
    Matcher m = p.matcher(s);
    return m.find() ? m.group(1) : null;
  }

  private static String firstNonNull(String... vs) {
    for (String v : vs) if (v != null && !v.isBlank()) return v;
    return null;
  }
}
