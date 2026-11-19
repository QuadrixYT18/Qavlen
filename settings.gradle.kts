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
    "commands-processor",
    "adventure",
    "brigadier",
    "platform:paper",
    "platform:velocity",
    "platform:jda",
    "examples:jvm-kotlin",
    "examples:jvm-java",
)
