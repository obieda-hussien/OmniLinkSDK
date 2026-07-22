# OmniLink Protocol

## Action Naming
Satellite apps must never define their own action starting with `_`. The `_` prefix is reserved for protocol-level system calls (e.g. the heartbeat tick, `_tick`). This keeps a clean namespace between "the protocol talking to itself" and "the agent calling app-specific capabilities," with no new AIDL method required to add future system-level calls.

## OS-Level Security & Constants
The SDK defines the following constants in `OmniLinkConstants` which should be used by both Workspace and satellite extensions:
- `OmniLinkConstants.PERMISSION_BIND_EXTENSION` (`com.omnilink.sdk.permission.BIND_EXTENSION`): A `signature` level permission. Android will reject any `bindService()` call if the caller does not hold this permission and isn't signed with the same key. The SDK merges this automatically into consumer manifests.
- `OmniLinkConstants.ACTION_EXTENSION_BIND` (`com.omnilink.sdk.action.EXTENSION_BIND`): The intent action used to discover and bind extensions.

**Note:** The OS-level permission is the primary defense, but a `SecurityValidator` (specifically `SignatureSecurityValidator`) is a required second layer of defense, not optional. Every consumer's `ExtensionService` subclass must set a real `SecurityValidator` to protect against package spoofing on rooted devices. Do not leave it as `null`.

## Execution: Sync vs. Async
**`executeAction` is reserved for actions a consumer explicitly documents as fast/non-blocking in its own manifest; anything else must be called via `executeActionAsync`.**

- `executeAction` is a synchronous AIDL method. It blocks the caller's thread and occupies one of the callee's finite binder-pool threads for the duration of the execution. This is risky for network, DB, or file I/O operations and can lead to thread exhaustion or ANRs.
- `executeActionAsync` is a `oneway` AIDL method that returns immediately and streams the outcome back via `IOmniResultCallback`. All standard heavy processing must use this path.

## The `requiresConfirmation` Flow
The protocol handles operations requiring explicit user confirmation in a strictly defined way.

- **Primary mechanism (Client-side):** The caller (Workspace) checks `CapabilityDescriptor.requiresConfirmation` from the extension's *cached manifest* **before** making the call. If true, the caller is responsible for displaying the confirmation UI and only executing the action if the user approves.
- **Server-side Fallback (Not a retry protocol):** If a remote extension's `AccessController` returns `AccessDecision.REQUIRES_CONFIRMATION` (e.g., due to a stale cache or dynamic runtime policy), the caller receives `ActionOutcome.Failure(ActionError("requires_confirmation", ...))`. The caller must treat this as a **hard abort** and surface the failure to the agent/user. The caller must **never** silently retry the call assuming implicit confirmation.

## Launcher Interface
The SDK repo defines the `IOmniLauncherInterface` AIDL as the single source of truth for the launcher IPC contract. However, the Omni-launcher repo does **not** take a direct dependency on this SDK. Instead, it copies the `.aidl` file text directly. The actual implementation (the Service and Binder stub) lives exclusively in the Omni-launcher repo, mapping these calls to Lawnchair/Launcher3 internals.
