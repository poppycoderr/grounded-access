# Postmortem: Stale Invoice Search Results on 3 July 2026

This postmortem describes a fictional incident used in the Grounded Access demo corpus. It is written in the blameless format that Northstar Cloud uses for every operational incident.

## Summary

For 2 hours and 18 minutes, customers who searched their invoices saw results that were up to nine hours old. New invoices existed and could be opened from the invoice list, but searching for them returned nothing. No data was lost, and no customer saw another customer's invoices.

## Timeline

| Time (UTC) | Event |
|---|---|
| 06:10 | The nightly snapshot job of the standby search cluster fails with a full disk; nobody is alerted |
| 13:02 | The primary search cluster turns red after a node is replaced |
| 13:14 | The on-call engineer switches the search alias to the standby cluster, following the failover runbook |
| 13:40 | Support reports that customers cannot find invoices created this morning |
| 14:55 | The engineer finds that the standby was last refreshed at 04:00 |
| 15:32 | The alias points back at the repaired primary and results are current again |

## Root cause

The failover runbook says that the standby cluster is refreshed from snapshots every hour, and the on-call engineer relied on that statement when switching the alias. In fact the refresh job on the standby had been failing since 06:10, because the disk that holds incoming snapshots was full. The job wrote its failure to its own log, but no alert watched that log, and the search dashboards only showed the health of the primary cluster. The disk had filled up slowly over four months: the cleanup step of the job removed snapshots older than seven days from the primary's repository but never from the standby's local copy, so every snapshot ever restored on the standby stayed on its disk. The red primary was a separate and ordinary event, a node replacement that took longer than usual, and on its own it would have caused a few minutes of slower searches. The two events together turned a routine failover into more than two hours of stale results. The engineer also had no quick way to see how old the standby's data was. The search console shows the time of the last snapshot taken, not the time of the last snapshot restored, and those two times are the same only while the refresh job works.

## What went well

Support noticed the symptom within 26 minutes of the failover and linked every customer ticket to one incident. The alias switch itself worked as the runbook describes.

## Follow-up actions

1. Alert when the standby refresh job has not succeeded for two hours.
2. Show the time of the last restored snapshot in the search console.
3. Remove old snapshots from the standby's local copy in the same cleanup step that removes them from the repository.
4. Change the failover runbook so that the engineer checks the age of the standby before switching the alias.
