rootProject.name = "openfluxandroid"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        // JOGL for JCEF (KCEF, the built-in browser).
        maven("https://jogamp.org/deployment/maven") {
            mavenContent { includeGroupAndSubgroups("org.jogamp") }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// shared/ is an ordinary directory in this repository, vendored from
// OpenFluxClientShared. It carries fixes of its own, so it is deliberately not a
// submodule pinned to a commit in someone else's repository.
// The Windows client. Same shared module, a second front end: one interface,
// one core, two platforms. The directory name matches the module, so there is
// no projectDir line to keep in step with anything.
include(":shared")
include(":androidApp")
include(":OpenFluxPC")
