// :os-bridge provides Android implementations of the OsCapabilities
// interfaces defined in :contracts. The app is responsible for wiring
// the concrete implementations into ScriptContext at construction time.
//
// **KMP status — partial.** This module is now KMP-published with empty
// iOS targets so it joins its peers in the published artifact set. The
// implementations stay in androidMain because every capability binds
// to platform-specific Android APIs:
//   • Camera / Vision — ML Kit (Google-only)
//   • Location — Play Services FusedLocationProviderClient
//   • Pdf — PDFBox-Android + android.graphics.pdf.PdfRenderer
//   • Biometric — androidx.biometric
//   • Custom Tabs / Files / Notifications — Android framework APIs
//
// iOS hosts implement OsCapabilities themselves against CoreLocation,
// Vision.framework, PDFKit, LocalAuthentication, etc. Lifting the
// abstraction into expect/actual for shared implementations would be a
// much bigger refactor — for now :contracts already exposes the shared
// interface, which is the part that has to be cross-platform.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

base { archivesName.set("weft-os-bridge") }

kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        androidMain.dependencies {
            api(project(":contracts"))

            implementation(libs.androidx.security.crypto)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.browser)
            implementation(libs.androidx.biometric)

            // androidx.activity for the ActivityResultRegistry surface
            // AndroidCamera uses to launch ACTION_IMAGE_CAPTURE and
            // observe the result without requiring the host activity to
            // pre-register a launcher.
            implementation(libs.androidx.activity)

            // ML Kit — on-device OCR + barcode scanning for the Vision
            // capability. Both ship their own model files; first-call
            // latency includes model download on Play-services-enabled
            // devices, then runs fully offline.
            implementation(libs.mlkit.text.recognition)
            implementation(libs.mlkit.barcode.scanning)
            implementation(libs.mlkit.translate)
            implementation(libs.mlkit.language.id)

            // FusedLocationProviderClient for the Location capability.
            // Geocoder is in android.location and needs no extra dep.
            implementation(libs.play.services.location)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.kotlinx.serialization.json)

            // PDFBox-Android — backs the Pdf capability (extractText,
            // create). PdfRenderer (page → bitmap) uses platform APIs
            // only, no extra dep. ~5 MB AAR; the cost is worth the
            // "summarize this PDF" use case since no other Android
            // library does proper text extraction.
            implementation(libs.pdfbox.android)
        }

        val androidUnitTest by getting {
            dependencies {
                implementation(libs.kotest.runner.junit5)
                implementation(libs.kotest.assertions.core)
            }
        }
    }
}

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
