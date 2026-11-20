plugins {
    id("qavlen.example")
}

// Compiles and runs the public API from plain Java, as a Java consumer of the published artifacts would.
dependencies {
    testImplementation(project(":config"))
    testImplementation(project(":language"))
    testImplementation(project(":commands"))
    testImplementation(project(":serialization"))
    testImplementation(project(":storage"))
    testImplementation(project(":cache"))
    testImplementation(project(":network"))
    testImplementation(project(":scheduler"))
    testImplementation(libs.adventure.api)
    testImplementation(libs.adventure.minimessage)
    testImplementation(libs.adventure.plain)
    testRuntimeOnly(libs.h2)
}
