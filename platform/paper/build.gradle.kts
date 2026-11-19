plugins {
    id("qavlen.library")
}

dependencies {
    api(project(":brigadier"))
    api(project(":adventure"))
    compileOnly(libs.paper.api)
    testImplementation(libs.paper.api)
    testImplementation(libs.adventure.plain)
}
