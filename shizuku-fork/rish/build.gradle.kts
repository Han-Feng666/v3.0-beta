plugins {
    id("com.android.library")
}

android {
    namespace = "rikka.rish"
    compileSdk = 36

    defaultConfig {
        minSdk = 24

        externalNativeBuild {
            cmake {
                arguments("-DANDROID_STL=none")
            }
        }
    }

    buildFeatures {
        prefab = true
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

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1+"
        }
    }
}

dependencies {
    implementation(project(":shizuku-fork:api"))
    implementation("androidx.annotation:annotation:1.8.0")
    implementation("org.lsposed.libcxx:libcxx:27.0.12077973")
}
