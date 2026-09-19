# OmniLinkSDK Architecture Evolution\n\n## Implemented in 1.4\n\n### Real desktop/JVM transport\n\n- pure JVM `omni-link-transport` artifact,\n- TCP client and server,\n- ECDSA P-256 mutual signed handshake,\n- ephemeral ECDH P-256 key agreement,\n- HKDF-SHA256 session derivation,\n- independent AES-256-GCM keys per direction,\n- replay/out-of-order sequence enforcement,\n- capability-scoped inbound/outbound ACLs,\n- strict peer pinning and explicit pairing hooks,\n- chat-sandbox trust profile,\n- encrypted desktop identity persistence,\n- AndroidKeyStore identity adapter and Android peer trust persistence.\n

This file tracks what is implemented in the 1.3 source line and what remains staged for later transport
work.

## Implemented in 1.3

### Identity and trust

- shared first-party signing model documented,
- fail-closed `SameSignerSecurityValidator` default,
- Binder UID -> package set -> signing certificate resolution,
- signing history support for controlled key rotation,
- CORE / FIRST_PARTY / TRUSTED_PARTNER / UNTRUSTED tiers,
- partner capability allowlists,
- communication direction and data-scope metadata.

### Runtime hardening

- single SDK version source,
- canonical forward-compatible JSON codec,
- parse-once execution/audit path,
- inline request size guard,
- request deadlines,
- undeclared-capability rejection for explicit manifests,
- synchronous-call rejection for declared non-IMMEDIATE work,
- bounded concurrent async execution,
- coroutine cancellation when the Service dies,
- bounded local event buffering.

### Agent/tool semantics

- richer capability contracts,
- capability graph models,
- agent task DAG/delegation models,
- preview -> commit confirmation models,
- external app adapter routing models,
- separate limited public Omni request contract.

### Transport-independent session foundation

`OmniSessionHello` and `OmniFrame` model:

- multiplexed streams,
- request/result/event frames,
- ACK credits,
- cancellation,
- health pings,
- control/data/event lanes,
- codec negotiation,
- large-payload transport negotiation.

The session layer contains no Android classes so the same semantics can later be carried over Binder,
local sockets, LAN, USB/ADB or desktop transports.

## Staged next work

### 1. Actual large-payload Binder path

The protocol now models FILE_DESCRIPTOR / PIPE / SHARED_MEMORY / CONTENT_URI, but the deployed AIDL
surface still uses inline JSON.

The next transport release should append new AIDL methods rather than mutate existing transaction
positions. Android implementations should prefer:

- inline for small control payloads,
- ParcelFileDescriptor/pipe for large streams,
- SharedMemory where supported and appropriate for large immutable data.

Thresholds must come from benchmarks, not guesses.

### 2. True cross-process flow control

The current Flow wrapper bounds the client queue, while event replay/sequence numbers enable gap
recovery.

The session layer already defines credit ACKs. A later Binder/session implementation should make the
producer stop emitting when the receiver has no credit instead of relying on callback buffering.

### 3. Persistent reliability layer

Add a durable outbox/inbox for requests that must survive process death, including:

- request ID,
- idempotency key,
- attempt number,
- retry/backoff policy,
- terminal state,
- deduplication record.

This is especially important for destructive operations where a caller can lose the Binder connection
after execution but before receiving the result.

### 4. Transport adapters

Once the session protocol is stable, add adapters for:

- Android Binder,
- local socket,
- LAN,
- USB/ADB,
- desktop companion runtime.

The agent/tool model should not change when transport changes.

### 5. Benchmarks and regression budgets

Add instrumentation/macro benchmarks for:

- Binder RPC p50/p95/p99,
- serialization/deserialization,
- allocations per request,
- events/sec,
- reconnect/resume latency,
- large-payload throughput,
- memory behavior under sustained streams.

CI should eventually fail changes that exceed agreed regression budgets.

### 6. Workspace integration

Protocol support is not runtime support. Workspace must explicitly advertise feature flags only after
it implements:

- trust-aware capability graph,
- task DAG scheduling,
- preview/commit confirmation,
- event replay/credit flow,
- session protocol,
- large-payload transport,
- external-app adapter router.

Until then, corresponding gateway/manifest support flags remain false.
