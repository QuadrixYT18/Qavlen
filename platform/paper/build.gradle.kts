plugins {
    id("qavlen.library")
}

dependencies {
    api(project(":commands"))
    api(project(":adventure"))
    compileOnly(libs.paper.api)
    testImplementation(libs.paper.api)
    testImplementation(libs.adventure.plain)
}
