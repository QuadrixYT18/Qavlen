plugins {
    id("qavlen.kotlin-example")
    application
}

dependencies {
    implementation(project(":commands"))
    implementation(project(":config"))
    ksp(project(":commands-processor"))
}

application {
    mainClass.set("com.example.qavlen.MainKt")
}
