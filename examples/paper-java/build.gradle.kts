plugins {
    id("qavlen.plugin-example")
}

dependencies {
    compileOnly(libs.paper.api)
    implementation(project(":platform:paper"))
    annotationProcessor(project(":commands-processor"))
}
