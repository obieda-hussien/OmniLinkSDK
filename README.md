# OmniLinkSDK
[![](https://jitpack.io/v/obieda-hussien/OmniLinkSDK.svg)](https://jitpack.io/#obieda-hussien/OmniLinkSDK)

This is now its own GitHub repo, not a folder copied between projects. Every satellite app (and Workspace itself) consumes it via JitPack + a version tag. Fed to the agent first, before any of the other five repos.

*   **Latest Release:** [v1.1.0](https://github.com/obieda-hussien/OmniLinkSDK/releases/tag/v1.1.0)
*   **JitPack Artifacts:** [obieda-hussien/OmniLinkSDK](https://jitpack.io/#obieda-hussien/OmniLinkSDK)

## R8 Minification & Shrinking
This SDK is optimized out-of-the-box. It ships with `consumer-rules.pro` to ensure that consumer apps leveraging R8 shrinking will safely preserve the SDK's AIDL stubs and `@Serializable` JSON models while stripping out unused code, significantly reducing the final APK size of the consumer apps without breaking reflection-based JSON parsing.

## Consumption instructions

To consume this library in your Android project:

```kotlin
// settings.gradle.kts (consumer)
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
    }
}

// app/build.gradle.kts (consumer)
dependencies {
    implementation("com.github.obieda-hussien:OmniLinkSDK:v1.1.0")
}
```

Bumping the SDK means: make the change here, tag a new version, then bump the version string in each of the four consumer `build.gradle.kts` files — a visible, reviewable diff instead of a silent copy-paste that's easy to forget.

**Migration note:** JitPack is the current, low-friction way to consume this repo, not the permanent architecture. It builds on demand from GitHub at resolution time, which means occasional slow or failed builds and a dependency on JitPack's own infrastructure staying healthy — acceptable for now, not ideal for something this central once the ecosystem is load-bearing. The concrete long-term target is **GitHub Packages** (not Maven Central — Maven Central's release ceremony, Sonatype account, and GPG signing exist for public library distribution, which this isn't): GitHub Packages publishes directly from the same Actions workflow already built in Phase 5, with no third-party build-on-demand step in between. Migrate once the ecosystem stabilizes; don't block current progress on it.


## Embedded Omni Agent Gateway

OmniLink now supports **bidirectional** integration. Extensions expose capabilities to Workspace through
`IExtensionService`, while trusted same-signer applications can embed the full Omni agent through
`IAgentGatewayService`.

The gateway is intended for IDEs and first-party companion apps that need the real Workspace runtime:
MCP servers, web/deep search, local tools, memory, project context and the Agent Console all remain owned
by Workspace. Clients send typed `AgentTaskRequest` objects and receive ordered `AgentTaskEvent` events.

Security is enforced with the signature-level
`com.omnilink.sdk.permission.BIND_AGENT` permission. Clients must never spoof their package name in the
payload; Workspace resolves Binder UID/package identity itself.

Long-running work is task based. A client starts a task, receives a task id + Workspace history session,
streams status/console/tool/final events, and may cancel or query the latest snapshot after reconnecting.
