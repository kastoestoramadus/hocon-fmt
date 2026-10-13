import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.plugin.compatibility.compatibility

plugins {
    id("com.gradle.plugin-publish") version "2.2.1"
}

group = "eu.ww86"
version = "0.1.0"

repositories {
    // Until the core reaches Maven Central, `sbt coreJVM/publishM2 javaApi/publishM2` puts it here.
    mavenLocal()
    mavenCentral()
}

// Scala 3.9 needs Java 17, so a lower target would only move the failure to the first format.
tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    // As in the Scala build, every warning fails it.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

gradlePlugin {
    website = "https://github.com/kastoestoramadus/hocon-fmt"
    vcsUrl = "https://github.com/kastoestoramadus/hocon-fmt"
    plugins {
        create("hoconFormatter") {
            id = "eu.ww86.hocon-fmt"
            implementationClass = "eu.ww86.hoconfmt.gradle.HoconFormatterPlugin"
            tags = listOf("hocon", "formatting", "code-quality")
            compatibility {
                features {
                    configurationCache = true
                }
            }
            displayName = "HOCON formatter"
            description = "Formats HOCON configuration files, or checks that they are formatted."
        }
    }
}

val functionalTestSourceSet = sourceSets.create("functionalTest")

dependencies {
    // Compile only: at run time the formatter is resolved in the consumer's build and loaded in an
    // isolated worker class loader, so its Scala library never lands on the buildscript classpath.
    compileOnly("eu.ww86:hocon-fmt-java-api:$version")
    "functionalTestImplementation"(platform("org.junit:junit-bom:6.0.1"))
    "functionalTestImplementation"("org.junit.jupiter:junit-jupiter")
    "functionalTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

val functionalTest = tasks.register<Test>("functionalTest") {
    description = "Runs builds that apply the plugin, through Gradle TestKit."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = functionalTestSourceSet.output.classesDirs
    classpath = functionalTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    systemProperty("maven.repo.local", System.getProperty("maven.repo.local", "${System.getProperty("user.home")}/.m2/repository"))
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

gradlePlugin.testSourceSets.add(functionalTestSourceSet)

tasks.check {
    dependsOn(functionalTest)
}

// The plugin asks for the Java API (the core comes with it) by these coordinates, so the two are released in lockstep.
tasks.processResources {
    val coordinates = "${project.group}:hocon-fmt-java-api:${project.version}"
    inputs.property("coordinates", coordinates)
    filesMatching("**/formatter.properties") {
        expand("coordinates" to coordinates)
    }
}

// The release workflow imports the key into GnuPG. Local checks and Maven Local publication
// need no signing key; release builds explicitly opt in, including dry runs.
if (providers.gradleProperty("releaseSigning").isPresent) {
    apply(plugin = "signing")
    providers.environmentVariable("PGP_PASSPHRASE").orNull?.let {
        extra["signing.gnupg.passphrase"] = it
    }
    configure<SigningExtension> {
        useGpgCmd()
        sign(publishing.publications)
    }
}
