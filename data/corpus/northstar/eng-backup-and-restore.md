# Backup and Restore Standard

This fictional standard defines how Northstar Cloud protects production data.

## Objectives

For the billing database the recovery point objective is 5 minutes and the recovery time objective is 1 hour. For all other production databases the objectives are 1 hour and 4 hours.

## Retention

Daily snapshots are kept for 35 days and monthly snapshots for 13 months. Snapshots are encrypted and copied to a second region.

## Restore drills

Every team restores one production database into an isolated environment once per quarter and records the measured restore time.
