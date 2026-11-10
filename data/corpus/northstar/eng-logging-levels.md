# Logging Levels

This fictional guide is part of the Northstar Cloud engineering handbook.

## Levels

ERROR is for failures that need a person to act. WARN is for unexpected situations the service handled itself. INFO is for events that help explain what happened. DEBUG is off in production.

## Structured logs

Logs are written as JSON with a fixed set of fields, including the trace identifier.

## Noise

A log line that is written more than 100 times per second by one instance is a bug and is fixed or sampled.
