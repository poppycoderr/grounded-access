# API Style Guide

This fictional guide is part of the Northstar Cloud engineering handbook.

## Resource names

Resource names are plural nouns in lowercase, such as invoices or customers. Actions that are not create, read, update or delete use a verb after a colon, for example invoices/{id}:finalize.

## Field names

Field names use snake case. Timestamps end in _at and are given in UTC with a time zone suffix.

## Errors

Every error carries a code from the public error catalog. New codes are added to the catalog in the same change.
