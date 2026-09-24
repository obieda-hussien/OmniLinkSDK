# OmniLink 3.0 — Trust Mesh, Capability Graph and Device Trust

## Design objective

OmniLink is evolving from a shared AIDL library into the protocol and trust substrate for the Omni
ecosystem. First-party applications should cooperate as one distributed environment while keeping
Android process isolation, least privilege, user consent and independent data ownership.

The architecture separates four questions that must never be collapsed into one:

1. **Who is calling?** Android UID + signing identity.
2. **How much can that identity potentially be trusted?** Trust tier.
3. **What may it do right now?** Capability/access policy.
4. **Did the user authorize a sensitive side effect?** Confirmation/commit policy.

## Trust tiers

`CORE`, `FIRST_PARTY`, `TRUSTED_PARTNER`, and `UNTRUSTED` are modeled by `TrustTier`.

First-party same-signer applications are eligible for privileged Binder communication, subject to
capability and access policy.

Trusted partners keep their own identity and receive only explicit authority. A differently-signed
partner does not bypass Android's signature permission merely because application-level trust policy
recognizes its certificate.

Untrusted applications cannot enter privileged Binder surfaces. Their intended inbound path is the
Public Gateway, normally mapped by the host to CHAT-only behavior.

## Capability contracts

`CapabilityDescriptor` now describes more than a name:

- execution mode,
- streaming support,
- trust requirement,
- communication direction,
- risk,
- idempotency semantics,
- dry-run support,
- timeout,
- maximum inline payload,
- data scopes,
- required Android permissions,
- input/output schema.

This lets the agent choose tools semantically instead of relying on hard-coded package knowledge.

## Data ownership

Signing does not merge sandboxes and Omni does not use deprecated shared-UID architecture.

Each app remains the owner of its database/files and exposes narrow capabilities such as:

```text
notes.search
notes.read
notes.write
diagnostics.read
gradle.build
git.commit
media.current_track
```

Suggested `DataScope` semantics:

- `PUBLIC`: safe to expose through a public surface.
- `OMNI_ECOSYSTEM`: first-party ecosystem use.
- `OMNI_AGENT`: agent may reason over it under policy.
- `USER_CONFIRMATION_REQUIRED`: release only after explicit user authorization.
- `APP_ONLY`: never leave the owning app.

## Cross-app capability graph

Workspace can combine manifests into a `CapabilityGraphSnapshot`.

Example route:

```text
AndroidIDE.diagnostics.read
        -> AndroidIDE.project.patch
        -> AndroidIDE.gradle.build
        -> Launcher.apps.launch
```

The graph models dependencies, alternatives, delegation and observations without embedding those
relationships in the LLM prompt.

## Agent task DAG

`AgentTaskGraph` standardizes multi-step/multi-agent execution. Nodes declare dependencies and
requested capabilities. A client can render the graph, resume it, and correlate console/events without
pretending every task is one flat request.

## Reliability metadata

`ActionRequest` supports request/correlation IDs, idempotency keys, deadlines, priorities, dry-run,
confirmation tokens and metadata.

The base `ExtensionService` now:

- authenticates same-signer callers by default,
- resolves Binder identity from kernel UID instead of trusting payload package names,
- parses a request once instead of once for execution and again for audit,
- enforces a bounded inline request size,
- rejects expired requests,
- rejects undeclared capabilities when a manifest is present,
- rejects synchronous invocation of non-`IMMEDIATE` declared capabilities,
- bounds concurrent async work with a semaphore,
- cancels its coroutine scope on service destruction,
- preserves audit isolation if a logger fails.

## Event pressure and replay

`observeEvents()` now has a bounded local buffer with DROP_OLDEST behavior. Sequence numbers on
`OmniEvent` enable consumers to detect a gap and use an owning replay/job API where losslessness
matters.

True cross-process credit-based flow control is represented by the new session protocol and remains an
opt-in runtime feature to wire into services incrementally.

## Session protocol models

`OmniSessionHello` and `OmniFrame` provide transport-independent models for:

- multiplexed requests,
- results,
- events,
- acknowledgements/credits,
- cancellation,
- ping/health,
- errors,
- separate control/data/event lanes.

The model also negotiates codecs and payload transports.

In 1.4, Android Binder remains the privileged same-device transport, while the new pure JVM
`omni-link-transport` module implements real authenticated encrypted TCP communication for LAN,
localhost and ADB tunnels.

The SessionProtocol types and the TCP runtime are related foundations, but consumers must not claim a
feature is wired end to end merely because both models exist.

## Large payload strategy

Small control messages stay inline. Large data should move through a negotiated payload transport
rather than growing Binder JSON indefinitely.

The protocol models:

- inline,
- file descriptor,
- pipe,
- shared memory,
- content URI.

Actual Android FD/SharedMemory transport should be appended to the Binder ABI only when the Workspace
and consumers are ready to implement it end to end. Until then, the existing hard cap and
pagination/JOB rules prevent TransactionTooLarge failures.

## Preview -> commit

`ActionPreview`, `PreparedAction` and `ActionCommit` model two-phase destructive work.

A service can prepare an exact operation, show the user affected resources/diff/risk, and issue an
opaque short-lived commit token. Same signer never counts as consent.

## Foreign application bridge

Foreign apps are outbound-controlled by Omni through an adapter router. The preferred adapter order is
semantic and least-privilege first: API -> Intent -> media/notification -> Accessibility ->
Shizuku/shell -> root.

The foreign app does not receive privileged access back into Omni.

A separate public request surface exists for intentionally limited share/ask/open interoperability.

## Compatibility strategy

SDK 1.4 keeps the deployed Binder method order and `CURRENT_PROTOCOL_VERSION = 4`.

New fields have conservative defaults and the canonical `OmniJson` codec omits defaults while
ignoring unknown fields on upgraded peers. Runtime feature flags remain false until Workspace really
implements the corresponding feature.

This avoids claiming capabilities that only exist as protocol models and gives consumers a staged
migration path.

## Recommended integration order

1. Read [INTEGRATION_GUIDE.md](INTEGRATION_GUIDE.md) and classify the consumer.
2. First-party Android apps adopt the shared signing identity before privileged Binder integration.
3. Declare semantic capability manifests with trust, direction, risk and data scopes.
4. Wire Binder ExtensionService/Agent Gateway only where the host really implements them.
5. Unknown apps stay on Public Gateway CHAT-only behavior.
6. Trusted partners use a narrow public or encrypted-transport surface rather than the Omni signing key.
7. Desktop peers pair at low privilege, then are promoted explicitly with inbound/outbound ACLs.
8. Add preview/commit and task/capability graphs only in runtimes that can execute them.
9. Add large-payload and strict credit-based flow control only after both endpoints support them.
10. Add benchmarks/regression budgets before raising transport or concurrency limits.


## Transport trust mesh in 1.4

The network/device transport has a separate trust ladder:

```text
UNTRUSTED
PAIRED
TRUSTED_PARTNER
FIRST_PARTY
CORE
```

This does not replace Android Binder trust tiers.

Every network peer is pinned by transport public-key fingerprint and receives separate inbound and
outbound capability ACLs.

PAIRED is intentionally constrained. Its hard capability ceiling is:

```text
chat.*
search.*
summarize.*
translate.*
extract.*
```

plus reserved authenticated transport health controls.

Even a wildcard ACL cannot grant a PAIRED peer terminal, Agent/Team, root/system control, private
memory or project-modification namespaces.

## Trust boundaries by integration type

| Integration | Identity proof | Authority source |
|---|---|---|
| First-party Android Binder | Android UID/signing relationship + same-signer validator | Capability metadata + AccessController + confirmation |
| Trusted Android partner | partner/public surface policy; privileged Binder needs explicit host design | explicit partner allowlist |
| Unknown app | public request surface | CHAT-only host policy |
| Paired PC/device | pinned transport key + signed handshake | PAIRED hard ceiling + ACL |
| Trusted PC/device | pinned transport key + explicit promotion | transport trust level + inbound/outbound ACL |
| External app controlled by Omni | adapter/platform permissions | route policy + user grants/confirmation |

## Runtime honesty

The SDK contains protocol types for capability graphs, task DAGs, preview/commit, session negotiation
and large-payload transports.

A consuming product must only advertise support after it implements the behavior end to end.

OmniLink's job is to make that distinction explicit rather than let protocol availability masquerade
as runtime capability.
