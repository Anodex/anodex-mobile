plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.anodex.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.anodex.mobile"
        minSdk = 26
        targetSdk = 35

        // Overridable from CI so a tagged build stamps its own tag:
        //   ./gradlew assembleDebug -PappVersionCode=16 -PappVersionName=0.16.0
        //
        // These were left at 1 and "0.1.0" through twenty-one releases, which meant
        // the app could not say which build it was and nothing could tell whether an
        // APK was newer than the one already installed. Both are prerequisites for an
        // update check.
        versionCode = (project.findProperty("appVersionCode") as String?)?.toInt() ?: 16
        versionName = (project.findProperty("appVersionName") as String?) ?: "0.16.0"
    }

    /**
     * One fixed key for every preview build, on every machine and every CI runner.
     *
     * Without this, Gradle signs debug builds with an auto-generated keystore, and a
     * fresh CI runner generates a fresh one every single run. Android refuses to
     * install an APK over one signed by a different key, so every update meant
     * uninstalling first - and uninstalling wipes the app's storage, which is where
     * the pairing lives. Every single update was silently costing a re-pair.
     *
     * The password is the Android debug convention and is deliberately not a secret:
     * this key exists to make preview builds installable over each other, not to
     * establish authenticity. A real release key would live in CI secrets and would
     * never be committed - see keystore/README.md.
     */
    signingConfigs {
        create("preview") {
            storeFile = rootProject.file("keystore/anodex-preview.jks")
            storePassword = "android"
            keyAlias = "anodex-preview"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("preview")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("preview")
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
        compose = true
        // So the app can say which build it is. An update check needs a version to
        // compare, and a bug report needs one to be worth reading.
        buildConfig = true
    }

    testOptions {
        unitTests.all {
            // A test task that runs zero tests still reports BUILD SUCCESSFUL, which is the exact
            // shape of a check that silently stopped checking anything. Print each test so the
            // CI log shows what actually ran.
            it.testLogging {
                events("passed", "skipped", "failed")
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
