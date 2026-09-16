plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

dependencies {
    implementation(project(":aurora-core"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.okhttp)
    implementation(libs.quickjs.kt)

    // Unit Testing & MockWebServer
    testImplementation(libs.junit)
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.truth)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    useJUnitPlatform {
        excludeTags("live-integration")
    }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

// Explicit opt-in task for live integration tests
tasks.register<Test>("liveTest") {
    useJUnitPlatform {
        includeTags("live-integration")
    }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
