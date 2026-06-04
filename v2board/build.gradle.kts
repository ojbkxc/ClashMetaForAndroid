plugins {
    kotlin("android")
    id("com.android.library")
}

dependencies {
    implementation(project(":common"))

    implementation(libs.kotlin.coroutine)
    implementation(libs.androidx.core)

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
