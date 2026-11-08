plugins {
    id("java-library")
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.named("compileKotlin") {
    dependsOn(rootProject.tasks.named("generateI18nSources"))
}

dependencies {
    api(project(":mod-api"))
    compileOnly(libs.httpclient)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    api(libs.kool.core)
    api(libs.tomlkt)
    api(libs.koin.core)
    api(libs.jvm.libp2p)
    api(libs.kotlinx.serialization.json)
    implementation(kotlin("reflect"))
    testImplementation(kotlin("test"))
    testImplementation(libs.httpclient)
}

tasks.register<JavaExec>("timelinePublisherBenchmark") {
    group = "verification"
    description = "Measure synthetic v7 timeline publication, allocation and full hashing without a window."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.rwx.net.timeline.TimelinePublisherBenchmark")
    workingDir = rootProject.projectDir
    maxHeapSize = "2g"
    systemProperty("java.awt.headless", "true")
    providers.gradleProperty("timelineJfr").orNull?.let { path ->
        jvmArgs("-XX:StartFlightRecording=filename=$path,settings=profile,dumponexit=true")
    }
}
