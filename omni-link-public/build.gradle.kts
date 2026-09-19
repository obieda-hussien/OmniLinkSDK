plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("maven-publish")
}

val omniLinkVersion = providers.gradleProperty("OMNILINK_VERSION").get()

android {
    namespace = "com.omnilink.publicsdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
        buildConfigField("String", "OMNILINK_SDK_VERSION", "\"$omniLinkVersion\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
}

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                groupId = "com.omnilink.sdk"
                artifactId = "omni-link-public"
                version = omniLinkVersion
                from(components["release"])

                pom {
                    name.set("OmniLink Public SDK")
                    description.set("Narrow unprivileged Ask/Share/Open integration surface for third-party Android apps.")
                    url.set("https://github.com/obieda-hussien/OmniLinkSDK")
                }
            }
        }
    }
}
