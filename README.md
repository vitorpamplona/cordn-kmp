# cordn KMP

[![GitHub downloads](https://img.shields.io/github/downloads/vitorpamplona/cordn-kmp/total?label=Downloads&labelColor=27303D&color=0D1117&logo=github&logoColor=FFFFFF&style=flat)](https://github.com/vitorpamplona/cordn-kmp/releases)
[![Last Version](https://img.shields.io/github/release/vitorpamplona/cordn-kmp.svg?maxAge=3600&label=Stable&labelColor=06599d&color=043b69)](https://github.com/vitorpamplona/cordn-kmp/releases/latest)
[![Maven Central](https://img.shields.io/maven-central/v/com.vitorpamplona.cordn/cordn?label=Maven%20Central&labelColor=27303D&color=0877d2)](https://central.sonatype.com/artifact/com.vitorpamplona.cordn/cordn)
[![JitPack snapshots](https://img.shields.io/badge/snapshots-JitPack-27303D?labelColor=27303D&color=0877d2)](https://jitpack.io/#vitorpamplona/cordn-kmp)
[![CI](https://img.shields.io/github/actions/workflow/status/vitorpamplona/cordn-kmp/build.yml?labelColor=27303D)](https://github.com/vitorpamplona/cordn-kmp/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/github/license/vitorpamplona/cordn-kmp?labelColor=27303D&color=0877d2)](LICENSE)

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
