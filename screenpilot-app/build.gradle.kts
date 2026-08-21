import org.gradle.api.plugins.jvm.JvmTestSuite
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test
import java.nio.file.Files
import java.security.MessageDigest

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
val jpackageInstallerDirectory = layout.buildDirectory.dir("jpackage/installer")
val jpackageTemporaryDirectory = layout.buildDirectory.dir("jpackage/temp")
val jpackageIcon = layout.projectDirectory.file("src/main/resources/ru/pavelkuzmin/screenpilot/app/ui/icons/screenpilot.ico")

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
            "--java-options", "-Dscreenpilot.version=${rootProject.version}"
        )
    }
}

tasks.register<Exec>("packageInstaller") {
    group = "distribution"
    description = "Builds a per-user Windows EXE installer from the verified app image."
    dependsOn(packageAppImage)
    inputs.dir(jpackageImageDirectory)
    outputs.dir(jpackageInstallerDirectory)
    doFirst {
        val installerDirectory = jpackageInstallerDirectory.get().asFile
        installerDirectory.deleteRecursively()
        val temporaryDirectory = jpackageTemporaryDirectory.get().asFile
        temporaryDirectory.deleteRecursively()
        commandLine(
            "jpackage",
            "--type", "exe",
            "--name", "ScreenPilot",
            "--app-version", rootProject.version.toString(),
            "--vendor", "Pavel Kuzmin",
            "--description", "Control local video on an external display",
            "--copyright", "Copyright (c) 2026 Pavel Kuzmin",
            "--dest", installerDirectory.absolutePath,
            "--temp", temporaryDirectory.absolutePath,
            "--verbose",
            "--app-image", jpackageImageDirectory.get().file("ScreenPilot").asFile.absolutePath,
            "--win-per-user-install",
            "--win-menu",
            "--win-menu-group", "ScreenPilot",
            "--win-shortcut",
            "--win-dir-chooser"
        )
    }
}
