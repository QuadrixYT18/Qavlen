plugins {
    id("qavlen.library")
}

dependencies {
    api(project(":core"))
    implementation(libs.snakeyaml)
    implementation(libs.gson)
}
