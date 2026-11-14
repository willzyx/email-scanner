plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    application
}

group = "org.willzyx"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.jackson.module.kotlin)
    // The Jackson module asks for an older kotlin-reflect; this keeps it at the version of the compiler.
    runtimeOnly(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.slf4j.api)
    implementation(libs.sqlite.jdbc)
    implementation(libs.angus.mail)
    implementation(libs.jsoup)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.greenmail) {
        // The Angus jakarta.mail bundle above already carries these API classes.
        exclude(group = "jakarta.mail", module = "jakarta.mail-api")
    }
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors = true
    }
}

ktlint {
    version = libs.versions.ktlint.get()
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("config/detekt/detekt.yml")
}

tasks.check {
    // The plain detekt task skips the rules that need type resolution; these two run every rule.
    dependsOn("detektMain", "detektTest")
}

application {
    mainClass = "org.willzyx.mailscan.MainKt"
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
