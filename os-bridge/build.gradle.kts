// os-bridge provides Android (and later iOS) implementations of the OsCapabilities
// interfaces defined in :contracts. The app is responsible for wiring the
// concrete implementations into ScriptContext at construction time.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

base { archivesName.set("weft-os-bridge") }

android {
    namespace = "dev.weft.osbridge"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            all { it.useJUnitPlatform() }
        }
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":contracts"))

    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.biometric)

    // androidx.activity for the ActivityResultRegistry surface AndroidCamera
    // uses to launch ACTION_IMAGE_CAPTURE and observe the result without
    // requiring the host activity to pre-register a launcher.
    implementation(libs.androidx.activity)

    // ML Kit — on-device OCR + barcode scanning for the Vision capability.
    // Both ship their own model files; first-call latency includes model
    // download on Play-services-enabled devices, then runs fully offline.
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.mlkit.translate)
    implementation(libs.mlkit.language.id)

    // FusedLocationProviderClient for the Location capability. Geocoder
    // is in android.location and needs no extra dep.
    implementation(libs.play.services.location)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // PDFBox-Android — backs the Pdf capability (extractText, create).
    // PdfRenderer (page → bitmap) uses platform APIs only, no extra dep.
    // ~5 MB AAR; the cost is worth the "summarize this PDF" use case
    // since no other Android library does proper text extraction.
    implementation(libs.pdfbox.android)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}
