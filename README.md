# OmniLinkSDK
[![](https://jitpack.io/v/obieda-hussien/OmniLinkSDK.svg?token=YOUR_JITPACK_TOKEN)](https://jitpack.io/#obieda-hussien/OmniLinkSDK)

This is now its own GitHub repo, not a folder copied between projects. Every satellite app (and Workspace itself) consumes it via JitPack + a version tag. Fed to the agent first, before any of the other five repos.

## R8 Minification & Shrinking
This SDK is optimized out-of-the-box. It ships with `consumer-rules.pro` to ensure that consumer apps leveraging R8 shrinking will safely preserve the SDK's AIDL stubs and `@Serializable` JSON models while stripping out unused code, significantly reducing the final APK size of the consumer apps without breaking reflection-based JSON parsing.

## Consumption instructions (Private Repository)

Because this repository is private, JitPack requires an authentication token to read the source code and build it, and your consumer apps require that same token to download the built artifact.

### Step 1: Generate a GitHub Personal Access Token (PAT)
1. Go to your GitHub Settings -> Developer settings -> Personal access tokens (Classic).
2. Generate a new token with the `repo` scope (Full control of private repositories).
3. Copy this token.

### Step 2: Authorize JitPack
1. Go to [JitPack.io](https://jitpack.io/) and log in with your GitHub account.
2. In the JitPack UI, on the left sidebar, click on **Private Repos**.
3. Paste your GitHub PAT there and click **Authorize**. JitPack will now be able to see and build `obieda-hussien/OmniLinkSDK`.

### Step 3: Configure Consumer Apps securely
Do **NOT** hardcode your PAT in your `build.gradle.kts` files. Pass it via an environment variable or `local.properties`.

**In `local.properties` (or CI environment variables):**
```properties
authToken=YOUR_GITHUB_PAT
```

**In `settings.gradle.kts` (Consumer App):**
```kotlin
import java.util.Properties

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}
val jitpackAuthToken = System.getenv("authToken") ?: localProperties.getProperty("authToken")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://jitpack.io")
            credentials { username = jitpackAuthToken }
        }
    }
}
```

**In `app/build.gradle.kts` (Consumer App):**
```kotlin
dependencies {
    implementation("com.github.obieda-hussien:OmniLinkSDK:v1.0.0")
}
```

Bumping the SDK means: make the change here, tag a new version, then bump the version string in each of the four consumer `build.gradle.kts` files — a visible, reviewable diff instead of a silent copy-paste that's easy to forget.

**Migration note:** JitPack is the current, low-friction way to consume this repo, not the permanent architecture. It builds on demand from GitHub at resolution time, which means occasional slow or failed builds and a dependency on JitPack's own infrastructure staying healthy — acceptable for now, not ideal for something this central once the ecosystem is load-bearing. The concrete long-term target is **GitHub Packages** (not Maven Central — Maven Central's release ceremony, Sonatype account, and GPG signing exist for public library distribution, which this isn't): GitHub Packages publishes directly from the same Actions workflow already built in Phase 5, with no third-party build-on-demand step in between. Migrate once the ecosystem stabilizes; don't block current progress on it.
