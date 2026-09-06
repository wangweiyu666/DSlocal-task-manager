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
        versionCode = 8
        versionName = "0.1.0-alpha.7"
        manifestPlaceholders["appLabel"] = "@string/app_name"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val offlineSigningPropertiesFile = File(gradle.gradleUserHomeDir, "local-task-manager-signing.properties")
    val connectedSigningPropertiesFile = File(gradle.gradleUserHomeDir, "local-task-manager-connected-signing.properties")
    val productionSigningPropertiesFile = File(gradle.gradleUserHomeDir, "local-task-manager-connected-production-signing.properties")
    fun loadSigning(file: File) = Properties().apply { if (file.isFile) file.inputStream().use(::load) }
    fun Properties.isReady() = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        .all { !getProperty(it).isNullOrBlank() }
    val offlineSigning = loadSigning(offlineSigningPropertiesFile)
    val connectedSigning = loadSigning(connectedSigningPropertiesFile)
    val productionSigning = loadSigning(productionSigningPropertiesFile)

    signingConfigs {
        if (offlineSigning.isReady()) {
            create("offlineRelease") {
                storeFile = file(requireNotNull(offlineSigning.getProperty("storeFile")))
                storePassword = offlineSigning.getProperty("storePassword")
                keyAlias = offlineSigning.getProperty("keyAlias")
                keyPassword = offlineSigning.getProperty("keyPassword")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
        if (connectedSigning.isReady()) {
            create("connectedRelease") {
                storeFile = file(requireNotNull(connectedSigning.getProperty("storeFile")))
                storePassword = connectedSigning.getProperty("storePassword")
                keyAlias = connectedSigning.getProperty("keyAlias")
                keyPassword = connectedSigning.getProperty("keyPassword")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
        if (productionSigning.isReady()) {
            create("productionRelease") {
                storeFile = file(requireNotNull(productionSigning.getProperty("storeFile")))
                storePassword = productionSigning.getProperty("storePassword")
                keyAlias = productionSigning.getProperty("keyAlias")
                keyPassword = productionSigning.getProperty("keyPassword")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    flavorDimensions += "connectivity"
    productFlavors {
        create("offline") {
            dimension = "connectivity"
            manifestPlaceholders["appLabel"] = "@string/app_name"
            buildConfigField("boolean", "CONNECTED_BUILD", "false")
            buildConfigField("String", "CLOUD_ENVIRONMENT", "\"none\"")
            buildConfigField("String", "CLOUD_API_BASE_URL", "\"\"")
            if (offlineSigning.isReady()) signingConfig = signingConfigs.getByName("offlineRelease")
        }
        create("connected") {
            dimension = "connectivity"
            applicationIdSuffix = ".connected"
            versionCode = 11
            versionName = "0.1.0-alpha.10"
            versionNameSuffix = "-connected"
            manifestPlaceholders["appLabel"] = "@string/app_name"
            buildConfigField("boolean", "CONNECTED_BUILD", "true")
            buildConfigField("String", "CLOUD_ENVIRONMENT", "\"staging\"")
            buildConfigField("String", "CLOUD_API_BASE_URL", "\"https://api-staging.rochelimit.me\"")
            if (connectedSigning.isReady()) signingConfig = signingConfigs.getByName("connectedRelease")
        }
        create("production") {
            dimension = "connectivity"
            applicationIdSuffix = ".connected.production"
            versionCode = 12
            versionName = "0.1.0-alpha.11"
            versionNameSuffix = "-executor"
            manifestPlaceholders["appLabel"] = "@string/app_name"
            buildConfigField("boolean", "CONNECTED_BUILD", "true")
            buildConfigField("String", "CLOUD_ENVIRONMENT", "\"production\"")
            buildConfigField("String", "CLOUD_API_BASE_URL", "\"https://api.rochelimit.me\"")
            if (productionSigning.isReady()) signingConfig = signingConfigs.getByName("productionRelease")
        }
    }

    sourceSets.getByName("production") {
        java.srcDir("src/connected/java")
        manifest.srcFile("src/connected/AndroidManifest.xml")
    }
    sourceSets.getByName("testProduction").java.srcDir("src/testConnected/java")

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
        unitTests.all { it.systemProperty("dstationery.unitTest", "true") }
    }

    sourceSets["test"].resources.srcDir(rootProject.file("protocol-test-vectors"))
    sourceSets["test"].resources.srcDir(rootProject.file("cloud-protocol-test-vectors"))
}

fun registerSigningVerification(taskName: String, fileName: String) = tasks.register(taskName) {
    group = "verification"
    doLast {
        val propertiesFile = File(gradle.gradleUserHomeDir, fileName)
        check(propertiesFile.isFile) {
            "Missing signing properties: $propertiesFile"
        }
        val properties = Properties().apply {
            propertiesFile.inputStream().use(::load)
        }
        listOf("storeFile", "storePassword", "keyAlias", "keyPassword").forEach { key ->
            check(!properties.getProperty(key).isNullOrBlank()) { "Missing release signing property: $key" }
        }
        check(file(properties.getProperty("storeFile")).isFile) { "Release keystore does not exist" }
    }
}

val verifyOfflineReleaseSigning = registerSigningVerification("verifyOfflineReleaseSigning", "local-task-manager-signing.properties")
val verifyConnectedReleaseSigning = registerSigningVerification("verifyConnectedReleaseSigning", "local-task-manager-connected-signing.properties")
val verifyProductionReleaseSigning = registerSigningVerification("verifyProductionReleaseSigning", "local-task-manager-connected-production-signing.properties")
tasks.matching { it.name in setOf("assembleOfflineRelease", "bundleOfflineRelease") }.configureEach { dependsOn(verifyOfflineReleaseSigning) }
tasks.matching { it.name in setOf("assembleConnectedRelease", "bundleConnectedRelease") }.configureEach { dependsOn(verifyConnectedReleaseSigning) }
tasks.matching { it.name in setOf("assembleProductionRelease", "bundleProductionRelease") }.configureEach { dependsOn(verifyProductionReleaseSigning) }

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
    "connectedImplementation"(libs.sqlcipher.android)
    "connectedImplementation"(libs.androidx.sqlite)
    "productionImplementation"(libs.sqlcipher.android)
    "productionImplementation"(libs.androidx.sqlite)

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
