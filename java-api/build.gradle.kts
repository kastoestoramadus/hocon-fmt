import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    kotlin("jvm") version "2.4.21"
}

group = "eu.ww86"
version = "0.1.0-SNAPSHOT"

repositories {
    // Until the core reaches Maven Central, `sbt coreJVM/publishM2` is what puts it here.
    mavenLocal()
    mavenCentral()
}

// The API is Java; only the tests speak Kotlin.
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
    sourceSets.getByName("main") {
        kotlin.setSrcDirs(emptyList<String>())
    }
}

// Scala 3.8 needs Java 17, so a lower target would only move the failure to the first format.
tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    // As in the Scala build, every warning fails it.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

dependencies {
    // FormatRefusedException appears in this API's signatures, so the core is `api`.
    api("eu.ww86:hocon-fmt-core_3:$version")
    // @NullMarked sits on the package, so consumers read it from their classpath too.
    api("org.jspecify:jspecify:1.0.0")
    testImplementation(kotlin("stdlib"))
    testImplementation(platform("org.junit:junit-bom:6.0.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // The contract suite loads exactly the jar this version names in Maven Local, so a stale jar
    // of another version there cannot pass for the packaging under test.
    systemProperty("hocon-fmt-java-api.version", version.toString())
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
