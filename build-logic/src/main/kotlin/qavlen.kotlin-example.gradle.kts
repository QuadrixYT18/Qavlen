plugins {
    id("qavlen.example")
    id("org.jetbrains.kotlin.jvm")
    id("com.google.devtools.ksp")
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    "testImplementation"(kotlin("test-junit5"))
}
