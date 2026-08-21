import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    base
}

group = "ru.pavelkuzmin.screenpilot"
version = "0.1.0"

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
