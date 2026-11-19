plugins {
    id("qavlen.library")
}

dependencies {
    api(project(":language"))
    compileOnly(libs.adventure.api)
    compileOnly(libs.adventure.minimessage)
    testImplementation(libs.adventure.api)
    testImplementation(libs.adventure.minimessage)
    testImplementation(libs.adventure.plain)
}
