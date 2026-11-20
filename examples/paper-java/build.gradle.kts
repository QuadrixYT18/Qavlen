plugins {
    id("qavlen.plugin-example")
}

dependencies {
    compileOnly(libs.paper.api)
    implementation(project(":platform:paper"))
    implementation(project(":updater"))
    annotationProcessor(project(":commands-processor"))
}
