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
                // org.lsposed.libcxx:27.0.12077973 由 LLVM 27 编译，与 NDK r26 编译器
                // data layout 不一致(i128:128 vs i128:64)，LTO 链接报错。改用 NDK 自带
                // c++_static(LLVM 18)，与编译器匹配。
                arguments("-DANDROID_STL=c++_static")
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
}
