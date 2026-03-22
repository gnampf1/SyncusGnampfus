package de.gnampf.syncusgnampfus.scalablecapital;

import com.microsoft.playwright.options.Cookie;
import java.util.List;

final class BrokerRequestSupport {
  private BrokerRequestSupport() {
  }

  static String buildCookieHeader(List<Cookie> cookies) {
    return cookies.stream()
        .map(cookie -> cookie.name + "=" + cookie.value)
        .reduce((left, right) -> left + "; " + right)
        .orElse("");
  }
}
