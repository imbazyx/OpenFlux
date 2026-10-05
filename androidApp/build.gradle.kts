plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

/**
 * The app version, from the single `appVersion` in the root gradle.properties.
 *
 * Not written here on purpose: the Android app and the Windows client are
 * released together from one page, and two hand-maintained copies of the same
 * number is exactly how they end up disagreeing.
 */
val appVersion = providers.gradleProperty("appVersion").orNull?.removePrefix("v")?.takeIf { it.isNotBlank() }
    ?: error("appVersion is not set - it belongs in the root gradle.properties")

/** 1.2.3 -> 10203: grows with every release as Android requires. */
val appVersionCode = appVersion.split('.', '-').take(3).map { it.toIntOrNull() ?: 0 }
    .let { (it + listOf(0, 0, 0)).take(3) }.let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }

/** The core the gomobile library was built from, written by scripts/build-android-core.sh. */
val coreVersion = file("libs/openflux-core.version").takeIf { it.isFile }?.readText()?.trim() ?: "встроенное"

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "io.openflux.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        // Its own id: installs beside the Java app (io.openflux.app).
        applicationId = "io.openflux.client"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = appVersionCode
        versionName = appVersion
        buildConfigField("String", "CORE_VERSION", "\"$coreVersion\"")
    }

    buildFeatures {
        buildConfig = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }

    signingConfigs {
        create("release") {
            val keystore = System.getenv("ANDROID_KEYSTORE_FILE")
            if (!keystore.isNullOrBlank()) {
                storeFile = file(keystore)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // The Go core loads as a plain .so; extracting it keeps startup simple.
        jniLibs.useLegacyPackaging = true
    }
}

/**
 * Refuse to build a release APK without a key.
 *
 * Without one the signing config is not attached, the APK comes out unsigned,
 * and it installs nowhere - an error the user sees on their phone and nothing
 * in the build output explains. That is a release-day surprise, so it is
 * caught here instead.
 *
 * Checked against the task graph rather than thrown during configuration,
 * because configuration also happens for an unrelated task: wsl-build.sh runs
 * :shared:jvmTest before it exports the signing variables, and a throw in
 * here would take that down too.
 */
gradle.taskGraph.whenReady {
    val wantsReleaseApk = allTasks.any { it.name.contains("Release") && it.name.contains("Apk") }
    if (wantsReleaseApk && android.signingConfigs.getByName("release").storeFile == null) {
        throw GradleException(
            "Нет ключа подписи: задайте ANDROID_KEYSTORE_FILE, " +
                "ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD"
        )
    }
}

dependencies {
    implementation(project(":shared"))
    // The OpenFlux core (gomobile), built by scripts/build-android-core.sh.
    implementation(files("libs/openflux.aar"))
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.components.resources)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core)
    implementation(libs.zxing.android)
    // Unit tests only - not packaged into the APK. Without this the Android
    // layer had no way to be tested at all, and its defects were visible only
    // by reading.
    testImplementation(kotlin("test"))
}
