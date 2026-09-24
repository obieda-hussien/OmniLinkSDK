# Authorized external execution (3.0 work in progress)

`AuthorizedExternalAppExecutor` joins the route planner and the capability grant ledger at one
opt-in execution boundary. The host supplies a `VerifiedAppPairResolver` that reads the current
installed package signing identities, and host-owned `ExternalAppOperationAdapter` implementations
whose operations and authorization were verified independently. An app's own manifest or response
must not be treated as an authorization verdict.

For each call, the executor requires an exact operation and provider package match, checks both
identities, selects one permitted route, obtains host UI confirmation if the route is destructive
or needs consent, checks identities again, consumes or checks an exact scoped grant, and invokes
only the selected adapter. Missing consent defaults to rejection. It never retries with a more
privileged adapter after denial or execution failure. A one-time grant is consumed even if the
adapter fails; retrying requires another user decision.

The host still owns the permission prompt, adapter implementation, Android permission checks,
execution audit, and error reporting. In-flight operations must cooperate with cancellation and
check revocation at their own irreversible commit point; this class checks a grant before entry,
not during the adapter's work. This API does not automatically wire into `ExtensionService` or
Omni Dev Workspace yet, and it does not change the released 2.0.1 artifact.
