import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildFeatures.commitStatusPublisher
import jetbrains.buildServer.configs.kotlin.buildFeatures.pullRequests
import jetbrains.buildServer.configs.kotlin.triggers.vcs

/**
 * PR validation — the gate before merge/publish.
 * Build + test + lint + detekt across Android + iOS. macOS agent (iOS klibs).
 */
object PrValidation : BuildType({
    name = "PR validation · build + test + lint + detekt"
    description = "Compiles Android + iOS, runs unit tests, Android lint and detekt."

    artifactRules = "**/build/reports/tests/** => test-reports"

    weftCheckout()

    steps {
        gradleStep(
            "build + test + lint + detekt",
            "detekt lint test compileKotlinIosSimulatorArm64",
        )
    }

    triggers {
        vcs {
            branchFilter = """
                +:refs/heads/main
                +:pull/*
            """.trimIndent()
        }
    }

    features {
        pullRequests {
            vcsRootExtId = WEFT_VCS_ID
            provider = github {
                authType = vcsRoot()
                filterTargetBranch = "+:refs/heads/main"
            }
        }
        commitStatusPublisher {
            vcsRootExtId = WEFT_VCS_ID
            publisher = github {
                githubUrl = "https://api.github.com"
                authType = personalToken { token = "%github.token%" }
            }
        }
        feature {
            type = "xml-report-plugin"
            param("xmlReportParsing.reportType", "junit")
            param("xmlReportParsing.reportDirs", "+:**/build/test-results/**/*.xml")
        }
    }

    requireMacOs()
    requireAndroidSdk()
})
