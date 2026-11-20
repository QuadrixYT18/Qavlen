pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

rootProject.name = "qavlen"

include(
    "core",
    "config",
    "language",
    "commands",
    "serialization",
    "network",
    "storage",
    "cache",
    "cache-redis",
    "scheduler",
    "updater",
    "commands-processor",
    "brigadier",
    "platform:paper",
    "platform:velocity",
    "platform:jda",
    "tests:java-api",
    "examples:jvm-kotlin",
    "examples:jvm-java",
    "examples:paper-kotlin",
    "examples:paper-java",
    "examples:velocity-kotlin",
    "examples:velocity-java",
    "examples:jda-kotlin",
    "examples:jda-java",
)
