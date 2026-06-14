import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.triggers.vcs

object BuildWeft : BuildType({
    name = "Build · compile + test + detekt"
    description = "Compiles Android/JVM + iOS targets, runs unit tests and detekt. macOS agent (iOS)."

    artifactRules = "**/build/reports/tests/** => test-reports"

    weftCheckout()

    steps {
        gradleStep(
            "detekt + test + iOS compile",
            "detekt test compileKotlinIosSimulatorArm64",
        )
    }

    triggers {
        vcs {
            branchFilter = "+:*"
        }
    }

    features {
        feature {
            type = "xml-report-plugin"
            param("xmlReportParsing.reportType", "junit")
            param("xmlReportParsing.reportDirs", "+:**/build/test-results/**/*.xml")
        }
    }

    requireMacOs()
    requireAndroidSdk()
})
