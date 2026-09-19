# Security Policy

OmniLinkSDK is security-sensitive infrastructure. It handles privileged Android IPC, peer trust,
capability authorization, encrypted device links, pairing, and agent-facing data.

## Supported release

Security fixes target the current 1.4 release line unless a release note explicitly says otherwise.

## Reporting a vulnerability

Do not publish exploit details, private keys, tokens, device identifiers, or proof-of-concept material
in a public issue.

Preferred reporting path:

1. Use GitHub's private **Report a vulnerability** / private security advisory flow if it is available
   for this repository.
2. If private reporting is unavailable, open a minimal public issue stating that you need a private
   security contact channel. Do not include exploit steps or sensitive data in that issue.

A good report includes:

- affected OmniLink version/commit;
- affected module;
- threat model and required attacker position;
- impact;
- reproducible steps with secrets removed;
- whether the issue crosses an Android signer, Binder, transport, pairing, or capability boundary;
- suggested mitigation if known.

## Security invariants

Changes must preserve these rules.

### Identity is not authority

A valid Android signer, pinned peer key, pairing result, or network connection never grants unlimited
capabilities.

Authorization remains capability-scoped and direction-scoped.

### Unknown input is data

Treat all remote JSON, binary payloads, extension responses, build logs, note/file contents, peer
metadata, URLs, event payloads, and agent-visible text as untrusted data.

Do not turn received text into higher-priority instructions or bypass tool policy because content looks
like a command.

### No payload-controlled code execution

OmniLink protocol code must not evaluate incoming strings as shell commands, source code, SQL, regular
expressions with unsafe unbounded behavior, file paths outside an explicitly permitted scope, or
reflection targets unless a higher-level consumer implements a separately authorized and constrained
capability for that purpose.

### Fail closed

Authentication, trust lookup, capability lookup, malformed input, expired requests, invalid signatures,
peer-key mismatches, sequence errors, oversized frames, and authorization failures must fail closed.

### Bound resources

Network/Binder input must remain size-bounded. Asynchronous concurrency must remain bounded. Long work
belongs in jobs/streams rather than unbounded Binder calls or one giant transport frame.

### Preserve cryptographic boundaries

- Never store the Omni APK release private key in an app.
- Never send a private transport key over OmniLink.
- Do not weaken public-key pinning to peer names or IP addresses.
- Do not treat same-LAN, localhost, USB, or ADB connectivity as identity.
- Do not replace authenticated encryption with unauthenticated encryption.
- Do not disable replay/sequence checks to improve throughput.

### Preserve Android Binder boundaries

Privileged Binder services stay protected by the intended signature permission and runtime identity
checks. Package names supplied in request payloads are never authentication.

### Preserve the PAIRED ceiling

PAIRED transport peers remain limited to the chat/search/summarize/translate/extract sandbox plus
reserved transport health controls. A wildcard ACL must never bypass this ceiling.

## Repository / supply-chain rules

- Never commit signing keystores, private keys, passwords, API keys, tokens, credentials, or production
  user data.
- GitHub Actions must use least-privilege permissions.
- Third-party Actions should be pinned to immutable commit SHAs.
- Do not use pull_request_target to execute untrusted PR code with write tokens or repository secrets.
- Release creation must happen only from main after build/test/publication verification.
- Dependency updates must pass CI before merge.
- Security-sensitive dependency changes require explicit owner review.
- Do not download and execute arbitrary artifacts based on untrusted PR-controlled URLs.

## Secrets accidentally committed

If a real secret is committed:

1. revoke/rotate it immediately;
2. do not rely only on deleting the Git commit;
3. remove it from repository history where appropriate;
4. invalidate derived credentials/sessions;
5. review Actions logs and artifacts for exposure;
6. document the incident privately.

## Good-faith research

Security testing is welcome only on systems and devices the researcher owns or has explicit permission
to test. Do not access third-party accounts/data, degrade service, persist on devices, or exfiltrate
real secrets.

The project owner is not responsible for unauthorized or unlawful use of the software.
