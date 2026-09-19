# OmniLink Protocol

## Core rule

OmniLink separates identity, authority and consent:

> Certificate establishes identity. Capabilities establish authority. The user establishes consent.

A same-signer application is trusted as first-party identity, but it does not automatically receive
every capability or every piece of app-owned data.

## Shared first-party signing identity

All first-party Omni APKs should be signed by the same release certificate, and all development builds
should use the same Omni debug certificate. See `SIGNING_TRUST.md`.

The SDK declares these signature-level permissions:

- `OmniLinkConstants.PERMISSION_BIND_EXTENSION`
  (`com.omnilink.sdk.permission.BIND_EXTENSION`)
- `OmniLinkConstants.PERMISSION_BIND_AGENT`
  (`com.omnilink.sdk.permission.BIND_AGENT`)

Android checks signature permissions before application Binder code runs.

The base `ExtensionService` is also fail-closed with `SameSignerSecurityValidator`. Consumers do not
need to paste a first-party certificate hash into every app. Explicit
`SignatureSecurityValidator` allowlists remain available for controlled partner access or key
migration.

## Binder identity

Caller identity must come from `Binder.getCallingUid()`, never a package name supplied inside JSON.

`CallerIdentityResolver` records:

- calling UID,
- every package associated with that UID,
- signing certificate SHA-256 values,
- whether the caller matches the host signing identity.

The old `getPackagesForUid(uid).firstOrNull()` pattern is not sufficient as an authorization
primitive because one UID may map to more than one package.

## Trust tiers

`DefaultTrustResolver` maps authenticated callers into:

- `CORE`: same signer plus an explicitly configured core package.
- `FIRST_PARTY`: same Omni signer.
- `TRUSTED_PARTNER`: explicitly allowlisted external signer with an explicit capability allowlist.
- `UNTRUSTED`: everything else.

A privileged extension should normally reject untrusted callers before capability execution.

## Capability authorization

`CapabilityDescriptor` can declare:

- `requiredTrustTier`
- `communicationDirection`
- `risk`
- `idempotency`
- `supportsDryRun`
- `timeoutMillis`
- `maxInlinePayloadBytes`
- `dataScopes`
- `requiredPermissions`
- `inputSchema` / `outputSchema`

Authentication does not replace `AccessController`. The flow is:

```text
Binder UID
  -> signing identity
  -> trust tier
  -> capability trust/direction check
  -> AccessController
  -> confirmation policy
  -> execution
```

## Action naming

Satellite apps must never define their own action starting with `_`. The prefix is reserved for
protocol-level/system actions such as `_tick`.

## Sync vs async execution

`executeAction` is reserved for declared `CapabilityExecutionMode.IMMEDIATE` work.

Anything touching disk, databases, the network, long computation or external processes belongs on
`executeActionAsync` or a `JOB` capability.

When a service has an explicit capability manifest, OmniLink 1.3 rejects a synchronous call to a
non-`IMMEDIATE` capability with `async_required`.

Legacy services with an empty capability list remain source-compatible.

## Request reliability metadata

`ActionRequest` can carry:

- request ID,
- correlation ID,
- idempotency key,
- deadline,
- priority,
- dry-run request,
- confirmation token,
- structured metadata.

The base service rejects already-expired calls and oversized inline JSON before running app logic.

## Confirmation

`CapabilityDescriptor.requiresConfirmation` remains the fast client-side hint.

A server-side `ActionOutcome.RequiresConfirmation` is a hard stop, not an implicit retry protocol.

For destructive operations that need a stronger contract, SDK 1.3 also defines:

- `ActionPreview`
- `PreparedAction`
- `ActionCommit`

The host may prepare the exact side effect, show affected resources/diff/risk, then issue a short-lived
opaque commit token after user approval.

Same signer is never user consent.

## Binder transaction limits

Binder's transaction buffer is limited and shared across in-flight transactions. OmniLink therefore
keeps a defensive inline JSON limit (`DEFAULT_MAX_INLINE_JSON_BYTES`) and requires pagination,
chunking or JOB semantics for unbounded result sets.

`SessionProtocol.kt` models future negotiated large-payload transports:

- inline,
- file descriptor,
- pipe,
- shared memory,
- content URI.

Actual FD/SharedMemory AIDL methods should only be appended once both endpoints implement them end to
end.

## Event flow

`observeEvents()` wraps raw AIDL callbacks as Kotlin `Flow<OmniEvent>`.

SDK 1.3 bounds the local client queue. Events that require lossless replay should carry monotonically
increasing `sequence` values; consumers can detect gaps and use the owning replay/job API.

The session protocol additionally models ACK/credit frames for future true cross-process backpressure.

## Process death and reconnect

Every remote Binder call site must treat `RemoteException` / `DeadObjectException` as an immediate
connection failure and route it into the same reconnect-with-backoff path as
`onServiceDisconnected`.

Long-running work should be represented by durable task/job IDs so callers can reconnect and query or
replay state instead of assuming one Binder connection lives forever.

## Data is untrusted input

Anything returned by an extension is data, not instruction authority:

- note bodies,
- calendar descriptions,
- file contents,
- webpage text,
- diagnostics,
- build output,
- event payloads.

Agent prompts and tool policy must not treat extension-provided text as higher-priority instructions.

## Agent Gateway

Trusted same-signer apps can delegate work to Workspace through `IAgentGatewayService`.

Workspace owns:

- model/runtime state,
- MCP,
- web/deep search,
- local tools,
- memory,
- project context,
- persistent history,
- Agent Console,
- task state.

A client reuses `clientConversationId` when continuing the same logical conversation.

SDK 1.3 adds task graph/delegation models but the corresponding
`AgentGatewayManifest.supportsTaskGraphs` flag defaults to `false` until Workspace really supports
it.

## Foreign applications

Foreign apps must not receive privileged `BIND_AGENT` or `BIND_EXTENSION` access.

Omni can control/interact outward through the External App Bridge using the lowest-privilege semantic
adapter available:

1. native public API,
2. Intent/deep link,
3. MediaSession,
4. notification action,
5. Accessibility,
6. Shizuku/shell,
7. root if explicitly granted by the user.

For intentional inbound interoperability, Workspace may expose the separate
`ACTION_PUBLIC_OMNI_REQUEST` Intent. It is a narrow share/ask/open surface with its own size limits,
sanitization and confirmation policy. It does not weaken the privileged Agent Gateway.

## Compatibility

Existing AIDL method order is append-only and Binder transaction IDs must never be reordered.

SDK 1.3 keeps `CURRENT_PROTOCOL_VERSION = 4`. New models are additive/opt-in. `OmniJson` omits
default fields and upgraded peers ignore unknown fields, while runtime feature flags stay false until a
consumer implements the feature.
