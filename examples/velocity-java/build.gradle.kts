plugins {
    id("qavlen.plugin-example")
}

dependencies {
    compileOnly(libs.velocity.api)
    annotationProcessor(libs.velocity.api)
    implementation(project(":platform:velocity"))
    annotationProcessor(project(":commands-processor"))
}
