# cordn KMP

[![GitHub downloads](https://img.shields.io/github/downloads/vitorpamplona/cordn-kmp/total?label=Downloads&labelColor=27303D&color=0D1117&logo=github&logoColor=FFFFFF&style=flat)](https://github.com/vitorpamplona/cordn-kmp/releases)
[![Last Version](https://img.shields.io/github/release/vitorpamplona/cordn-kmp.svg?maxAge=3600&label=Stable&labelColor=06599d&color=043b69)](https://github.com/vitorpamplona/cordn-kmp/releases/latest)
[![Magpie](https://img.shields.io/github/v/release/vitorpamplona/cordn-kmp?label=Magpie%20%28Nostr%20%2B%20Blossom%29&labelColor=27303D&color=7a4fd6)](#install)
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

## Install

Releases are published on Nostr and Blossom with [Magpie](https://github.com/vitorpamplona/magpie),
not on Maven Central (versions up to 1.2.1 are on Central). Magpie's settings plugin serves them to
Gradle, accepting only releases signed by the key `vitorpamplona.com/.well-known/nostr.json` names
as `releases`. In `settings.gradle.kts`:

```kotlin
plugins {
    id("com.vitorpamplona.magpie") version "0.2.0"
}

magpie {
    group("com.vitorpamplona.cordn")
}
```

Then depend on it as usual, and commit the `gradle/magpie.lock` the first build writes. Gradle has
to run on Java 21 or newer.

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
