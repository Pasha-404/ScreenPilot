import org.gradle.api.plugins.jvm.JvmTestSuite
import org.gradle.api.tasks.testing.Test

plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    api(project(":screenpilot-domain"))
    implementation(libs.jna)
    implementation(libs.jna.platform)
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
                implementation(libs.jna)
                implementation(libs.assertj.core)
            }
            targets.all {
                testTask.configure {
                    shouldRunAfter(tasks.named<Test>("test"))
                }
            }
        }
    }
}

val includeHardwareTests = providers.gradleProperty("includeHardwareTests")
    .map { value -> value.equals("true", ignoreCase = true) }
    .orElse(false)

tasks.withType<Test>().configureEach {
    useJUnitPlatform {
        if (!includeHardwareTests.get()) {
            excludeTags("hardware")
        }
    }
    systemProperty("file.encoding", "UTF-8")
}
