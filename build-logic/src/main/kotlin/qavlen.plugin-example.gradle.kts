import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("qavlen.example")
    id("com.gradleup.shadow")
}

// Relocate everything that is bundled so two plugins with different Qavlen versions never share classes.
// Platform APIs (Paper, Velocity, Adventure) are compileOnly and therefore never part of the plugin jar.
tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    val prefix = "com.example.qavlen.libs"
    relocate("de.quadrixyt.qavlen", "$prefix.qavlen")
    relocate("org.yaml.snakeyaml", "$prefix.snakeyaml")
    relocate("com.google.gson", "$prefix.gson")
    mergeServiceFiles()
    dependencies {
        exclude(dependency("com.google.errorprone:.*"))
        exclude(dependency("org.jspecify:.*"))
    }
}

tasks.named("assemble") {
    dependsOn("shadowJar")
}
