plugins {
    id("qavlen.kotlin-plugin-example")
}

dependencies {
    compileOnly(libs.paper.api)
    implementation(project(":platform:paper"))
    implementation(project(":config"))
    ksp(project(":commands-processor"))
}

// Paper downloads the Kotlin standard library listed under `libraries` in plugin.yml, so it is not bundled.
tasks.shadowJar {
    dependencies {
        exclude(dependency("org.jetbrains.kotlin:.*"))
        exclude(dependency("org.jetbrains:annotations"))
    }
}
