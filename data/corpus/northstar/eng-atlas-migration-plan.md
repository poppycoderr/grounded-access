# Project Atlas: Billing Database Migration Plan

This fictional plan is for members of project Atlas. Atlas moves the billing database to a new storage engine.

## Timeline

The cutover is planned for the second weekend of November. A rehearsal on the staging cluster takes place three weeks before the cutover.

## Rollback

If error rates stay above 2 percent for 10 minutes after the cutover, the Atlas lead switches traffic back to the old primary. The old primary is kept for 14 days before it is decommissioned.

## Freeze

All schema changes to the billing database are frozen from five days before the cutover until the old primary is decommissioned.
