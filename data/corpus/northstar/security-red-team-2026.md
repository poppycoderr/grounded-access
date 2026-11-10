# Red Team Exercise 2026: Findings

This fictional report is restricted. It lists the findings of the 2026 red team exercise against Northstar Cloud.

## Scope

The red team had four weeks and the goal of reading invoice data of a customer account without being given any credentials.

## Findings

The team reached the goal in eleven days. The path went through a forgotten staging host that still accepted an old shared password, then through a build server whose credentials could read the production artifact store.

## Remediation

The staging host was removed, shared passwords were retired across all environments, and build servers lost read access to production stores. A follow-up exercise is planned for the first quarter of 2027.
