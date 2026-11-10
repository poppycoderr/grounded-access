# Incident Commander Guide

This fictional guide is for Northstar Cloud engineers who take the role of incident commander during an operational incident.

## When a commander is needed

Every SEV1 and every SEV2 that lasts longer than 30 minutes has an incident commander. The first engineer who declares the incident is the commander until someone takes the role over explicitly in the incident channel.

## What the commander does

The commander coordinates the response and does not debug. That separation is the most important rule of the role and the one most often broken, because the commander is usually an experienced engineer who knows the failing system well and is tempted to open a terminal. A commander who starts debugging stops noticing what the rest of the team is doing, and within minutes two people are restarting the same service or one person is waiting for an answer that nobody knows they owe. The commander instead keeps a written list of the people involved and of what each of them is doing, asks every ten minutes what has changed, and decides between options when the responders disagree. The commander also chooses who talks to whom: one person, the communications lead, writes every customer-facing update and every message to support, so that customers never receive two versions of the same story. When the commander needs to step away, even for a few minutes, they hand the role over in the incident channel and the new commander confirms it in writing. A handover that is not written down did not happen. If the commander finds that they are the only person who can fix the problem, they hand the commander role to someone else before starting to work on the fix.

## Closing an incident

The commander closes the incident when the symptom has been gone for 30 minutes and no mitigation needs to be watched. Closing the incident starts the clock for the postmortem, which is due within five business days.
