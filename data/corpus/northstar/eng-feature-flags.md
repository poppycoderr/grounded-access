# Feature Flag Guidelines

This fictional guide explains how Northstar Cloud engineers use feature flags to release changes gradually.

## Kinds of flags

A release flag hides unfinished work and is removed once the feature is fully released. An operational flag lets on-call engineers turn an expensive feature off during an incident and may stay for as long as the feature exists.

## Naming

Flag names start with the owning team and describe what the flag enables, for example `team-search.enable-fuzzy-matching`. A flag name never describes the state it disables, because double negations lead to mistakes during incidents.

## Lifetime

A release flag must be removed within 60 days after it has been turned on for every customer. The flag service lists release flags that are older than that, and their owning team receives a weekly reminder until they are gone.

## Flags and customers

A flag may be turned on for a list of customer accounts. Customers on the Enterprise plan are only added to a flag's list with the consent of their account executive.
