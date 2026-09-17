import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    base
}

group = "ru.pavelkuzmin.screenpilot"
version = providers.gradleProperty("version").get()

val semanticVersion = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$")

val verifyReleaseVersion = tasks.register("verifyReleaseVersion") {
    group = "verification"
    description = "Rejects a release version that is not MAJOR.MINOR.PATCH."
    inputs.property("version", rootProject.version.toString())
    doLast {
        check(semanticVersion.matches(rootProject.version.toString())) {
            "ScreenPilot version must use MAJOR.MINOR.PATCH SemVer, got: ${rootProject.version}"
        }
    }
}

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

tasks.register("integrationTest") {
    group = "verification"
    description = "Runs integration tests from every ScreenPilot module."
    dependsOn(
        ":screenpilot-domain:integrationTest",
        ":screenpilot-player-mpv:integrationTest",
        ":screenpilot-platform-windows:integrationTest",
        ":screenpilot-persistence:integrationTest",
        ":screenpilot-app:integrationTest",
    )
}

tasks.register("buildWindowsInstaller") {
    group = "distribution"
    description = "Runs all tests and creates the AppFleet-compatible Windows release assets."
    dependsOn(
        verifyReleaseVersion,
        ":screenpilot-domain:test",
        ":screenpilot-player-mpv:test",
        ":screenpilot-platform-windows:test",
        ":screenpilot-persistence:test",
        ":screenpilot-app:test",
        "integrationTest",
        ":screenpilot-app:verifyReleaseAssets",
    )
}
