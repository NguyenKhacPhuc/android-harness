import jetbrains.buildServer.configs.kotlin.*

/**
 * Full validation (build + test + lint + detekt) followed by publishing
 * dev.weft:weft-* (all KMP modules + platform variants) to GitHub Packages.
 * Manual trigger with an explicit version — bump per release.
 *
 * Validation runs first so a broken build never publishes. macOS agent
 * required: `publish` builds the iOS klib variants (weft-*-iosarm64,
 * weft-*-iossimulatorarm64), which only compile on macOS.
 */
object PublishWeft : BuildType({
    name = "Publish · build + test + lint + detekt + publish"
    description = "Validates then publishes the weft SDK to GitHub Packages. Manual; set the version."

    artifactRules = "**/build/reports/tests/** => test-reports"

    params {
        param("weft.version", "0.0.1")
    }

    weftCheckout()

    steps {
        gradleStep(
            "build + test + lint + detekt + publish",
            // jvmTest runs kotest (root `test` only runs Android unit tests);
            // publish builds + uploads all variants incl. the iOS klibs.
            "detekt lint assembleDebug jvmTest test publish -PweftVersion=%weft.version% -Pgpr.user=%github.username% -Pgpr.key=%github.token%",
        )
    }

    requireMacOs()
    requireAndroidSdk()
})
