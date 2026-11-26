// The Compose BOM is declared again for androidTest, a separate classpath that needs it for its own versions, and the
// test manifest once per build type the tests can run against.
@file:Suppress("AvoidDuplicateDependencies")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

room {
    // Kept in git: each new version's schema is what a migration is written and tested against.
    schemaDirectory("$projectDir/schemas")
}

android {
    namespace = "nl.imreboersma.volti"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "nl.imreboersma.volti"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        // Separate app ID, so debug builds and tests never touch the rehearsal build's folder grant.
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Project rules, if any are ever needed, go in src/main/keepRules/; AGP picks them up from there.
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
        // The instrumented tests run against this: R8 as in release, so a missing keep rule fails a test instead
        // of a rehearsal. Debug's app ID and signing, so it uses the debug build's folder grant on the tablet. Not
        // debuggable: R8 only optimises (merges, inlines, strips Log.d) when the build isn't, as in release.
        // What only the tests use is kept in src/minified/keepRules/, traced by scripts/trace-test-references.sh.
        create("minified") {
            initWith(getByName("debug"))
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            testProguardFiles("src/androidTest/test-rules.pro")
            matchingFallbacks += "debug"
        }
    }
    // Minified by default, also on CI. For a quicker local run: -Pvolti.testBuildType=debug (connectedDebugAndroidTest).
    testBuildType = providers.gradleProperty("volti.testBuildType").getOrElse("minified")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.play.services.auth)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // The Compose tests need its test activity in the build they run against, which is the minified one.
    "minifiedImplementation"(libs.androidx.compose.ui.test.manifest)
}
