plugins {
    id("com.android.application")

    id("kotlin-parcelize")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.21"
    alias(libs.plugins.kotlinCompose)
}
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.bnyro.clock"
    compileSdk = 37

    val signingStoreFile = providers.gradleProperty("jaySigningStoreFile").orNull
    val signingStorePassword = providers.gradleProperty("jaySigningStorePassword").orNull
    val signingKeyAlias = providers.gradleProperty("jaySigningKeyAlias").orNull
    val signingKeyPassword = providers.gradleProperty("jaySigningKeyPassword").orNull

    signingConfigs {
        if (
            signingStoreFile != null &&
            signingStorePassword != null &&
            signingKeyAlias != null &&
            signingKeyPassword != null
        ) {
            create("jay") {
                storeFile = file(signingStoreFile)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.rispng.jay"
        minSdk = 23
        targetSdk = 37
        versionCode = providers.gradleProperty("jayVersionCode").get().toInt()
        versionName = providers.gradleProperty("jayVersionName").get()

        buildConfigField("String", "JAY_VERSION", "\"$versionName\"")
        buildConfigField(
            "String",
            "CLOCK_YOU_VERSION_NAME",
            "\"${providers.gradleProperty("clockYouVersionName").get()}\""
        )
        buildConfigField(
            "int",
            "CLOCK_YOU_VERSION_CODE",
            providers.gradleProperty("clockYouVersionCode").get()
        )

        buildConfigField(
            "String",
            "JAY_FIREBASE_PROJECT_ID",
            "\"${providers.gradleProperty("jayFirebaseProjectId").orElse("").get()}\""
        )
        buildConfigField(
            "String",
            "JAY_FIREBASE_API_KEY",
            "\"${providers.gradleProperty("jayFirebaseApiKey").orElse("").get()}\""
        )
        buildConfigField(
            "long",
            "JAY_PLAY_CLOUD_PROJECT_NUMBER",
            "${providers.gradleProperty("jayPlayCloudProjectNumber").get()}L"
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }


    }

    buildTypes {
        release {
            signingConfigs.findByName("jay")?.let { signingConfig = it }
            buildConfigField("boolean", "JAY_PLAY_ENTITLEMENT_ELIGIBLE", "true")
            buildConfigField(
                "String",
                "JAY_FIREBASE_APPLICATION_ID",
                "\"${providers.gradleProperty("jayFirebaseReleaseApplicationId").orElse("").get()}\""
            )
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

        }
        debug {
            signingConfigs.findByName("jay")?.let { signingConfig = it }
            buildConfigField("boolean", "JAY_PLAY_ENTITLEMENT_ELIGIBLE", "false")
            buildConfigField(
                "String",
                "JAY_FIREBASE_APPLICATION_ID",
                "\"${providers.gradleProperty("jayFirebaseDebugApplicationId").orElse("").get()}\""
            )
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
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

    // Core And UI
    implementation(libs.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.navigation.compose)

    // Compose BOM
    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)

    // Material Theme
    implementation(libs.material3)
    implementation(libs.material)
    implementation(libs.material.icons.extended)

    implementation(libs.sdp.android)
    implementation(libs.ui.viewbinding)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Room DB
    ksp(libs.room.compiler)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.work.runtime.ktx)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.play.integrity)

    implementation(libs.kotlinx.serialization.json)
}
