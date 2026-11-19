plugins {
    id("qavlen.library")
}

dependencies {
    api(project(":config"))
    api(libs.jackson.databind)
    implementation(libs.jackson.kotlin)
    implementation(libs.jackson.toml)
    implementation(libs.snakeyaml)
}
