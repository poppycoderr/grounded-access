# Code Review Standard

This fictional standard describes how Northstar Cloud engineers review each other's changes before they are merged.

## Who reviews

Every change needs one approval from an engineer other than the author. A change to a service's `deploy.yaml`, to database migrations or to anything under `security/` needs a second approval from the owning team. The author never approves their own change, even as an administrator of the repository.

## What a reviewer looks for

A review is first a check that the change does what its description says, and only then a check of style. The reviewer reads the description and the linked ticket before reading any code, so that they can tell whether the change solves the problem it claims to solve. They then look for behaviour that the tests do not cover: error paths, retries, timeouts, concurrent access and inputs at the edges of what the code accepts. Reviewers ask for a test when they find such behaviour, rather than describing a scenario in a comment and trusting the author to have tried it. Questions about naming, formatting and structure come last, and anything a formatter or linter could decide is left to the tools; a reviewer who finds themselves commenting on whitespace should propose a linter rule instead. A reviewer who does not understand part of a change says so plainly, because code that one experienced colleague cannot follow will be harder still for the engineer who is paged for it at night. Comments are marked as either blocking or optional, and an optional comment never holds a change back. When a discussion goes past three rounds of comments, the reviewer and the author talk in person or on a call and write the outcome into the review, so that the history explains the decision.

## Size of a change

A change should be small enough to review in about 30 minutes. As a rule of thumb that means fewer than 400 changed lines, not counting generated files and test data. A larger change is split, or the author walks the reviewer through it on a call before the review starts.

## Response times

Reviewers respond to a review request within one business day. If they cannot, they say so and suggest another reviewer. An author who has waited two business days may ask the team lead to assign someone else.

## Example of a blocking comment

```diff
-    retry(times = 5) { client.send(invoice) }
+    retry(times = 5, idempotencyKey = invoice.id) { client.send(invoice) }
```

> Blocking: without the idempotency key a retry after a timeout can create a second invoice. See the postmortem of 12 March 2026.
