package de.gnampf.syncusgnampfus.scalablecapital;

import java.io.IOException;
import java.util.Map;

final class PortfolioCashClient {
  private final BrokerApiClient brokerApiClient = new BrokerApiClient();

  private static final String GET_CASH_BREAKDOWN_QUERY = """
      query getCashBreakdown($personId: ID!, $portfolioId: ID!) {
        account(id: $personId) {
          id
          brokerPortfolio(id: $portfolioId) {
            id
            ...PaymentsOnBrokerPortfolioFragment
            __typename
          }
          __typename
        }
      }

      fragment PaymentsOnBrokerPortfolioFragment on BrokerPortfolio {
        payments {
          id
          buyingPower {
            id
            cashBalance
            liveLimit
            pendingBuyOrdersAmount
            pendingDividendsReinvestmentAmount
            pendingPocketMoneyAmount
            pendingSavingsPlanAmount
            pendingWithdrawalsAmount
            estimatedTaxes
            directDebit
            cashAvailableToInvest
            __typename
          }
          derivativesBuyingPower {
            id
            cashAvailableToInvest
            derivativesDirectDebit
            cashAvailableForDerivatives
            __typename
          }
          withdrawalPower {
            id
            cashAvailableToInvest
            sellTradesAmount
            withdrawalDirectDebit
            cashAvailableForWithdrawal
            __typename
          }
          __typename
        }
        __typename
      }
      """;

  GraphqlEnvelope fetchPortfolioCash(Session session) throws IOException, InterruptedException {
    GraphqlRequestBody requestBody = new GraphqlRequestBody(
        "getCashBreakdown",
        Map.of(
            "personId", session.personId(),
            "portfolioId", session.portfolioId()),
        GET_CASH_BREAKDOWN_QUERY);

    return brokerApiClient.execute(session, requestBody);
  }
}
