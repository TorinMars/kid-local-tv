plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kidlocaltv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kidlocaltv"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so the release APK can be sideloaded directly.
            // Replace with your own signing config before distributing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // smbj uses Java 8+ APIs that Android 5-6 lack.
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // LibVLC ships native code for 4 CPU types (~25 MB each). Build one small APK per CPU type,
    // plus a universal APK that works everywhere.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        resources {
            // Duplicate jar metadata from Bouncy Castle / smbj that would otherwise clash.
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")

    // LibVLC (LGPL-2.1+): plays nearly any container/codec, with hardware decoding when available
    // and software (FFmpeg/libavcodec) fallback for everything else.
    implementation("org.videolan.android:libvlc-all:3.7.7")

    // SMB 2/3 client for importing from NAS / Windows / macOS shares.
    implementation("com.hierynomus:smbj:0.14.0")
    implementation("org.slf4j:slf4j-nop:2.0.9")

    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.3")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    testImplementation("junit:junit:4.13.2")
}
