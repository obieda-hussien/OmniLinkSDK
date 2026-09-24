# OmniLink 2.0 Protocol Rules

This document is the canonical behavior contract for the Android/Binder side of OmniLink 2.0.

For "which surface should I use?" start with
[INTEGRATION_GUIDE.md](INTEGRATION_GUIDE.md).

## Core rule

> Certificate establishes identity. Capabilities establish authority. The user establishes consent.

Never collapse these into one decision.

A same-signer application is first-party identity. It is not automatically authorized for every
capability, every data scope, or every destructive action.

## Protocol vs SDK version

Current SDK release:

```text
2.0.1
```

Current Android Binder protocol:

```text
CURRENT_PROTOCOL_VERSION = 5
```

These versions serve different purposes.

The SDK version identifies a build/release.

The protocol version identifies the Binder wire compatibility contract.

A security/performance/documentation/transport release does not automatically require a Binder protocol
bump.

## Integration surfaces

### Privileged Extension Binder

Use when an official first-party Omni Android app exposes semantic capabilities to other official Omni
apps on the same device.

Core APIs:

```text
IExtensionService
ExtensionService
CapabilityManifest
ActionRequest
ActionOutcome
```

### Privileged Agent Gateway

Use when an official first-party client delegates CHAT / AGENT / TEAM work to a Workspace runtime that
actually implements the gateway.

Core APIs:

```text
IAgentGatewayService
IOmniAgentCallback
AgentTaskRequest
AgentTaskEvent
```

### Public Gateway

Use for unknown or ordinary third-party callers.

Core contract:

```text
PublicOmniRequest
SHARE_TO_OMNI
ASK_OMNI
OPEN_OMNI
```

This is intentionally separate from the privileged Agent Gateway.

### External App Bridge

Use when Omni is controlling an unrelated app that did not integrate OmniLink.

The bridge models adapter selection; it does not grant that external app privileged access back into
Omni.

### Encrypted desktop/device transport

Cross-device networking is defined in the pure JVM transport module, not in Binder AIDL.

Read [DESKTOP_TRANSPORT.md](DESKTOP_TRANSPORT.md).

## Android signature permissions

The SDK declares:

```text
com.omnilink.sdk.permission.BIND_EXTENSION
com.omnilink.sdk.permission.BIND_AGENT
```

They are signature-level permissions.

A service exposing a privileged Binder endpoint should protect itself with the corresponding
permission.

A caller must request the permission it uses.

The full Android SDK is first-party-oriented. A differently-signed partner should not assume an
application-level TRUSTED_PARTNER rule bypasses the Android permission gate.

See [SIGNING_TRUST.md](SIGNING_TRUST.md).

## Caller identity

Never trust a package name supplied inside JSON.

Binder caller identity comes from the kernel UID.

`CallerIdentityResolver` records:

- calling UID;
- all packages associated with that UID;
- signing certificate SHA-256 values;
- same-signer status.

Do not return to a first-package-only authorization check.

## Trust tiers

Android trust tiers are:

```text
UNTRUSTED
TRUSTED_PARTNER
FIRST_PARTY
CORE
```

Default resolution:

- CORE: same signer plus an explicitly configured core package;
- FIRST_PARTY: same signer;
- TRUSTED_PARTNER: explicitly allowlisted external signer and capabilities;
- UNTRUSTED: everything else.

Trust is a maximum authority ceiling.

`AccessController` still decides each concrete request.

## Capability contracts

Every meaningful capability should have a `CapabilityDescriptor`.

Important fields:

- name;
- description;
- destructive;
- requiresConfirmation;
- executionMode;
- supportsStreaming;
- requiredTrustTier;
- communicationDirection;
- risk;
- idempotency;
- supportsDryRun;
- timeoutMillis;
- maxInlinePayloadBytes;
- dataScopes;
- requiredPermissions;
- inputSchema;
- outputSchema.

Prefer semantic names:

```text
notes.search
notes.create
diagnostics.read
project.patch
gradle.build
git.diff
media.current_track
```

Avoid unrestricted names such as:

```text
executeAnything
rawSql
readEverything
```

## Direction

`CommunicationDirection` can be:

```text
BIDIRECTIONAL
OMNI_TO_APP_ONLY
APP_TO_OMNI_ONLY
```

Direction is checked independently from trust tier.

Do not use BIDIRECTIONAL merely because it is convenient.

## Execution mode

### IMMEDIATE

Only for very small, non-blocking operations.

The synchronous AIDL path blocks the caller and occupies a Binder thread in the callee.

### ASYNC

Default choice for disk/database/network or ordinary I/O.

Use `executeActionAsync`.

### JOB

Use when work has a separate lifecycle:

```text
gradle.build
test.run
project.index
large.export
repository.clone
```

Return a job/task identifier quickly and expose progress separately.

When a service declares an explicit capability list, OmniLink rejects a synchronous call to a
non-IMMEDIATE capability with:

```text
async_required
```

## Action naming

Application capabilities must not begin with an underscore.

The underscore prefix is reserved for protocol/internal actions, for example heartbeat/control
operations.

## Request metadata

`ActionRequest` supports:

- requestId;
- correlationId;
- idempotencyKey;
- deadlineEpochMs;
- priority;
- dryRun;
- confirmationToken;
- metadata.

### requestId

Use as the identity of one logical request.

### correlationId

Use to connect related requests, responses, events, tasks or traces.

### idempotencyKey

Use when retrying could repeat a side effect.

Do not blindly retry a destructive operation after the connection dies between execution and response
delivery.

### deadlineEpochMs

The base service rejects a request whose deadline already elapsed.

### dryRun

The base service rejects dry-run requests when the declared capability has no dry-run contract.

## Confirmation

`requiresConfirmation` is a client-side hint that allows the caller to ask the user before dialing the
operation.

A server may still return:

```text
ActionOutcome.RequiresConfirmation
```

This is a hard stop.

Do not silently retry it as though the user had approved.

For stronger destructive operations use the preview/commit primitives:

```text
prepare exact operation
-> show diff / affected resources / risk
-> user approves
-> issue short-lived opaque token
-> commit the exact prepared operation
```

Same signer is never equivalent to consent.

## Data scopes

`DataScope` values:

```text
PUBLIC
OMNI_ECOSYSTEM
OMNI_AGENT
USER_CONFIRMATION_REQUIRED
APP_ONLY
```

APP_ONLY data should never leave the owning application through OmniLink.

A capability contract should expose the smallest useful data surface.

## Binder payload limits

OmniLink applies a defensive inline JSON limit.

Current default:

```text
256 KiB
```

Unbounded collections must use one of:

- pagination;
- cursor-based reads;
- hard caps;
- JOB result retrieval;
- an explicitly implemented large-payload channel.

Do not increase the global inline limit as a substitute for pagination.

The protocol models future/optional payload transports such as file descriptors, pipes, shared memory
and content URIs, but a consumer must not claim support until it implements that path end to end.

## Events

`observeEvents()` wraps the raw event callback in `Flow<OmniEvent>`.

The local queue is bounded.

Use `sequence` when gap detection or replay matters.

Do not assume a high-rate event stream is lossless simply because it uses Flow.

For strict lossless semantics, the owner needs a replay/cursor API or a future fully wired credit-based
flow-control transport.

## Process death

Every Binder caller must handle:

```text
RemoteException
DeadObjectException
onServiceDisconnected
```

as connection failures.

Do not rely only on `onServiceDisconnected`.

Long-running work should have durable task/job identifiers so the caller can reconnect and query state.

## Agent Gateway

The Agent Gateway is privileged first-party IPC.

A request payload does not establish package identity.

The caller is resolved from Binder.

`AgentClientMode` defines:

```text
CHAT
AGENT
TEAM
```

Unknown third-party callers are not eligible for this privileged path.

Protocol models exist for task DAGs, capability graphs, history and event replay, but gateway feature
flags must stay false until the consuming runtime really implements the feature.

## Public Gateway

`PublicOmniRequest` is intentionally narrow.

Current request kinds:

```text
SHARE_TO_OMNI
ASK_OMNI
OPEN_OMNI
```

Maximum public request JSON:

```text
32 KiB
```

The host should:

1. validate size;
2. parse defensively;
3. treat content as untrusted data;
4. map unknown callers to CHAT-only behavior;
5. allow only a safe tool profile;
6. require explicit user approval before side effects outside submitted content.

The Public Gateway does not grant BIND_AGENT or BIND_EXTENSION.

## External App Bridge

Preferred adapter order:

1. native public API;
2. Intent/deep link;
3. MediaSession;
4. notification action;
5. Accessibility;
6. Shizuku/shell;
7. root where explicitly granted.

Choose the lowest-privilege semantic adapter that can perform the operation.

The protocol descriptors do not themselves implement those adapters.

## External data is untrusted input

All of the following are data, not agent authority:

- note bodies;
- webpage content;
- file content;
- build logs;
- diagnostics;
- transport metadata;
- event payloads;
- external-app labels/descriptions.

The agent may reason over them.

Do not treat embedded instruction-like text as higher-priority commands.

## Compatibility rules

1. Existing AIDL methods are append-only.
2. Never reorder transaction positions.
3. New serializable fields should have conservative defaults where possible.
4. Canonical JSON ignores unknown additive fields.
5. Runtime feature flags describe implementation reality.
6. SDK 1.4 keeps Binder protocol version 4.
7. Desktop transport evolution does not silently mutate Binder ABI.

## Consumer checklist

Before calling an extension:

- verify compatible protocol range;
- read/cache the capability manifest;
- confirm the capability exists;
- respect trust/direction/risk metadata;
- perform required confirmation;
- use the correct execution path;
- bound payloads;
- catch remote-process failure;
- treat returned content as untrusted data.

Before exposing an extension:

- protect the service;
- keep default fail-closed authentication or a deliberate stronger alternative;
- declare explicit capabilities;
- enforce app-specific access policy;
- audit meaningful attempts;
- bound concurrency and output size;
- avoid exporting APP_ONLY data.
