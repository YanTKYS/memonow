import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
}

val appVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val publishedVersionName = appVersion.getProperty("versionName")
require(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)").matches(publishedVersionName)) { "Invalid versionName" }
val publishedVersionCode = providers.gradleProperty("memonowVersionCode")
    .orElse(appVersion.getProperty("versionCode")).get().toInt()
require(publishedVersionCode in 1..2_100_000_000) { "Invalid versionCode" }
val signingNames = listOf("MEMONOW_RELEASE_KEYSTORE_PATH", "MEMONOW_RELEASE_STORE_PASSWORD",
    "MEMONOW_RELEASE_KEY_ALIAS", "MEMONOW_RELEASE_KEY_PASSWORD")
val signingValues = signingNames.associateWith { providers.environmentVariable(it).orNull }
val signingComplete = signingValues.values.all { !it.isNullOrBlank() }
val requireReleaseSigning = tasks.register("requireReleaseSigning") {
    doLast {
        check(signingComplete) { "Release signing requires: " + signingNames.filter { signingValues[it].isNullOrBlank() }.joinToString() }
        check(file(signingValues.getValue("MEMONOW_RELEASE_KEYSTORE_PATH")!!).isFile) { "Release keystore file is missing" }
    }
}

android {
    namespace = "com.yantkys.memonow"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.yantkys.memonow"
        minSdk = 26
        targetSdk = 36
        versionCode = publishedVersionCode
        versionName = publishedVersionName
    }

    signingConfigs {
        create("release") {
            if (signingComplete) {
                storeFile = file(signingValues.getValue("MEMONOW_RELEASE_KEYSTORE_PATH")!!)
                storePassword = signingValues.getValue("MEMONOW_RELEASE_STORE_PASSWORD")
                keyAlias = signingValues.getValue("MEMONOW_RELEASE_KEY_ALIAS")
                keyPassword = signingValues.getValue("MEMONOW_RELEASE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets.getByName("test").resources.srcDir("schemas")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.room.runtime)
    kapt(libs.androidx.room.compiler)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
}

kapt {
    arguments { arg("room.schemaLocation", "$projectDir/schemas") }
}

// An unsigned release must never be mistaken for a distributable APK.
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(requireReleaseSigning)
}

// Android 16 / API 36 Robolectric requires Java 21; app bytecode stays Java 17.
tasks.withType<Test>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}
