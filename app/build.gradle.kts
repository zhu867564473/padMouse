plugins {
    // AGP 9.0+ 内置 Kotlin 支持，不再需要 org.jetbrains.kotlin.android 插件
    // 参考 https://kotl.in/gradle/agp-built-in-kotlin
    id("com.android.application")
}

android {
    namespace = "com.fseven.padmouse"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.fseven.padmouse"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        externalNativeBuild {
            cmake {
                abiFilters += listOf("arm64-v8a")
            }
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            isMinifyEnabled = false
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // extractNativeLibs=true：让 .so 解压到 nativeLibraryDir，
        // 否则 Shizuku UserService（app_process）无法 System.load 到 APK 内的 .so
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
