# OmniLinkSDK 3.0 — Integration Order

This filename is retained for compatibility with older planning links. The old prompt index and
consumer-specific phase list are retired.

For API examples and detailed integration patterns, use
[INTEGRATION_GUIDE.md](INTEGRATION_GUIDE.md).

## Choose the trust class first

| Consumer | Surface | Starting authority |
|---|---|---|
| Official Omni Android app, same shared signer | Android SDK + Binder | FIRST_PARTY |
| Official Omni app talking to a PC | Encrypted transport | paired, then explicit FIRST_PARTY |
| Known external partner with its own signer | Public Gateway or encrypted transport | TRUSTED_PARTNER only after approval |
| Unknown third-party app | Public Gateway | chat-only |
| Unknown device explicitly paired | Encrypted transport | PAIRED hard chat sandbox |
| Non-integrated installed app Omni wants to control | External App Bridge | no privileged inbound authority |

## First-party Android rollout

1. Configure the shared Omni debug and release signing identities.
2. Add the 3.0 Android module after the release artifact is verified:
   ```kotlin
   implementation(
       "com.github.obieda-hussien.OmniLinkSDK:omni-link-sdk:v3.0.0"
   )
   ```
3. Decide whether the app is an extension provider, caller, Agent Gateway client, or several of these.
4. Add only the signature uses-permissions the app actually calls.
5. Protect privileged exported services with the matching OmniLink signature permission.
6. Define semantic capabilities before implementation.
7. Choose IMMEDIATE only for tiny non-blocking work; use ASYNC for I/O and JOB for long-running work.
8. Assign truthful trust, direction, risk, idempotency and DataScope metadata.
9. Add confirmation / dry-run / preview-commit for sensitive side effects.
10. Test denial paths, process death, oversized payloads and minified release builds.
11. Add PC transport only when the app actually needs cross-device communication.

Same signer establishes first-party identity. It does not merge application sandboxes or grant every
capability.

## Trusted-partner rollout

Do not share the Omni signing private key with a partner.

If the partner only needs to share content, ask questions, search or summarize, use the Public Gateway.

If structured bidirectional communication is needed, use the pure transport module:

```kotlin
implementation(
    "com.github.obieda-hussien.OmniLinkSDK:omni-link-transport:v3.0.0"
)
```

Pair the peer, verify the code/fingerprint, then store a TRUSTED_PARTNER record with separate,
explicit inbound and outbound ACLs.

A partner trust rule in application code does not bypass Android's signature-permission gate.
Differently-signed partners are therefore not privileged Binder peers by default in 2.0.

## Unknown third-party rollout

The default path is the Public Gateway:

```text
SHARE_TO_OMNI
ASK_OMNI
OPEN_OMNI
```

The host should force unknown callers into CHAT-only behavior. Do not expose AGENT, TEAM, private
memory, terminal, Shizuku, root, project modification or autonomous privileged cross-app execution.

If the user explicitly pairs a network peer, start with the PAIRED chat sandbox. OmniLink 2.0 enforces
that ceiling inside the transport policy even if a stored ACL accidentally contains a wildcard.

## Desktop rollout

1. Depend on the transport-only module.
2. Generate one stable desktop identity and persist it with encrypted identity storage.
3. Keep the public peer trust store separate from private key storage.
4. Reject unknown peers outside an explicit pairing flow.
5. Prefer OmniMultiplexedConnection for normal concurrent RPC/events.
6. Prefer OmniReliableClient for a long-lived outgoing connection.
7. Use explicit capability ACLs.
8. Test peer-key mismatch, network loss, reconnect, heartbeat timeout and concurrent requests.
9. Treat LAN membership and ADB connectivity as transport only, never as trust.

## OmniLink 3.0 release order

1. Pass Android/JVM tests, Maven publication, CodeQL and dependency review on the integrated PR.
2. Merge the reviewed SDK PR into `main`; the release workflow creates `v3.0.0` from the merged commit.
3. Verify the exact tag SHA, GitHub release and all JitPack module artifacts.
4. Update Workspace, AndroidIDE and CI staging scripts in separate consumer PRs.
5. Build and test actual APKs. The SDK does not automatically install its host consent UI or adapters.

## Canonical documentation

- [README.md](README.md)
- [INTEGRATION_GUIDE.md](INTEGRATION_GUIDE.md)
- [LINK_PROTOCOL.md](LINK_PROTOCOL.md)
- [SIGNING_TRUST.md](SIGNING_TRUST.md)
- [OMNILINK_TRUST_MESH.md](OMNILINK_TRUST_MESH.md)
- [DESKTOP_TRANSPORT.md](DESKTOP_TRANSPORT.md)
- [ARCHITECTURE_EVOLUTION.md](ARCHITECTURE_EVOLUTION.md)
- [CHANGELOG.md](CHANGELOG.md)
