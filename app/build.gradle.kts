import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.arichard.whooz"
    compileSdk = 34

    defaultConfig {
        applicationId = "fr.arichard.whooz"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "1.1.5"
        resourceConfigurations += listOf("en", "fr")
        vectorDrawables.useSupportLibrary = true
        // CameraX ships two small native libraries per ABI; phones are ARM only.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    packaging {
        // Kotlin builtins metadata is only read by kotlin-reflect, which the app never uses.
        resources.excludes += setOf("kotlin/**", "META-INF/*.version")
    }

    // Optional release signing: reads keystore.properties at the repo root when present.
    val keystoreProps = rootProject.file("keystore.properties")
    if (keystoreProps.isFile) {
        val props = Properties().apply { keystoreProps.inputStream().use { load(it) } }
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        lintConfig = file("lint.xml")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Every version below declares minCompileSdk <= 34; bumping any of them needs the
    // AAR-metadata check (core 1.15+, activity 1.10+, CameraX 1.5+ require compileSdk 35).
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    // Unbundled ML Kit: ~800 KB, the face model comes from Google Play services.
    implementation("com.google.android.gms:play-services-mlkit-face-detection:17.1.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
