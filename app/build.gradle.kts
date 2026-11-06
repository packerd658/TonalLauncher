import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing details live outside the repo, in ~/.tonal/keystore.properties by default
// (storeFile, storePassword, keyAlias, keyPassword). Without that file, release builds come out unsigned.
val signing = Properties().apply {
    val path = providers.gradleProperty("tonal.signing").orNull
        ?: "${System.getProperty("user.home")}/.tonal/keystore.properties"
    val file = File(path)
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "dev.tonal.launcher"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.tonal.launcher"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (!signing.isEmpty) {
            create("release") {
                storeFile = File(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.findByName("release")
        }
        // The release build, but debuggable: the Google app only attaches its feed to debuggable launchers.
        create("feed") {
            initWith(getByName("release"))
            isDebuggable = true
            matchingFallbacks += "release"
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.13.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.7.0")
}
