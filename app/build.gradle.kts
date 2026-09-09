plugins { id("com.android.application"); kotlin("android") }
android {
 namespace = "dev.nicotv.app"
 compileSdk = 35
 defaultConfig { minSdk = 26; targetSdk = 35; applicationId = "io.github.nmt3325.nicotvoverlay"; versionCode = 2; versionName = "0.1.1"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
 testOptions { unitTests.isIncludeAndroidResources = true }
 lint { abortOnError = true }
}
dependencies {
 implementation(project(":core"))
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.robolectric:robolectric:4.14.1")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
 testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
 implementation(project(":comment-client"))
 implementation(project(":detection"))
 implementation(project(":overlay"))
 implementation("androidx.core:core-ktx:1.16.0")
 androidTestImplementation("androidx.test:runner:1.6.2")
 androidTestImplementation("androidx.test:rules:1.6.1")
 androidTestImplementation("androidx.test.ext:junit:1.2.1")
 androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
