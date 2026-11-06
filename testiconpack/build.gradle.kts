// A tiny icon pack for checking the launcher's pack support on a device. Not part of the launcher itself.
plugins {
    id("com.android.application")
}

android {
    namespace = "dev.tonal.testiconpack"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.tonal.testiconpack"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1"
    }
}
