// The Sendspin player as a library: protocol client, sync audio output,
// decoders and the listener side of server-initiated connections. No UI and
// no MediaSession, so another app can host it in a service of its own.
plugins {
    id("com.android.library")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    jvmToolchain(21)
}

android {
    namespace = "com.sendspindroid.player"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    api(project(":shared"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
}
