# Chat idle-load comparison: polling and SSE

This is a code-path estimate for an idle chat, not a live throughput benchmark. It counts repository calls visible in `ChatService` and `ChatController`; it does not claim latency, SQL duration, or server capacity.

## Calls per idle client

A history polling request runs three repository methods: check that the user exists, load and authorize the match, and fetch one message page. This still runs when no new message exists.

An SSE connection runs the user and match checks once when it opens. While it stays idle, SSE heartbeats are sent from memory every 15 seconds and do not call a repository. Sending a real message still performs the normal database writes in either design; this comparison concerns idle clients waiting for messages.

For `N` clients, observation time `T` seconds, and polling interval `P` seconds:

- Polling repository calls: `3 × N × ceil(T / P)`
- SSE repository calls while idle: `2 × N`

Example: 20 clients waiting for 60 seconds, polling every 2 seconds:

- Polling: `3 × 20 × 30 = 1,800` repository calls
- SSE: `2 × 20 = 40` repository calls to establish the streams, then zero for idle heartbeats
- Estimated idle repository-call reduction: `45×` for this scenario

Polling also makes a new message wait until the next poll (up to the polling interval, averaging about half that interval when arrivals are evenly distributed). SSE sends the event after the message transaction commits, subject to network and server scheduling.

## Reproduce with a real database

To measure actual SQL counts and latency for this deployment, use the same PostgreSQL instance, match data, JWTs, and 20 authenticated clients for each run. Run each scenario for 60 seconds: one run calls history every 2 seconds; the other opens an SSE stream per client and leaves it idle. Capture SQL counts and query timings with Hibernate statistics or a datasource proxy, and record API response/event latency. Repeat several runs and compare medians. Do not compare repository-call estimates with SQL timings as if they were the same measurement.

The estimate assumes one application instance. SSE connections currently live in that instance's memory; a multi-instance deployment needs shared pub/sub for events to reach clients connected to a different instance.