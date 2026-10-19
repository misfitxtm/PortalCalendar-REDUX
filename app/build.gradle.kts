import java.security.MessageDigest
import java.util.Properties

plugins {
  id("com.android.application")
}

android {
  namespace = "com.portal.calendar"
  compileSdk = 36
  ndkVersion = "29.0.14206865"

  defaultConfig {
    // Own id so this installs next to (and never collides with) the original PortalHub. The code package stays com.portal.calendar.
    applicationId = "com.portalhub.redux"
    minSdk = 28
    // Portal+ gen-1 is API 28; staying at 28 avoids newer background restrictions.
    targetSdk = 28
    versionCode = 34
    versionName = "4.0.0-redux.8"
    // The Portal is arm64 only; this also keeps Vosk's native libraries from bloating the APK.
    ndk { abiFilters += "arm64-v8a" }
  }

  lint {
    // targetSdk 28 is deliberate (Portal screen takeover); this app is sideloaded, never on Google Play.
    disable += "ExpiredTargetSdkVersion"
  }

  // The release key lives outside the repo (see docs/RELEASING.md). Without it, `assembleRelease` makes an unsigned APK.
  signingConfigs {
    val f = file(System.getenv("PORTALHUB_SIGNING") ?: "${System.getProperty("user.home")}/.config/portalhub/signing.properties")
    if (f.exists()) {
      val p = Properties().apply { f.inputStream().use { load(it) } }
      create("release") {
        storeFile = file(p.getProperty("storeFile"))
        storePassword = p.getProperty("storePassword")
        keyAlias = p.getProperty("keyAlias")
        keyPassword = p.getProperty("keyPassword")
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      signingConfig = signingConfigs.findByName("release")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

dependencies {
  testImplementation("junit:junit:4.13.2")
  // Offline speech recognition (voice commands, off by default). Needs JNA's Android build.
  implementation("com.alphacephei:vosk-android:0.3.75")
  implementation("net.java.dev.jna:jna:5.13.0@aar")
  // QR code for the setup URL.
  implementation("com.google.zxing:core:3.5.3")
  // CalDAV (PROPFIND/PUT) — HttpURLConnection can't send PROPFIND.
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

// Builds the Rust core into jniLibs. Needs rustup (~/.cargo/bin) + cargo-ndk; arm64 only (the Portal).
val buildRustCore by tasks.registering(Exec::class) {
  workingDir = rootProject.file("core")
  commandLine("cargo", "ndk", "-t", "arm64-v8a", "-o", file("src/main/jniLibs").path, "build", "--release")
  val sdk = androidComponents.sdkComponents.sdkDirectory.get().asFile
  environment("ANDROID_NDK_HOME", "$sdk/ndk/29.0.14206865")
  environment("PATH", "${System.getProperty("user.home")}/.cargo/bin:" + System.getenv("PATH"))
}
tasks.named("preBuild") { dependsOn(buildRustCore) }

// Bundles the Vosk English model into the APK without committing 40 MB to git:
// downloaded once into the Gradle user home, checked against a pinned SHA-256, then unzipped.
val voskModelZip = "vosk-model-small-en-us-0.15"
val voskModelSha256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
val voskAssetsDir = layout.buildDirectory.dir("generated/voskAssets")

val fetchVoskModel by tasks.registering {
  val cache = File(gradle.gradleUserHomeDir, "portalhub/$voskModelZip.zip")
  val out = voskAssetsDir.get().asFile
  inputs.property("sha256", voskModelSha256)
  outputs.dir(out)
  doLast {
    fun sha256(f: File) = MessageDigest.getInstance("SHA-256")
      .digest(f.readBytes()).joinToString("") { "%02x".format(it) }
    if (!cache.exists() || sha256(cache) != voskModelSha256) {
      cache.parentFile.mkdirs()
      uri("https://alphacephei.com/vosk/models/$voskModelZip.zip").toURL().openStream()
        .use { input -> cache.outputStream().use { input.copyTo(it) } }
      check(sha256(cache) == voskModelSha256) { "Vosk model download failed its checksum; delete $cache and retry" }
    }
    out.deleteRecursively()
    copy {
      from(zipTree(cache)) { eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray()) } }
      into(File(out, "model-en-us"))
      includeEmptyDirs = false
    }
  }
}
android.sourceSets.getByName("main").assets.srcDir(voskAssetsDir.get().asFile)
// Anything that reads the assets folder (merging, lint) must wait for the model.
tasks.configureEach {
  if (name != "fetchVoskModel" && ((name.startsWith("merge") && name.endsWith("Assets")) || name.contains("lint", ignoreCase = true))) dependsOn(fetchVoskModel)
}
