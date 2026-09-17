import org.gradle.api.plugins.jvm.JvmTestSuite
import org.gradle.api.GradleException
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.jar.JarFile
import java.util.stream.Collectors

plugins {
    application
    alias(libs.plugins.javafx)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

javafx {
    version = libs.versions.javafx.get()
    modules = listOf("javafx.controls", "javafx.fxml")
}

application {
    applicationName = "ScreenPilot"
    mainClass = "ru.pavelkuzmin.screenpilot.app.BootstrapMain"
}

dependencies {
    implementation(project(":screenpilot-domain"))
    implementation(project(":screenpilot-player-mpv"))
    implementation(project(":screenpilot-platform-windows"))
    implementation(project(":screenpilot-persistence"))
    implementation(libs.jackson.databind)
    implementation(libs.jna)
    implementation(libs.logback.classic)
    implementation(libs.slf4j.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

testing {
    suites {
        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter(libs.versions.junit.get())
            dependencies {
                implementation(project())
            }
            targets.all {
                testTask.configure {
                    shouldRunAfter(tasks.named<Test>("test"))
                }
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("file.encoding", "UTF-8")
}

val mpvRuntimeDirectory = rootProject.layout.projectDirectory.dir("vendor/mpv/runtime")
val jpackageInputDirectory = layout.buildDirectory.dir("jpackage/input")
val jpackageImageDirectory = layout.buildDirectory.dir("jpackage/app-image")
val jpackageIcon = layout.projectDirectory.file("src/main/resources/ru/pavelkuzmin/screenpilot/app/ui/icons/screenpilot.ico")
val installerScript = rootProject.layout.projectDirectory.file("packaging/screenpilot.iss")
val appFleetAppId = providers.gradleProperty("screenpilot.app-id").get()
val technicalName = providers.gradleProperty("screenpilot.technical-name").get()
val repositoryUrl = providers.gradleProperty("screenpilot.repository-url").get()
val installerFileName = "$technicalName-Setup-${rootProject.version}-x64.exe"
val releaseDirectory = rootProject.layout.projectDirectory.dir("dist/release/${rootProject.version}")
val installerFile = releaseDirectory.file(installerFileName)
val checksumFile = releaseDirectory.file("$installerFileName.sha256")
val manifestFile = releaseDirectory.file("appfleet-manifest.json")
val requiredReleaseAssetNames = setOf(
    installerFileName,
    "$installerFileName.sha256",
    "appfleet-manifest.json"
)

val expectedMpvHashes = mapOf(
    "mpv.exe" to "b0bb2dc1928e6d86cc26d950815c80c977440081e814c6a46e93f6e9e99c276d",
    "d3dcompiler_43.dll" to "4b074a3976399dc735484f5d43d04b519b7bdee8ac719d9ab8ed6bd4e6be0345",
    "mpv/fonts.conf" to "f141c1b89b172d22f213531646c21e288f0ebf3ec46484698896e1b33c626756"
)

fun sha256(path: java.nio.file.Path): String = Files.newInputStream(path).use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) {
            break
        }
        digest.update(buffer, 0, count)
    }
    digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

val verifyMpvRuntime = tasks.register("verifyMpvRuntime") {
    group = "verification"
    description = "Verifies the pinned local mpv runtime before it is packaged."
    inputs.dir(mpvRuntimeDirectory)
    doLast {
        expectedMpvHashes.forEach { (relativePath, expectedHash) ->
            val file = mpvRuntimeDirectory.file(relativePath).asFile.toPath()
            check(Files.isRegularFile(file)) { "Bundled mpv runtime is missing: $relativePath" }
            check(sha256(file).equals(expectedHash, ignoreCase = true)) {
                "Unexpected SHA-256 for bundled mpv runtime file: $relativePath"
            }
        }
    }
}

tasks.named<Jar>("jar") {
    manifest {
        attributes[
            "Implementation-Title"
        ] = "ScreenPilot"
        attributes["Implementation-Version"] = rootProject.version.toString()
        attributes["ScreenPilot-App-Id"] = appFleetAppId
        attributes["ScreenPilot-Repository-Url"] = repositoryUrl
    }
}

val verifyPackagedApplicationMetadata = tasks.register("verifyPackagedApplicationMetadata") {
    group = "verification"
    description = "Verifies release identity metadata embedded into the application JAR."
    dependsOn(tasks.named<Jar>("jar"))
    inputs.file(tasks.named<Jar>("jar").flatMap { it.archiveFile })
    doLast {
        val archive = tasks.named<Jar>("jar").get().archiveFile.get().asFile
        JarFile(archive).use { jar ->
            val attributes = jar.manifest.mainAttributes
            check(attributes.getValue("Implementation-Version") == rootProject.version.toString()) {
                "JAR version metadata does not match the release version"
            }
            check(attributes.getValue("ScreenPilot-App-Id") == appFleetAppId) {
                "JAR AppFleet AppId metadata is incorrect"
            }
            check(attributes.getValue("ScreenPilot-Repository-Url") == repositoryUrl) {
                "JAR repository URL metadata is incorrect"
            }
        }
    }
}

val prepareJpackageInput = tasks.register<Sync>("prepareJpackageInput") {
    group = "distribution"
    description = "Prepares application JARs, mpv and notices for jpackage."
    dependsOn(tasks.named("installDist"), verifyMpvRuntime)
    from(layout.buildDirectory.dir("install/ScreenPilot/lib"))
    from(mpvRuntimeDirectory) {
        into("mpv")
    }
    from(rootProject.layout.projectDirectory.file("vendor/mpv/THIRD_PARTY_NOTICES.md")) {
        into("notices")
    }
    into(jpackageInputDirectory)
}

val packageAppImage = tasks.register<Exec>("packageAppImage") {
    group = "distribution"
    description = "Builds a self-contained Windows application image with the pinned mpv runtime."
    dependsOn(prepareJpackageInput)
    inputs.dir(jpackageInputDirectory)
    inputs.file(jpackageIcon)
    inputs.property("appVersion", rootProject.version.toString())
    outputs.dir(jpackageImageDirectory)
    doFirst {
        val imageDirectory = jpackageImageDirectory.get().asFile
        imageDirectory.deleteRecursively()
        val mainJarName = tasks.named<Jar>("jar").get().archiveFileName.get()
        commandLine(
            "jpackage",
            "--type", "app-image",
            "--name", "ScreenPilot",
            "--app-version", rootProject.version.toString(),
            "--vendor", "Pavel Kuzmin",
            "--description", "Control local video on an external display",
            "--copyright", "Copyright (c) 2026 Pavel Kuzmin",
            "--input", jpackageInputDirectory.get().asFile.absolutePath,
            "--dest", imageDirectory.absolutePath,
            "--main-jar", mainJarName,
            "--main-class", "ru.pavelkuzmin.screenpilot.app.BootstrapMain",
            "--icon", jpackageIcon.asFile.absolutePath,
            "--java-options", "-Dscreenpilot.version=${rootProject.version}",
            "--java-options", "-Dscreenpilot.app-id=$appFleetAppId",
            "--java-options", "-Dscreenpilot.repository-url=$repositoryUrl"
        )
    }
}

val verifyAppImageNativeIcon = tasks.register<Exec>("verifyAppImageNativeIcon") {
    group = "verification"
    description = "Checks the packaged Windows EXE for native multi-resolution icon resources."
    dependsOn(packageAppImage)
    val executable = jpackageImageDirectory.map { image -> image.file("ScreenPilot/ScreenPilot.exe") }
    inputs.file(executable)
    inputs.file(rootProject.layout.projectDirectory.file("packaging/verify-exe-icon.ps1"))
    commandLine(
        "powershell",
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", rootProject.layout.projectDirectory.file("packaging/verify-exe-icon.ps1").asFile.absolutePath,
        "-Executable", executable.get().asFile.absolutePath
    )
}

val verifyInstallerScriptContract = tasks.register("verifyInstallerScriptContract") {
    group = "verification"
    description = "Checks the static AppFleet safety contract in the Inno Setup script."
    inputs.file(installerScript)
    doLast {
        val script = Files.readString(installerScript.asFile.toPath(), StandardCharsets.UTF_8)
        listOf(
            "PrivilegesRequired=lowest",
            "ArchitecturesAllowed=x64compatible",
            "ArchitecturesInstallIn64BitMode=x64compatible",
            "UsePreviousAppDir=yes",
            "UsePreviousTasks=yes",
            "CloseApplications=yes",
            "RestartApplications=no",
            "Name: \"desktopicon\"",
            "Type: files; Name: \"{app}\\{#TechnicalName}.exe\"",
            "Type: filesandordirs; Name: \"{app}\\app\"",
            "Type: filesandordirs; Name: \"{app}\\runtime\"",
            "Type: filesandordirs; Name: \"{app}\\icons\"",
            "Root: HKCU64; Subkey: \"Software\\PashaApps\\{#AppId}\"",
            "ValueName: \"InstallerType\"; ValueData: \"inno\"",
            "Filename: \"{app}\\{#TechnicalName}.exe\"; Description: \"Запустить {#AppName}\"; Flags: nowait postinstall skipifsilent"
        ).forEach { required ->
            check(script.contains(required)) { "Inno Setup script is missing required AppFleet contract: $required" }
        }
        check(!script.contains("{app}\\*")) { "Inno Setup script must not delete all application files broadly" }
    }
}

fun resolveInnoCompiler(): File {
    val explicitCompiler = providers.gradleProperty("innoCompiler").orNull
        ?: System.getenv("INNO_SETUP_COMPILER")
    val candidates = buildList {
        if (!explicitCompiler.isNullOrBlank()) {
            add(File(explicitCompiler))
        }
        add(File("C:\\Program Files (x86)\\Inno Setup 6\\ISCC.exe"))
        add(File("C:\\Program Files\\Inno Setup 6\\ISCC.exe"))
    }
    return candidates.firstOrNull(File::isFile)
        ?: throw GradleException(
            "Inno Setup 6 compiler was not found. Install Inno Setup 6 or pass -PinnoCompiler=<path-to-ISCC.exe>."
        )
}

val packageInstaller = tasks.register<Exec>("packageInstaller") {
    group = "distribution"
    description = "Builds the AppFleet Inno Setup per-user Windows installer from the verified app image."
    dependsOn(
        packageAppImage,
        verifyAppImageNativeIcon,
        verifyInstallerScriptContract,
        rootProject.tasks.named("verifyReleaseVersion")
    )
    inputs.dir(jpackageImageDirectory)
    inputs.file(installerScript)
    inputs.property("appId", appFleetAppId)
    inputs.property("appVersion", rootProject.version.toString())
    inputs.property("repositoryUrl", repositoryUrl)
    outputs.file(installerFile)
    doFirst {
        Files.createDirectories(releaseDirectory.asFile.toPath())
        listOf(installerFile, checksumFile, manifestFile).forEach { asset ->
            Files.deleteIfExists(asset.asFile.toPath())
        }
        commandLine(
            resolveInnoCompiler().absolutePath,
            "/DSourceDir=${jpackageImageDirectory.get().file("ScreenPilot").asFile.absolutePath}",
            "/DOutputDir=${releaseDirectory.asFile.absolutePath}",
            "/DIconFile=${jpackageIcon.asFile.absolutePath}",
            "/DAppId=$appFleetAppId",
            "/DAppName=ScreenPilot",
            "/DTechnicalName=$technicalName",
            "/DAppVersion=${rootProject.version}",
            "/DRepositoryUrl=$repositoryUrl",
            installerScript.asFile.absolutePath
        )
    }
}

val writeInstallerChecksum = tasks.register("writeInstallerChecksum") {
    group = "distribution"
    description = "Writes the SHA-256 file for the final Inno Setup installer."
    dependsOn(packageInstaller)
    inputs.file(installerFile)
    outputs.file(checksumFile)
    outputs.upToDateWhen { false }
    doLast {
        val installerPath = installerFile.asFile.toPath()
        check(Files.isRegularFile(installerPath)) { "Installer is missing: $installerPath" }
        Files.writeString(
            checksumFile.asFile.toPath(),
            "${sha256(installerPath)}  $installerFileName${System.lineSeparator()}",
            StandardCharsets.US_ASCII
        )
    }
}

val writeAppFleetManifest = tasks.register("writeAppFleetManifest") {
    group = "distribution"
    description = "Creates the AppFleet release manifest next to the installer."
    dependsOn(packageInstaller)
    inputs.property("appId", appFleetAppId)
    inputs.property("appVersion", rootProject.version.toString())
    inputs.property("repositoryUrl", repositoryUrl)
    outputs.file(manifestFile)
    outputs.upToDateWhen { false }
    doLast {
        val manifest = """
            {
              "schemaVersion": 1,
              "appId": "$appFleetAppId",
              "name": "ScreenPilot",
              "technicalName": "$technicalName",
              "version": "${rootProject.version}",
              "repositoryUrl": "$repositoryUrl",
              "platform": "windows",
              "architecture": "x64",
              "installer": {
                "type": "inno",
                "assetName": "$installerFileName",
                "sha256AssetName": "$installerFileName.sha256",
                "silentArgs": [
                  "/VERYSILENT",
                  "/SUPPRESSMSGBOXES",
                  "/NORESTART",
                  "/CLOSEAPPLICATIONS"
                ],
                "desktopShortcutTask": "desktopicon"
              },
              "detection": {
                "registryKey": "HKCU\\Software\\PashaApps\\$appFleetAppId",
                "versionValue": "Version",
                "executableValue": "Executable"
              },
              "processNames": ["ScreenPilot.exe"],
              "minimumAppFleetVersion": "2.0.0"
            }
        """.trimIndent() + System.lineSeparator()
        Files.writeString(manifestFile.asFile.toPath(), manifest, StandardCharsets.UTF_8)
    }
}

tasks.register("verifyReleaseAssets") {
    group = "verification"
    description = "Verifies names, checksum and manifest for the AppFleet Windows release assets."
    dependsOn(writeInstallerChecksum, writeAppFleetManifest, verifyPackagedApplicationMetadata)
    inputs.files(installerFile, checksumFile, manifestFile)
    doLast {
        val installerPath = installerFile.asFile.toPath()
        val checksumPath = checksumFile.asFile.toPath()
        val manifestPath = manifestFile.asFile.toPath()
        check(Files.isRegularFile(installerPath)) { "Installer is missing: $installerPath" }
        check(Files.readString(checksumPath, StandardCharsets.US_ASCII).trim()
                == "${sha256(installerPath)}  $installerFileName") {
            "Installer checksum does not match $installerFileName"
        }
        val manifest = Files.readString(manifestPath, StandardCharsets.UTF_8)
        check(manifest.contains("\"appId\": \"$appFleetAppId\"")) { "Manifest AppId is incorrect" }
        check(manifest.contains("\"version\": \"${rootProject.version}\"")) { "Manifest version is incorrect" }
        check(manifest.contains("\"assetName\": \"$installerFileName\"")) { "Manifest installer asset is incorrect" }
        check(manifest.contains("\"type\": \"inno\"")) { "Manifest installer type is incorrect" }
        check(manifest.contains("\"desktopShortcutTask\": \"desktopicon\"")) {
            "Manifest desktop shortcut task is incorrect"
        }
        check(manifest.contains("\"minimumAppFleetVersion\": \"2.0.0\"")) {
            "Manifest AppFleet baseline is incorrect"
        }
        val releasePath = releaseDirectory.asFile.toPath()
        val actualAssets = Files.list(releasePath).use { files ->
            files.filter(Files::isRegularFile)
                .map { it.fileName.toString() }
                .collect(Collectors.toSet())
        }
        check(actualAssets == requiredReleaseAssetNames) {
            "Release directory must contain exactly $requiredReleaseAssetNames, got $actualAssets"
        }
        requiredReleaseAssetNames.forEach { name ->
            check(Files.size(releasePath.resolve(name)) > 0) { "Release asset is empty: $name" }
        }
    }
}
