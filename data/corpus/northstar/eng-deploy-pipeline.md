# Deployment Pipeline Standard

This fictional standard describes how Northstar Cloud services move from a merged change to production. It applies to every service that the platform team hosts, including the billing, search and identity services.

## Stages

| Stage | What runs | Who can approve | Typical duration |
|---|---|---|---|
| build | compile, unit tests, container image | nobody, automatic | 6 minutes |
| staging | integration tests against staging data | nobody, automatic | 20 minutes |
| canary | 5 percent of production traffic in one region | the service owner | 30 minutes |
| regional | every region, one region at a time | the service owner | 2 hours |
| global | the remaining traffic | automatic after the last region | 10 minutes |

A change that fails a stage never moves to the next one. The release dashboard shows which stage each change has reached.

## Pipeline definition

Every service keeps its pipeline in a file named `deploy.yaml` at the root of its repository. The platform team reviews any change to that file. A minimal definition looks like this:

```yaml
service: invoice-worker
owner: team-billing
stages:
  canary:
    traffic_percent: 5
    region: eu-central
    bake_minutes: 30
  regional:
    order: [eu-central, eu-west, us-east, us-west]
    pause_between_regions_minutes: 20
rollback:
  automatic: true
  error_budget_burn: 4x
```

The `bake_minutes` value may be raised but not lowered below 30. The `pause_between_regions_minutes` value may not be lower than 15.

## Canary analysis

The canary stage is where most bad changes are caught, so its rules are stricter than those of any other stage. While a canary runs, the pipeline compares the canary instances with the instances that still run the previous version in the same region, and it does so every minute for the whole bake time. It compares the error rate, the 99th percentile latency and the rate of restarts. A canary fails when its error rate is more than twice the error rate of the previous version for three consecutive minutes, when its 99th percentile latency is more than 25 percent higher for five consecutive minutes, or when any canary instance restarts more than twice. The comparison uses the previous version in the same region rather than a fixed threshold, because traffic patterns differ between regions and between hours of the day, and a fixed threshold would either miss real regressions at night or block healthy changes at peak time.

A canary that fails is rolled back automatically, and the change is marked as blocked in the release dashboard. The author of the change receives a message with the metric that failed and a link to the comparison graphs. A blocked change cannot be retried by pressing the retry button. The author must push a new commit, even if that commit only reverts part of the change, so that the history shows what was done about the failure. The service owner may override a failed canary only for an incident fix, and the override is recorded with the name of the person who made it and the incident number.

Some metrics are too noisy for automatic comparison on small services. A service that receives fewer than 50 requests per minute in its canary region may switch the latency comparison off in its pipeline definition, but never the error rate comparison or the restart check. Such a service must instead run its synthetic checks against the canary instances before the bake time starts. The platform team publishes a list of services with the latency comparison switched off every quarter, and each owner on that list confirms that the reason still holds.

Canary traffic is chosen by customer account, not by request. A customer whose account falls into the canary group sends all of its requests to the canary instances for the whole bake time, so that one customer never sees two versions of a service alternate within a single session. Accounts on the Enterprise plan are excluded from the canary group unless the customer has asked to receive early changes. The canary group is reshuffled every week so that the same small customers do not always carry the risk.

## Rollback

A rollback redeploys the previous image to every region that has received the change. It takes about 8 minutes for a service with ten instances per region. Database migrations are never rolled back by the pipeline; a migration that has to be undone needs a new forward migration. For that reason a migration must be deployed, and must have run in every region, at least one day before any code that depends on it.

## Deploy windows

Deploys to production run from Monday to Thursday between 09:00 and 16:00 in the time zone of the canary region. On Fridays only incident fixes are deployed. During a declared incident the incident commander may set the deploy lock, which stops every pipeline at the end of its current stage until the lock is lifted.
