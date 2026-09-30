import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    // No version here: the shared module already put these on the build
    // classpath, and a second declaration of a plugin that is already loaded
    // fails rather than being ignored.
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

/** -PappVersion=2.0.0, the same property the Android app and the release use. */
val appVersion = (findProperty("appVersion") as String?)?.removePrefix("v")?.takeIf { it.isNotBlank() } ?: "2.0.0"

/**
 * The Go core for this OS, built by scripts/build-pc-core.sh into
 * resources/windows. It is a build product, not source: a 20 MB binary has no
 * business in git, and the one in the tree would drift from the code next to
 * it without anyone noticing.
 */
val coreDir = layout.projectDirectory.dir("resources/windows")
val coreExe = coreDir.file("openflux-windows-amd64.exe")

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    // Brings the platform's Compose artifacts, and with them the Skiko native
    // runtime that actually draws the window. Without it the app builds and
    // then dies at launch with UnsatisfiedLinkError on skiko-windows-x64.dll:
    // the jvm-side Skiko jar is already on the classpath, so the missing piece
    // is not obvious until the first run.
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.materialIconsExtended)
    implementation(libs.compose.components.resources)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
}

/** Version as a constant, so nothing has to parse it out of a resource at runtime. */
val generateVersion by tasks.registering {
    val out = layout.buildDirectory.file("generated/pc/kotlin/io/openflux/pc/Version.kt")
    val value = appVersion
    inputs.property("version", value)
    outputs.file(out)
    doLast {
        out.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                """
                package io.openflux.pc

                /** Written by the build from -PappVersion; keeps one source of truth. */
                object Version {
                    const val VALUE: String = "$value"
                }
                """.trimIndent() + "\n"
            )
        }
    }
}

kotlin.sourceSets["main"].kotlin.srcDir(generateVersion.map { layout.buildDirectory.dir("generated/pc/kotlin").get().asFile })

/** Fails the build rather than shipping an app that cannot connect. */
val checkCore by tasks.registering {
    doLast {
        if (!coreExe.asFile.isFile) {
            throw GradleException(
                "Ядро не собрано: ${coreExe.asFile}\n" +
                "Соберите его: bash scripts/build-pc-core.sh"
            )
        }
    }
}

compose.desktop {
    application {
        mainClass = "io.openflux.pc.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "OpenFlux"
            packageVersion = appVersion
            description = "OpenFlux VPN client"
            // The core is not a JVM module: it ships as a file next to the app.
            modules("java.sql", "java.naming")
        }
    }
}

/**
 * Puts the core where the app looks for it.
 *
 * CoreBinary reads `compose.application.resources.dir`, which a packaged app
 * sets to its own resources folder. Copying into it after the distributable is
 * written is what makes the difference between an app that opens and an app
 * that can actually connect - a missing core fails at the first connect, not
 * at launch, so the window would look perfectly healthy.
 */
fun File.copyCoreInto(resources: File) {
    resources.mkdirs()
    coreDir.asFile.listFiles()
        ?.filter { it.isFile }
        ?.forEach { it.copyTo(File(resources, it.name), overwrite = true) }
}

tasks.matching { it.name == "createDistributable" }.configureEach {
    dependsOn(checkCore)
    doLast {
        val app = layout.buildDirectory.dir("compose/binaries/main/app/OpenFlux").get().asFile
        coreDir.asFile.copyCoreInto(File(app, "resources/windows"))
        logger.lifecycle("Ядро уложено рядом с приложением: ${File(app, "resources/windows")}")
    }
}

/**
 * A dev run has no packaged resources folder, so point it at the source one.
 *
 * Matched by type rather than by name: the compose plugin's run task is a
 * JavaExec, but naming it as a plain task leaves systemProperty unresolved.
 */
tasks.withType<JavaExec>().configureEach {
    if (name == "run") {
        dependsOn(checkCore)
        systemProperty("compose.application.resources.dir", coreDir.asFile.absolutePath)
    }
}

tasks.named("processResources") { dependsOn(checkCore) }
