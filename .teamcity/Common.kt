import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildSteps.gradle
import jetbrains.buildServer.configs.kotlin.buildSteps.script

/*
 * VCS root already exists on the server — referenced by id, not defined here.
 * Set it to the "VCS root ID" of the weft (android-harness) root.
 */
const val WEFT_VCS_ID = "Weft_Weft"

/** Single-root checkout at the repo root (weft is standalone — no siblings). */
fun BuildType.weftCheckout() {
    vcs {
        root(AbsoluteId(WEFT_VCS_ID))
        cleanCheckout = true
    }
}

/** Preflight: report the toolchain so build logs show what ran. */
fun BuildSteps.preflightStep() {
    script {
        name = "preflight · toolchain"
        scriptContent = "java -version; xcodebuild -version || true; ./gradlew --version | grep -i gradle || true"
    }
}

/** Gradle step using the wrapper + agent JDK 17, run at the repo root. */
fun BuildSteps.gradleStep(stepName: String, gradleTasks: String) {
    gradle {
        name = stepName
        tasks = gradleTasks
        useGradleWrapper = true
        gradleWrapperPath = ""
        jdkHome = "%jdk.home%"
        // --no-configuration-cache: TeamCity's gradle-runner init script
        // registers build listeners, which the config cache (on in
        // gradle.properties) rejects under Gradle 9. The daemon is kept (a
        // persistent self-hosted agent reuses it across the staged steps).
        gradleParams = "--stacktrace --no-configuration-cache"
    }
}

/** iOS Kotlin/Native compilation + publishing needs a macOS agent. */
fun BuildType.requireMacOs() {
    requirements {
        contains("teamcity.agent.jvm.os.name", "Mac")
    }
}

/** Android targets need the SDK on the agent. */
fun BuildType.requireAndroidSdk() {
    requirements {
        exists("env.ANDROID_HOME")
    }
}
