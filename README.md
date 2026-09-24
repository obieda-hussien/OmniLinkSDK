# OmniLinkSDK 3.0

[![](https://jitpack.io/v/obieda-hussien/OmniLinkSDK.svg)](https://jitpack.io/#obieda-hussien/OmniLinkSDK)

OmniLinkSDK is the protocol, trust, IPC and encrypted device-link layer for the Omni ecosystem.

**Copyright © 2026 Abdelrahman Hussein (عبدالرحمن حسين). All rights reserved.**

This repository is **source-available, not open source**. You may study the architecture and independently
implement similar ideas, but copying, modifying, redistributing, republishing, sublicensing, selling,
or shipping derivative builds of this source is not permitted except for the limited GitHub-hosted
rights that apply to public repositories. See [LICENSE](LICENSE), [NOTICE.md](NOTICE.md), and
[CONTRIBUTING.md](CONTRIBUTING.md).

Version **3.0.0** is the source version prepared for release. Consumers should verify that the
`v3.0.0` tag, GitHub release and JitPack modules exist before updating their builds. The modules
retain distinct trust boundaries:

- `omni-link-public`: narrow Ask/Share/Open contracts for unknown or third-party Android apps;
- `omni-link-sdk`: trusted Android Binder/AIDL, provider verification and large-payload adapters;
- `omni-link-transport`: authenticated encrypted Android/desktop transport with explicit
  directional ACLs and resumable file transfer.

Importing source code or an artifact never grants trust. Privileged authority is decided by the
receiving host from Android signing identity, pinned transport identity and explicit capability ACLs.

The version source of truth is:

```properties
OMNILINK_VERSION=3.0.0
```

in `gradle.properties`.

Version 3.0 adds hardened transfer/session handling, host-controlled Android identity and Binder
capability discovery, exact-scope grants, a consent coordinator and an opt-in external-app executor.
See [CHANGELOG.md](CHANGELOG.md) and [AUTHORIZED_EXTERNAL_EXECUTION.md](AUTHORIZED_EXTERNAL_EXECUTION.md).
Workspace still needs to integrate these APIs and provide its real consent UI and adapters. The
installation examples below target `v3.0.0` after its publication is verified.

## Read this first

The canonical integration guide is:

**[INTEGRATION_GUIDE.md](INTEGRATION_GUIDE.md)**

For host-controlled Android package classification and installer-provenance limits, see
**[IDENTITY_POLICY.md](IDENTITY_POLICY.md)**.

It explains which API to use for:

- a first-party Omni Android app;
- a trusted partner signed with a different certificate;
- an unknown third-party app;
- a desktop/PC companion;
- an Omni app exposing capabilities to other Omni apps;
- an app delegating work to the Omni Agent Gateway;
- Omni controlling a non-integrated external app.

Do not start by copying the most privileged example. Choose the trust surface that matches the caller.

## Modules

### `omni-link-sdk`

Android AAR for first-party Omni applications.

Contains:

- Binder/AIDL extension protocol;
- privileged Agent Gateway protocol;
- same-signer security;
- Binder caller identity resolution;
- Android trust tiers;
- capability manifests;
- action execution and events;
- public gateway models;
- external-app bridge models;
- capability graph / task DAG / preview-commit protocol types;
- verified installed-app identities, scoped grants, consent and Binder capability discovery;
- opt-in authorized external-app execution with host-owned adapters;
- AndroidKeyStore transport identity adapter;
- Android peer-trust persistence;
- `omni-link-transport` as an API dependency.

### `omni-link-transport`

Pure JVM module for Android or desktop Java/Kotlin.

Contains:

- TCP client/server;
- ECDSA P-256 peer identities;
- ephemeral ECDH session agreement;
- HKDF-SHA256;
- AES-256-GCM encrypted frames;
- replay/out-of-order protection;
- peer pinning and pairing;
- directional capability ACLs;
- hard chat-only PAIRED trust ceiling;
- multiplexed request/response/event routing;
- heartbeat, reconnect, backoff and circuit breaker;
- encrypted JVM identity storage.

## Installation

Add JitPack:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

### First-party Android app

```kotlin
dependencies {
    implementation(
        "com.github.obieda-hussien.OmniLinkSDK:omni-link-sdk:v3.0.0"
    )
}
```

### Desktop/JVM or transport-only consumer

```kotlin
dependencies {
    implementation(
        "com.github.obieda-hussien.OmniLinkSDK:omni-link-transport:v3.0.0"
    )
}
```

### Ordinary third-party Android app

```kotlin
dependencies {
    implementation("com.github.obieda-hussien.OmniLinkSDK:omni-link-public:v3.0.0")
}
```

The public module exposes bounded entry points; it does not include the privileged Android SDK.

### All repository modules

```kotlin
dependencies {
    implementation("com.github.obieda-hussien:OmniLinkSDK:v3.0.0")
}
```

For security-sensitive integrations, prefer the exact module you need.

## Trust model

The core rule is:

> Certificate establishes identity. Capabilities establish authority. The user establishes consent.

On Android, first-party apps use a shared signing certificate and signature-level permissions.

Across devices, peers use separate transport identities and explicit public-key pinning.

These are related trust systems, not the same key.

Read:

- [SIGNING_TRUST.md](SIGNING_TRUST.md)
- [OMNILINK_TRUST_MESH.md](OMNILINK_TRUST_MESH.md)
- [DESKTOP_TRANSPORT.md](DESKTOP_TRANSPORT.md)

## Quick decision table

| Need | Use |
|---|---|
| Omni calls a capability owned by another official Omni Android app | `ExtensionService` / `IExtensionService` |
| Official Omni app asks Workspace to answer or perform a task | `IAgentGatewayService` protocol |
| Unknown app shares/asks/opens something in Omni | `PublicOmniRequest` public gateway |
| Omni controls a non-integrated app | External App Bridge descriptors/router contract |
| Android talks to PC over LAN/USB tunnel | `omni-link-transport` |
| One encrypted connection with concurrent requests/events | `OmniMultiplexedConnection` |
| Long-lived client with reconnect/heartbeat | `OmniReliableClient` |
| Unknown paired network peer | `PeerTrustProfiles.chatSandbox` |
| Official paired desktop companion | explicit `FIRST_PARTY` transport trust + narrow ACLs |

## First-party Android security

The SDK defines:

```text
com.omnilink.sdk.permission.BIND_EXTENSION
com.omnilink.sdk.permission.BIND_AGENT
```

Both are signature-level permissions.

`ExtensionService` is also fail-closed with:

```kotlin
SameSignerSecurityValidator()
```

The privileged Binder path is therefore intended for apps signed with the shared Omni key.

A differently-signed partner should not be given the Omni private key and should not casually consume
the first-party Android AAR. Use the public gateway, the pure transport module, or a deliberately
designed partner-specific Binder surface instead.

## Capability model

Capabilities are semantic contracts, for example:

```text
notes.search
notes.create
diagnostics.read
project.patch
gradle.build
git.diff
media.current_track
```

Each capability can declare:

- execution mode;
- trust requirement;
- direction;
- risk;
- idempotency;
- dry-run support;
- timeout;
- inline size limit;
- data scopes;
- Android permissions;
- input/output schemas.

Same signer never means unlimited authority.

## Unknown apps

Unknown Android apps must not receive privileged Agent or extension Binder access.

The intended inbound surface is the Public Gateway:

```text
SHARE_TO_OMNI
ASK_OMNI
OPEN_OMNI
```

Hosts should map unknown-app requests to a **chat-only** runtime profile.

A low-trust paired transport peer is hard-limited by the 1.4 transport policy to:

```text
chat.*
search.*
summarize.*
translate.*
extract.*
```

It cannot escape that ceiling through a wildcard ACL.

## Desktop transport

Default port:

```text
49371
```

Recommended layers:

```text
OmniTcpClient / OmniTcpServer
          ↓
SecureTransportSession
          ↓
OmniMultiplexedConnection
          ↓
OmniReliableClient   (optional long-lived client supervisor)
```

For full details, pairing rules, identity storage, ACL examples, ADB forwarding and lifecycle guidance,
read [DESKTOP_TRANSPORT.md](DESKTOP_TRANSPORT.md).

## Compatibility

Android Binder protocol remains:

```text
CURRENT_PROTOCOL_VERSION = 5
```

Version 2.0 does not reorder existing AIDL methods.

New protocol fields use conservative defaults and the canonical JSON codec ignores unknown additive
fields.

The desktop transport has its own transport protocol version and does not change the deployed Binder
transaction ordering.

## Verification

CI validates:

```text
./gradlew build test
./gradlew publishToMavenLocal
```

for the Android and JVM modules.

## Documentation index

- [INTEGRATION_GUIDE.md](INTEGRATION_GUIDE.md) — which integration to use, when and why.
- [LINK_PROTOCOL.md](LINK_PROTOCOL.md) — canonical Binder/action/capability protocol rules.
- [SIGNING_TRUST.md](SIGNING_TRUST.md) — first-party signing and trust.
- [OMNILINK_TRUST_MESH.md](OMNILINK_TRUST_MESH.md) — trust/capability architecture.
- [DESKTOP_TRANSPORT.md](DESKTOP_TRANSPORT.md) — encrypted Android/desktop transport.
- [ARCHITECTURE_EVOLUTION.md](ARCHITECTURE_EVOLUTION.md) — implemented vs future work.
- [00_INTEGRATION_ORDER.md](00_INTEGRATION_ORDER.md) — rollout order for a consumer ecosystem.
- [01_OmniLinkSDK_PROMPT.md](01_OmniLinkSDK_PROMPT.md) — maintainer/agent release invariants.
- [CHANGELOG.md](CHANGELOG.md) — version history.
- [SECURITY.md](SECURITY.md) — vulnerability reporting and security invariants.
- [REPOSITORY_HARDENING.md](REPOSITORY_HARDENING.md) — GitHub rulesets, Actions, secrets and access policy.
- [LICENSE](LICENSE) — proprietary source-available terms.
- [NOTICE.md](NOTICE.md) — copyright and ownership notice.
