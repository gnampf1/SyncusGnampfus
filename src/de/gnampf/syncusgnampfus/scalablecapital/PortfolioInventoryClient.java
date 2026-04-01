package de.gnampf.syncusgnampfus.scalablecapital;

import java.io.IOException;
import java.util.Map;

final class PortfolioInventoryClient {
  private final BrokerApiClient brokerApiClient = new BrokerApiClient();

  private static final String GET_PORTFOLIO_GROUPS_INVENTORY_QUERY = """
      query getPortfolioGroupsInventory($personId: ID!, $portfolioId: ID!) {
        account(id: $personId) {
          id
          brokerPortfolio(id: $portfolioId) {
            ...PortfolioGroupsInventoryFragment
            __typename
          }
          __typename
        }
      }

      fragment PortfolioGroupsInventoryFragment on BrokerPortfolio {
        id
        inventory {
          id
          portfolioGroups {
            id
            maxPortfolioGroupsPerPortfolioReached
            offerAllowsAdditionalPortfolioGroup
            items {
              id
              details {
                id
                name
                description
                __typename
              }
              items {
                ...SecurityInfoFragment
                ...SecurityQuoteTick
                __typename
              }
              numberOfPendingOrders
              savingsPlansAmount
              performance {
                id
                valuation
                performancesByTimeframe {
                  performance
                  simpleAbsoluteReturn
                  timeframe
                  __typename
                }
                __typename
              }
              __typename
            }
            __typename
          }
          ungroupedInventoryItems {
            id
            items {
              ...SecurityInfoFragment
              ...SecurityQuoteTick
              __typename
            }
            __typename
          }
          __typename
        }
        __typename
      }

      fragment SecurityInfoFragment on Security {
        id
        isin
        wkn
        name
        type
        isSustainable
        isOnWatchlist
        numberOfPendingOrders
        inventory {
          id
          ...SavingsPlanFragment
          ...PositionFragment
          __typename
        }
        partnerType
        reimbursedFor
        __typename
      }

      fragment SavingsPlanFragment on InventoryItem {
        savingsPlan {
          isin
          amount
          dayOfTheMonth
          dynamizationRate
          frequency
          paymentMethod
          nextExecutionDate {
            date
            __typename
          }
          __typename
        }
        __typename
      }

      fragment PositionFragment on InventoryItem {
        position {
          filled
          blocked
          pending
          sellableByVenue {
            venue
            sellable
            __typename
          }
          fifoPrice
          __typename
        }
        __typename
      }

      fragment SecurityQuoteTick on Security {
        quoteTick {
          ...QuoteTickFragment
          __typename
        }
        __typename
      }

      fragment QuoteTickFragment on QuoteTick {
        id
        isin
        midPrice
        time
        currency
        bidPrice
        askPrice
        isOutdated
        timestampUtc {
          time
          epochMillisecond
          __typename
        }
        performanceDate {
          date
          __typename
        }
        performancesByTimeframe {
          timeframe
          performance
          simpleAbsoluteReturn
          __typename
        }
        __typename
      }
      """;

  GraphqlEnvelope fetchPortfolioInventory(Session session) throws IOException, InterruptedException {
    GraphqlRequestBody requestBody = new GraphqlRequestBody(
        "getPortfolioGroupsInventory",
        Map.of(
            "personId", session.personId(),
            "portfolioId", session.portfolioId()),
        GET_PORTFOLIO_GROUPS_INVENTORY_QUERY);

    return brokerApiClient.execute(session, requestBody);
  }
}
