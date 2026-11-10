# Public API Pagination

This fictional page explains how list endpoints of the Northstar Cloud public API return long results. It is public documentation for customers.

## Cursors

List endpoints return at most 100 items per page. A response that has more items carries a `next_cursor` value, such as `cur_inv_2026-03-12T08:15:00Z_000481`, which the client sends as the `cursor` parameter of the next request. A cursor is valid for 24 hours.

## Page size

Clients may ask for fewer items with the `limit` parameter, between 1 and 100. Asking for more than 100 returns an error rather than a shorter page.

## Ordering

Items are returned in the order in which they were created, oldest first. Items created while a client pages through a list appear at the end of the list, never in a page the client has already read.
