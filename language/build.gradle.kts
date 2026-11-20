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
