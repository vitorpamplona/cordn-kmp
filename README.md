# cordn KMP

[cordn](https://cordn.net), MLS group messaging whose delivery service is an MCP coordinator reached
over Nostr, for Kotlin Multiplatform: JVM, Android, iOS, macOS and Linux. It implements the cordn
specs (coordinator tools, group metadata, envelopes, sealed payloads, group refs, encrypted media,
multi-device) on top of [Quartz](https://github.com/vitorpamplona/quartz) and the RFC 9420 engine
[mls-kmp](https://github.com/vitorpamplona/mls-kmp), and ships a headless client,
`CordnGroupManager`, that a front end drives to create, join, send to and sync groups.

It is the cordn layer of [Amethyst](https://github.com/vitorpamplona/amethyst), its desktop app and
the `amy` CLI, and is tested against ts-mls, the engine cordn's own client runs.

```kotlin
dependencies {
    implementation("com.vitorpamplona.cordn:cordn:<version>")
}
```

## Developer Setup

```bash
./gradlew installGitHook
./gradlew -PquartzDir=../quartz :cordn:jvmTest   # -PquartzDir until the pinned Quartz is released
```

[AGENTS.md](AGENTS.md) has the project rules, for people and coding agents alike.
