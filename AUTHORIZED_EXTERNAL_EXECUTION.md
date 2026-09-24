# Authorized external execution in OmniLink 3.0

`AuthorizedExternalAppExecutor` joins the route planner and the capability grant ledger at one
opt-in execution boundary. The host can use `HostVerifiedAppPairResolver.android(context, target,
registry)` to read current installed-package signers on each call, and supplies host-owned
`ExternalAppOperationAdapter` implementations
whose operations and authorization were verified independently. An app's own manifest or response
must not be treated as an authorization verdict.

For each call, the executor requires an exact operation and provider package match, checks both
identities, selects one permitted route, obtains host UI confirmation if the route is destructive
or needs consent, checks identities again, consumes or checks an exact scoped grant, and invokes
only the selected adapter. Missing consent defaults to rejection. It never retries with a more
privileged adapter after denial or execution failure. A one-time grant is consumed even if the
adapter fails; retrying requires another user decision.

`CapabilityConsentCoordinator` prepares an exact prompt and records the user's choice through a
host-owned foreground `CapabilityConsentPresenter`. For lasting grants use
`AndroidEncryptedCapabilityGrantPersistence`; the in-memory store refuses permanent decisions.
`TrustedCapabilityDiscovery` binds only services accepted by `TrustedServiceResolver`, reads a
bounded manifest, and rechecks the service identity after the reply. Manifest contents do not
grant authority. The host must still verify each actual execution through its own access policy.

The host still owns the UI, adapter implementation, Android permission checks, execution audit,
and error reporting. In-flight operations must cooperate with cancellation and check revocation
at their irreversible commit point; the executor checks a grant before entry, not during the
adapter's work. This API does not automatically wire into `ExtensionService` or Omni Dev Workspace.
Verify the `v3.0.0` published artifacts before updating consumers.
