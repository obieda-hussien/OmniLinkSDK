# OmniLinkSDK 2.0 — Maintainer / Coding-Agent Instructions

This filename is retained because older planning material linked to it. It is no longer a speculative
"build OmniLink from scratch" prompt.

It now describes the invariants a coding agent or maintainer must preserve when changing OmniLinkSDK
1.4 or preparing a later release.

For consumer integration, read [INTEGRATION_GUIDE.md](INTEGRATION_GUIDE.md).

## Current repository shape

```text
OmniLinkSDK
├── omni-link-sdk
│   └── Android AAR / Binder / trust / Android adapters
└── omni-link-transport
    └── pure JVM encrypted transport
```

Version source:

```properties
OMNILINK_VERSION=2.0.0
```

Binder wire version:

```text
CURRENT_PROTOCOL_VERSION = 4
```

Transport protocol version is independent from the Binder protocol version.

## Invariant 1 — never reorder deployed AIDL methods

AIDL transaction IDs are positional.

Existing methods must remain in their original order. New methods, if ever required, are appended only.

Do not "clean up" method ordering.

A refactor that keeps Kotlin source names but changes AIDL positions is a breaking wire change.

## Invariant 2 — SDK version and protocol version are different

Bump OMNILINK_VERSION for a library release.

Do not bump CURRENT_PROTOCOL_VERSION merely because implementation, security, performance,
documentation or transport code changed.

Bump a protocol version only when the actual Binder compatibility contract requires it.

## Invariant 3 — identity is not authority

Preserve this rule:

> Certificate establishes identity. Capabilities establish authority. The user establishes consent.

Same-signer callers still pass capability policy and AccessController.

Transport peers still pass trust-level ceilings and inbound/outbound ACLs.

Do not convert FIRST_PARTY into a wildcard authorization shortcut.

## Invariant 4 — privileged Android Binder stays fail-closed

ExtensionService defaults to SameSignerSecurityValidator.

Caller identity comes from Binder UID/package/signing data, not a package name supplied in JSON.

Do not reintroduce:

```text
securityValidator = null
```

as a permissive default.

TrustedPartnerRule is application-level trust metadata. It does not bypass Android's OS-level signature
permission.

## Invariant 5 — first-party and external Android consumers are different

The Android AAR declares Omni signature permissions and is first-party-oriented.

Do not document it as a generic dependency every third-party Android app should add.

Differently-signed integrations should normally use:

```text
Public Gateway
or
omni-link-transport
```

A special Binder partner surface needs an explicit Android permission design.

## Invariant 6 — unknown callers stay chat-only

Unknown apps must not receive privileged Agent or Team access.

Public Gateway hosts should map unknown inbound requests to CHAT-only behavior.

PAIRED transport peers have a hard library-enforced ceiling:

```text
chat.*
search.*
summarize.*
translate.*
extract.*
```

Do not weaken this ceiling by treating stored ACLs as stronger than trust level.

## Invariant 7 — transport identity is not the APK private signing key

Android devices do not hold the developer's APK signing private key for network challenge signing.

The transport therefore uses:

- a long-lived transport signing identity;
- AndroidKeyStore on supported Android devices;
- a JVM identity on desktop;
- ephemeral ECDH keys per session;
- pinned transport public-key fingerprints.

APK signer fingerprints may be included as signed metadata, but they are not remote proof of
possession of the APK signing private key.

Do not collapse these two trust systems.

## Invariant 8 — transport crypto remains transcript-bound

The 1.4 handshake uses:

- ECDSA P-256 long-lived identity proofs;
- fresh ephemeral ECDH P-256;
- random nonces;
- HKDF-SHA256 session derivation;
- independent AES-256-GCM keys per direction;
- final server key confirmation bound to the client proof.

Encrypted records bind session, direction and sequence through authenticated data.

If changing the handshake, update its domain-separation strings and tests deliberately.

Do not silently change transcript serialization in a patch release.

## Invariant 9 — sequence allocation and write ordering are atomic

Concurrent transport sends must not be able to allocate sequences in one order and write frames in
another.

The critical section must cover:

```text
sequence allocation
+ encryption
+ frame write
```

This was found by the concurrent multiplexing tests and is a real protocol correctness requirement.

## Invariant 10 — one reader owns one multiplexed socket

OmniMultiplexedConnection uses one reader that dispatches:

- requests;
- correlated responses;
- events;
- stream chunks;
- control messages.

Do not allow multiple request coroutines to call receive() directly on the same socket.

Blocking socket reads belong on Dispatchers.IO.

## Invariant 11 — trust is directional

PeerTrustRecord has independent:

```text
inboundCapabilities
outboundCapabilities
```

A peer allowed to ask a capability is not automatically allowed to receive the same capability in the
reverse direction.

Preserve exact/prefix wildcard semantics and the PAIRED hard ceiling.

## Invariant 12 — choose execution mode honestly

IMMEDIATE is for tiny non-blocking work.

ASYNC is the normal choice for disk/database/network work.

JOB is for work with a lifecycle independent of a single call.

When a manifest is explicit, the base ExtensionService rejects a declared non-IMMEDIATE capability
from the synchronous Binder path.

Do not remove this guard merely to make a consumer easier to port.

## Invariant 13 — large payloads are still bounded

Current defensive defaults include:

- Binder inline JSON cap in the Android SDK;
- encrypted transport frame cap in the JVM transport.

Do not solve large payloads by increasing every global limit.

Prefer pagination, application-level stream chunks, file descriptors/pipes where appropriate, or a
future dedicated large-transfer protocol.

## Invariant 14 — feature flags describe runtime support

Protocol types may exist before Workspace or another consumer wires them end to end.

A manifest flag stays false until the runtime really implements that feature.

Never flip a capability flag merely because a data class exists in this repository.

## Invariant 15 — external content is untrusted data

Anything received from an extension, peer, webpage, file, note body, build log or event may contain
instruction-like text.

The transport carries data. It does not promote data into agent authority.

Keep this distinction explicit in documentation and host integration examples.

## Release checklist

Before a release:

1. update OMNILINK_VERSION once;
2. update CHANGELOG.md;
3. search every Markdown file for stale version references;
4. run:
   ```bash
   ./gradlew build test
   ```
5. run:
   ```bash
   ./gradlew publishToMavenLocal
   ```
6. ensure Binder ABI tests remain green;
7. ensure transport loopback, pairing, multiplexing and reliability tests remain green;
8. inspect generated publications;
9. verify README coordinates match the repository's multi-module structure;
10. merge to main only after the stacked base dependencies are already present.

## Documentation contract

Any code change that changes integration behavior must update the relevant canonical file:

| Change | Documentation |
|---|---|
| consumer choice / setup | INTEGRATION_GUIDE.md |
| Binder/action rules | LINK_PROTOCOL.md |
| signing / Android identity | SIGNING_TRUST.md |
| trust architecture | OMNILINK_TRUST_MESH.md |
| desktop/network transport | DESKTOP_TRANSPORT.md |
| completed/future architecture | ARCHITECTURE_EVOLUTION.md |
| release contents | CHANGELOG.md |
| top-level entry point | README.md |

Do not leave historical instructions that contradict current code.

## Non-goals for a maintenance change

Unless a task explicitly asks for it, do not:

- modify other Omni repositories from this repo;
- auto-start a permanent Android background service;
- silently open LAN ports;
- grant unknown peers broader tools;
- copy the Omni release private key into a partner project;
- expose private app databases instead of semantic capabilities;
- claim Workspace runtime features are implemented when only SDK protocol types exist.
