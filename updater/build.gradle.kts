plugins {
    id("qavlen.library")
}

dependencies {
    // HTTP and JSON come from qavlen-network; the public API only uses JDK types.
    implementation(project(":network"))
}
