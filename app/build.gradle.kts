plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.dizzle.chessai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dizzle.chessai"
        minSdk = 26
        targetSdk = 35
        versionCode = runNumber
        versionName = "1.0.$runNumber"
        // OAuth client id for "Sign in with ChatGPT" (GitHub Actions variable CHATGPT_CLIENT_ID)
        val clientId = System.getenv("CHATGPT_CLIENT_ID") ?: (project.findProperty("chatgptClientId") as String?) ?: ""
        buildConfigField("String", "CHATGPT_CLIENT_ID", "\"$clientId\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        // Fixed key committed to the repo so every CI build can be installed as an update.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        // Stockfish is shipped as libstockfish.so and must be extracted to disk to be executable.
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/FastDoubleParser-*",
                "META-INF/{AL2.0,LGPL2.1}",
            )
        }
    }
}

dependencies {
    implementation(project(":core"))
}
