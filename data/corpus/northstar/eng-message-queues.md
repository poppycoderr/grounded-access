# Message Queues

This fictional standard is part of the Northstar Cloud engineering handbook.

## Retention

Messages stay in a queue for at most seven days. Messages that are not consumed in that time are moved to the queue's dead-letter queue.

## Dead letters

A message moves to the dead-letter queue after five failed delivery attempts. The owning team is alerted when its dead-letter queue holds more than 100 messages.

## Ordering

Queues do not guarantee ordering between partitions. Consumers that need ordering use the customer account as the partition key.
