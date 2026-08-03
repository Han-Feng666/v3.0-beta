plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.HanFeng"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.HanFeng"
        minSdk = 24
        targetSdk = 35
        versionCode = 306
        versionName = "3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        aidl = true
        buildConfig = true
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
        jniLibs {
            // 让 PM 在安装时把 APK 内的 .so 解压到 /data/app/<pkg>/lib/<abi>/
            // Shizuku server 进程(app_process root)以绝对路径加载 libshizuku-rish.so / librish.so
            // 必须解压到磁盘上才能被 root 进程透过 isolated namespace 加载,
            // 仅有 zip 中的 .so 在 Android 13+ 的 isolated loader 下 dlopen 会 "not found"
            useLegacyPackaging = true
        }
    }
}

dependencies {
    val shizukuVersion = "13.1.5"
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.fragment:fragment-ktx:1.8.1")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-process:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    implementation("org.brotli:dec:0.1.2")
    implementation("dev.rikka.shizuku:api:$shizukuVersion")
    implementation("dev.rikka.shizuku:provider:$shizukuVersion")
    // 官方 AuthorizationManager 通过 binder 事务 getApplications 拿应用列表时,
    // reply 里用 ParcelableListSlice 反序列化,必须依赖此包
    implementation("dev.rikka.rikkax.parcelablelist:parcelablelist:2.0.1")

    // 内置 Shizuku fork：仅取 starter native 二进制 (libshizuku.so / librish.so)，
    // RequestPermissionActivity、BootCompleteReceiver 等 Activity/Receiver 组件也是从这里来的。
    // 客户端 SDK 仍然使用 dev.rikka.shizuku:api，不重复引入 fork 的 :api module
    implementation(project(":shizuku-fork:manager"))
    implementation(project(":shizuku-fork:server"))
    implementation(project(":shizuku-fork:starter"))
    implementation(project(":shizuku-fork:server-shared"))

    // shizuku-fork:manager 拉的 transitive deps 可能升到 androidx.core 1.16.0，需 AGP 8.6+，
    // 当前是 AGP 8.5.0，故强制压回 1.13.1
    configurations.all {
        resolutionStrategy.force("androidx.core:core:1.13.1")
        resolutionStrategy.force("androidx.core:core-ktx:1.13.1")
    }

    // P2.2 新增：单元测试依赖
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.3.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")

    // LSPosed/Xposed API - 仅编译期需要, 运行期由用户设备上的 LSPosed 框架提供
    compileOnly("de.robv.android.xposed:api:82")
}
