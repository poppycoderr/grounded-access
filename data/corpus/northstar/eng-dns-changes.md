# DNS Changes

This fictional procedure is part of the Northstar Cloud engineering handbook.

## Making a change

DNS records are changed only through the infrastructure repository. Changes made in the provider's console are overwritten at the next deployment.

## Time to live

Records for customer-facing endpoints have a time to live of 300 seconds, so that a change takes effect within five minutes.

## Lowering the TTL first

Before moving an endpoint, the team lowers its time to live to 60 seconds at least one day in advance.
