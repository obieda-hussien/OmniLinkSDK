# OmniLinkSDK Architecture Evolution

This document separates implemented runtime behavior from protocol models and future work.

Do not advertise a feature as available merely because a model class exists.

## Implemented runtime foundations

### Android identity, consent and capability discovery in 3.0

- `HostVerifiedAppPairResolver` re-reads current installed APK signers for each operation;
- `CapabilityGrantLedger` persists exact grants and handles expiry and revocation;
- `CapabilityConsentCoordinator` provides exact prompt details for a host-owned user UI;
- `TrustedCapabilityDiscovery` binds verified components, bounds manifest reads and rechecks
  provider identity after receiving a reply;
- `AuthorizedExternalAppExecutor` combines host route policy with current identity, confirmation
  and an exact grant before invoking one host-owned adapter.

The SDK provides enforcement primitives; installed Workspace and AndroidIDE APKs must still consume
them, implement their own capabilities and UI, and be rebuilt and tested separately.

### Real desktop/JVM transport

The pure JVM `omni-link-transport` module now provides:

- TCP client and server;
- Android/desktop Java-Kotlin interoperability;
- long-lived ECDSA P-256 identities;
- ephemeral ECDH P-256 session agreement;
- random handshake nonces;
- HKDF-SHA256 key derivation;
- independent AES-256-GCM keys per direction;
- final server key confirmation;
- sequence-based replay/out-of-order rejection;
- frame-size enforcement;
- binary message encoding;
- peer pinning;
- pairing hooks/codes;
- directional capability ACLs;
- PAIRED chat-sandbox hard ceiling.

### Multiplexed transport runtime

`OmniMultiplexedConnection` provides:

- one socket reader;
- concurrent requests;
- correlation-id response dispatch;
- request flow;
- event flow;
- stream/control routing;
- encrypted transport ping/pong;
- clean close/error propagation.

### Reliability runtime

`OmniReliableClient` provides:

- persistent outgoing connection supervision;
- heartbeat and RTT measurement;
- reconnect;
- exponential backoff;
- jitter;
- circuit breaker;
- observable connection state.

### Android transport adapters

The Android SDK provides:

- `AndroidKeystoreSigningIdentity`;
- `AndroidPeerTrustStore`;
- network permissions required by optional transport use.

The SDK intentionally does not auto-start a permanent Android service.

### Packaging

The repository publishes three logical modules:

```text
omni-link-sdk
omni-link-transport
omni-link-public
```

CI verifies:

```text
./gradlew build test
./gradlew publishToMavenLocal
```

## Implemented in 1.3 and carried forward

### Android identity and trust

- shared first-party signing model;
- fail-closed same-signer validator;
- Binder UID/package/signing-certificate identity resolution;
- signing history support;
- CORE / FIRST_PARTY / TRUSTED_PARTNER / UNTRUSTED Android trust tiers;
- partner certificate/capability rules;
- capability communication direction;
- data-scope metadata.

### Extension runtime hardening

- single source of SDK/publication version;
- canonical forward-compatible JSON codec;
- parse-once execution/audit flow;
- inline request-size guard;
- request deadlines;
- undeclared-capability rejection for explicit manifests;
- synchronous-call rejection for declared non-IMMEDIATE work;
- bounded asynchronous concurrency;
- service coroutine cancellation;
- bounded event buffering.

### Capability and agent models

- richer capability contracts;
- capability-graph models;
- agent task DAG/delegation models;
- preview/commit confirmation models;
- external-app bridge descriptors;
- limited public Omni request contract;
- task history/replay protocol types.

`ExternalAppRoutePlanner` adds a deterministic planning step for already-discovered external-app
adapters. It matches the exact package and operation, prefers semantic/low-privilege routes, and
defaults to excluding Accessibility, Shizuku, shell and root. A host may explicitly allow additional
adapter kinds for a request. Authorization must come from host policy; descriptor claims from a
provider are not evidence. Permission denial and unknown authorization stop route selection. Only
an unavailable authorized route can fall through to another candidate. Consent and destructive
routes are returned with `requiresConfirmation` set.

The planner alone does not discover or probe adapters, verify host grants, request user consent, or
execute operations. The opt-in `AuthorizedExternalAppExecutor` in the 3.0 source
combines a host-verified identity resolver, the grant ledger, a host confirmation callback and one
host-owned adapter. It does not implement the consent UI or the adapter's own Android permission
checks. A selected route alone is never authorization to perform it.

### Transport-independent session models

`OmniSessionHello` and `OmniFrame` model:

- streams;
- request/result/event frames;
- ACK credits;
- cancellation;
- health;
- control/data/event lanes;
- codec negotiation;
- payload-transport negotiation.

These are protocol foundations. They are not automatically the same runtime as the 1.4 TCP transport.

## Current architectural split

```text
                 Omni capability / task semantics
                            |
             +--------------+--------------+
             |                             |
      Android Binder IPC             JVM TCP transport
      same-device trust              device/desktop trust
             |                             |
      Android signer/UID             transport key pinning
             |                             |
      AccessController               peer ACL / trust ceiling
             +--------------+--------------+
                            |
                      host business logic
```

The two sides can eventually share more orchestration semantics, but they intentionally use different
identity mechanisms.

## Still future / incomplete

### 1. Large-payload Binder transport

The Android protocol models:

- FILE_DESCRIPTOR;
- PIPE;
- SHARED_MEMORY;
- CONTENT_URI.

The deployed AIDL path still uses inline JSON for ordinary actions.

Future work should append compatible Binder methods only after both caller and callee implement the
large-payload path end to end.

### 2. Resumable TCP file transfers

`OmniFileTransferSender` and `OmniFileTransferReceiver` provide bounded chunk transfers, resume
offsets, per-chunk hashes and final SHA-256 validation under explicit `_transfer.*` ACLs. Hosts
still provide lifecycle, storage management and policy for individual transfers.

Future work may add:

- concurrent chunk windows and negotiated backpressure credits;
- durable transfer garbage collection across process death;
- end-to-end retry policy for interrupted destructive operations.

### 3. Durable outbox/inbox

Reconnect/backoff exists in 1.4.

What does not yet exist is a durable request journal that survives process death and safely deduplicates
destructive work.

Future persistent reliability should include:

- request id;
- idempotency key;
- attempt state;
- terminal state;
- deduplication record;
- replay/recovery policy.

### 4. Session resumption

Every TCP connection currently performs a new authenticated handshake.

Future resumption may use short-lived resumable tickets without weakening peer pinning or forward
secrecy expectations.

### 5. Discovery

No automatic LAN discovery is enabled in 1.4.

Possible future discovery adapters:

- Android NSD/mDNS;
- desktop mDNS;
- QR/pairing bootstrap;
- USB/ADB helper discovery.

Discovery must never equal trust.

### 6. Alternative transports

Possible future adapters:

- QUIC;
- local domain sockets where available;
- WebSocket/TLS bridge when justified;
- direct USB protocol.

They should preserve the same trust/capability semantics.

### 7. Hardware-backed attestation

AndroidKeyStore identity is implemented.

Remote verification of hardware-backed key attestation is not yet implemented.

If added later, attestation should strengthen device provenance, not replace user pairing or capability
policy.

### 8. Strict cross-process flow control

Binder event buffering is bounded and sequence-aware.

Session models include credits.

A fully wired producer-side credit system is still future work.

### 9. Performance benchmark budgets

Add repeatable benchmarks for:

- Binder RPC p50/p95/p99;
- transport handshake;
- encrypted message latency;
- throughput;
- serialization;
- allocations;
- reconnect/resume;
- event rates;
- sustained memory;
- large-stream behavior.

CI can eventually enforce agreed regression budgets.

### 10. Consumer runtime integration

OmniLinkSDK intentionally does not modify Workspace, AndroidIDE, Launcher, Note or other consumer
repositories.

Consumers still need to implement their own:

- capabilities;
- Agent Gateway host/client wiring;
- capability graph ingestion;
- task DAG scheduling;
- preview/commit UI;
- external-app adapters;
- lifecycle ownership for TCP client/server;
- public gateway host behavior.

## Integration priority

When consumers start adopting 1.4, recommended order is:

1. signing/trust identity;
2. semantic capability manifests;
3. Binder extension integration;
4. safe Public Gateway behavior;
5. confirmation/audit policy;
6. desktop pairing at low privilege;
7. multiplexed/reliable transport;
8. capability graph/task DAG runtime;
9. large payloads;
10. benchmark-driven tuning.

See [INTEGRATION_GUIDE.md](INTEGRATION_GUIDE.md) for concrete integration examples.
