# Postmortem: Duplicate Invoices on 12 March 2026

This postmortem describes a fictional incident used in the Grounded Access demo corpus.

## Summary

For 47 minutes, a retry bug in the invoice worker sent duplicate invoices to 312 customers. No customer was charged twice because the payment provider rejected duplicate charges.

## Root cause

The invoice worker retried requests after a timeout without an idempotency key, so the billing API created a second invoice for each retried request.

## Follow-up actions

Add idempotency keys to every invoice request, alert when the duplicate-invoice rate exceeds 0.1 percent, and send a correction email to affected customers.
