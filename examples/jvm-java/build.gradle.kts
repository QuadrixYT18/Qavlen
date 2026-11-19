plugins {
    id("qavlen.example")
    application
}

dependencies {
    implementation(project(":commands"))
    implementation(project(":config"))
    annotationProcessor(project(":commands-processor"))
}

application {
    mainClass.set("com.example.qavlen.Main")
}
