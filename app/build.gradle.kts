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
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
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
