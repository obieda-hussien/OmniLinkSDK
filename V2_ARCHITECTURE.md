# OmniLinkSDK 2.0 Architecture

OmniLink 2.0 treats distribution, identity, authority, and data movement as separate security
concerns.

## Public vs trusted surfaces

`omni-link-public` contains only bounded Ask/Share/Open contracts. It intentionally exposes no
privileged Binder/AIDL interface, no Agent Gateway and no FIRST_PARTY promotion helper.

`omni-link-sdk` contains the trusted Android integration surface. Importing this artifact is not a
credential. The receiving host validates the real UID/package/signing certificate and capability
policy before any privileged operation.

`TrustedServiceResolver` makes provider discovery fail closed:

```text
implicit discovery
  -> inspect ServiceInfo
  -> require exact privileged permission
  -> verify APK signer / explicit pin
  -> build explicit ComponentName
  -> bind
```

## Directional authority

Omni controlling an application does not give that application equivalent power over Omni.

Inbound and outbound ACLs are independent. FIRST_PARTY has no wildcard defaults in v2.

Android FIRST_PARTY promotion requires host-owned signer fingerprints. Desktop FIRST_PARTY promotion
requires a host-pinned long-lived transport public key. A peer cannot become trusted by repeating its
own claimed identity.

Unknown or merely paired peers retain the chat/search/summarize/translate/extract ceiling.

## Large-payload data plane

Control messages stay small. Images, videos, archives, logs and long documents move through a
separate data plane.

On the same Android device, Binder carries `PayloadDescriptor` metadata while Content URI, file
descriptor or pipe transport carries bytes incrementally. `AndroidPayloadBroker` validates declared
size and optional SHA-256 before final commit.

Across TCP/LAN/ADB/desktop, `OmniFileTransferSender` and `OmniFileTransferReceiver` provide:

- bounded chunks;
- per-chunk SHA-256;
- final whole-file SHA-256;
- durable `.part` files;
- resume from the last durable byte;
- exact size enforcement;
- path sanitization;
- encrypted transport inherited from the OmniLink session;
- ACL-controlled `_transfer.*` capabilities.

No transfer requires loading the whole file into RAM.

## OmniDev / AndroidIDE memory contract

OmniDev is the agent-memory hub; AndroidIDE remains the owner of live IDE state.

```text
AndroidIDE                         OmniDev
   |                                 |
   |-- project/context ------------->|
   |-- diagnostics/build/logs ------>|
   |-- memory deltas -------------->|
   |<----- memory/search/context ----|
   |<----- agent/tool requests ------|
   |====== large data plane ========>|
```

Memory sharing means versioned records and deltas, not sharing one Room database file. Each process
keeps independent storage and exchanges stable IDs, source identity, timestamps and conflict metadata.
That avoids cross-process schema corruption and lets reconnect/replay be idempotent.

## Reliability invariants

- exactly one reader per encrypted socket;
- bounded request/event queues;
- no whole-file buffering;
- reconnect with backoff and circuit breaker;
- transfer resume by stable transfer ID;
- idempotent memory updates by stable record ID;
- integrity checks before commit;
- remote content remains untrusted data, never executable instructions;
- failures reject narrowly rather than broadening trust.

## Distribution note

Because this repository is public, source visibility can never be a security boundary. A private
package registry can reduce artifact distribution, but copied code still must not grant authority.

OmniLink therefore enforces privileged trust on the receiving side through signer/key identity and
capability policy. That remains mandatory even if trusted artifacts are later moved to a private Maven
registry.
