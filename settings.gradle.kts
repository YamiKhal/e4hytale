plugins {
    // Downloads the Java 25 toolchain automatically when it isn't installed locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://maven.hytale.com/release") {
            name = "HytaleRelease"
            content { includeGroup("com.hypixel.hytale") }
        }
        maven("https://maven.hytale.com/pre-release") {
            name = "HytalePreRelease"
            content { includeGroup("com.hypixel.hytale") }
        }
    }
}

rootProject.name = "e4hytale"

// common: tunnel wire format + host-side tunnel client (plain Java, shared)
// relay:  the public relay server (standalone app, Docker image for Coolify)
// mod:    the Hytale plugin that opens the tunnel from a hosted world or dedicated server
include("common", "relay", "mod")
