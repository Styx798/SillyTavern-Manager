plugins {
    id("com.android.application") version "9.3.0"
}

android {
    namespace = "io.github.styx798.sillytavernmanager.gate4.untrusted"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.styx798.sillytavernmanager.gate4.untrusted"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1"
        testInstrumentationRunner =
            "io.github.styx798.sillytavernmanager.gate4.untrusted.UntrustedCoreBindingInstrumentation"
    }
}
