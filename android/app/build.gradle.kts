plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "org.oregonlandexplorer"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.oregonlandexplorer.expanded"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    signingConfigs {
        getByName("debug") {
            System.getenv("OLE_KEYSTORE")?.let {
                storeFile = file(it)
                storePassword = System.getenv("OLE_STORE_PASSWORD")
                keyAlias = "oregon-expanded"
                keyPassword = System.getenv("OLE_STORE_PASSWORD")
            }
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += "pmtiles" }
}
dependencies {
    implementation("org.maplibre.gl:android-sdk:12.0.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
