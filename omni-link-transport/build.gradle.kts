plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("maven-publish")
}

val omniLinkVersion = providers.gradleProperty("OMNILINK_VERSION").get()

group = "com.omnilink.sdk"
version = omniLinkVersion

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("transport") {
            groupId = "com.omnilink.sdk"
            artifactId = "omni-link-transport"
            version = omniLinkVersion
            from(components["java"])

            pom {
                name.set("OmniLink Transport")
                description.set("Authenticated encrypted JVM transport for OmniLink Android/Desktop communication.")
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
