# OmniLinkSDK
This is now its own GitHub repo, not a folder copied between projects. Every satellite app (and Workspace itself) consumes it via JitPack + a version tag. Fed to the agent first, before any of the other five repos.

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
    implementation("com.github.obieda-hussien:OmniLinkSDK:v1.0.0")
}
```

Bumping the SDK means: make the change here, tag a new version, then bump the version string in each of the four consumer `build.gradle.kts` files — a visible, reviewable diff instead of a silent copy-paste that's easy to forget.

**Migration note:** JitPack is the current, low-friction way to consume this repo, not the permanent architecture. It builds on demand from GitHub at resolution time, which means occasional slow or failed builds and a dependency on JitPack's own infrastructure staying healthy — acceptable for now, not ideal for something this central once the ecosystem is load-bearing. The concrete long-term target is **GitHub Packages** (not Maven Central — Maven Central's release ceremony, Sonatype account, and GPG signing exist for public library distribution, which this isn't): GitHub Packages publishes directly from the same Actions workflow already built in Phase 5, with no third-party build-on-demand step in between. Migrate once the ecosystem stabilizes; don't block current progress on it.
