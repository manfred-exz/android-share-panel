import java.util.Properties

plugins {
    id("com.android.application")
}

val localSigning = Properties().apply {
    rootProject.file("signing.properties").inputStream().use(::load)
}

android {
    namespace = "com.merxury.sharepanel"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.merxury.sharepanel"
        minSdk = 29
        targetSdk = 36
        versionCode = 9
        versionName = "1.6"
    }

    signingConfigs {
        create("localRelease") {
            storeFile = rootProject.file(localSigning.getProperty("storeFile"))
            storePassword = localSigning.getProperty("storePassword")
            keyAlias = localSigning.getProperty("keyAlias")
            keyPassword = localSigning.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("localRelease")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
