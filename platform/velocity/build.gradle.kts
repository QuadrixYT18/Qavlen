plugins {
    id("qavlen.library")
}

dependencies {
    api(project(":brigadier"))
    api(project(":adventure"))
    compileOnly(libs.velocity.api)
    testImplementation(libs.velocity.api)
    testImplementation(libs.adventure.plain)
}
