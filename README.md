# Mobile Agent Substrate

An open-source Kotlin Multiplatform substrate for building LLM-orchestrated native mobile apps. Users describe intent in natural language; Claude orchestrates pre-defined scripts and pre-built UI templates to fulfill it through a polished, App Store–compliant mobile experience.

**Status:** pre-v1, foundations in progress. Not yet usable. See the roadmap before opening issues.

## Mental model

- **Scripts** are the verbs the app can do.
- **Templates** are the nouns the app can show.
- The LLM composes them per-request to fulfill user intent.
- A harness wraps every LLM call: reliability, quality, observability, cost, behavior, memory.

## Stack

- Kotlin Multiplatform (Android + iOS day-one).
- Compose for Android, SwiftUI for iOS consuming the KMP framework.
- Koog as the agent framework foundation.
- Anthropic Claude as the orchestrating model.
- Apache 2.0 licensed.

## Quick links

- Vision and scope: `docs/01-vision-and-scope.md`
- Architecture: `docs/02-architecture.md`
- Modules: `docs/03-modules.md`
- Roadmap: `docs/09-roadmap.md`
- Ownership and process: `docs/10-ownership-and-process.md`

The full plan lives under `docs/` (mirrored from the `substrate-plan/` directory used during design).

## Audience

- Mobile developers building LLM-orchestrated apps without rebuilding the harness from scratch.
- Power users / prosumers comfortable with bring-your-own Anthropic API key.
- OSS contributors interested in agentic mobile infrastructure.

This is not a managed AI service, a chat wrapper, or a code-generation tool. See `docs/01-vision-and-scope.md` for the full "what this is NOT" list.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) (stub during Phase 0; expanded in week 2). Security disclosure: [SECURITY.md](SECURITY.md). Conduct: [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

## License

Apache 2.0. See [LICENSE](LICENSE).
