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

        param("github.username", "NguyenKhacPhuc")
        // `github.token` is a SECRET — do not declare it here. Add it in the
        // TeamCity UI (Project → Parameters → Add → type Password) with
        // write:packages scope. TeamCity stores the value securely and writes
        // the credentialsJSON token back into this file on the next sync.
    }
}
