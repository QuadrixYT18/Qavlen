plugins {
    id("qavlen.library")
}

dependencies {
    api(project(":cache"))
    api(project(":serialization"))
    api(libs.jedis)
}
