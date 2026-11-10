# Scheduled Jobs

This fictional standard is part of the Northstar Cloud engineering handbook.

## Where jobs run

Scheduled jobs run on the shared job platform, never as cron entries on individual hosts.

## Overlap

A job whose previous run is still going is skipped, not started twice. Jobs that must run every time say so in their definition.

## Monitoring

Every job reports success or failure. A job that has not succeeded for twice its interval alerts its owning team.
