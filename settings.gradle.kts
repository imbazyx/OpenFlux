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
include(":shared")
include(":androidApp")

// The Windows client lives outside this repository so that the Android build
// never sees it, but it is the same code: one shared module, two front ends.
//
// Conditional on purpose. Pointing at a directory outside the repository would
// break the build for anyone who clones it without also having that directory
// beside it, and a broken checkout fails the person who did nothing wrong.
val pcAppDir = file("../OpenFluxPC")
if (pcAppDir.isDirectory) {
    include(":pcApp")
    project(":pcApp").projectDir = pcAppDir
}
