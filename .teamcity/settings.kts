import jetbrains.buildServer.configs.kotlin.*

/*
 * Weft substrate SDK — TeamCity versioned settings (Kotlin DSL, portable).
 *
 * Pipeline:
 *   BuildWeft    — compile (Android + iOS) + tests + detekt, on push/PR
 *   PublishWeft  — `./gradlew publish` to GitHub Packages, manual + version param
 *
 * The VCS root already exists on the server (Weft_Weft) and is referenced by
 * id from Common.kt — not redefined here.
 */

version = "2025.03"

project {
    description = "Weft SDK CI — KMP substrate published to GitHub Packages"

    buildType(BuildWeft)
    buildType(PublishWeft)

    params {
        // JDK 17 on the agent (weft targets JVM 17). Adjust to your agents'
        // published JDK env var.
        param("jdk.home", "%env.JDK_17_0%")

        // GitHub Packages auth. `github.token` must be a PASSWORD param with
        // write:packages scope (publishing). Define it in the project UI.
        param("github.username", "NguyenKhacPhuc")
        password("github.token", "credentialsJSON:REPLACE_WITH_TOKEN", display = ParameterDisplay.HIDDEN)
    }
}
