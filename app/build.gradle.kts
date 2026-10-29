import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ─── prebuilt artifact fetch ────────────────────────────────────────────────
// re-adb consumes two sets of prebuilt artifacts, like Switchboard/ShellBox:
//   • portmux static libs (the tunnel/forward engine) from XNet-NGO/portmux
//     releases  -> app/.portmux/libs/<abi>/{libportmux.a,libhev-task-system.a}
//   • PRoot native binaries (for the rootless Alpine container) from
//     XNet-NGO/deps "native-latest"  -> app/src/main/jniLibs/<abi>/*.so
// The Alpine rootfs itself is downloaded at runtime (ProotBootstrap) from
// XNet-NGO/deps "rootfs-alpine-3.21.3".
val portmuxVersion = (project.findProperty("portmuxVersion") as String?) ?: "v0.7.0"
val portmuxRepo = "XNet-NGO/portmux"
val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
val portmuxDir = layout.projectDirectory.dir(".portmux")
val portmuxLibsDir = portmuxDir.dir("libs")
val portmuxIncludeDir = portmuxDir.dir("include")

val fetchPortmux by tasks.registering {
    description = "Download + extract portmux $portmuxVersion prebuilt static libs."
    val marker = portmuxDir.file(".fetched-$portmuxVersion").asFile
    outputs.file(marker)
    outputs.dir(portmuxLibsDir)
    doLast {
        if (marker.exists()) return@doLast
        portmuxDir.asFile.mkdirs(); portmuxLibsDir.asFile.mkdirs(); portmuxIncludeDir.asFile.mkdirs()
        abis.forEach { abi ->
            val tgz = portmuxDir.file("portmux-$portmuxVersion-android-$abi.tar.gz").asFile
            val url = "https://github.com/$portmuxRepo/releases/download/" +
                "$portmuxVersion/portmux-$portmuxVersion-android-$abi.tar.gz"
            logger.lifecycle("portmux: fetching $abi")
            ant.invokeMethod("get", mapOf("src" to url, "dest" to tgz.absolutePath, "verbose" to false))
            val outAbi = portmuxLibsDir.dir(abi).asFile; outAbi.mkdirs()
            ProcessBuilder("tar", "-xzf", tgz.absolutePath, "-C", portmuxDir.asFile.absolutePath)
                .inheritIO().start().waitFor().let { if (it != 0) throw GradleException("tar failed $abi") }
            File(portmuxDir.asFile, "android/$abi").listFiles()
                ?.filter { it.extension == "a" }
                ?.forEach { it.copyTo(File(outAbi, it.name), overwrite = true) }
        }
        marker.writeText("portmux $portmuxVersion fetched\n")
    }
}

// PRoot native .so binaries from XNet-NGO/deps "native-latest" (arm64-v8a only
// upstream; placed under jniLibs/arm64-v8a).
val depsNativeTag = "native-latest"
val jniLibsArm64 = layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a")
val fetchProotNatives by tasks.registering {
    description = "Download PRoot native binaries from XNet-NGO/deps."
    val marker = layout.projectDirectory.file("src/main/jniLibs/.fetched-proot").asFile
    outputs.file(marker)
    doLast {
        if (marker.exists()) return@doLast
        jniLibsArm64.asFile.mkdirs()
        val files = listOf("libproot-xed.so", "libproot.so", "libproot32.so", "libtalloc.so", "libbsdtar.so")
        files.forEach { name ->
            val dst = File(jniLibsArm64.asFile, name)
            val url = "https://github.com/XNet-NGO/deps/releases/download/$depsNativeTag/$name"
            logger.lifecycle("proot: fetching $name")
            ant.invokeMethod("get", mapOf("src" to url, "dest" to dst.absolutePath, "verbose" to false))
        }
        marker.writeText("proot natives fetched\n")
    }
}

tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") }
    .configureEach { dependsOn(fetchPortmux) }
tasks.matching { it.name.startsWith("merge") && it.name.contains("JniLibFolders") }
    .configureEach { dependsOn(fetchProotNatives) }
tasks.named("preBuild").configure { dependsOn(fetchPortmux, fetchProotNatives) }

android {
    namespace = "org.xnet.readb"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.xnet.readb"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.2.3"
        ndk { abiFilters += abis }
        externalNativeBuild {
            cmake {
                arguments += "-DPORTMUX_LIBS_DIR=${portmuxLibsDir.asFile.absolutePath}"
                arguments += "-DPORTMUX_INCLUDE_DIR=${portmuxIncludeDir.asFile.absolutePath}"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs { useLegacyPackaging = true } // PRoot .so must be extracted to run
    }

    signingConfigs {
        create("release") {
            // Credentials come from the environment, never hardcoded in the
            // repo. Set XNET_KEYSTORE / XNET_KEYSTORE_PASS / XNET_KEY_ALIAS
            // (see XNet-NGO/xnet-keystore). When unset, release builds are
            // produced unsigned.
            val ksPath = System.getenv("XNET_KEYSTORE")
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = System.getenv("XNET_KEYSTORE_PASS")
                keyAlias = System.getenv("XNET_KEY_ALIAS") ?: "xnet-key"
                keyPassword = System.getenv("XNET_KEY_PASS")
                    ?: System.getenv("XNET_KEYSTORE_PASS")
            }
        }
    }

    buildTypes {
        debug { isDebuggable = true }
        release {
            // Diagnostic release: NOT minified and debuggable, so the user can
            // read logs / attach while testing on-device. Signed with the XNet
            // key when the env is set (see Switchboard), else unsigned.
            isMinifyEnabled = false
            isDebuggable = true
            if (System.getenv("XNET_KEYSTORE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Termux terminal-emulator: native PTY (libtermux.so, forkpty) + emulator.
    implementation("com.github.termux.termux-app:terminal-emulator:v0.118.0")
}
