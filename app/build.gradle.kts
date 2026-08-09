import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.screenshot)
}

android {
    experimentalProperties["android.experimental.enableScreenshotTest"] = true

    namespace = "com.ds.localtaskmanager"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ds.localtaskmanager"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.1.0-alpha.2"
        manifestPlaceholders["appLabel"] = "@string/app_name"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val signingPropertiesFile = File(gradle.gradleUserHomeDir, "local-task-manager-signing.properties")
    val signingProperties = Properties().apply {
        if (signingPropertiesFile.isFile) signingPropertiesFile.inputStream().use(::load)
    }
    val releaseSigningReady = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        .all { !signingProperties.getProperty(it).isNullOrBlank() }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = file(requireNotNull(signingProperties.getProperty("storeFile")))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = "DStationery（调试）"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseSigningReady) signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = true
        disable += setOf(
            "AndroidGradlePluginVersion",
            "GradleDependency",
            "KaptUsageInsteadOfKsp",
            "ModifierParameter",
            "ObsoleteSdkInt",
            "OldTargetApi",
        )
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    sourceSets["test"].resources.srcDir(rootProject.file("protocol-test-vectors"))
}

val verifyReleaseSigningConfig = tasks.register("verifyReleaseSigningConfig") {
    group = "verification"
    doLast {
        check(File(gradle.gradleUserHomeDir, "local-task-manager-signing.properties").isFile) {
            "Missing signing properties: ${File(gradle.gradleUserHomeDir, "local-task-manager-signing.properties")}"
        }
        val properties = Properties().apply {
            File(gradle.gradleUserHomeDir, "local-task-manager-signing.properties").inputStream().use(::load)
        }
        listOf("storeFile", "storePassword", "keyAlias", "keyPassword").forEach { key ->
            check(!properties.getProperty(key).isNullOrBlank()) { "Missing release signing property: $key" }
        }
        check(file(properties.getProperty("storeFile")).isFile) { "Release keystore does not exist" }
    }
}

tasks.matching { it.name in setOf("assembleRelease", "bundleRelease") }.configureEach {
    dependsOn(verifyReleaseSigningConfig)
}

kapt {
    correctErrorTypes = true
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    kapt(libs.androidx.room.compiler)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)

    screenshotTestImplementation(libs.screenshot.validation.api)
    screenshotTestImplementation(libs.compose.ui.tooling)
}
