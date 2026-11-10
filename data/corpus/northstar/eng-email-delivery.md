# Transactional Email

This fictional standard is part of the Northstar Cloud engineering handbook.

## Provider

Transactional email, such as invoices and password reset messages, is sent through one provider with a dedicated sending domain.

## Rate

Each customer account can send at most 1,000 transactional emails per hour, to protect the domain's reputation.

## Bounces

An address that bounces three times is suppressed, and the customer's administrator is told in the console.
