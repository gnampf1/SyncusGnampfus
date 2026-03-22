package de.gnampf.syncusgnampfus.scalablecapital;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

final class BrokerApiClient {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
      .followRedirects(HttpClient.Redirect.NORMAL)
      .connectTimeout(Duration.ofSeconds(30))
      .build();

  private static final String GRAPHQL_URL = "https://de.scalable.capital/broker/api/data";
  private static final String TRANSACTIONS_REFERER = "https://de.scalable.capital/broker/transactions?portfolioId=%s";
  private static final String USER_AGENT = "scalable-capital-java/0.1.0";

  GraphqlEnvelope execute(Session session, GraphqlRequestBody requestBody) throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(URI.create(GRAPHQL_URL))
        .timeout(Duration.ofSeconds(30))
        .header("Content-Type", "application/json")
        .header("Cookie", BrokerRequestSupport.buildCookieHeader(session.cookies()))
        .header("Referer", TRANSACTIONS_REFERER.formatted(session.portfolioId()))
        .header("Origin", "https://de.scalable.capital")
        .header("User-Agent", USER_AGENT)
        .header("x-scacap-features-enabled", "CRYPTO_MULTI_ETP,UNIQUE_SECURITY_ID")
        .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(requestBody), StandardCharsets.UTF_8))
        .build();

    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IOException("GraphQL request failed: " + response.statusCode() + " " + response.body());
    }

    String rawJson = response.body();
    GraphqlEnvelope envelope = OBJECT_MAPPER.readValue(rawJson, GraphqlEnvelope.class);
    envelope.rawJson = rawJson;
    if (envelope.errors != null && !envelope.errors.isEmpty()) {
      throw new IOException("GraphQL returned errors: " + toPrettyJson(envelope.errors));
    }
    return envelope;
  }

  private static String toPrettyJson(Object value) {
    try {
      return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to serialize value", e);
    }
  }
}
