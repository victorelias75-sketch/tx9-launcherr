plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// A chave é criada pelo GitHub Actions (nunca fica salva como arquivo no repositório)
val keystoreFile = file("release.keystore")
val runNumber = System.getenv("GITHUB_RUN_NUMBER") ?: "1"

android {
    namespace = "com.tx9.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tx9.launcher"
        minSdk = 21
        targetSdk = 34
        // Cada compilação gera uma versão maior, permitindo atualizar por cima
        versionCode = runNumber.toInt()
        versionName = "0.2.$runNumber"
    }

    signingConfigs {
        create("fixa") {
            storeFile = keystoreFile
            storePassword = "tx9launcher"
            keyAlias = "tx9"
            keyPassword = "tx9launcher"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystoreFile.exists()) {
                signingConfigs.getByName("fixa")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
