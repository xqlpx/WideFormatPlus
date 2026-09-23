plugins {
    id("com.android.application")
}

android {
    namespace = "com.wideformat.plus"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wideformat.plus"
        minSdk = 24
        targetSdk = 35
        versionCode = 10
        versionName = "2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key on purpose: the distributed APK stays
            // installable and keeps the same signature as the copy already on
            // the device, so LSPosed scope does not have to be redone.
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "LOG_ENABLED", "false")
        }
        debug {
            buildConfigField("boolean", "LOG_ENABLED", "true")
        }
    }

    // lint-gradle is not in the offline cache and the only thing wanted from a
    // release build here is an APK, so the lint task is switched off.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    // The durable /sdcard log is compiled out of release builds entirely: the
    // shipped module must not write anything on its own.
    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/*"
        }
    }
}

dependencies {
    // Xposed API：用本仓库的离线 stub 做 compileOnly，运行期由 LSPosed 框架注入真实实现
    compileOnly(project(":xposed-stub"))
}
