import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.ahdcamera"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "io.github.ahdcamera"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.2.0"
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (System.getenv("AHD_KEYSTORE_PATH") != null) {
            create("upload") {
                storeFile = file(System.getenv("AHD_KEYSTORE_PATH"))
                storePassword = System.getenv("AHD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("AHD_KEY_ALIAS")
                keyPassword = System.getenv("AHD_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-debug" }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}

val nativeOutput = layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libahd_android.so")
val buildRustV4l2 by tasks.registering(Exec::class) {
    workingDir(rootDir)
    inputs.files(rootProject.fileTree("crates"), rootProject.fileTree("third_party/rga"), rootProject.file("Cargo.toml"), rootProject.file("Cargo.lock"))
    outputs.file(nativeOutput)
    doFirst {
        rootDir.resolve("target/.find-ignore").apply { parentFile.mkdirs(); writeText("") }
        val properties = Properties()
        rootDir.resolve("local.properties").takeIf { it.isFile }?.inputStream()?.use(properties::load)
        val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
            ?: properties.getProperty("sdk.dir") ?: error("Configure Android SDK")
        val ndk = file(System.getenv("ANDROID_NDK_HOME") ?: sdk + "/ndk/28.2.13676358")
        val linker = ndk.resolve("toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android31-clang")
        check(linker.canExecute()) { "Install NDK 28.2.13676358: " + linker }
        environment("ANDROID_NDK_HOME", ndk.absolutePath)
        environment("CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER", linker.absolutePath)
        environment("CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS", "-C link-arg=-Wl,-z,max-page-size=16384 -C force-unwind-tables=yes")
        commandLine("cargo", "build", "--locked", "--release", "--target", "aarch64-linux-android", "-p", "ahd-android")
    }
    doLast {
        nativeOutput.asFile.parentFile.mkdirs()
        rootDir.resolve("target/aarch64-linux-android/release/libahd_android.so").copyTo(nativeOutput.asFile, overwrite = true)
    }
}
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("JniLibFolders")) dependsOn(buildRustV4l2)
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.08.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
