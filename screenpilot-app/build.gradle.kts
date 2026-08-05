import org.gradle.api.plugins.jvm.JvmTestSuite
import org.gradle.api.tasks.testing.Test

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
