# Time Zones and Dates

This fictional guide is part of the Northstar Cloud engineering handbook.

## Storage

Timestamps are stored in UTC. The customer's time zone is applied only when a date is shown or a report is cut.

## Billing periods

A billing period starts at midnight in the time zone set on the customer account, not in UTC.

## Daylight saving

Jobs that run once a day are scheduled in UTC so that they neither skip nor repeat a day when clocks change.
