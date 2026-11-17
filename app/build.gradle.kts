plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "xyz.wraithnet.phantom"
    compileSdk = 35

    defaultConfig {
        applicationId = "xyz.wraithnet.phantom"
        minSdk = 26
        targetSdk = 35
        versionCode = 26
        versionName = "0.2.24"
        // Must track the pinned AndroidLibXrayLite release in build.yml.
        buildConfigField("String", "XRAY_CORE_VERSION", "\"26.11.9\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        ndk {
            // Production remains arm64. The CI-only emulator runs x86_64
            // to exercise both Go/JNI runtimes on real Android.
            abiFilters += if (project.hasProperty("nativeSmoke")) {
                listOf("x86_64")
            } else {
                listOf("arm64-v8a")
            }
        }
    }

    splits {
        abi {
            isEnable = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signing done separately in CI (apksigner), so release APK is unsigned
            signingConfig = null
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Xray core (libv2ray.aar downloaded by CI)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    implementation("com.squareup.okhttp3:okhttp:4.12.1")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.11.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
