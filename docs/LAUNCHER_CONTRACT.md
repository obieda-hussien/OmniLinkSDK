# Launcher integration contract (SDK v3.0.0)

This is an application-level contract over the existing published v3.0.0 SDK. It adds no SDK
code, AIDL transaction, protocol version, or release requirement. Launcher/Workspace implementations
must be updated together; the SDK tag already contains the typed public gateway and extension APIs.

## Public, user-visible search

The launcher creates PublicOmniRequest(kind=ASK_OMNI, text=query), serializes with OmniJson, and
places it in OmniLinkConstants.EXTRA_PUBLIC_REQUEST_JSON. It discovers activities using
ACTION_PUBLIC_OMNI_REQUEST and launches an explicit Workspace component. OPEN_OMNI opens chat.
Only exact known Workspace flavor packages are shown; multiple installed flavors get a chooser.

Workspace exposes an exported activity with that action and DEFAULT category. This public path
requires no privileged permission or signature match. It is strictly a UI draft handoff: enforce
PublicGatewayPolicy.MAX_PUBLIC_REQUEST_BYTES (32 KiB), typed parsing, 8,192 prompt characters,
nonblank ASK_OMNI, and no automatic submission. Do not accept client extras as identity, roles,
grants, session IDs, confirmations or execution instructions. SHARE_TO_OMNI is not implemented
by the launcher-specific Workspace ingress. Typing and generating suggestions never sends a request.

## Privileged Workspace → Launcher

Export an ExtensionService with ACTION_EXTENSION_BIND, protected by PERMISSION_BIND_EXTENSION.
Workspace remains the permission definer. Do not redefine the SDK permissions in Launcher.
The SDK SameSignerSecurityValidator verifies actual installed identity; Launcher additionally
requires an exact Workspace package, a receiver-owned flavor ceiling, and local opt-in. Capability declarations do not grant access.

| Capability | Payload | Execution / semantics |
| --- | --- | --- |
| launcher.health | `{}` | ASYNC; idempotent; local consent status, works when opt-in is off |
| launcher.get_settings | `{}` | ASYNC; idempotent; configured provider and assistant toggles |
| launcher.list_apps | offset, limit integers | ASYNC; idempotent; current-profile visible launchable components, pages 1–40 |
| launcher.set_preference | key, value | ASYNC; idempotent; approval, dryRun; search_provider string or omni_suggestions/omni_always_suggest boolean |
| launcher.open_app | component string | ASYNC; non-idempotent; approval, dryRun; visible exact component; resumed Home |
| launcher.open_drawer | `{}` | ASYNC; non-idempotent; approval, dryRun; resumed Home |

All require FIRST_PARTY trust and OMNI_TO_APP_ONLY direction. Launcher caps requests at 16 KiB and
async concurrency at two. Mutations advertise requiresConfirmation so the Workspace approval gate
runs. Local opt-in is off by default and never writable remotely. Recheck consent after dispatch
and before mutation; service discovery alone must not grant control or start polling.

Common failures: control_disabled/access_denied, invalid_payload, invalid_page, unknown_provider,
preference_not_allowed, app_not_visible, foreground_required, plus standard SDK identity/version/
deadline/transport failures. Honor errors; never replace a refusal with shell/accessibility calls.
Hidden apps, other profiles, workspace layout databases and conversation history are outside this
capability contract. No capability clears Home or changes root/security grants.

## Interoperability checks

Test exact signatures, flavor package/tier ceilings, revoke-while-queued, advertised capability
schema and actual payload validation, dryRun with no side effects, hidden apps/paging, foreground
refusal, provider persistence, process death, Arabic text, and public ingress with hostile extras.
Search works across differently signed builds; Binder control intentionally does not. For local
signing, use the same private certificate for both APKs. No changes to SDK ABI tests are needed
because the wire/API surface is unchanged.

## Maintainer and rights preservation

Original Omni ecosystem integration development and updates are maintained by
**Abdelrahman Hussein (عبدالرحمن حسين / Obieda)**. The Launcher application remains derived from
Lawnchair/AOSP, whose original notices, authors and license headers are preserved in that fork.
The SDK's [NOTICE.md](../NOTICE.md) and [LICENSE](../LICENSE) remain separate and unchanged in
meaning. This contract documents consumer behavior; it does not relicense any SDK/upstream code.
