import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildSteps.gradle

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

/** Gradle step using the wrapper + agent JDK 17, run at the repo root. */
fun BuildSteps.gradleStep(stepName: String, gradleTasks: String) {
    gradle {
        name = stepName
        tasks = gradleTasks
        useGradleWrapper = true
        gradleWrapperPath = ""
        jdkHome = "%jdk.home%"
        gradleParams = "--no-daemon --stacktrace"
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
