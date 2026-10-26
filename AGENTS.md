# cordn KMP

cordn (MLS group messaging whose delivery service is an MCP server reached over Nostr, rather than
relays) as a Kotlin Multiplatform library for JVM, Android, iOS, macOS and Linux, published to Maven
Central as `com.vitorpamplona.cordn:cordn`. It is built on [Quartz](https://github.com/vitorpamplona/quartz)
(events, signers, NIP-44 and `contextvm`, MCP over Nostr) and the RFC 9420 engine
[mls-kmp](https://github.com/vitorpamplona/mls-kmp), and is used by
[Amethyst](https://github.com/vitorpamplona/amethyst), its desktop app and the `amy` CLI.

The code was the `com.vitorpamplona.quartz.cordn` package of Quartz, plus the headless group client
from Amethyst's `commons` (`CordnGroupManager` and friends). It moved here so clients that do not speak
cordn don't carry an MLS engine. Marmot, the other MLS binding, is
[marmot-kmp](https://github.com/vitorpamplona/marmot-kmp); the two never import each other.

The spec and reference implementation are `Cordn-msg/cordn` on GitHub; `cordn/src/commonMain/kotlin/com/vitorpamplona/cordn/README.md`
names the commit this follows and every place the implementation wins over the prose. Interop is
checked against ts-mls through Staircase fixtures (`src/commonTest/resources/tsmls/`) and
`cordn/interop/verify-with-ts-mls.sh`.

## Layout

- `cordn/`: the library module, `cordn/src/<sourceSet>/kotlin/com/vitorpamplona/cordn/…`.
  - `spec00Coordinator/` … `spec03Payloads/`: one package per spec document; `app*/` for the
    application documents (group refs, encrypted media, multi-device); `groups/` for the MLS
    binding (`CordnCredential`, `CordnGroupPolicy`); `sync/` for cursors and ingestion; `tlv/`.
  - `fixture/`: `CordnFixtureCoordinator`, an in-memory coordinator that records who made each call.
  - `client/`: the headless client a front end drives: `CordnGroupManager`, `CordnKeyPackages`,
    `CordnSyncLoop`, `CordnCoordinatorRegistry` (invitations, coverage, the handoff guard),
    `CordnLinks` (the coordinator transport), the store interfaces with in-memory versions,
    `CordnLocalState` (what a backup and a handoff carry), `CordnBackup`, `CordnHandoffState`,
    `CordnTipBlobStore` (the migration blob rules over an app's HTTP).
  - Tests: `commonTest`, `jvmAndroidTest` (JUnit, ts-mls interop), `jvmTest` (`appMultiDevice`
    and the `client/` suites).
  - `cordn/tools/cordn-vector-gen`: Node generator for `coordinator-contracts.json`.
    `cordn/interop/`: the reverse interop check. `cordn/plans/`: design docs.
- `gradle/kmp-purity.gradle.kts`: the `verifyKmpPurity` task, the same file as Quartz's.
- `tools/ci/quartz-args.sh`: points the build at a Quartz checkout while the pinned Quartz release is
  not on Maven Central yet (CI and the pre-push hook call it). `tools/ci/mls-kmp-args.sh` does the
  same for mls-kmp.

## Rules

- **Protocol and headless client only.** No UI, no Compose UI, no app singletons. Platform storage,
  keystores, the relay pool and UI state belong to the app; the library defines the interfaces
  (`CordnGroupStore`, `CordnBlobCipher`, `CordnCoordinatorLinkFactory`, …).
- **Never depend on marmot-kmp**, and never let Quartz depend on this library. Tests that compare the
  two bindings live in Amethyst, the one place that has both.
- **What stays in Amethyst:** the store implementations that encrypt files at rest with a platform
  keystore, the account wiring (which relays, which signer, the coordinator list), chatroom models,
  UI and the `amy` verbs. Protocol decisions shared by every front end belong here, not there: a
  test, bench or vector that exercises only this library lives here too.
- **What members send inside a group is cordn's, MLS state or not.** The kinds, tags and rules of
  the application messages (`spec02Envelopes/`: `CordnMessageKinds`, `CordnAnnotationIndex`,
  `CordnMessageReferences`, mentions, media tags) live here, as do the transport assembly and
  discovery rules every client applies. The test: if another cordn client (cordn-web, `amy`, a bot)
  would need the code to agree with us, it belongs here, not in an app.
- Persisted formats are API: a store's bytes, a backup file, a TLV. Changing one breaks every device
  that already wrote it.
- `commonMain` and `commonTest` stay free of JVM-only APIs; `verifyKmpPurity` and the metadata
  compiles catch most of it on Linux, CI's `test-apple` job the rest. Use `kotlin.concurrent.Volatile`
  and Quartz's `PlatformLock`/`ConcurrentMap`.
- JVM target is 17. Releases follow semantic versioning; Amethyst pins a version.
- Commits: conventional commits (`feat:`, `fix:`, …). Never `--no-verify`.

## Verify, don't guess

Don't assert a diagnosis you haven't reproduced. If a claim is checkable in under a minute, check
it before stating it: write the failing case first, watch it fail, then explain.

## Build and test

```bash
./gradlew installGitHook                        # pre-commit (spotless) and pre-push (checks + tests)
./gradlew spotlessApply
./gradlew :cordn:jvmTest                       # also `./gradlew test`
./gradlew :cordn:verifyKmpPurity :cordn:compileCommonMainKotlinMetadata :cordn:compileAppleMainKotlinMetadata
./gradlew :cordn:linuxX64Test                  # the whole suite on Linux Native
./gradlew :cordn:iosSimulatorArm64Test         # macOS only
```

Until the Quartz version in `gradle/libs.versions.toml` is on Maven Central, add
`-PquartzDir=../quartz` (a checkout beside this repo); `-PmlsKmpDir` and `-PcryptoKmpDir` work the
same way. To try a change in Amethyst, build Amethyst with `-PcordnKmpDir=../cordn-kmp`.

## Dependency licensing

Before adding a third-party dependency, read its actual `LICENSE`/`COPYING` file or published
POM, not your memory. This library is MIT and ships inside apps.

- Permissive (MIT, Apache-2.0, BSD, ISC, MPL-2.0, zlib, …): OK.
- LGPL, or GPL/EPL with a linking or Classpath exception: WARN. Acceptable, but say so in your
  summary, and confirm the exception exists in the license text.
- Anything stricter (GPL/AGPL without a linking exception, SSPL, proprietary): STOP. Don't add it;
  surface it and require an explicit call-out in the PR description for a maintainer to decide.

## Kotlin style

- **Log through Quartz's `Log`** (`com.vitorpamplona.quartz.utils.Log`), never `android.util.Log`.
  Interpolated messages use the lambda overload (`Log.d(TAG) { "…$x" }`); in a catch block pass the
  throwable (`Log.w(TAG, "…", e)`).
- **Never write fully-qualified class names inline in function bodies**: import the class (use
  `import … as Alias` for a real collision). Comments, KDoc and string literals are exempt.
- **Never put raw invisible or bidirectional Unicode characters in source files**: write them as
  `\uXXXX` escapes, even in test data. The one exception is U+200D inside a real emoji ZWJ sequence.

## Agent configuration (Claude Code + Codex)

Laid out like Quartz's: this file holds the rules, `.claude/CLAUDE.md` imports it with
`@../AGENTS.md`, skills (none yet) live in `.claude/skills/<name>/` with an `.agents/skills/<name>`
symlink, and the shared pre-push checks are in `tools/agent-hooks/` (`agent_config_check.py` runs
in CI too).

## Releasing

Release Quartz first if this version needs a new one. Bump `VERSION_NAME` in `gradle.properties`,
then push a `v<version>` tag. `create-release.yml` refuses to publish against an unpublished Quartz,
runs the Apple tests on macOS, then the checks, publishes to Maven Central and opens the GitHub
release. It needs `SONATYPE_USERNAME`, `SONATYPE_PASSWORD`, `SIGNING_PRIVATE_KEY` and
`SIGNING_PASSWORD`, the same secrets as Quartz.
