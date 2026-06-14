import jetbrains.buildServer.configs.kotlin.*

/*
 * Weft substrate SDK — TeamCity versioned settings (Kotlin DSL, portable).
 *
 * Pipeline (two configs):
 *   PrValidation — build + test + lint + detekt, on main + PRs
 *   PublishWeft  — all of the above + `publish` to GitHub Packages, manual + version
 *
 * The VCS root already exists on the server (Weft_Weft) and is referenced by
 * id from Common.kt — not redefined here.
 */

version = "2025.03"

project {
    description = "Weft SDK CI — KMP substrate published to GitHub Packages"

    buildType(PrValidation)
    buildType(PublishWeft)

    params {
        // JDK 17 on the agent (weft targets JVM 17). Adjust to your agents'
        // published JDK env var.
        param("jdk.home", "%env.JDK_17_0%")

        param("github.username", "NguyenKhacPhuc")
        // `github.token` is a SECRET — do not declare it here. Add it in the
        // TeamCity UI (Project → Parameters → Add → type Password). Needs
        // write:packages (publish) + repo:status (PR commit-status publisher).
        // TeamCity stores it securely and writes the credentialsJSON token back.
    }
}
