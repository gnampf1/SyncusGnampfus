package de.gnampf.syncusgnampfus.scalablecapital;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.options.Cookie;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
class GraphqlEnvelope {
  public GraphqlData data;
  public List<GraphqlError> errors;
  @JsonIgnore
  public String rawJson;
}

@JsonIgnoreProperties(ignoreUnknown = true)
class GraphqlError {
  public String message;
  public List<Map<String, Object>> path;
  public Map<String, Object> extensions;
}

@JsonIgnoreProperties(ignoreUnknown = true)
class GraphqlData {
  public Account account;
}

@JsonIgnoreProperties(ignoreUnknown = true)
class Account {
  public String id;
  public List<SavingsAccount> savingsAccounts;
  public BrokerPortfolio brokerPortfolio;
  public SavingsAccount savingsAccount;
}

@JsonIgnoreProperties(ignoreUnknown = true)
class SavingsAccount {
  public String id;
  public ProductPersonalization personalizations;
  public TransactionCollection moreTransactions;
  public Double totalAmount;
  public Double depositInterestRate;
  public JsonNode nextPayoutDate;
  public JsonNode interests;
}

@JsonIgnoreProperties(ignoreUnknown = true)
class ProductPersonalization {
  public String id;
  public String name;
}

@JsonIgnoreProperties(ignoreUnknown = true)
class BrokerPortfolio {
  public String id;
  public FreeUpgradeEligibility freeUpgradeEligibility;
  public TransactionCollection moreTransactions;
  public Inventory inventory;
  public Payments payments;
}

@JsonIgnoreProperties(ignoreUnknown = true)
class FreeUpgradeEligibility {
  public Boolean isEligible;
  public Integer periodLengthInMonths;
}

@JsonIgnoreProperties(ignoreUnknown = true)
class Payments {
  public String id;
  public BuyingPower buyingPower;
  public DerivativesBuyingPower derivativesBuyingPower;
  public WithdrawalPower withdrawalPower;
}

@JsonIgnoreProperties(ignoreUnknown = true)
class BuyingPower {
  public String id;
  public Double cashBalance;
  public Double liveLimit;
  public Double pendingBuyOrdersAmount;
  public Double pendingDividendsReinvestmentAmount;
  public Double pendingPocketMoneyAmount;
  public Double pendingSavingsPlanAmount;
  public Double pendingWithdrawalsAmount;
  public Double estimatedTaxes;
  public Double directDebit;
  public Double cashAvailableToInvest;
}

@JsonIgnoreProperties(ignoreUnknown = true)
class DerivativesBuyingPower {
  public String id;
  public Double cashAvailableToInvest;
  public Double derivativesDirectDebit;
  public Double cashAvailableForDerivatives;
}

@JsonIgnoreProperties(ignoreUnknown = true)
class WithdrawalPower {
  public String id;
  public Double cashAvailableToInvest;
  public Double sellTradesAmount;
  public Double withdrawalDirectDebit;
  public Double cashAvailableForWithdrawal;
}

@JsonIgnoreProperties(ignoreUnknown = true)
class Inventory {
  public String id;
  public PortfolioGroups portfolioGroups;
  public UngroupedInventoryItems ungroupedInventoryItems;
}

@JsonIgnoreProperties(ignoreUnknown = true)
class PortfolioGroups {
  public String id;
  public Boolean maxPortfolioGroupsPerPortfolioReached;
  public Boolean offerAllowsAdditionalPortfolioGroup;
  public List<PortfolioGroup> items;
}

@JsonIgnoreProperties(ignoreUnknown = true)
class PortfolioGroup {
  public String id;
  public PortfolioGroupDetails details;
  public List<Security> items;
  public Integer numberOfPendingOrders;
  public Double savingsPlansAmount;
  public PortfolioGroupPerformance performance;
}

@JsonIgnoreProperties(ignoreUnknown = true)
class PortfolioGroupDetails {
  public String id;
  public String name;
  public String description;
}

@JsonIgnoreProperties(ignoreUnknown = true)
class UngroupedInventoryItems {
  public String id;
  public List<Security> items;
}

@JsonIgnoreProperties(ignoreUnknown = true)
class Security {
  public String id;
  public String isin;
  public String wkn;
  public String name;
  public String type;
  public Boolean isSustainable;
  public Boolean isOnWatchlist;
  public Integer numberOfPendingOrders;
  public InventoryItem inventory;
  public String partnerType;
  public List<String> reimbursedFor;
  public QuoteTick quoteTick;
}

@JsonIgnoreProperties(ignoreUnknown = true)
class InventoryItem {
  public String id;
  public SavingsPlan savingsPlan;
  public InventoryPosition position;
}

@JsonIgnoreProperties(ignoreUnknown = true)
class SavingsPlan {
  public String isin;
  public Double amount;
  public Integer dayOfTheMonth;
  public Double dynamizationRate;
  public String frequency;
  public String paymentMethod;
  public SimpleDateValue nextExecutionDate;
}

@JsonIgnoreProperties(ignoreUnknown = true)
class InventoryPosition {
  public Double filled;
  public Double blocked;
  public Double pending;
  public List<SellableByVenue> sellableByVenue;
  public Double fifoPrice;
}

@JsonIgnoreProperties(ignoreUnknown = true)
class SellableByVenue {
  public String venue;
  public Double sellable;
}

@JsonIgnoreProperties(ignoreUnknown = true)
class QuoteTick {
  public String id;
  public String isin;
  public Double midPrice;
  public String time;
  public String currency;
  public Double bidPrice;
  public Double askPrice;
  public Boolean isOutdated;
  public ZonedTimestamp timestampUtc;
  public SimpleDateValue performanceDate;
  public List<PerformanceOverTime> performancesByTimeframe;
}

@JsonIgnoreProperties(ignoreUnknown = true)
class ZonedTimestamp {
  public String time;
  public Long epochMillisecond;
}

@JsonIgnoreProperties(ignoreUnknown = true)
class SimpleDateValue {
  public String date;
}

@JsonIgnoreProperties(ignoreUnknown = true)
class PerformanceOverTime {
  public String timeframe;
  public Double performance;
  public Double simpleAbsoluteReturn;
}

@JsonIgnoreProperties(ignoreUnknown = true)
class PortfolioGroupPerformance {
  public String id;
  public Double valuation;
  public List<PerformanceOverTime> performancesByTimeframe;
}

@JsonIgnoreProperties(ignoreUnknown = true)
class TransactionCollection {
  public String cursor;
  public Integer total;
  public List<TransactionSummary> transactions;
}

@JsonIgnoreProperties(ignoreUnknown = true)
class TransactionSummary {
  public String id;
  public String currency;
  public String type;
  public String status;
  public Boolean isCancellation;
  public String lastEventDateTime;
  public String description;
  public Double amount;
  public Double quantity;
  public Double eltifQuantity;
  public String side;
  public String isin;
  public String relatedIsin;
  public String cashTransactionType;
  public String nonTradeSecurityTransactionType;
  public String securityTransactionType;
}

record Session(List<Cookie> cookies, String personId, String portfolioId) {
}

record GraphqlRequestBody(String operationName, Map<String, Object> variables, String query) {
}
