# Publishing weft as a library

Host apps (undercurrent) can consume weft either as a **composite build**
(live source, `includeBuild("../weft")`) or as **published artifacts** from
GitHub Packages. CI uses artifacts so it never needs the weft checkout.

## Coordinates

Every KMP module publishes under `dev.weft` with an artifactId of
`weft-<gradle-path>`:

| Gradle project | Artifact |
|---|---|
| `:runtime` | `dev.weft:weft-runtime` |
| `:contracts` | `dev.weft:weft-contracts` |
| `:harness:agents` | `dev.weft:weft-harness-agents` |
| `:devtools` | `dev.weft.devtools:weft-devtools` (own group) |
| … | `dev.weft:weft-<path-with-dashes>` |

The KMP plugin also publishes per-platform variants
(`weft-runtime-jvm`, `weft-runtime-android`, `weft-runtime-iosarm64`,
`weft-runtime-iossimulatorarm64`); consumers just depend on the root
`weft-runtime` and Gradle metadata picks the right variant.

The artifactId rewrite + Android `release`-variant publishing are configured
once in the root [`build.gradle.kts`](../build.gradle.kts) `subprojects` block —
no per-module setup.

## Versioning

`version` defaults to a fixed `0.0.1` (root build). GitHub Packages handles
Maven `-SNAPSHOT` poorly, so we publish fixed versions and **bump per
release**, or override in CI:

```bash
./gradlew publish -PweftVersion=0.2.0
```

The consumer (undercurrent) pins the same version via `weftVersion` in its root
build.

## Publishing

Credentials come from `gpr.user` / `gpr.key` Gradle properties or the
`GITHUB_ACTOR` / `GITHUB_TOKEN` env vars (token needs `write:packages`).

```bash
# All modules to GitHub Packages
./gradlew publish -PweftVersion=0.2.0 \
  -Pgpr.user=<github-user> -Pgpr.key=<token-with-write:packages>

# Validate locally without credentials (coordinates + variants):
./gradlew publishToMavenLocal
ls ~/.m2/repository/dev/weft/
```

## How undercurrent consumes it

`undercurrent/settings.gradle.kts` is hybrid:

```kotlin
val useWeftComposite = file("../weft").exists() &&
    providers.gradleProperty("weft.useArtifacts").orNull != "true"
if (useWeftComposite) includeBuild("../weft") { /* substitutions */ }
```

- **Local dev:** `../weft` present → live composite build, unchanged.
- **CI / no sibling:** artifacts resolve from the `WeftGitHubPackages` repo.
- **Force artifacts locally:** `-Pweft.useArtifacts=true`.

A `consumer` token with `read:packages` (the `gpr.user`/`gpr.key` or
`GITHUB_ACTOR`/`GITHUB_TOKEN`) is the only thing CI needs from weft — no
checkout.
