# Host-Controlled Android App Identity

`HostAppIdentityRegistry` classifies a locally installed Android package from identity facts read
through `PackageManager` and a registry configured by the host application. Do not populate the
registry from a remote capability manifest.

The resolver checks the package name and installed signing identity. For a registered Omni or
partner app, a matching current signer or Android-reported signer lineage is required. The host gets
`OMNI_CORE` only when both the configured host package and its signer match; another package sharing
the host certificate does not become core automatically.

`installerPackageName` is returned as provenance for display or audit. Google Play, Galaxy Store, or
another installer does not make a package official. Likewise, an official APK installed outside a
store may still match the host registry's package and signing identity.

Classification labels are not grants. A `capabilityCeiling` is the maximum set configured for that
registry entry. Each call still needs capability policy, current caller/provider identity, resource
scope, and any required user consent. The identity resolver does not execute Binder calls or grant
access.

Example host policy:

```kotlin
val identityRegistry = HostAppIdentityRegistry(
    hostPackageName = context.packageName,
    hostSignerSha256 = setOf(hostCertificateSha256),
    registrations = listOf(
        HostAppRegistration(
            packageName = "com.omni.notes",
            signerSha256 = setOf(notesCertificateSha256),
            classification = AppClassification.OFFICIAL_OMNI_APP,
            roles = setOf(OmniApplicationRole.CAPABILITY_PROVIDER),
            capabilityCeiling = setOf("notes.search", "notes.read")
        )
    ),
    blockedPackages = setOf("com.example.blocked")
)

val installedFacts = AndroidAppIdentityResolver(context)
    .readInstalledPackage("com.omni.notes")
val identity = installedFacts?.let(identityRegistry::classify)
```

If `readInstalledPackage` returns `null`, the package is absent or unavailable to this app under
Android package-visibility rules. Add a narrowly scoped `<queries>` declaration where appropriate;
do not interpret missing data as a trusted identity.

The policy is host-controlled process configuration. Persisted approvals and capability grants need
separate storage, expiry, revocation, and UI and are not implemented by this registry.
