# OmniLink 1.4 Desktop Transport

OmniLink 1.4 adds a real cross-platform transport implementation without changing any consuming Omni
application.

The repository now publishes two layers:

- `omni-link-sdk`: Android AAR, Binder protocol, trust/capability model and Android adapters.
- `omni-link-transport`: pure JVM transport usable by Android and desktop Java/Kotlin.

## What works now

The transport module can open a TCP server or connect as a TCP client on either side:

```text
Android                         Desktop
   │                               │
   ├──── ClientHello ─────────────►│
   │◄── signed ServerHello ────────┤
   ├──── signed ClientProof ──────►│
   │◄──── HandshakeFinished ───────┤
   │                               │
   │════ AES-GCM session ══════════│
   │                               │
   ├──── encrypted REQUEST ───────►│
   │◄── encrypted RESPONSE ────────┤
   │◄──────── EVENT ───────────────┤
   └──────── STREAM ──────────────►│
```

Both peers may act as client or server. The protocol is symmetrical after the handshake.

## Cryptographic identity

The network identity is intentionally distinct from the APK signing private key.

An Android installation does not possess the developer's APK signing private key, so it cannot use
that key to sign a network challenge.

Instead:

1. Android generates a long-lived P-256 transport signing key in AndroidKeyStore.
2. Desktop generates a long-lived P-256 transport signing key.
3. The first pairing pins the peer's transport public-key SHA-256 fingerprint.
4. Future sessions require proof of possession of that private key.
5. Android also includes the locally observed APK signer certificate fingerprints in the signed
   handshake metadata.

The APK signer fingerprints are useful for policy and continuity checks, but they are **not** treated
as standalone remote proof of APK signing-key possession.

## Session handshake

Every connection uses fresh ephemeral P-256 ECDH keys and 32-byte random nonces.

The long-lived identity keys sign the handshake transcript with SHA256withECDSA. Once both signatures
are accepted, both sides derive fresh session material using HKDF-SHA256.

The derived material contains independent:

- client -> server AES-256 key,
- server -> client AES-256 key,
- client -> server GCM nonce prefix,
- server -> client GCM nonce prefix.

The long-lived private identity key is never used as an encryption key.

## Frame security

Each encrypted record contains a monotonically increasing sequence number.

AES-GCM authenticates:

- ciphertext,
- session id,
- direction,
- sequence number.

The receiver requires the exact next sequence. A duplicate, replayed or reordered frame terminates the
session.

TCP gives reliable ordered delivery; the sequence layer adds protocol-level replay protection and
prevents an encrypted record from being valid in another position or direction.

## Trust store

A peer is represented by `PeerTrustRecord`:

```text
peerId
publicKeySha256
trustLevel
inboundCapabilities
outboundCapabilities
expectedPlatformSignerSha256
expiresAtEpochMs
```

Trust levels are:

```text
UNTRUSTED
PAIRED
TRUSTED_PARTNER
FIRST_PARTY
CORE
```

Trust level alone does not grant authority. The inbound/outbound capability ACL is always enforced.

Capability patterns can be exact, global `*`, or prefix wildcards such as:

```text
chat.*
search.*
build.read
```

## Chat-only sandbox

`PeerTrustProfiles.chatSandbox(candidate)` provides a safe default for a paired but low-trust peer.

It allows only:

```text
chat.*
search.*
summarize.*
translate.*
extract.*
```

It does not grant terminal, root, device-control, agent-team, memory, project or arbitrary cross-app
capabilities.

A higher-trust UI may later replace the record with a narrower or broader explicitly approved policy.

## Unknown peers

Unknown peers are rejected by default through `StrictPeerAdmission`.

For explicit pairing, a consumer supplies `PeerAdmissionHandler`. The handler receives:

- peer id,
- role,
- public-key fingerprint,
- signed platform signer claims,
- six-digit pairing code.

A newly admitted peer is **not persisted until its signature proof succeeds**.

The six-digit code is derived from both long-lived public-key fingerprints. A real UI should show it
on both endpoints or verify the full fingerprint out of band before granting meaningful privileges.

## Android identity

`AndroidKeystoreSigningIdentity.createOrLoad(...)` stores the transport private key in
AndroidKeyStore on API 23+.

The private key is never exported by OmniLink.

API 21-22 remain supported by the Android AAR, but the built-in Keystore transport identity is not
available there; a consumer must provide a custom `SigningIdentity` if it intentionally enables
desktop transport on those versions.

## Desktop identity

`JvmEcSigningIdentity` provides a portable JVM identity.

For persistence, `EncryptedJvmIdentityStore` encrypts PKCS#8 private-key material using:

- PBKDF2-HMAC-SHA256,
- a random salt,
- AES-256-GCM,
- authenticated identity-store domain data.

File permissions are restricted to owner read/write on a best-effort basis.

## Network permissions and Android lifecycle

The Android AAR declares:

```xml
android.permission.INTERNET
android.permission.ACCESS_NETWORK_STATE
```

They are normal install-time permissions.

The library deliberately does not auto-start a permanent background server. A consuming application
must decide when a server/client is allowed to run and own the lifecycle/notification policy.

This matters because modern Android restricts background and foreground-service starts.

Local-network privacy rules are also evolving on current Android releases. A consumer must handle any
runtime local-network/nearby-device permission required by the Android version it targets.

## Example: desktop client

```kotlin
val identity = JvmEcSigningIdentity.generate(
    peerId = "my-desktop",
    role = PeerRole.DESKTOP
)

val trustStore = JsonFilePeerTrustStore(File("omnilink-trust.json"))

val session = OmniTcpClient.connect(
    host = "192.168.1.20",
    identity = identity,
    trustStore = trustStore
)

session.sendUtf8(
    capability = "chat.ask",
    text = "Summarize this text"
)

val response = session.receive()
```

## Example: server

```kotlin
val server = OmniTcpServer(
    identity = identity,
    trustStore = trustStore,
    port = OmniTransportConstants.DEFAULT_PORT
)

server.start { session ->
    while (session.isOpen) {
        val message = session.receive()
        // Route only to a capability that the host application explicitly exposes.
    }
}
```

## What this release does not do

Because no other Omni repository is modified in this release:

- Workspace does not automatically start this server yet.
- AndroidIDE does not automatically expose build/project capabilities over LAN yet.
- No desktop UI is created here.
- No consumer lifecycle service is auto-installed.
- No peer receives capabilities that the consuming application has not implemented.

The transport is nevertheless real and executable: once a consumer supplies a handler and starts
`OmniTcpServer` or `OmniTcpClient`, signed trusted peers can exchange encrypted capability-scoped
messages.

## Future hardening

Potential next layers include:

- hardware-backed key attestation verification,
- QUIC/TLS transport adapter,
- mDNS/NSD discovery with explicit user approval,
- session resumption tickets,
- multiplexed concurrent logical streams,
- durable request outbox/inbox,
- circuit breakers and health scoring,
- large-stream chunking with backpressure,
- benchmark regression budgets.


## Reliability runtime

`OmniReliableClient` is an optional long-lived client supervisor. It does not auto-start on Android.

It provides:

- encrypted application-independent ping/pong health checks,
- measured round-trip latency,
- reconnect after process/network loss,
- exponential backoff with configurable jitter,
- a circuit breaker after repeated failures,
- observable `StateFlow<ReliableConnectionState>`,
- access to the currently active `OmniMultiplexedConnection`.

Reserved transport health controls are only available to authenticated peers and do not grant
application capabilities.

## Multiplexed RPC

`OmniMultiplexedConnection` places a single reader on each encrypted socket and dispatches:

- concurrent requests,
- correlated responses,
- events,
- stream chunks,
- transport control messages.

Concurrent sends allocate their encryption sequence number inside the same critical section that writes
the frame. This prevents two writers from reversing sequence order on the TCP stream.

## USB / ADB tunnels

The wire protocol is ordinary authenticated TCP and does not depend on Wi-Fi.

A developer or future desktop companion can carry it through an ADB tunnel without weakening
OmniLink authentication.

Desktop connecting to an Android-side server:

```bash
adb forward tcp:49371 tcp:49371
```

The desktop client can then connect to:

```text
127.0.0.1:49371
```

Android connecting to a desktop-side server can use an ADB reverse tunnel where the device/ADB
environment supports it:

```bash
adb reverse tcp:49371 tcp:49371
```

The ADB tunnel is only transport. OmniLink still performs its normal signed mutual handshake, peer
pinning, encryption and capability authorization inside that tunnel.

## JitPack multi-module coordinates

JitPack supports multi-module Gradle projects. The repository aggregate can still be consumed with the
normal repository coordinate, while individual modules can be selected with the repository-qualified
group and module artifact id.

For consumers that want only the JVM transport, the intended module is:

```text
com.github.obieda-hussien.OmniLinkSDK:omni-link-transport:<tag>
```

For Android consumers, use the Android SDK module or repository aggregate according to the published
JitPack module list for the release.
