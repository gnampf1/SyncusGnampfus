package de.gnampf.syncusgnampfus.scalablecapital;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import io.github.kihdev.playwright.stealth4j.Stealth4jConfig;
import io.github.kihdev.playwright.stealth4j.Stealth4jKt;
import java.util.List;

final class ScalableCapitalLogin {

  Session runLogin(Playwright playwright, String username, String pwd) {
    Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(false));
    try {
      BrowserContext context = browser.newContext(
          new Browser.NewContextOptions().setServiceWorkers(ServiceWorkerPolicy.BLOCK));
      Stealth4jKt.stealth(context, Stealth4jConfig.Companion.getDEFAULT());
      Page page = context.newPage();

      page.navigate("https://secure.scalable.capital/u/login");
      page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("E-Mail-Adresse")).fill(username);
      page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Passwort")).fill(pwd);
      page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Login")).click();
      page.getByTestId("uc-deny-all-button").click();

      PlaywrightAssertions.assertThat(page).not().hasURL("https://secure.scalable.capital/u/login");

      page.navigate("https://de.scalable.capital/broker/transactions");
      waitForPortfolioIdInUrl(page);

      String portfolioId = extractPortfolioId(page);
      String personId = extractPersonId(page);
      List<Cookie> cookies = context.cookies("https://de.scalable.capital");

      return new Session(cookies, personId, portfolioId);
    } finally {
      browser.close();
    }
  }

  private void waitForPortfolioIdInUrl(Page page) {
    try {
      page.waitForCondition(
          () -> page.url().contains("portfolioId="),
          new Page.WaitForConditionOptions().setTimeout(10_000));
    } catch (RuntimeException ignored) {
      // Fallback extractors below cover pages where the client router does not update the URL in time.
    }
  }

  private String extractPersonId(Page page) {
    Object result = page.evaluate("""
        () => {
          function visit(node) {
            if (!node) return null;
            if (Array.isArray(node)) {
              for (const sub of node) {
                const found = visit(sub);
                if (found) return found;
              }
              return null;
            }
            if (typeof node === 'object') {
              if (node.personId) return node.personId;
              for (const key in node) {
                if (['children', 'props', 'security', 'items'].includes(key) || key.startsWith('__reactProps')) {
                  const found = visit(node[key]);
                  if (found) return found;
                }
              }
            }
            if (node.childNodes?.forEach) {
              for (const child of node.childNodes) {
                const found = visit(child);
                if (found) return found;
              }
            }
            return null;
          }
          return visit(document.body);
        }
        """);
    if (result instanceof String value && !value.isBlank()) {
      return value;
    }
    throw new IllegalStateException("Could not extract personId via React fiber walk.");
  }

  private String extractPortfolioId(Page page) {
    String url = page.url();
    String fromUrl = extractQueryParam(url, "portfolioId");
    if (!fromUrl.isBlank()) {
      return fromUrl;
    }

    Object fromNextData = page.evaluate("""
        () => {
          try {
            const el = document.getElementById('__NEXT_DATA__');
            if (!el?.textContent) return null;
            const data = JSON.parse(el.textContent);
            const result = data?.props?.initialQueryResult;
            if (!result || typeof result !== 'object') return null;
            for (const key of Object.keys(result)) {
              const match = key.match(/^BrokerValuation:(.+)$/);
              if (match?.[1]) return match[1];
            }
            return null;
          } catch {
            return null;
          }
        }
        """);
    if (fromNextData instanceof String value && !value.isBlank()) {
      return value;
    }

    Object fromFiber = page.evaluate("""
        () => {
          function visit(node) {
            if (!node) return null;
            if (Array.isArray(node)) {
              for (const sub of node) {
                const found = visit(sub);
                if (found) return found;
              }
              return null;
            }
            if (typeof node === 'object') {
              if (typeof node.portfolioId === 'string') return node.portfolioId;
              for (const key in node) {
                if (['children', 'props', 'security', 'items', 'portfolio', 'broker'].includes(key)
                    || key.startsWith('__reactProps')) {
                  const found = visit(node[key]);
                  if (found) return found;
                }
              }
            }
            return null;
          }
          return visit(document.body);
        }
        """);
    if (fromFiber instanceof String value && !value.isBlank()) {
      return value;
    }

    throw new IllegalStateException("Could not extract portfolioId from URL, __NEXT_DATA__, or React fiber walk.");
  }

  private String extractQueryParam(String url, String name) {
    String marker = name + "=";
    int start = url.indexOf(marker);
    if (start < 0) {
      return "";
    }
    int valueStart = start + marker.length();
    int end = url.indexOf('&', valueStart);
    return end >= 0 ? url.substring(valueStart, end) : url.substring(valueStart);
  }

}
