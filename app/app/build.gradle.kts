plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val verCode = (project.findProperty("verCode") as String?)?.toIntOrNull() ?: 1
val repoSlug = (project.findProperty("repoSlug") as String?) ?: ""
val ksPath: String? = System.getenv("KEYSTORE_PATH")

android {
    namespace = "id.pemantau.tugas"
    compileSdk = 34
    defaultConfig {
        applicationId = "id.pemantau.tugas"
        minSdk = 26
        targetSdk = 34
        versionCode = verCode
        versionName = "1.$verCode"
        resValue("string", "repo_slug", repoSlug)
    }
    signingConfigs {
        if (ksPath != null && file(ksPath).exists()) {
            create("ci") {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = "key0"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("ci") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}
dependencies { implementation("androidx.work:work-runtime-ktx:2.9.1") }
