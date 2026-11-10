# Observability Standard

This fictional standard describes how Northstar Cloud services expose metrics, traces and alerts. It applies to every production service.

## Service level objectives

| Service class | Availability objective | Latency objective |
|---|---|---|
| customer-facing API | 99.95 percent of requests per 30 days | 99th percentile below 800 ms |
| background worker | 99.9 percent of jobs per 30 days | 95 percent of jobs finish within 10 minutes |
| internal tool | 99.5 percent of requests per 30 days | none |

## Alert design

An alert must be actionable: whoever is paged must be able to do something about it at that moment. Alerts on causes, such as high processor use or a full queue, are not allowed to page anyone; they may open a ticket instead. Pages are reserved for symptoms that customers feel, which in practice means the burn rate of an error budget. A service pages when it spends two percent of its 30-day error budget within one hour, or five percent within six hours. The first rule catches sudden outages quickly, and the second catches slow degradations that would otherwise stay below every short-term threshold until the budget is gone. Each paging alert links to a runbook section, and an alert without a runbook link is rejected when the alert definition is reviewed. Every quarter the owning team looks at each alert that paged during the quarter and asks whether the page led to an action. An alert that paged more than three times without leading to any action is either changed or deleted before the end of the following quarter. Teams that run a service with fewer than one page per month of primary on-call should still keep at least one paging alert per objective, so that a complete outage can never go unnoticed.

```yaml
alert: InvoiceApiErrorBudgetFastBurn
expr: error_budget_burn_rate{service="invoice-api", window="1h"} > 14.4
for: 2m
labels:
  severity: page
annotations:
  runbook: https://runbooks.northstar.example/invoice-api#fast-burn
```

## Trace sampling

Every service samples traces with the shared sampling configuration, which is stored as one line of JSON so that it can be passed in an environment variable. A service must not change the default sample rate on its own; it asks the platform team to add a rule. The current configuration is:

```json
{"version":3,"default_sample":0.02,"rules":[{"service":"invoice-worker","route":"/v1/invoices","sample":0.01,"keep_errors":true},{"service":"invoice-worker","route":"/v1/invoices/{id}","sample":0.05,"keep_errors":true},{"service":"invoice-worker","route":"/v1/payments","sample":0.1,"keep_errors":true},{"service":"invoice-worker","route":"/v1/customers","sample":0.25,"keep_errors":true},{"service":"invoice-api","route":"/v1/invoices","sample":0.05,"keep_errors":true},{"service":"invoice-api","route":"/v1/invoices/{id}","sample":0.1,"keep_errors":true},{"service":"invoice-api","route":"/v1/payments","sample":0.25,"keep_errors":true},{"service":"invoice-api","route":"/v1/customers","sample":0.01,"keep_errors":true},{"service":"payment-gateway","route":"/v1/invoices","sample":0.1,"keep_errors":true},{"service":"payment-gateway","route":"/v1/invoices/{id}","sample":0.25,"keep_errors":true},{"service":"payment-gateway","route":"/v1/payments","sample":0.01,"keep_errors":true},{"service":"payment-gateway","route":"/v1/customers","sample":0.05,"keep_errors":true},{"service":"search-indexer","route":"/v1/invoices","sample":0.25,"keep_errors":true},{"service":"search-indexer","route":"/v1/invoices/{id}","sample":0.01,"keep_errors":true},{"service":"search-indexer","route":"/v1/payments","sample":0.05,"keep_errors":true},{"service":"search-indexer","route":"/v1/customers","sample":0.1,"keep_errors":true},{"service":"search-api","route":"/v1/invoices","sample":0.01,"keep_errors":true},{"service":"search-api","route":"/v1/invoices/{id}","sample":0.05,"keep_errors":true},{"service":"search-api","route":"/v1/payments","sample":0.1,"keep_errors":true},{"service":"search-api","route":"/v1/customers","sample":0.25,"keep_errors":true},{"service":"identity","route":"/v1/invoices","sample":0.05,"keep_errors":true},{"service":"identity","route":"/v1/invoices/{id}","sample":0.1,"keep_errors":true},{"service":"identity","route":"/v1/payments","sample":0.25,"keep_errors":true},{"service":"identity","route":"/v1/customers","sample":0.01,"keep_errors":true},{"service":"edge-gateway","route":"/v1/invoices","sample":0.1,"keep_errors":true},{"service":"edge-gateway","route":"/v1/invoices/{id}","sample":0.25,"keep_errors":true},{"service":"edge-gateway","route":"/v1/payments","sample":0.01,"keep_errors":true},{"service":"edge-gateway","route":"/v1/customers","sample":0.05,"keep_errors":true},{"service":"metering","route":"/v1/invoices","sample":0.25,"keep_errors":true},{"service":"metering","route":"/v1/invoices/{id}","sample":0.01,"keep_errors":true},{"service":"metering","route":"/v1/payments","sample":0.05,"keep_errors":true},{"service":"metering","route":"/v1/customers","sample":0.1,"keep_errors":true},{"service":"pricing-engine","route":"/v1/invoices","sample":0.01,"keep_errors":true},{"service":"pricing-engine","route":"/v1/invoices/{id}","sample":0.05,"keep_errors":true},{"service":"pricing-engine","route":"/v1/payments","sample":0.1,"keep_errors":true},{"service":"pricing-engine","route":"/v1/customers","sample":0.25,"keep_errors":true},{"service":"notification","route":"/v1/invoices","sample":0.05,"keep_errors":true},{"service":"notification","route":"/v1/invoices/{id}","sample":0.1,"keep_errors":true},{"service":"notification","route":"/v1/payments","sample":0.25,"keep_errors":true},{"service":"notification","route":"/v1/customers","sample":0.01,"keep_errors":true}]}
```

Requests that end in an error are always kept, whatever their sample rate.

## Metric names

Metric names use lowercase words joined by underscores and end in the unit, for example `invoice_worker_job_duration_seconds`. A metric must not carry a label whose values are unbounded, such as a customer identifier or a request identifier.
