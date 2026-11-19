plugins {
    java
}

val catalog = versionCatalogs.named("libs")

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

dependencies {
    "testImplementation"(platform(catalog.findLibrary("junit-bom").get()))
    "testImplementation"(catalog.findLibrary("junit-jupiter").get())
    "testRuntimeOnly"(catalog.findLibrary("junit-launcher").get())
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
