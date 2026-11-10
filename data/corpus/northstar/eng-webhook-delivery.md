# Webhook Delivery

This fictional page is part of the public Northstar Cloud developer documentation.

## Retries

A webhook delivery that does not receive a 2xx response within ten seconds is retried with exponential backoff for up to 72 hours.

## Disabling endpoints

An endpoint that has failed every delivery for 72 hours is disabled, and the account administrators receive an email.

## Order

Webhooks are not guaranteed to arrive in the order in which events happened. Each event carries a creation timestamp.
