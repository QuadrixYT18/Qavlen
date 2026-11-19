plugins {
    id("qavlen.library")
}

dependencies {
    compileOnly(libs.ksp.api)

    testImplementation(project(":commands"))
    testImplementation(libs.ksp.api)
    testImplementation(libs.ksp.aa.embeddable)
    testImplementation(libs.ksp.common.deps)
    testImplementation(libs.kotlin.compile.testing)
    testImplementation(libs.kotlin.compile.testing.ksp)
    testImplementation(libs.compile.testing)
}

tasks.test {
    // compile-testing needs access to javac internals.
    jvmArgs(
        "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
    )
}
