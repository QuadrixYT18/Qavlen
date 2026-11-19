plugins {
    id("qavlen.library")
}

dependencies {
    api(project(":commands"))
    compileOnly(libs.jda)
    testImplementation(libs.jda)
}
