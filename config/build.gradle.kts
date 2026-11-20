plugins {
    id("qavlen.library")
}

dependencies {
    api(project(":core"))
    implementation(libs.snakeyaml)
    implementation(libs.gson)
    // Provided by Paper and Velocity; other applications add adventure-text-minimessage to use components.
    compileOnly(libs.adventure.api)
    compileOnly(libs.adventure.minimessage)
    testImplementation(libs.adventure.api)
    testImplementation(libs.adventure.minimessage)
    testImplementation(libs.adventure.plain)
}
