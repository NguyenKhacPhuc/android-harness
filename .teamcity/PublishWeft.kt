import jetbrains.buildServer.configs.kotlin.*

/**
 * Publishes dev.weft:weft-* (all KMP modules + platform variants) to GitHub
 * Packages. Manual trigger with an explicit version — bump per release.
 *
 * macOS agent required: `publish` builds the iOS klib variants
 * (weft-*-iosarm64, weft-*-iossimulatorarm64), which only compile on macOS.
 */
object PublishWeft : BuildType({
    name = "Publish · GitHub Packages"
    description = "Publishes the weft SDK artifacts to GitHub Packages. Manual; set the version."

    params {
        param("weft.version", "0.0.1")
    }

    weftCheckout()

    steps {
        gradleStep(
            "publish",
            "publish -PweftVersion=%weft.version% -Pgpr.user=%github.username% -Pgpr.key=%github.token%",
        )
    }

    requireMacOs()
    requireAndroidSdk()
})
