plugins {
    id("qavlen.library")
}

dependencies {
    api(project(":commands"))
    // Provided by Paper (com.mojang:brigadier) and Velocity (its API-compatible fork) at runtime.
    compileOnly(libs.brigadier)
    testImplementation(libs.brigadier)
}
