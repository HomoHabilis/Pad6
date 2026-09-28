plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// The version comes from the git tag, as for the desktop builds; CI passes
// it in with -Ppyp6.versionName=5.3.0 -Ppyp6.versionCode=50300.
val appVersionName = providers.gradleProperty("pyp6.versionName").orElse("0.0.0-dev").get()
val appVersionCode = providers.gradleProperty("pyp6.versionCode").orElse("1").get().toInt()

android {
    namespace = "io.github.pyp6.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.pyp6.android"
        minSdk = 30
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // A fixed key checked into the repo, so every build - local or CI -
        // can be installed over the previous one without uninstalling (which
        // would delete the app's samples). This is a sideloading key for a
        // personal install, not a Play Store identity.
        create("sideload") {
            storeFile = file("sideload.jks")
            storePassword = "pyp6-sideload"
            keyAlias = "pyp6"
            keyPassword = "pyp6-sideload"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("sideload")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.maxHeapSize = "3g" }
        }
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.1")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.compose.ui:ui:1.12.1")
    implementation("androidx.compose.foundation:foundation:1.12.1")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.compose.ui:ui-tooling-preview:1.12.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    debugImplementation("androidx.compose.ui:ui-tooling:1.12.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.12.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.compose.ui:ui-test-junit4:1.12.1")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.56.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.56.0")
}
