plugins {
    id("qavlen.library")
}

dependencies {
    api(project(":core"))
    // Translations expose MessageTemplate, which lives in config together with the component renderer.
    api(project(":config"))
    compileOnly(libs.adventure.api)
    compileOnly(libs.adventure.minimessage)
    testImplementation(libs.adventure.api)
    testImplementation(libs.adventure.minimessage)
    testImplementation(libs.adventure.plain)
}

// AdventureMessages moved here from qavlen-adventure (up to 0.4.0). Declaring that artifact's capability makes Gradle
// report a conflict when an old qavlen-adventure jar is still declared, instead of putting the class on the classpath twice.
listOf("apiElements", "runtimeElements").forEach { name ->
    configurations.named(name) {
        // The project's own capability keeps project(":language") dependencies inside this build working.
        outgoing.capability("${project.group}:${project.name}:${project.version}")
        outgoing.capability("${project.group}:qavlen-language:${project.version}")
        outgoing.capability("${project.group}:qavlen-adventure:${project.version}")
    }
}

// Maven POMs cannot express capabilities; Maven users follow the migration notes instead.
extensions.configure<PublishingExtension> {
    publications.named<MavenPublication>("maven") {
        suppressPomMetadataWarningsFor("apiElements")
        suppressPomMetadataWarningsFor("runtimeElements")
    }
}
