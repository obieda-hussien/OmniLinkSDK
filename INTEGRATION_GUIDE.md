# OmniLinkSDK 3.0 Integration Guide

This is the canonical consumer guide for OmniLinkSDK 3.0. Read this before adding OmniLink to any
Android app, trusted partner, third-party app, desktop companion, test tool, or external-app bridge.

OmniLink has several integration surfaces. They are intentionally different. Do not choose a surface
only because it is the most powerful one.

> Identity proves who a peer is. Trust sets its maximum authority. Capabilities define what it may do.
> User consent still gates sensitive side effects.

## 1. Start here: choose the integration type

| Your app/device | Recommended surface | Default trust | What it is for |
|---|---|---:|---|
| First-party Omni Android app signed with the shared Omni key | `omni-link-sdk` + Binder `ExtensionService` | `FIRST_PARTY` | Expose app-owned capabilities to Omni or other first-party Omni apps on the same device |
| First-party Omni app that wants to ask/delegate work to Workspace | `IAgentGatewayService` client protocol | `FIRST_PARTY` | CHAT / AGENT / TEAM delegation to the Workspace runtime |
| First-party Omni Android app talking to a PC | `omni-link-sdk` + `omni-link-transport` | explicitly paired, normally `FIRST_PARTY` | Encrypted LAN / localhost / ADB-tunnel communication |
| Trusted partner signed with its own certificate | Public Gateway or `omni-link-transport` with a narrow partner ACL | `TRUSTED_PARTNER` only after explicit approval | Selected capabilities without sharing the Omni signing key |
| Unknown or ordinary third-party Android app | Public Gateway; optionally a paired transport chat sandbox | untrusted / `PAIRED` | Ask, share, summarize, search, translate, extract; never privileged Agent/Team access |
| Desktop Java/Kotlin companion | `omni-link-transport` | paired first, then explicitly promoted if appropriate | Bidirectional encrypted requests, responses, events, reconnect and health |
| Omni controlling an unrelated installed Android app | External App Bridge model | target remains external | Pick the lowest-privilege adapter: API, Intent, media, notification, Accessibility, Shizuku/shell, root |

### The short rule

Use **Binder** for first-party same-device Android IPC.

Use **Public Gateway** for unknown apps that only need a safe user-facing Omni entry point.

Use **the encrypted transport** for PC/device links, partner links, or any case where Android's
same-signature Binder identity is not the right trust boundary.

Use **External App Bridge** when Omni is controlling an app that did not integrate OmniLink.

Do not expose the privileged Agent Gateway to an unknown app just because that app wants more features.

---

## 2. Version 3.0 artifacts

The source version is defined only by:

```properties
OMNILINK_VERSION=3.0.0
```

in `gradle.properties`.

The examples below use `v3.0.0`. Verify the release tag and JitPack modules before changing
consuming apps; the previous verified version was `v2.0.1`. The opt-in execution boundary is described in
[AUTHORIZED_EXTERNAL_EXECUTION.md](AUTHORIZED_EXTERNAL_EXECUTION.md).

JitPack multi-module projects publish individual modules under
`com.github.USER.REPO:MODULE:VERSION`.

### Android first-party SDK

Use this when the application is part of the same-signature Omni suite:

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        maven { url = uri("https://jitpack.io") }
    }
}

dependencies {
    implementation(
        "com.github.obieda-hussien.OmniLinkSDK:omni-link-sdk:v3.0.0"
    )
}
```

### Pure JVM / desktop transport only

Use this for Java/Kotlin desktop software, command-line tools, or a differently-signed integration that
must not merge the privileged Android SDK manifest:

```kotlin
dependencies {
    implementation(
        "com.github.obieda-hussien.OmniLinkSDK:omni-link-transport:v3.0.0"
    )
}
```

### Aggregate repository dependency

JitPack can also expose the repository aggregate:

```kotlin
implementation("com.github.obieda-hussien:OmniLinkSDK:v3.0.0")
```

For security-sensitive integrations, prefer the exact module you actually need. A third-party Android
app should not pull the first-party Android AAR merely to reuse a constant.

---

# Part A — First-party Omni Android applications

## 3. What qualifies as first-party?

A first-party Omni Android app is an app you control and intentionally sign with the shared Omni
release certificate.

Examples may include Workspace, AndroidIDE integration, Launcher, Note, Memoria, Equalizer,
PriceWatch, and future official Omni apps.

Release builds use the same release signer. Development builds should use the same Omni debug signer.

See [SIGNING_TRUST.md](SIGNING_TRUST.md).

Android signature permissions are the first gate. OmniLink then performs application-level signer,
trust, capability and access checks.

Same signer does **not** mean "all data is shared." Every app keeps its own sandbox and database.

## 4. When should a first-party app expose an ExtensionService?

Use `ExtensionService` when the app owns useful logic or data that Omni should call directly without
opening the app's Activity.

Good capabilities:

```text
notes.search
notes.read
notes.create
memory.search
diagnostics.read
project.patch
gradle.build
git.diff
media.current_track
pricewatch.refresh
```

Bad capability design:

```text
doAnything
runWhateverTheModelSays
readWholeDatabase
executeRawSql
```

Expose semantic operations, not unrestricted internal primitives.

### Example service

```kotlin
class NotesOmniService : ExtensionService() {

    override val minSupportedVersion = OmniLinkConstants.CURRENT_PROTOCOL_VERSION
    override val maxSupportedVersion = OmniLinkConstants.CURRENT_PROTOCOL_VERSION

    override val accessController = object : AccessController {
        override fun decide(
            caller: CallerContext,
            request: ActionRequest
        ): AccessDecision {
            return AccessDecision.ALLOW
        }
    }

    override val auditLogger = object : AuditLogger {
        override fun log(
            caller: CallerContext,
            request: ActionRequest,
            result: ActionOutcome
        ) {
            // Persist or forward an audit record owned by this app.
        }
    }

    override val capabilities = listOf(
        CapabilityDescriptor(
            name = "notes.search",
            description = "Search note titles and bodies",
            executionMode = CapabilityExecutionMode.ASYNC,
            requiredTrustTier = TrustTier.FIRST_PARTY,
            communicationDirection = CommunicationDirection.BIDIRECTIONAL,
            risk = CapabilityRisk.LOW,
            idempotency = IdempotencySemantics.IDEMPOTENT,
            dataScopes = setOf(DataScope.OMNI_AGENT)
        ),
        CapabilityDescriptor(
            name = "notes.delete",
            description = "Delete a note",
            destructive = true,
            requiresConfirmation = true,
            executionMode = CapabilityExecutionMode.ASYNC,
            requiredTrustTier = TrustTier.FIRST_PARTY,
            risk = CapabilityRisk.HIGH,
            idempotency = IdempotencySemantics.IDEMPOTENT_WITH_KEY,
            supportsDryRun = true,
            dataScopes = setOf(DataScope.USER_CONFIRMATION_REQUIRED)
        )
    )

    override suspend fun onAction(
        caller: CallerContext,
        request: ActionRequest
    ): ActionOutcome {
        return when (request.name) {
            "notes.search" -> searchNotes(request)
            "notes.delete" -> deleteNote(request)
            else -> ActionOutcome.Failure(
                ActionError("unsupported", "Unknown action")
            )
        }
    }
}
```

The default `securityValidator` is already fail-closed with `SameSignerSecurityValidator()`.

Only override it when you have a deliberate alternative security design.

## 5. Android manifest for a first-party extension provider

The service that exposes privileged capabilities should be protected:

```xml
<service
    android:name=".NotesOmniService"
    android:exported="true"
    android:permission="com.omnilink.sdk.permission.BIND_EXTENSION">

    <intent-filter>
        <action android:name="com.omnilink.sdk.action.EXTENSION_BIND" />
    </intent-filter>
</service>
```

A first-party app that calls such extensions should request the signature permission:

```xml
<uses-permission android:name="com.omnilink.sdk.permission.BIND_EXTENSION" />
```

If the app calls the privileged Agent Gateway, request:

```xml
<uses-permission android:name="com.omnilink.sdk.permission.BIND_AGENT" />
```

The library declares the permission definitions. Android only grants the signature permissions to
matching signing identities.

## 6. IMMEDIATE vs ASYNC vs JOB

Choose execution mode from behavior, not convenience.

### IMMEDIATE

Use only for tiny, non-blocking operations that are safe to finish while the synchronous Binder call
is waiting.

Examples:

```text
settings.get_mode
media.get_playback_state
feature.is_enabled
```

Do not perform network, disk, database scans, Gradle, Git, indexing, or large serialization here.

### ASYNC

Use for ordinary I/O or medium work:

```text
notes.search
git.diff
diagnostics.query
pricewatch.refresh
```

Call through `executeActionAsync`.

### JOB

Use when the work has an independent lifecycle and may outlive the original call:

```text
gradle.build
test.run_suite
project.index
large_export
repository.clone
```

Return a job/task identifier quickly. Progress belongs in events or a cursor/poll/replay surface.

## 7. Capability metadata: what each field means

`CapabilityDescriptor` is a contract for humans, agents and policy code.

- `requiredTrustTier`: minimum Android trust tier.
- `communicationDirection`: whether Omni may call the app, the app may call Omni, or both.
- `risk`: LOW / MEDIUM / HIGH / CRITICAL.
- `idempotency`: tells retry logic whether repeating the operation can duplicate a side effect.
- `supportsDryRun`: use when the app can calculate effects without committing them.
- `timeoutMillis`: semantic expectation for the capability.
- `maxInlinePayloadBytes`: defensive inline size contract.
- `dataScopes`: how data may leave the owning app.
- `requiredPermissions`: Android permissions the implementation relies on.
- `inputSchema` / `outputSchema`: machine-readable schemas for tool discovery.

Do not label a destructive write as LOW risk merely to avoid a confirmation UI.

## 8. DataScope: keep ownership local

Use the narrowest truthful scope.

`PUBLIC`
: Data is intentionally safe for a public surface.

`OMNI_ECOSYSTEM`
: Data may move between official first-party Omni apps.

`OMNI_AGENT`
: The agent may reason over it under normal Omni policy.

`USER_CONFIRMATION_REQUIRED`
: Do not release or modify it without a user-approved flow.

`APP_ONLY`
: Never put this data on OmniLink.

Examples:

```text
note.title                 -> OMNI_AGENT
note.body                  -> OMNI_AGENT or USER_CONFIRMATION_REQUIRED
private encryption key     -> APP_ONLY
raw authentication token   -> APP_ONLY
build diagnostic text      -> OMNI_AGENT
```

## 9. Agent Gateway: when an app wants Omni to do the work

`IExtensionService` means:

```text
Omni -> app capability
```

`IAgentGatewayService` means:

```text
trusted app -> Workspace agent runtime
```

Use the Agent Gateway when a first-party client wants Workspace to answer or perform a delegated task
using Workspace-owned agent features.

Possible modes in the protocol are:

```text
CHAT
AGENT
TEAM
```

Only a privileged, same-signer integration should be eligible for AGENT or TEAM.

The SDK defines the protocol. It does not automatically start a Workspace implementation.

Do not advertise `supportsTaskGraphs`, `supportsCapabilityGraph`, `supportsTrustMesh`, or
`supportsSessionProtocol` as true until the actual Workspace runtime implements the feature.

## 10. Confirmation and destructive work

`requiresConfirmation = true` is a client-side hint.

A server-side `ActionOutcome.RequiresConfirmation` is a stop condition, not permission to retry
silently.

OmniLink 2.0 includes the preview/commit models for stronger destructive flows:

```text
prepare
  -> exact preview/diff/affected resources
  -> user confirmation
  -> short-lived opaque token
  -> commit exact prepared operation
```

The confirmation token must never become a reusable "yes to everything" credential.

---

# Part B — Trusted partners signed with another key

## 11. Do not share the Omni signing private key

A partner is not made trusted by giving it the first-party key.

Keep the partner's signing key independent.

This preserves revocation and prevents one partner compromise from becoming a compromise of every Omni
APK.

## 12. Important: the full Android AAR is first-party-oriented

`omni-link-sdk` declares Omni signature permissions in its Android manifest.

A differently-signed Android app should **not** casually consume that AAR. Android custom-permission
ownership/signature rules make that an inappropriate default for independent third parties.

A trusted partner should normally choose one of these paths.

### Partner path A — Public Gateway

Use this if the partner only needs to hand text/URLs/content to Omni and receive a safe chat-style
experience.

This is the simplest path and does not require privileged Binder.

### Partner path B — encrypted transport

Use `omni-link-transport` when the partner needs bidirectional structured requests.

The Omni-side user/admin explicitly pairs the peer, verifies the pairing code/fingerprint, then stores
a `PeerTrustRecord` with:

```kotlin
PeerTrustRecord(
    peerId = candidate.peerId,
    publicKeySha256 = candidate.publicKeySha256,
    trustLevel = TransportTrustLevel.TRUSTED_PARTNER,
    inboundCapabilities = setOf(
        "media.read",
        "calendar.free_busy"
    ),
    outboundCapabilities = setOf(
        "partner.status"
    )
)
```

Do not use `*` for a partner unless the peer is actually meant to receive every current and future
capability.

### Partner path C — custom Android Binder surface

If you intentionally need differently-signed partner Binder access, design a separate partner surface
with an explicit Android permission/signing policy, for example a host-side `knownSigner` strategy on
supported Android versions or a separate permission.

That is **not** the default OmniLink 2.0 privileged Binder path.

`TrustedPartnerRule` can participate in application-level trust resolution, but it does not magically
bypass Android's OS-level signature permission gate.

---

# Part C — Unknown / ordinary third-party applications

## 13. Default third-party contract: Public Gateway

Unknown apps should not bind to:

```text
BIND_EXTENSION
BIND_AGENT
```

Use the separate public surface represented by `PublicOmniRequest`.

Available request kinds:

```text
SHARE_TO_OMNI
ASK_OMNI
OPEN_OMNI
```

The SDK's public request size limit is 32 KiB.

The host that implements the public gateway should treat all supplied text, URLs, MIME metadata and
extras as untrusted data.

### Recommended runtime policy

An unknown app asking Omni a question should be mapped to:

```text
CHAT ONLY
```

not AGENT and not TEAM.

A sensible safe tool profile is:

```text
chat.*
search.*
summarize.*
translate.*
extract.*
```

The public caller should not receive private memory, project files, terminal/shell, Shizuku, root,
device control, privileged cross-app delegation or autonomous background execution.

The Public Gateway models the request contract. The consuming host is responsible for enforcing this
chat-only runtime policy.

## 14. If an unknown peer is explicitly paired over transport

Use:

```kotlin
PeerTrustProfiles.chatSandbox(candidate)
```

This produces `TransportTrustLevel.PAIRED`.

In 1.4 the PAIRED ceiling is enforced inside the transport policy itself. Even if an ACL accidentally
contains `*`, a PAIRED peer is still restricted to:

```text
chat.*
search.*
summarize.*
translate.*
extract.*
```

plus reserved authenticated transport health controls.

This is stronger than a documentation convention.

To grant anything more, replace the trust record intentionally with a higher trust level and explicit
ACLs.

---

## 14.1 Public Gateway from a third-party app without the Android AAR

An ordinary Android app does not need the first-party Omni Android SDK just to send a public request.

The public action and extra are stable contract strings:

```text
Action: com.omnilink.sdk.action.PUBLIC_OMNI_REQUEST
Extra:  com.omnilink.sdk.extra.PUBLIC_REQUEST_JSON
```

Example ASK request JSON:

```json
{
  "kind": "ASK_OMNI",
  "text": "Summarize this page for me",
  "url": "https://example.com/article",
  "clientRequestId": "client-123"
}
```

Example Android caller:

```kotlin
val requestJson = """
{
  "kind": "ASK_OMNI",
  "text": "Summarize this text",
  "clientRequestId": "client-123"
}
""".trimIndent()

val intent = Intent(
    "com.omnilink.sdk.action.PUBLIC_OMNI_REQUEST"
).apply {
    // Prefer an explicit, user-selected/known Omni host package.
    setPackage(omniHostPackage)
    putExtra(
        "com.omnilink.sdk.extra.PUBLIC_REQUEST_JSON",
        requestJson
    )
}

startActivity(intent)
```

The exact Android component that receives this Intent belongs to the Omni host application. OmniLink
1.4 defines the contract; it does not auto-register a public Activity in every consumer.

The receiving host must still apply the 32 KiB limit, parse defensively, sanitize external content and
force unknown callers into its safe CHAT policy.

## 14.2 Mode eligibility matrix

| Caller class | CHAT | AGENT | TEAM | Notes |
|---|---:|---:|---:|---|
| Same-signer first-party Android app through privileged Agent Gateway | yes | host policy | host policy | Only if Workspace implements the requested mode |
| CORE first-party app | yes | host policy | host policy | CORE still does not bypass confirmation |
| Trusted partner through Public Gateway | yes | no by default | no | Public path is intentionally narrow |
| Trusted partner through encrypted transport | capability-based | only if host explicitly exposes such a capability | only if host explicitly exposes such a capability | Never implied by TRUSTED_PARTNER alone |
| Unknown third-party | yes, sandboxed | no | no | Public Gateway |
| PAIRED network peer | yes, sandboxed | no | no | Hard transport ceiling |
| Explicitly verified FIRST_PARTY desktop peer | capability-based | possible only through explicit ACL + host runtime | possible only through explicit ACL + host runtime | Network FIRST_PARTY is an explicit promotion |

Do not map a request to AGENT or TEAM merely because the incoming JSON asks for that mode.


# Part D — Desktop / PC integration

## 15. Which transport class should you use?

### `OmniTcpClient`

Use for a one-shot or manually managed connection.

You are responsible for reconnects and lifecycle.

### `OmniTcpServer`

Use when this process accepts incoming OmniLink connections.

Unknown peers are rejected by default.

### `SecureTransportSession`

Use when you need the lowest-level authenticated encrypted request/event transport and you deliberately
want manual send/receive.

Most real applications should wrap it.

### `OmniMultiplexedConnection`

Use when one encrypted connection must carry concurrent requests, responses and events.

This is the recommended layer for a real desktop companion.

It gives:

- one reader per socket,
- concurrent requests,
- `correlationId` response routing,
- request/event flows,
- encrypted ping/pong,
- clean pending-request failure on disconnect.

### `OmniReliableClient`

Use for a long-lived outgoing connection.

It adds:

- reconnect,
- exponential backoff,
- jitter,
- heartbeat,
- round-trip measurement,
- circuit breaker,
- `StateFlow<ReliableConnectionState>`.

Use this for a persistent PC companion rather than writing your own reconnect loop.

## 16. Desktop identity setup

Generate a long-lived identity once:

```kotlin
val identity = JvmEcSigningIdentity.generate(
    peerId = "desktop-main",
    role = PeerRole.DESKTOP
)
```

Persist it encrypted:

```kotlin
val identityStore = EncryptedJvmIdentityStore(
    File("secrets/omnilink-identity.json")
)

identityStore.save(identity, password)

val restored = identityStore.load(password)
```

Do not generate a new identity on every process launch. That would look like a new computer at every
connection and defeat pinning.

## 17. Desktop trust store

```kotlin
val trustStore = JsonFilePeerTrustStore(
    File("data/omnilink-trust.json")
)
```

A trust record contains public fingerprints and policy only. Private keys live in the identity store.

## 18. First pairing

Strict mode rejects unknown peers:

```kotlin
OmniTcpClient.connect(
    host = "192.168.1.20",
    identity = identity,
    trustStore = trustStore
)
```

For a user-approved first pair provide a `PeerAdmissionHandler`.

Start low:

```kotlin
val admission = PeerAdmissionHandler { candidate ->
    // Real UI: show candidate.pairingCode and/or full fingerprint.
    PeerTrustProfiles.chatSandbox(candidate)
}
```

After the user verifies the peer and decides it is an official companion, the host may replace the
record with a `FIRST_PARTY` record containing explicit ACLs.

## 19. Persistent desktop connection

```kotlin
val client = OmniReliableClient(
    host = "192.168.1.20",
    identity = restored,
    trustStore = trustStore
)

client.start()

client.state.collect { state ->
    println(state)
}
```

When connected:

```kotlin
val connection = client.connectionOrNull()
    ?: error("Not connected")

val response = connection.requestUtf8(
    capability = "chat.ask",
    text = "Summarize the current project status"
)
```

## 20. Receiving requests on the server side

```kotlin
val server = OmniTcpServer(
    identity = serverIdentity,
    trustStore = trustStore
)

server.start { secureSession ->
    val connection = OmniMultiplexedConnection(secureSession)

    connection.incomingRequests.collect { request ->
        when (request.capability) {
            "desktop.system.info" -> {
                connection.respondUtf8(
                    request,
                    collectSafeSystemInfo()
                )
            }
        }
    }
}
```

Trust policy is checked before an unauthorized capability reaches this handler.

## 21. LAN vs USB/ADB

For normal LAN use the phone/PC IP address and default port `49371`.

For a desktop client connecting through USB to an Android-side server:

```bash
adb forward tcp:49371 tcp:49371
```

then connect the desktop client to:

```text
127.0.0.1:49371
```

For the reverse direction, where the ADB environment supports it:

```bash
adb reverse tcp:49371 tcp:49371
```

ADB is only a byte tunnel. OmniLink still performs its own authentication, pinning, encryption and ACL
checks.

## 22. Transport trust is not APK trust

These are different questions.

### Android same-device Binder trust

Android verifies installed-app signing identities and signature permissions.

### Desktop/network transport trust

Each device owns a transport key. Pairing pins its public-key fingerprint.

The Android transport handshake also includes locally observed APK signer fingerprints as signed
metadata.

That metadata is useful for policy and continuity, but the remote PC does not possess magical proof of
the developer's APK private signing key. The cryptographic remote identity is the paired transport key.

---

# Part E — Omni controlling an unrelated app

## 23. External App Bridge

Use the External App Bridge model when the target application is not an OmniLink participant.

Preferred order:

1. native public API,
2. Intent/deep link,
3. MediaSession,
4. notification action,
5. Accessibility,
6. Shizuku/shell,
7. root only when deliberately granted.

Why this order?

A semantic API is more stable, more precise and lower privilege than screen automation or shell
control.

`ExternalAppBridgeProtocol.kt` defines routing descriptors and decisions. The 3.0 source also
offers `AuthorizedExternalAppExecutor` to enforce exact grants and host-confirmed execution for
host-owned adapters. Neither component installs an Accessibility service, Shizuku bridge or root
daemon for a consuming app. The host must verify adapter permissions before executing.

### 3.0 host authorization flow

1. Create a host-owned `HostAppIdentityRegistry` with your actual host signer and approved
   package/signer registrations. A marketplace installer string never grants a trust tier.
2. Use `TrustedCapabilityDiscovery(context).discover(policy)` to discover exported services whose
   permission and current APK signer satisfy `TrustedServicePolicy`. Treat the graph as advertised
   capabilities, not execution authority. Set appropriate Android package visibility for discovery.
3. For a target app, obtain fresh identities via
   `HostVerifiedAppPairResolver.android(context, targetPackage, registry)` and construct an exact
   `CapabilityGrantRequest` for the capability, resource, purpose, data scopes and risk.
4. Show a real foreground UI through `CapabilityConsentPresenter` and record its decision with
   `CapabilityConsentCoordinator`. Use `AndroidEncryptedCapabilityGrantPersistence` for persistent
   decisions. A dismissed UI or unavailable store denies the request.
5. Execute through `AuthorizedExternalAppExecutor` with a host-owned adapter. Provide an additional
   confirmation callback for a selected route marked destructive or consent-required. The adapter
   rechecks Android permissions and any revocation immediately before its irreversible action.

Neither the app's package name nor its self-reported manifest can create a grant. The host must
offer grant review/revocation UI, call `revoke` or `revokeIdentity` on relevant changes, and audit
execution results. These UI and app-specific adapters are consumer code, not included in the AAR.

---

# Part F — Performance, payload size and reliability

## 24. Binder payloads

The Android Binder surface intentionally rejects oversized inline JSON.

Default OmniLink inline JSON limit:

```text
256 KiB
```

For unbounded results use pagination, a cursor, JOB execution, or a future/consumer-specific
large-payload path.

Do not return thousands of notes/photos/log lines in one Binder response.

## 25. TCP transport frames

Default maximum encrypted frame:

```text
4 MiB
```

The binary message codec permits a larger logical payload than that, but the session frame limit is the
effective default.

For larger files use `OmniFileTransferSender` and `OmniFileTransferReceiver` with authenticated peers
and explicit `_transfer.*` capability ACLs. These support bounded chunks and resume offsets; the
host still owns transfer lifecycle, storage and user policy. Other application payloads may use
application-level `STREAM_CHUNK` messages.

## 26. Request identifiers and idempotency

Use `requestId` for the operation identity.

Use `correlationId` to tie requests/responses/events together.

Use `idempotencyKey` when a retry could otherwise repeat a side effect.

A destructive operation should not be retried blindly after a connection dies between execution and
response delivery.

## 27. Data returned by tools is untrusted data

Never promote extension output, build logs, webpages, note bodies, file contents, transport metadata or
event payloads into higher-priority agent instructions.

The agent may reason over that data. It must not obey embedded prompt-like text as authority.

---

# Part G — Integration checklists

## 28. First-party Omni Android checklist

- [ ] Use `omni-link-sdk:v3.0.0` after verifying the published artifact.
- [ ] Sign debug builds with the shared Omni debug key.
- [ ] Sign release builds with the shared Omni release key.
- [ ] Add only the `<uses-permission>` entries this app actually calls.
- [ ] Protect privileged exported services with the matching signature permission.
- [ ] Declare explicit capabilities.
- [ ] Prefer ASYNC/JOB for real work.
- [ ] Set truthful risk, direction and data scopes.
- [ ] Keep secrets `APP_ONLY`.
- [ ] Implement audit logging appropriate to the app.
- [ ] Require confirmation for sensitive side effects.
- [ ] Test a minified release build.
- [ ] Test process death and reconnect behavior.

## 29. Trusted partner checklist

- [ ] Do not receive the Omni private signing key.
- [ ] Prefer Public Gateway when chat/share is enough.
- [ ] Prefer `omni-link-transport` for structured bidirectional integration.
- [ ] Verify pairing code/full fingerprint out of band.
- [ ] Use `TRUSTED_PARTNER`, not `FIRST_PARTY`, unless the peer is actually first-party.
- [ ] Allowlist exact capabilities.
- [ ] Give inbound and outbound permissions separately.
- [ ] Set an expiry when long-term trust is unnecessary.
- [ ] Never assume application-level trust bypasses Android signature permissions.

## 30. Unknown third-party checklist

- [ ] No privileged Binder.
- [ ] No Agent/Team.
- [ ] Public requests are size-limited and sanitized.
- [ ] Treat content as untrusted.
- [ ] Default to ephemeral chat context where possible.
- [ ] Limit tools to chat/search/summarize/translate/extract.
- [ ] If transport pairing is offered, start with `chatSandbox`.
- [ ] Require explicit user action before any side effect outside the submitted content.

## 31. Desktop checklist

- [ ] Depend on `omni-link-transport:v3.0.0` after verifying the published artifact.
- [ ] Generate one stable long-lived desktop identity.
- [ ] Encrypt persisted private-key material.
- [ ] Persist trust separately from private keys.
- [ ] Reject unknown peers unless in an explicit pairing flow.
- [ ] Show the pairing code or full fingerprint.
- [ ] Prefer `OmniMultiplexedConnection` for normal app traffic.
- [ ] Prefer `OmniReliableClient` for long-lived outgoing links.
- [ ] Use explicit capability ACLs.
- [ ] Do not treat LAN membership or ADB connectivity as trust.
- [ ] Test reconnect, network loss and peer key mismatch.

---

## 32. What OmniLink 2.0 does not automatically do

The SDK provides contracts and transport. It does not automatically:

- expose Workspace's model runtime,
- start an Android foreground service,
- scan the LAN,
- open firewall ports,
- build a desktop UI,
- grant a peer Agent or Team mode,
- map a public request into private memory,
- implement every external-app adapter,
- transfer arbitrary multi-gigabyte files,
- make a destructive operation safe without host policy.

The host application remains responsible for deciding which concrete capabilities exist.

That separation is intentional: OmniLink carries authenticated, authorized messages; the consumer owns
business logic and user policy.
