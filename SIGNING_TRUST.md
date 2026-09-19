# Omni Ecosystem Signing & Trust Model

## Goal

Every first-party Omni APK is signed by the same long-lived release key. Android can then enforce
signature-level permissions before Binder reaches application code.

The signing certificate establishes **identity**. It does **not** establish unlimited authority.

> Certificate establishes identity. Capabilities establish authority. The user establishes consent.

## First-party topology

All first-party APKs should use the same release keystore:

- OmniDev Workspace
- Omni AndroidIDE integration/app
- Omni Launcher
- Omni Note
- Omni Memoria
- Omni Equalizer
- Omni PriceWatch
- future first-party Omni applications

Debug builds should likewise share one Omni debug keystore so debug-to-debug integration behaves the
same way during development.

The AAR itself is not an installed Android identity. The final consuming APK is what Android signs and
authenticates.

## Generate the keys once

Run:

```bash
pkg install openjdk-17
bash generate_omni_shared_keystore.sh
```

The script creates:

```text
~/omni-ecosystem-keys/
  omni-release.keystore
  omni-debug.keystore
```

Do not regenerate the release key for each app. Do not commit it to Git.

## Gradle signing

Each first-party application's `app/build.gradle.kts` should point to the same keystore through
environment variables:

```kotlin
android {
    signingConfigs {
        create("omniRelease") {
            storeFile = file(System.getenv("OMNI_RELEASE_KEYSTORE"))
            storePassword = System.getenv("OMNI_RELEASE_STORE_PASSWORD")
            keyAlias = System.getenv("OMNI_RELEASE_KEY_ALIAS") ?: "omni_ecosystem_key"
            keyPassword = System.getenv("OMNI_RELEASE_KEY_PASSWORD")
        }

        getByName("debug") {
            storeFile = file(System.getenv("OMNI_DEBUG_KEYSTORE"))
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("omniRelease")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}
```

## GitHub Actions

Never commit a keystore. Store the release keystore as base64 plus its passwords in repository or
organization Actions secrets:

- `OMNI_RELEASE_KEYSTORE_BASE64`
- `OMNI_RELEASE_STORE_PASSWORD`
- `OMNI_RELEASE_KEY_PASSWORD`
- `OMNI_RELEASE_KEY_ALIAS`

A workflow reconstructs the file only for the job:

```bash
echo "$OMNI_RELEASE_KEYSTORE_BASE64" | base64 --decode > "$RUNNER_TEMP/omni-release.keystore"
```

Then expose `OMNI_RELEASE_KEYSTORE=$RUNNER_TEMP/omni-release.keystore` to Gradle.

For a multi-repository Omni ecosystem, organization-level secrets are preferable when available:
one signing source, no copied plaintext passwords.

## Runtime trust

OmniLink 1.3 is fail-closed by default:

```kotlin
open val securityValidator: SecurityValidator = SameSignerSecurityValidator()
```

This means a first-party Omni app signed by the same key can reach the privileged extension surface.
A random app signed by another key cannot.

The resolver then assigns one of:

- `CORE`: explicitly named Omni core package + same signer.
- `FIRST_PARTY`: same signer.
- `TRUSTED_PARTNER`: explicitly allowlisted external certificate with explicit capability allowlist.
- `UNTRUSTED`: everything else.

Same signer does not mean "read everything". Every capability still has trust/risk/data-scope metadata
and the service's `AccessController` remains authoritative.

## Foreign applications

Foreign applications must not receive `BIND_AGENT` or `BIND_EXTENSION`.

Omni may still interact outward with them through the External App Bridge using the lowest-privilege
available adapter:

1. native public API,
2. Intent/deep link,
3. MediaSession,
4. notification action,
5. Accessibility,
6. Shizuku/shell,
7. root where the user explicitly granted it.

That direction is conceptually Omni -> foreign app. It does not grant the foreign app privileged Binder
access back into Omni.

For useful inbound interoperability, Workspace may expose the separate
`ACTION_PUBLIC_OMNI_REQUEST` share/ask/open Intent. It is intentionally narrow, size-limited,
sanitized, and subject to user/policy confirmation.

## Key rotation and partners

`SignatureSecurityValidator` supports an explicit SHA-256 allowlist and considers Android signing
certificate history on Android P+, making it suitable for controlled key rotation and partner rules.

Never distribute the Omni private signing key to a partner. Partners keep their own key and receive
only the capabilities explicitly allowlisted for their certificate.

## Migration warning

An installed APK can normally be updated only by an APK accepted under its signing lineage. If an
existing Omni app was previously signed with another unrelated key, moving it to the shared key may
require uninstall/reinstall in development unless a supported signing-key upgrade path already exists.

Back up the release key and credentials outside the development phone. Losing the release signing key
can prevent normal updates to installed builds that depend on it.
