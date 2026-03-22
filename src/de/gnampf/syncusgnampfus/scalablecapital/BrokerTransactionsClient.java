package de.gnampf.syncusgnampfus.scalablecapital;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class BrokerTransactionsClient {
  private final BrokerApiClient brokerApiClient = new BrokerApiClient();
  private static final String MORE_TRANSACTIONS_QUERY = """
      query moreTransactions($personId: ID!, $input: BrokerTransactionInput!, $portfolioId: ID!) {
        account(id: $personId) {
          id
          brokerPortfolio(id: $portfolioId) {
            id
            moreTransactions(input: $input) {
              ...MoreTransactionsFragment
              __typename
            }
            __typename
          }
          __typename
        }
      }

      fragment MoreTransactionsFragment on BrokerTransactionSummaries {
        cursor
        total
        transactions {
          id
          currency
          type
          status
          isCancellation
          lastEventDateTime
          description
          ...BrokerCashTransactionSummaryFragment
          ...BrokerNonTradeSecurityTransactionSummaryFragment
          ...BrokerSecurityTransactionSummaryFragment
          ...BrokerEltifTransactionSummaryFragment
          __typename
        }
        __typename
      }

      fragment BrokerCashTransactionSummaryFragment on BrokerCashTransactionSummary {
        cashTransactionType
        amount
        relatedIsin
        __typename
      }

      fragment BrokerNonTradeSecurityTransactionSummaryFragment on BrokerNonTradeSecurityTransactionSummary {
        nonTradeSecurityTransactionType
        quantity
        amount
        isin
        __typename
      }

      fragment BrokerSecurityTransactionSummaryFragment on BrokerSecurityTransactionSummary {
        securityTransactionType
        quantity
        amount
        side
        isin
        __typename
      }

      fragment BrokerEltifTransactionSummaryFragment on BrokerEltifTransactionSummary {
        amount
        eltifQuantity
        isin
        securityTransactionType
        side
        __typename
      }
      """;

  GraphqlEnvelope fetchBrokerTransactions(Session session) throws IOException, InterruptedException {
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("pageSize", 50);
    input.put("cursor", null);
    input.put("searchTerm", "");
    input.put("type", List.of());
    input.put("status", List.of());
    input.put("includeReinvestmentSubtypes", true);

    Map<String, Object> variables = new LinkedHashMap<>();
    variables.put("personId", session.personId());
    variables.put("portfolioId", session.portfolioId());
    variables.put("input", input);

    GraphqlRequestBody requestBody = new GraphqlRequestBody("moreTransactions", variables, MORE_TRANSACTIONS_QUERY);
    return brokerApiClient.execute(session, requestBody);
  }
}
