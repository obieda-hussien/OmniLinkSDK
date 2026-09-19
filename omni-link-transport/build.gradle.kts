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
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
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
        }
    }
}
