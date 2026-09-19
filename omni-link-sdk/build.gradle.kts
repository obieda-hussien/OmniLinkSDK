plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("maven-publish")
}

val omniLinkVersion = providers.gradleProperty("OMNILINK_VERSION").get()

android {
    namespace = "com.omnilink.sdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "OMNILINK_SDK_VERSION", "\"$omniLinkVersion\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    api(project(":omni-link-transport"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                groupId = "com.omnilink.sdk"
                artifactId = "omni-link-sdk"
                version = omniLinkVersion
                from(components["release"])

                pom {
                    name.set("OmniLinkSDK Android")
                    description.set("Privileged Android IPC, trust, capability, and transport adapters for the Omni ecosystem.")
                    url.set("https://github.com/obieda-hussien/OmniLinkSDK")

                    licenses {
                        license {
                            name.set("Omni Reference Source License 1.0")
                            url.set("https://github.com/obieda-hussien/OmniLinkSDK/blob/main/LICENSE")
                            distribution.set("repo")
                        }
                    }

                    developers {
                        developer {
                            id.set("obieda-hussien")
                            name.set("Abdelrahman Hussein (عبدالرحمن حسين)")
                        }
                    }

                    scm {
                        url.set("https://github.com/obieda-hussien/OmniLinkSDK")
                        connection.set("scm:git:https://github.com/obieda-hussien/OmniLinkSDK.git")
                        developerConnection.set("scm:git:ssh://git@github.com/obieda-hussien/OmniLinkSDK.git")
                    }
                }
            }
        }
    }
}
