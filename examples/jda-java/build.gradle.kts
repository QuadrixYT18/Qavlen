plugins {
    id("qavlen.example")
    application
}

dependencies {
    implementation(libs.jda)
    implementation(project(":platform:jda"))
    annotationProcessor(project(":commands-processor"))
}

application {
    mainClass.set("com.example.qavlen.jda.Bot")
}
