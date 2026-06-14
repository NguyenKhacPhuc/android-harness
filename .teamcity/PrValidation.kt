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
        // Stage 1 — environment
        preflightStep()
        // Stage 2 — code quality
        gradleStep("quality · detekt + lint", "detekt lint")
        // Stage 2 — testing: shared kotest (jvmTest) + Android unit tests
        // (`test` runs runtime's migration/JUnit tests). iOS sim tests omitted —
        // the agent has no iOS simulator runtime; iOS is compile-validated in the
        // build stage. Empty androidUnitTest tasks: failOnNoDiscoveredTests=false.
        gradleStep("test · shared + android", "jvmTest test")
        // Stage 3 — build: Android AARs + iOS klibs
        gradleStep("build · android + ios", "assembleDebug compileKotlinIosSimulatorArm64")
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
                // Token auth (not vcsRoot) — the VCS root may be anonymous, and
                // the PR API can't be queried anonymously. Uses github.token.
                authType = token { token = "%github.token%" }
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
