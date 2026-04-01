package de.gnampf.syncusgnampfus.scalablecapital;

import java.io.IOException;
import java.util.List;
import java.util.Map;

final class OvernightSavingsTransactionsClient {
  private final BrokerApiClient brokerApiClient = new BrokerApiClient();
  private final InterestApiClient interestApiClient = new InterestApiClient();

  private static final String GET_SAVINGS_PRODUCTS_QUERY = """
      query getSavingsProducts($personId: ID!) {
        account(id: $personId) {
          savingsAccounts {
            __typename
            id
            personalizations {
              id
              name
              __typename
            }
          }
          __typename
        }
      }
      """;

  private static final String OVERNIGHT_OVERVIEW_PAGE_DATA_QUERY = """
      query OvernightOverviewPageData($savingsAccountId: ID!, $accountId: ID!, $recentTransactionsInput: SavingsAccountCashTransactionInput!) {
        account(id: $accountId) {
          savingsAccount(id: $savingsAccountId) {
            id
            ... on OvernightSavingsAccount {
              ...TotalAmount
              ...InterestTable
              ...InterestBarChart
              ...RecentTransactions
              __typename
            }
            __typename
          }
          __typename
        }
      }

      fragment TotalAmount on OvernightSavingsAccount {
        totalAmount
        __typename
      }

      fragment InterestTable on OvernightSavingsAccount {
        nextPayoutDate {
          time
          __typename
        }
        depositInterestRate: interestRate
        interests {
          effectiveYearlyDepositInterestRate
          estimatedNextPayoutAmount
          currentAccruedAmount
          __typename
        }
        __typename
      }

      fragment InterestBarChart on OvernightSavingsAccount {
        nextPayoutDate {
          time
          __typename
        }
        interests {
          estimatedNextPayoutAmount
          currentAccruedAmount
          depositAccruedPerInterestPeriod(period: MONTH) {
            depositAccruedAmount
            lastDayOfInterestPeriod {
              time
              __typename
            }
            __typename
          }
          __typename
        }
        __typename
      }

      fragment RecentTransactions on OvernightSavingsAccount {
        id
        moreTransactions(input: $recentTransactionsInput) {
          transactions {
            id
            type
            status
            description
            amount
            currency
            lastEventDateTime
            cashTransactionType
            __typename
          }
          __typename
        }
        __typename
      }
      """;

  GraphqlEnvelope fetchOvernightSavingsTransactions(Session session) throws IOException, InterruptedException {
    String savingsAccountId = resolveSavingsAccountId(session);
    GraphqlRequestBody requestBody = new GraphqlRequestBody(
        "OvernightOverviewPageData",
        Map.of(
            "accountId", session.personId(),
            "savingsAccountId", savingsAccountId,
            "recentTransactionsInput", Map.of("pageSize", 20)),
        OVERNIGHT_OVERVIEW_PAGE_DATA_QUERY);

    return interestApiClient.execute(session, savingsAccountId, requestBody);
  }

  private String resolveSavingsAccountId(Session session) throws IOException, InterruptedException {
    GraphqlRequestBody requestBody = new GraphqlRequestBody(
        "getSavingsProducts",
        Map.of("personId", session.personId()),
        GET_SAVINGS_PRODUCTS_QUERY);

    GraphqlEnvelope envelope = brokerApiClient.execute(session, requestBody);
    List<SavingsAccount> savingsAccounts = envelope.data != null && envelope.data.account != null
        ? envelope.data.account.savingsAccounts
        : null;
    if (savingsAccounts == null || savingsAccounts.isEmpty() || savingsAccounts.getFirst().id == null) {
      throw new IOException("Could not resolve savingsAccountId from getSavingsProducts.");
    }
    return savingsAccounts.getFirst().id;
  }
}
