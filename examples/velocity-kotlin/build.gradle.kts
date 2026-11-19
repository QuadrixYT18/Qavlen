plugins {
    id("qavlen.kotlin-plugin-example")
}

dependencies {
    compileOnly(libs.velocity.api)
    implementation(project(":platform:velocity"))
    ksp(project(":commands-processor"))
}

// Velocity has no library loader, so the Kotlin standard library is bundled and relocated.
tasks.shadowJar {
    relocate("kotlin", "com.example.qavlen.libs.kotlin")
}
