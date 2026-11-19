import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jlleitschuh.gradle.ktlint")
    id("dev.detekt")
    `java-library`
    `maven-publish`
}

val catalog = versionCatalogs.named("libs")

kotlin {
    jvmToolchain(25)
    explicitApi()
    // Public signatures are recorded in api/<module>.api; `check` fails when they change without updating the dump.
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        allWarningsAsErrors.set(true)
    }
}

java {
    withSourcesJar()
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("gradle/detekt.yml"))
    parallel = true
}

dependencies {
    testImplementation(platform(catalog.findLibrary("junit-bom").get()))
    testImplementation(catalog.findLibrary("junit-jupiter").get())
    testImplementation(catalog.findLibrary("kotlin-test").get())
    testRuntimeOnly(catalog.findLibrary("junit-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.compilerArgs.add("-parameters")
}

tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "qavlen-${project.name}"
            pom {
                name.set("Qavlen ${project.name}")
                url.set("https://github.com/QuadrixYT18/qavlen")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
            }
        }
    }
}
