# TLS Certificates

This fictional standard is part of the Northstar Cloud engineering handbook.

## Issuing

Certificates for public endpoints are issued automatically by the certificate service and are valid for 90 days.

## Renewal

The certificate service renews a certificate 30 days before it expires. If renewal fails three times, the owning team is paged.

## Internal services

Internal services use certificates from the internal authority, which are valid for seven days and renewed every day.
