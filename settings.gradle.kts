pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "ChessAI"
include(":core")
// CORE_ONLY=1 builds only the pure-Kotlin module (no Android SDK needed).
if (System.getenv("CORE_ONLY") == null) include(":app")
