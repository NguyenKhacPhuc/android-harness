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
        // Stage 1 — environment
        preflightStep()
        // Stage 2 — code quality
        gradleStep("quality · detekt + lint", "detekt lint")
        // Stage 2 — testing (shared kotest via jvmTest + android unit + ios)
        gradleStep("test · shared + android + ios", "jvmTest test iosSimulatorArm64Test")
        // Stage 3 — build (android + ios)
        gradleStep("build · android + ios", "assembleDebug compileKotlinIosSimulatorArm64")
        // Stage 4 — deploy: publish artifacts to GitHub Packages (the SDK's
        // "deployment"). For a library this is real, not a gated scaffold.
        gradleStep(
            "deploy · publish to GitHub Packages",
            "publish -PweftVersion=%weft.version% -Pgpr.user=%github.username% -Pgpr.key=%github.token%",
        )
    }

    requireMacOs()
    requireAndroidSdk()
})
