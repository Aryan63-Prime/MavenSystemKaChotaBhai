plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.sentinel.host.service"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(project(":shared"))

    // Hilt
    implementation("com.google.dagger:hilt-android:2.53.1")
    ksp("com.google.dagger:hilt-compiler:2.53.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Moshi & Okio
    implementation("com.squareup.moshi:moshi:1.15.2")
    implementation("com.squareup.okio:okio:3.9.1")

    // AndroidX
    implementation("androidx.core:core-ktx:1.15.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
    testImplementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("com.squareup.moshi:moshi:1.15.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
