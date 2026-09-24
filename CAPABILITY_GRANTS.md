# Fine-Grained Capability Grants

`CapabilityGrantLedger` evaluates a grant only for an exact requester identity, provider identity,
capability, resource scope, purpose, data scope, and risk. Callers must rebuild both principals from
current local identity verification for every authorization check. The ledger data model does not
authenticate identities; the host must construct principals from installed package signer facts and
must never construct them from request JSON.

## Decisions

- `THIS_ACTION` requires an action ID and allows that action once.
- `THIS_CAPABILITY_ONCE` binds to one request ID and consumes the grant before allowing it.
- `THIS_SESSION` requires a session ID, expires after at most 24 hours, and can be removed immediately
  with `endSession`.
- `ALWAYS_FOR_THIS_CAPABILITY_AND_SCOPE` is stored only by a persistence implementation that reports
  durable commits. It remains bound to exact identities, capability, scope, purpose, data scopes, and
  risk. It can be reviewed and revoked through the ledger API.
- `DENY` records a short-lived denial for the current action/request; it replaces grants for that
  exact capability and scope.
- `BLOCK` blocks the requester/provider pair across capabilities. It requires durable storage and
  remains until explicit unblock or identity-wide revocation.

Resource scopes are exact identifiers. The global scope must be declared with `global = true`; wildcard
matching is not supported. One-time approvals and denials expire after ten minutes. Expired session
grants are capped at 24 hours.

The host must generate non-repeating request, action, and session IDs and keep session IDs scoped to
the authenticated local session. The approval context binds the exact purpose, `DataScope` set, and
risk as well as the capability/resource scope; a later request with changed consent details does not
reuse the stored decision. Principal signer sets are canonicalized, so signing identity changes do
not inherit a prior grant.

## Android storage

Use `AndroidEncryptedCapabilityGrantPersistence` for host-owned persistent decisions on Android 6.0
(API 23) and newer. It encrypts the snapshot with an AES-GCM key stored in Android Keystore and binds
the ciphertext to the app package and preference name. Writes use synchronous `commit()` so a grant
is never used before its durable write succeeds. Run ledger operations on a worker thread.

If stored data is corrupt, the Keystore key is unavailable, or a write fails, the ledger fails closed.
The caller should surface that state and require the user to review/recreate grants; do not replace an
unreadable store with an empty successful store silently. On Android versions below API 23, persistent
grants are unavailable; one-time and session grants can use an in-memory persistence implementation,
but `ALWAYS` and `BLOCK` decisions are rejected there. After the user reviews the loss of grants, the
host may call `resetAfterUserReview()` and construct a new ledger; the SDK never resets corrupted state
automatically. Snapshot and resource counts are bounded to limit storage growth.

The ledger emits revoked grant IDs after explicit revocation, session end, expiry, replacement, and
blocking. Hosts should connect this listener to active capability sessions/tokens so revocation takes
effect immediately. Authorization checks still need to occur at the provider's execution boundary;
recording a user decision does not itself execute an operation.
