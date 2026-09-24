# Changelog

All notable OmniLinkSDK changes are documented here.

The repository version is sourced from `OMNILINK_VERSION` in `gradle.properties`.

## 2.0.1

### Android Binder crash fix

- Explicitly read the enclosing `ExtensionService` capability manifest in the Binder getter, preventing
  recursive resolution of the Stub's synthetic property and a `StackOverflowError` during discovery.
- Cover the actual Binder getter, manifest metadata, and repeated calls with regression tests.
- Keep the AIDL method order, negotiated protocol, transport wire format, and permissions unchanged.
- Consumers of the immutable `v2.0.0` artifact must update to `v2.0.1` and rebuild their APKs.

## 2.0.0

### Security architecture

- Added the dedicated `omni-link-public` artifact for bounded third-party Ask/Share/Open integration.
- Added host-side trusted provider verification before privileged Android service binding.
- Removed wildcard defaults from FIRST_PARTY network trust profiles.
- Android FIRST_PARTY promotion now requires host-owned expected signer fingerprints.
- Trusted desktop promotion now requires a pinned long-lived transport public key and explicit
  directional ACLs.
- Bumped encrypted transport protocol to v2 and the Android negotiated protocol/session contract to
  5/2 while preserving deployed AIDL method ordering.

### Large payload data plane

- Added incremental Android Content URI/file-descriptor payload support.
- Added resumable encrypted file transfer over ACL-controlled `_transfer.*` capabilities.
- Added durable partial files, resume offsets, bounded chunks, per-chunk hashes and final SHA-256
  verification.
- Large payloads no longer need to fit in one Binder transaction or one in-memory transport frame.

### Compatibility

- 2.0 is a major trust/distribution boundary release.
- Existing AIDL transaction ordering remains intact and version negotiation remains explicit.
- Third-party Android integrations should migrate to `omni-link-public`.

## 1.4.2

### Build and supply-chain maintenance

- Upgraded the Gradle wrapper from 8.13 to 8.14.5.
- Regenerated the Gradle wrapper scripts/JAR from the Gradle 8.14.5 wrapper.
- Updated the pinned Gradle distribution SHA-256 to the official 8.14.5 checksum.
- Kept Android Gradle Plugin 8.13.2, Kotlin 2.4.10, JDK 17, public APIs, and Binder protocol version 4 unchanged.
- Retained all existing CI, security-sanity, Dependency Review, CodeQL, wrapper-validation, and Maven publication gates.

### Compatibility

- This is a build-tool patch release with no intended source/API/AIDL behavior change.
- Existing 1.4.x integrations can update coordinates to v1.4.2 without changing protocol code.

## 1.4.1

### Build toolchain

- Upgraded Android Gradle Plugin from 8.5.1 to 8.13.2.
- Upgraded the Gradle distribution from 8.8 to 8.13 with the official SHA-256 checksum.
- Upgraded Kotlin Android/JVM/serialization plugins from 2.0.0 to 2.4.10.
- Upgraded kotlinx.serialization JSON from 1.7.0 to 1.11.0.
- Upgraded kotlinx.coroutines core/Android/test from 1.8.1 to 1.11.0.
- Upgraded Robolectric from 4.11.1 to 4.17.
- Kept JDK 17 and the deployed Binder protocol version unchanged.

### CI and supply chain

- Upgraded actions/checkout to 7.0.1.
- Upgraded actions/setup-java to 6.0.1.
- Upgraded actions/dependency-review-action to 5.0.0.
- Upgraded gradle/actions wrapper validation to 6.3.0.
- Continued pinning all external GitHub Actions to immutable commit SHAs.
- Added Dependabot guardrails so AGP 9 and Gradle 9 are not upgraded independently.
- Changed release automation so an already-published version is a clean no-op while an orphan tag remains fail-closed.

### Compatibility

- This is a build/dependency patch release; OmniLink public APIs and AIDL transaction ordering are unchanged.
- Android Binder protocol remains version 4.
- The toolchain intentionally stays on AGP 8.x to avoid an implicit AGP 9 built-in-Kotlin migration.
- Kotlin is temporarily pinned to 2.4.10 because the currently published CodeQL 2.27.0 bundle rejects 2.4.20 during extraction; normal builds on 2.4.20 were otherwise successful.

## 1.4.0

### Cross-platform transport

- Added the pure JVM `omni-link-transport` module.
- Added TCP client/server support usable by Android and desktop Java/Kotlin.
- Added long-lived ECDSA P-256 peer identities.
- Added AndroidKeyStore-backed transport identity for Android API 23+.
- Added encrypted desktop identity persistence.
- Added ephemeral ECDH P-256 handshake keys.
- Added HKDF-SHA256 session-key derivation.
- Added independent AES-256-GCM keys per traffic direction.
- Added signed mutual handshake and final server key confirmation.
- Added strict monotonic frame sequences and replay/out-of-order rejection.
- Added frame-size limits and compact binary message encoding.
- Added LAN, localhost and ADB-tunnel compatibility.

### Trust and pairing

- Added persistent `PeerTrustRecord` policy.
- Added `UNTRUSTED`, `PAIRED`, `TRUSTED_PARTNER`, `FIRST_PARTY`, and `CORE` transport trust levels.
- Added separate inbound/outbound capability ACLs.
- Added optional expected Android signer fingerprints as signed peer metadata.
- Unknown peers are rejected by default.
- Added explicit pairing hooks and pairing codes.
- Newly paired peers are persisted only after cryptographic proof succeeds.
- Added `PeerTrustProfiles.chatSandbox`.
- Added a hard PAIRED trust ceiling for `chat.*`, `search.*`, `summarize.*`, `translate.*`, and `extract.*`.
- PAIRED peers cannot escape the sandbox even if a caller accidentally stores a wildcard ACL.

### Multiplexing and reliability

- Added `OmniMultiplexedConnection`.
- Added concurrent request/response correlation over one encrypted socket.
- Added request and event flows.
- Added encrypted transport ping/pong.
- Added connection-close signaling and pending-request failure propagation.
- Added `OmniReliableClient`.
- Added heartbeat RTT measurement.
- Added automatic reconnect.
- Added exponential backoff and jitter.
- Added circuit-breaker behavior.

### Android integration

- Added `AndroidPeerTrustStore`.
- Added network permissions required by the optional transport.
- Kept transport startup under consumer lifecycle control; the SDK does not auto-start a permanent service.

### Packaging and verification

- Repository is now multi-module.
- CI builds Android and JVM artifacts together.
- CI runs JVM/Android tests.
- CI verifies `publishToMavenLocal` on pull requests.
- Concurrency tests found and drove fixes for socket-reader dispatcher blocking and send-sequence wire-order inversion.

### Repository security and ownership

- Added proprietary Omni Reference Source License 1.0.
- Added explicit copyright/ownership notice for Abdelrahman Hussein (عبدالرحمن حسين).
- Added SECURITY.md and closed contribution policy.
- Added CODEOWNERS assigning repository ownership to @obieda-hussien.
- Added Dependabot monitoring for Gradle and GitHub Actions.
- Added CodeQL and dependency-review security workflows.
- Pinned GitHub Actions to immutable commit SHAs.
- Reduced ordinary CI workflow token permissions to read-only.
- Hardened release automation to rebuild/test/publish-verify before tagging.
- Added repository-hardening guidance for main/tag rulesets, secret scanning and access review.

### Documentation

- Added `INTEGRATION_GUIDE.md`.
- Added `DESKTOP_TRANSPORT.md`.
- Updated trust, signing, protocol, architecture and maintainer documentation for 1.4.

## 1.3.0

### Trust mesh and runtime hardening

- Made SDK/publication version derive from one source.
- Added fail-closed same-signer validation by default.
- Added robust Binder UID/package/signing-certificate resolution.
- Added `CORE`, `FIRST_PARTY`, `TRUSTED_PARTNER`, and `UNTRUSTED` Android trust tiers.
- Added capability direction, risk, idempotency, dry-run, data-scope, timeout, permission and schema metadata.
- Added request IDs, correlation IDs, idempotency keys, deadlines and priorities.
- Added inline request size checks.
- Added explicit unknown-capability rejection.
- Added synchronous-call rejection for declared non-IMMEDIATE capabilities.
- Added bounded asynchronous concurrency.
- Added lifecycle cancellation.
- Added bounded event buffering and event sequencing.
- Added capability-graph and agent-task-DAG protocol types.
- Added preview/commit protocol primitives.
- Added external-app bridge descriptors.
- Added narrow public Omni request protocol.
- Added transport-independent session protocol models.

## 1.2.0

### Agent history and replay

- Added canonical persistent Agent Gateway conversation/history types.
- Added conversation listing and reading models.
- Added task-event replay/pagination support.
- Preserved append-only Binder ABI ordering.

## 1.1.0

### Agent Gateway

- Added `IAgentGatewayService`.
- Added `IOmniAgentCallback`.
- Added typed `AgentTaskRequest`, snapshots and streamed Agent task events.
- Added support for CHAT / AGENT / TEAM protocol modes.
- Added signature-level Agent Gateway permission.

## 1.0.0

### Initial SDK

- Added the Android library and AIDL extension protocol.
- Added typed action requests/outcomes.
- Added capability manifests and version negotiation.
- Added synchronous and asynchronous action execution.
- Added optional event callbacks and Kotlin Flow wrapper.
- Added access-controller and audit abstractions.
- Added signature validation primitives.
- Added JitPack/Maven publication support.
