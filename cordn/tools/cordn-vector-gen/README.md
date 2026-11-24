# cordn-vector-gen

Emits `cordn/src/commonTest/resources/cordn/coordinator-contracts.json` from
**`@cordn/core`** — the package cordn's own reference coordinator and client
both import. Consumed by `CoordinatorContractVectorTest` and
`CordnGroupRefVectorTest`.

## Why this exists

Our ts-mls fixtures (`resources/tsmls/`) cover the *crypto* layer: KeyPackages,
Welcomes, exporters, sealed payloads. They say nothing about the layer above —
the eleven coordinator tools, their argument names, which fields are optional,
and the `cordn1…` group ref. That layer had been verified only against our
reading of the spec, which is exactly the kind of agreement that holds right up
until someone runs a real coordinator.

So this generator goes the other way: it takes the payloads **we** put on the
wire and hands each one to **their** zod schema. A field we named wrong fails at
generation time. Each method also carries `rejects` — payloads their schema must
refuse — because a positive result only means something if the schema is strict
where we assume it is.

Group refs are round-tripped through their bech32 codec, and the vectors pin
both directions: we must decode what they encode, *and* encode byte-identically.
Only the second half catches TLV ordering, since the spec lets decoders accept
any order.

## Regenerating

```
cd cordn/tools/cordn-vector-gen
npm install
node generate.mjs > ../../src/commonTest/resources/cordn/coordinator-contracts.json
```

Output is deterministic — fixed actors, fixed timestamps, no randomness — so a
regeneration that changes the file means cordn changed something. Commit the
result with the reason.

## Licensing

`@cordn/core` and `@cordn/cli` are **MIT** and each ship a LICENSE file; this
generator is a dev-only dependency on the first and ships in nothing.

The rest of the `Cordn-msg/cordn` repository — `packages/coordinator`,
`packages/server`, `packages/test-utils`, and the `ghcr.io/cordn-msg/cordn`
image built from them — carries **no license file and no `license` field**, so
default copyright applies. That is why Tier B (a live coordinator round-trip)
is not wired up here and why these vectors are generated from the two licensed
packages instead. See `cordn/plans/2026-09-17-cordn-interop.md` §7.

# App-event vectors

`app-events.mjs` emits `cordn/src/commonTest/resources/cordn/app-events/`: the unsigned
application events members send inside a group (spec/02.md envelopes: text, replies, mentions,
edits, deletions, reactions and custom emoji, pins, polls and votes, calendar events and RSVPs,
`imeta` media, call announcements), and what **cordn-web** — the reference client
(`Cordn-msg/cordn-web`, MIT) — decides about each one. `CordnAppEventVectorTest` asks cordn-kmp
the same questions and expects the same answers.

| File | Envelopes from | Read by |
| ---- | -------------- | ------- |
| `theirs-cordn-web.json` | cordn-web's own builders (`resolveOutboundMessage`, `createPollMessageTags`, `createCallAnnouncementTags`, `createCalendarEventMessage`, `buildImetaTag`, `serializeChatProfileMentions`, …), plus hand-written edge cases | cordn-web, then cordn-kmp |
| `ours-read-by-cordn-web.json` | cordn-kmp's builders (`OurAppEvents` in `jvmAndroidTest`) | cordn-web, then cordn-kmp |

One file per direction, an array of cases. A case is a small group stream:

```json
{ "name": "...", "source": "cordn-web 0.7.0 (949dfdf)", "me": "<viewer pubkey>", "now": 1767229200,
  "stream": [ { "cursor": 1, "at": 1767225660250, "event": { "id": "...", "pubkey": "...", "created_at": 0, "kind": 9, "tags": [], "content": "..." } } ],
  "expect": { "<event id>": { "row": true, "reply": {"root": "...", "parent": "..."}, "edit": "...", "deleted": true,
      "pinned": true, "reactions": {"👍": ["<pubkey>"]}, "custom_emoji": {":x:": {"name": "x", "url": "..."}},
      "mentions": ["<pubkey>"], "attention": "<row id a p tag to `me` lights up>",
      "attachments": ["<url of each attachment the row draws>"], "media": {...} | "unsupported",
      "call": {...} | "unsupported", "calendar": {...} | "unsupported", "poll": {...} | "unsupported" } } }
```

`at` is the coordinator's receive time (ms): cordn-web orders edits, pins and votes by it and
judges a poll's deadline by it, and it rises with the cursor; cordn-kmp reads it as
`CordnDeliveredMessage.receivedAt`. Two messages may share one (a coordinator's clock is in ms). A field is omitted when it has nothing to say. Where cordn-web departs
from the spec and cordn-kmp keeps the spec, the test lists the case and the fields with the
reason, and fails once they agree again.

## Regenerating

```
git clone https://github.com/Cordn-msg/cordn-web ../cordn-web     # beside this repo
cordn/tools/cordn-vector-gen/app-events.sh                         # CORDN_WEB_DIR=… to point elsewhere
./gradlew :cordn:jvmTest --tests '*CordnAppEventVectorTest*'
```

The script runs the test once to write `cordn/build/interop/app-events/ours-streams.json` (our
envelopes), then `node --experimental-strip-types app-events.mjs`, which loads cordn-web's
TypeScript modules straight from the checkout through `cordn-web-loader.mjs` (it maps `$lib/`,
resolves packages from this directory's `node_modules`, and stubs the three browser-only modules
the interpretation code never calls). No cordn-web code is copied into this repository; the
output records the cordn-web version and commit it came from. Deterministic: rerunning against
the same commit changes nothing. A change to `OurAppEvents` fails the test until the vectors are
regenerated, so cordn-web reads every new envelope before it ships.

`nostr-tools` (Unlicense), `@noble/*`, `applesauce-*`, `clsx` and `tailwind-merge` (MIT) are
installed only because cordn-web's modules import them; like `@cordn/core`, they ship in nothing.
