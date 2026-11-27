buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.0")
        if (System.getenv("CORE_ONLY") == null) {
            classpath("com.android.tools.build:gradle:8.7.3")
        }
    }
}
