plugins { id("com.android.application"); kotlin("android") }
android {
 namespace = "dev.nicotv.fixture"
 compileSdk = 35
 defaultConfig { applicationId = "dev.nicotv.fixture"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "1.0-test" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
