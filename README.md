# OmniLinkSDK

[![](https://jitpack.io/v/obieda-hussien/OmniLinkSDK.svg)](https://jitpack.io/#obieda-hussien/OmniLinkSDK)

OmniLinkSDK is the shared protocol, trust and IPC substrate for the Omni ecosystem. It lets first-party
apps expose capabilities to Omni, lets trusted first-party clients embed the Workspace agent, and keeps
foreign-app interoperability isolated from privileged Binder surfaces.

The source version is defined once in `gradle.properties` as `OMNILINK_VERSION`. The current source
line is **1.3.0**; merging a new version to `main` automatically creates the matching `vX.Y.Z` tag and
GitHub release.

## 1.4 desktop transport

OmniLink now includes a pure JVM `omni-link-transport` module that can run on Android and desktop
Java/Kotlin. It implements signed mutual authentication, ephemeral ECDH session agreement,
AES-256-GCM encrypted messaging, replay protection, peer pinning and directional capability ACLs.

Android uses an optional AndroidKeyStore-backed transport identity; desktop JVM identities can be
stored in an encrypted file. Unknown peers are rejected by default and explicit pairing can place a
peer into a chat-only sandbox.

See [DESKTOP_TRANSPORT.md](DESKTOP_TRANSPORT.md) for the complete protocol/security model.

## 1.3 trust architecture

OmniLink 1.3 adds:

- fail-closed same-signer authentication by default,
- Binder UID/package/certificate identity resolution,
- CORE / FIRST_PARTY / TRUSTED_PARTNER / UNTRUSTED trust tiers,
- per-capability trust, direction, risk, data scope, idempotency and schema metadata,
- bounded async concurrency, deadline/size checks and service-scope cancellation,
- canonical forward-compatible JSON handling,
- bounded event buffering plus sequence/replay primitives,
- transport-independent multiplexed session frames,
- payload transport negotiation models,
- capability graph + agent task DAG models,
- preview -> commit models for destructive operations,
- one-way foreign-app bridge descriptors,
- a separate narrow public share/ask/open request contract.

See [OMNILINK_TRUST_MESH.md](OMNILINK_TRUST_MESH.md) for the architecture and staged integration plan.
See [SIGNING_TRUST.md](SIGNING_TRUST.md) for the shared Omni signing-key model.

## Consumption

After the corresponding release tag exists:

```kotlin
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
    }
}

dependencies {
    implementation("com.github.obieda-hussien:OmniLinkSDK:v1.4.0")
}
```

JitPack remains the low-friction distribution path. The publication metadata and runtime SDK version
both derive from `OMNILINK_VERSION`; they can no longer silently drift.

## Privileged extension surface

Extensions expose app-owned capabilities through `IExtensionService` / `ExtensionService`.

The default security validator is now:

```kotlin
SameSignerSecurityValidator()
```

so privileged calls fail closed unless the Binder caller shares the host's Android signing identity.
Authentication is still followed by trust/capability policy and the extension's `AccessController`.

Heavy work belongs on `executeActionAsync` or a JOB capability. A declared non-`IMMEDIATE`
capability is rejected from the synchronous path.

## Embedded Omni Agent Gateway

Trusted first-party applications can delegate work to Workspace through `IAgentGatewayService`.
Workspace remains the owner of model/runtime state, MCP, web/deep search, local tools, memory, project
context, history and Agent Console events.

Clients send typed `AgentTaskRequest` values and receive ordered `AgentTaskEvent` events. History
and replay APIs are append-only and preserve deployed Binder transaction IDs.

The gateway stays protected by the signature-level
`com.omnilink.sdk.permission.BIND_AGENT` permission. Request payload package names are never trusted;
the host resolves identity from Binder.

## Foreign applications

A foreign app is not allowed into `BIND_AGENT` / `BIND_EXTENSION`.

Omni may interact outward through adapters such as public APIs, Intents, MediaSession, notification
actions, Accessibility, Shizuku/shell or root, choosing the lowest-privilege semantic adapter first.

If Workspace wants useful inbound third-party interoperability, it can expose the separate
`ACTION_PUBLIC_OMNI_REQUEST` Intent for narrowly scoped share/ask/open requests. That public path does
not weaken the privileged agent gateway.

## R8

The AAR ships consumer rules preserving serializable protocol types and AIDL stubs while allowing
unused implementation code to be removed from consuming APKs.

## Compatibility

Existing AIDL methods remain append-only and keep their original transaction IDs. SDK 1.3 intentionally
keeps `CURRENT_PROTOCOL_VERSION = 4`; new protocol models are additive/opt-in and runtime feature flags
stay false until a consumer actually implements them.
