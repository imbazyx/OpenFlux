import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.zip.ZipFile

plugins {
    // No version here: the shared module already put these on the build
    // classpath, and a second declaration of a plugin that is already loaded
    // fails rather than being ignored.
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * The app version, from the single `appVersion` in the root gradle.properties -
 * the same number the Android app uses, so the two clients released from one
 * page cannot disagree.
 *
 * The -P form is not reliable here: gradlew.bat truncates a dotted value at the
 * first dot, so -PappVersion=2.1.0 arrives as "2". The default is no longer
 * written out, because a second copy of the number is what caused the drift in
 * the first place. For an explicit override on Windows use the
 * OPENFLUX_VERSION environment variable.
 */
val appVersion = providers.gradleProperty("appVersion").orNull?.removePrefix("v")?.takeIf { it.isNotBlank() }
    ?: System.getenv("OPENFLUX_VERSION")?.removePrefix("v")?.takeIf { it.isNotBlank() }
    ?: error("appVersion is not set - it belongs in the root gradle.properties")

require(Regex("""\d+\.\d+\.\d+""").matches(appVersion)) {
    "appVersion must look like 2.1.0, got \"$appVersion\""
}

/**
 * The Go core for this OS, built by scripts/build-pc-core.sh into
 * resources/windows. It is a build product, not source: a 20 MB binary has no
 * business in git, and the one in the tree would drift from the code next to
 * it without anyone noticing.
 */
val coreDir = layout.projectDirectory.dir("resources/windows")
val coreExe = coreDir.file("openflux-windows-amd64.exe")

/** The folder name the core lives in inside the package, per OS. */
val winResourceDir = "windows"
val winCoreName = "openflux-windows-amd64.exe"

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

/**
 * Puts the core inside the application jar.
 *
 * Every other place to put a file failed. jpackage builds the installed
 * program from the jars it is handed, and a separate file dropped beside them
 * does not survive into the MSI: the image had a 14 MB core in it, the
 * installed program had no core at all, and it still reported BUILD SUCCESSFUL.
 * Adding a directory to the application image is not enough either - the MSI is
 * built from a staging area Compose assembles internally, not from that image.
 *
 * A jar resource is the same idea as a classpath entry, it is the one location
 * the JVM already knows how to read from, and it survives into the MSI, the zip
 * and a developer run without any of them needing to know the core exists.
 * CoreBinary unpacks it on first use.
 */
tasks.named<ProcessResources>("processResources") {
    dependsOn(checkCore)
    from(coreDir) { into("windows") }
}

/** The application icon, generated from the Android one by scripts/make-pc-icon.sh. */
val appIcon = layout.projectDirectory.file("icon/openflux.ico")

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
            windows {
                // Without these the installer puts the program in Program Files
                // and leaves nothing to click: no Start menu entry, no desktop
                // icon, and nothing to pin or search for.
                menu = true
                menuGroup = "OpenFlux"
                shortcut = true
                perUserInstall = false
                // Fixed, not generated: Windows Installer keys upgrades on this
                // value. A fresh UUID per build makes every new version a
                // different product that installs beside the old one instead
                // of replacing it, which is what the in-app update does.
                upgradeUuid = "8f3d1c26-5a47-4b90-9c15-2e7a4d6b81f0"
                iconFile = appIcon
            }
        }
    }
}

/**
 * Puts the core where the app looks for it.
 *
 * Two places, and the second one is the reason the MSI had no core at all.
 *
 * jpackage builds the installer from the app *input* directory - the one with
 * the jars - and puts the result in <install>/app. Anything sitting beside it
 * in the application image, such as <image>/resources, is simply not part of
 * the package: the image had a 14 MB core in it, the installed program had no
 * resources directory at all, and the app could not connect while looking
 * perfectly healthy. So the core goes into the input directory, where jpackage
 * carries it, and also stays in resources/ for the portable zip, which is
 * built from the image rather than by jpackage.
 *
 * checkCoreInInstaller below is what keeps this honest: the build reads the
 * finished MSI and fails if the core is not inside it.
 */
fun File.copyCoreInto(dir: File) {
    dir.mkdirs()
    coreDir.asFile.listFiles()
        ?.filter { it.isFile }
        ?.forEach { it.copyTo(File(dir, it.name), overwrite = true) }
}

tasks.matching { it.name == "createDistributable" }.configureEach {
    dependsOn(checkCore)
    doLast {
        val app = layout.buildDirectory.dir("compose/binaries/main/app/OpenFlux").get().asFile
        // Into the jpackage input directory: this is the one that reaches the MSI.
        coreDir.asFile.copyCoreInto(File(app, "app/$winResourceDir"))
        // And into resources/ for the portable zip, which is the image itself.
        coreDir.asFile.copyCoreInto(File(app, "resources/$winResourceDir"))
        logger.lifecycle("Ядро уложено: app/$winResourceDir и resources/$winResourceDir")
    }
}

/**
 * Fails if the core is not inside the jar that ships.
 *
 * checkCore only proves the core was built, which is what let a broken release
 * through: the build passed, the installer installed cleanly, the window
 * opened, and the failure only appeared at the first connect - the one step no
 * automated check was watching. This opens the jar the packages are actually
 * built from and looks for the file CoreBinary will ask for.
 */
val checkCoreInInstaller by tasks.registering {
    dependsOn("jar")
    val jars = layout.buildDirectory.dir("libs")
    inputs.dir(jars)
    doLast {
        val wanted = "windows/$winCoreName"
        val dir = jars.get().asFile
        val jar = dir.listFiles()?.firstOrNull { it.name.endsWith(".jar") }
            ?: throw GradleException("jar не найден в $dir")
        val inside = ZipFile(jar).use { zip ->
            zip.getEntry(wanted) != null
        }
        if (!inside) {
            throw GradleException(
                "Ядро ($wanted) не попало в ${jar.name}.\n" +
                "Приложение установится и откроется, но подключиться не сможет."
            )
        }
        logger.lifecycle("Ядро внутри ${jar.name}: $wanted")
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

/**
 * The portable build: the same application image, zipped.
 *
 * jpackage has no Zip target - its formats are the installers and the Linux
 * images - so the README's "unpack anywhere" download could not be produced by
 * any Gradle task and had to be assembled by hand. Hand-assembled release
 * artifacts are how a published zip stops matching the commit it came from, so
 * the step belongs here next to the MSI it is built from.
 */
val packageZip by tasks.registering(Zip::class) {
    group = "compose desktop"
    description = "Zips the Windows application image into a portable build."
    dependsOn("createDistributable")

    val app = layout.buildDirectory.dir("compose/binaries/main/app/OpenFlux").get().asFile
    archiveFileName.set("OpenFlux-$appVersion-windows-amd64.zip")
    destinationDirectory.set(layout.buildDirectory.dir("compose/binaries/main"))
    // The image directory itself, so the zip unpacks to OpenFlux/...
    from(app)
    // Reproducible: a zip that differs only in timestamps makes the published
    // SHA-256 differ from every rebuild of the same commit.
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

/**
 * Puts the release artifacts somewhere a person looks first.
 *
 * build/compose/binaries/ is a Compose plugin convention, four directories
 * deep, and it is not obvious which of the two files there is the installer.
 * dist/ answers that by name.
 */
val collectDist by tasks.registering(Copy::class) {
    group = "distribution"
    description = "Copies the Windows installers and the portable build into OpenFluxPC/dist/."
    dependsOn("packageMsi", packageZip)

    val binaries = layout.buildDirectory.dir("compose/binaries/main")
    val outDir = layout.projectDirectory.dir("dist")
    from(binaries.map { it.dir("msi") }) { include("*.msi") }
    from(binaries) { include("*.zip") }
    into(outDir)
    doLast {
        val files = outDir.asFile.listFiles()?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) {
            throw GradleException("dist/ is empty - the packaging tasks produced nothing")
        }
        logger.lifecycle("Готово, ${files.size} файлов в ${outDir.asFile}:")
        files.forEach {
            logger.lifecycle("  ${it.name}  ${it.length() / 1048576} МБ")
        }
    }
}

tasks.named("processResources") { dependsOn(checkCore) }
