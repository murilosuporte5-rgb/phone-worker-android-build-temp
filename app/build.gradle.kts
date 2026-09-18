import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val signingStoreFile = System.getenv("PW_SIGNING_STORE_FILE")
val signingStorePassword = System.getenv("PW_SIGNING_STORE_PASSWORD")
val signingKeyAlias = System.getenv("PW_SIGNING_KEY_ALIAS")
val signingKeyPassword = System.getenv("PW_SIGNING_KEY_PASSWORD")
val signingReady = listOf(
    signingStoreFile,
    signingStorePassword,
    signingKeyAlias,
    signingKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.phoneworker.bridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.phoneworker.bridge"
        minSdk = 30
        targetSdk = 36
        versionCode = 5
        versionName = "0.5.0"
    }

    signingConfigs {
        if (signingReady) {
            create("stableRelease") {
                storeFile = file(signingStoreFile!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (signingReady) {
                signingConfig = signingConfigs.getByName("stableRelease")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
}


val generateAppIcon = tasks.register("generateAppIcon") {
    val encoded = layout.projectDirectory.file("app_icon.b64")
    val output = layout.projectDirectory.file("src/main/res/drawable-nodpi/app_icon.jpg")

    inputs.file(encoded)
    outputs.file(output)

    doLast {
        val bytes = Base64.getDecoder().decode(encoded.asFile.readText().trim())
        output.asFile.parentFile.mkdirs()
        output.asFile.writeBytes(bytes)
    }
}

tasks.configureEach {
    if (name == "preBuild") {
        dependsOn(generateAppIcon)
    }
}
