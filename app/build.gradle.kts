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
        versionCode = (project.findProperty("appVersionCode") as String?)?.toInt() ?: 47
        versionName = (project.findProperty("appVersionName") as String?) ?: "0.47.0"
    }

    /**
     * The release key, supplied by CI and never committed.
     *
     * Android identifies an app by its signing key: it refuses to install a build
     * signed by a different one, which is the only thing standing between a user and
     * a hostile APK claiming to be an update. That protection is worth exactly as
     * much as the key's secrecy, so with the repository public the key cannot live
     * in it. CI decodes it from `ANODEX_KEYSTORE_BASE64` into `keystore/` at build
     * time; `.gitignore` keeps it out.
     *
     * An earlier preview key *was* committed, deliberately, to stop every CI build
     * producing an APK that would not install over the last one. That was the right
     * trade while the repository was private and builds were handed over one at a
     * time. It stops being the right trade the moment anyone can read the key, which
     * is why this replaced it before the repository was published.
     */
    val keystoreFile = rootProject.file("keystore/anodex-release.jks")
    val hasKeystore = keystoreFile.exists()

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = keystoreFile
                storePassword = System.getenv("ANODEX_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANODEX_KEY_ALIAS") ?: "anodex"
                keyPassword = System.getenv("ANODEX_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Falls back to Gradle's own debug key when the release key is absent,
            // so a checkout with no secrets still builds and tests. Such a build is
            // not installable over a released one, which is correct: it was not
            // signed by us.
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
        }
        release {
            // Minification is off, and that is a decision rather than an oversight.
            //
            // `proguard-rules.pro` is empty. kotlinx.serialization resolves serializers
            // reflectively, so shrinking without keep rules strips them and the app fails
            // at runtime — parsing every frame off the socket — which is precisely the
            // kind of break that compiles, passes unit tests, and only shows up on a
            // phone. There is no Android device here to catch it.
            //
            // The reason to build release at all is that debug builds are *debuggable*:
            // anyone with USB access and developer mode can attach to the process and read
            // its memory, including the device key after it is decrypted. That is the real
            // security difference between the two variants, and it costs nothing to fix.
            //
            // Turning minification on is worth doing later, with real keep rules and a
            // device to test on. Shipping it blind would trade a small hardening for a
            // good chance of a broken app.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
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
