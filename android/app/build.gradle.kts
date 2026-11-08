plugins {
    id("com.android.application")
    id("com.chaquo.python")
}

// ---------------------------------------------------------------- version
// Single source of truth: vpeen/__init__.py (same as the desktop builds).
val repoRoot: File = rootProject.projectDir.parentFile
val vpeenVersion: String = Regex("""__version__\s*=\s*"([^"]+)"""")
    .find(File(repoRoot, "vpeen/__init__.py").readText())
    ?.groupValues?.get(1) ?: "0.0.0"
val vpeenVersionCode: Int = vpeenVersion.split(".")
    .map { it.filter(Char::isDigit).ifEmpty { "0" }.toInt() }
    .let { p -> (p.getOrElse(0) { 0 } * 10000) + (p.getOrElse(1) { 0 } * 100) + p.getOrElse(2) { 0 } }

// ----------------------------------------------------------- python source
// The Python engine is NOT duplicated into the Android tree: it is copied
// from ../vpeen (plus the flag/icon assets the web UI serves) into a
// generated source dir at build time.
val pyOut = layout.buildDirectory.dir("generated/vpeen-python")
val copyVpeenSources by tasks.registering(Sync::class) {
    from(File(repoRoot, "vpeen")) {
        into("vpeen")
        exclude("**/__pycache__/**", "**/*.pyc")
    }
    from(File(repoRoot, "assets")) {
        into("vpeen/assets")
        include("flags/**", "icon.png")
    }
    into(pyOut)
}

// --------------------------------------------------------------- signing
// Release signing comes from CI secrets (see .github/workflows/android.yml).
// Without them the release APK is signed with the debug key so it is still
// installable for testing.
val ksPath: String? = System.getenv("VPEEN_KEYSTORE")
val hasReleaseKey = !ksPath.isNullOrBlank() && File(ksPath).exists()

android {
    namespace = "com.vpeen.app"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.vpeen.app"
        minSdk = 24
        targetSdk = 35
        versionCode = vpeenVersionCode
        versionName = vpeenVersion

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            ndkBuild {
                // only the JNI library, not hev's standalone executable
                targets += "hev-socks5-tunnel"
            }
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(ksPath!!)
                storePassword = System.getenv("VPEEN_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("VPEEN_KEY_ALIAS")
                keyPassword = System.getenv("VPEEN_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Chaquopy + JNI natives are looked up reflectively: no shrinking
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

chaquopy {
    defaultConfig {
        version = "3.11"
        // webui serves index.html / app.js / flags from the package dir,
        // so the package must exist as real files on disk.
        extractPackages("vpeen")
    }
    sourceSets {
        getByName("main") {
            srcDir(pyOut)
        }
    }
}

tasks.named("preBuild") { dependsOn(copyVpeenSources) }
tasks.configureEach {
    if (name.startsWith("merge") && name.contains("Python")) {
        dependsOn(copyVpeenSources)
    }
}
