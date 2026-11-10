# Project Borealis: Rating Rules

This fictional document is confidential and is for members of Project Borealis only. It describes how the usage-based pricing engine turns usage events into billable quantities.

## Units

Usage is metered in three units: API requests, stored gigabyte-days and search queries. Each event carries exactly one unit and a quantity.

## Rounding

Quantities are summed per customer, unit and day, and the daily sum is rounded up to the next whole unit only once, at the end of the billing period. Rounding each event or each day would overcharge customers with many small events.

## Late events

An event that arrives after its billing period has been closed is rated in the next period and marked as late on the invoice. Events older than 35 days are dropped and counted in the metering dashboard.

## Free tier

The first 10,000 API requests per month are not billed. The free tier applies per customer account, not per project inside an account.
