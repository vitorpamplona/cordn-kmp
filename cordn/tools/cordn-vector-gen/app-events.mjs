// Emits cordn app-event interop vectors from cordn-web, the reference cordn client.
//
//   resources/cordn/app-events/theirs-cordn-web.json
//       Envelopes cordn-web's own builders produce (plus a few hand-written edge cases), and
//       what cordn-web's own interpretation code decides about each message.
//   resources/cordn/app-events/ours-read-by-cordn-web.json
//       Envelopes OUR builders produce (written by CordnAppEventVectorTest to
//       cordn/build/interop/app-events/ours-streams.json), and what cordn-web decides about them.
//
// CordnAppEventVectorTest then asks cordn-kmp the same questions and expects the same answers,
// in both files. The answers are a per-message "view" (row, reply, edit, deleted, pinned,
// reactions, mentions, attention, media, call, calendar, poll), described in
// cordn/tools/cordn-vector-gen/README.md.
//
// cordn-web (MIT) is read from a checkout at generation time through cordn-web-loader.mjs and
// Node's type stripping; none of its code is copied here. Its version and commit go into the
// output. The only composition below that is not a cordn-web export is `isRow`, which restates
// the row filter of its ChatShell.svelte (a component Node cannot load); it cites the lines.
//
// Usage: cordn/tools/cordn-vector-gen/app-events.sh (see README.md).
import { register } from "node:module";
import { execFileSync } from "node:child_process";
import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, "../../..");
const web = resolve(process.env.CORDN_WEB_DIR ?? resolve(repo, "../cordn-web"));
const out = resolve(repo, "cordn/src/commonTest/resources/cordn/app-events");
const oursIn = resolve(repo, "cordn/build/interop/app-events/ours-streams.json");

if (!existsSync(`${web}/src/lib/chat/references.ts`)) {
  console.error(`no cordn-web checkout at ${web} (set CORDN_WEB_DIR)`);
  process.exit(2);
}

// Svelte 5 runes are compile-time macros; the modules below only use them at module scope for
// reactive stores this generator never reads. Plain values stand in.
globalThis.$state = (value) => value;
globalThis.$derived = (value) => value;
globalThis.$effect = Object.assign(() => {}, { root: () => () => {} });

register(pathToFileURL(resolve(here, "cordn-web-loader.mjs")).href, { data: { webRoot: web } });

const lib = (path) => import(pathToFileURL(`${web}/src/lib/${path}.ts`).href);
const { ChatKinds, isStreamHiddenKind } = await lib("chat/kinds");
const { buildAnnotationIndex, getMessageThreadReference, resolveOutboundMessage } = await lib("chat/references");
const { buildPollTally, getMessagePollDefinition, describePollVote } = await lib("chat/polls");
const { EMOJI_MEDIA_TAG } = await lib("chat/emoji");
const { parseCallAnnouncement, buildCallProjection, createCallAnnouncementTags } = await lib("chat/calls");
const {
  buildCalendarProjection,
  getCalendarEventDefinition,
  isCalendarEventKind,
  calendarCoordinate,
  createCalendarEventMessage,
  createCalendarRsvpTags,
  createCalendarCancelTags,
} = await lib("chat/calendar");
const { buildImetaTag, buildMediaTag, findImetaTag, isKnownMediaVersion, MEDIA_VERSION } = await lib("services/chatMediaCipher");
const { parseChatProfileMentions, serializeChatProfileMentions } = await lib("services/chatMentions");
const { buildMessageLedgerRows } = await lib("services/chatUnreadLedger.svelte");
const { getEventHash } = await import("nostr-tools/pure");
const { npubEncode } = await import("nostr-tools/nip19");

const webVersion = JSON.parse(readFileSync(`${web}/package.json`, "utf8")).version;
let webCommit = "unknown";
try {
  webCommit = execFileSync("git", ["-C", web, "rev-parse", "--short", "HEAD"], { encoding: "utf8" }).trim();
} catch {
  // not a git checkout
}
const SOURCE = `cordn-web ${webVersion} (${webCommit})`;

// ---- actors and clock ---------------------------------------------------------------------

// 64 lowercase hex, as a cordn credential carries them (spec/00.md §13).
const ALICE = "a11ce".padEnd(64, "0");
const BOB = "b0b".padEnd(64, "0");
const CAROL = "ca201".padEnd(64, "0");
const T0 = 1_767_225_600; // 2026-01-01T00:00:00Z
const NOW = T0 + 3_600; // what "now" is for a poll's open/closed state

// ---- the view: what cordn-web decides about every message in a stream ----------------------

function toStored(delivered, me) {
  const event = delivered.event;
  return {
    cursor: delivered.cursor,
    // cordn-web's StoredChatMessage.createdAt is the coordinator's `at` (ms), not the
    // envelope's created_at; eventCreatedAt is the envelope's.
    createdAt: delivered.at,
    eventCreatedAt: event.created_at,
    direction: event.pubkey === me ? "outbound" : "inbound",
    sender: event.pubkey,
    id: event.id,
    kind: event.kind,
    tags: event.tags,
    content: event.content,
  };
}

/**
 * ChatShell.svelte's `confirmedMessages` filter (cordn-web 949dfdf, lines 459-475), minus the
 * reaction "marker rows", which are a display setting and never a message's own row.
 */
function isRow(message, latestCallIds, latestCalendarIds) {
  if (message.kind === ChatKinds.Reaction) return false;
  if (isStreamHiddenKind(message.kind)) return false;
  if (message.kind === ChatKinds.Call) {
    return latestCallIds.has(message.id) || !parseCallAnnouncement(message.kind, message.tags ?? []);
  }
  if (!isCalendarEventKind(message.kind)) return true;
  const definition = getCalendarEventDefinition(message.kind, "", message.tags);
  const coordinate = definition ? calendarCoordinate({ kind: message.kind, author: message.sender, d: definition.d }) : null;
  return coordinate ? latestCalendarIds.has(message.id) : true;
}

function viewOf(stream, me, now) {
  const messages = stream.map((delivered) => toStored(delivered, me));
  const { reactionMap, editMap, deletedIds, pinSet } = buildAnnotationIndex(messages);
  const callCards = buildCallProjection(messages);
  const latestCallIds = new Set(callCards.map((card) => card.message.id));
  const calendarCards = buildCalendarProjection(messages, { myPubkey: me, timeZone: "UTC" });
  const latestCalendarIds = new Set(calendarCards.map((card) => card.id));
  const cardByRevision = new Map();
  for (const card of calendarCards) for (const id of card.revisionIds) cardByRevision.set(id, card);
  const attention = new Map(buildMessageLedgerRows("group", messages, me).map((row) => [row.cursor, row.refTargetId]));

  const views = {};
  for (const message of messages) {
    const view = { row: isRow(message, latestCallIds, latestCalendarIds) };

    const thread = getMessageThreadReference(message.tags);
    if (thread) view.reply = { root: thread.rootId, parent: thread.parentId };

    const cancelledCard = isCalendarEventKind(message.kind) && cardByRevision.get(message.id)?.cancelled;
    const deleted = deletedIds.has(message.id) && !cancelledCard;
    if (deleted) view.deleted = true;

    const edit = editMap.get(message.id);
    if (edit) view.edit = edit.content;

    const pin = deleted ? undefined : pinSet.get(message.id);
    if (pin?.op === "add") view.pinned = true;

    const reactions = deleted ? undefined : reactionMap.get(message.id);
    if (reactions && reactions.size > 0) {
      view.reactions = {};
      for (const [emoji, entry] of [...reactions.entries()].sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))) {
        view.reactions[emoji] = [...entry.reactors.keys()].sort();
        if (entry.display) {
          view.custom_emoji ??= {};
          view.custom_emoji[emoji] = {
            name: entry.display.name,
            ...(entry.display.url ? { url: entry.display.url } : {}),
            ...(entry.display.media ? { media_url: entry.display.media.url } : {}),
          };
        }
      }
    }

    const mentioned = [];
    for (const part of parseChatProfileMentions(message.content)) {
      if (part.type === "profile" && !mentioned.includes(part.pubkey)) mentioned.push(part.pubkey);
    }
    if (mentioned.length > 0) view.mentions = mentioned;

    const target = attention.get(message.cursor);
    if (target) view.attention = target;

    // What the row draws: cordn-web shows one attachment per message, the first `imeta` that
    // parses (ChatMessageMedia via findImetaTag), and none when its version is unknown; it sends
    // one file per message, but NIP-92 lets a message carry several.
    if (message.tags.some((tag) => tag[0] === "imeta")) {
      const first = findImetaTag(message.tags);
      view.attachments = first && isKnownMediaVersion(first.version) ? [first.url] : [];
    }

    const media = findImetaTag(message.tags);
    if (media) {
      view.media = isKnownMediaVersion(media.version)
        ? {
            url: media.url,
            mime: media.mime,
            filename: media.filename,
            x: media.plaintextHashHex,
            n: media.nonceHex,
            ...(media.dim ? { dim: media.dim } : {}),
            ...(media.blurhash ? { blurhash: media.blurhash } : {}),
            ...(media.thumbhash ? { thumbhash: media.thumbhash } : {}),
            ...(media.alt ? { alt: media.alt } : {}),
            ...(media.durationMs !== undefined ? { duration_ms: media.durationMs } : {}),
            ...(media.waveform ? { waveform: media.waveform } : {}),
          }
        : "unsupported";
    }

    if (message.kind === ChatKinds.Call) {
      const card = callCards.find((candidate) => candidate.message.id === message.id);
      if (!parseCallAnnouncement(message.kind, message.tags)) view.call = "unsupported";
      else if (card) {
        const a = card.announcement;
        view.call = {
          id: a.callId,
          open: a.status === "open",
          service: a.service,
          starts: a.startsAt,
          ...(a.endsAt !== undefined ? { ends: a.endsAt } : {}),
          ...(a.title ? { title: a.title } : {}),
          ...(a.identitySecret ? { identity_secret: a.identitySecret } : {}),
        };
      }
    }

    if (isCalendarEventKind(message.kind)) {
      if (!getCalendarEventDefinition(message.kind, message.content, message.tags)) view.calendar = "unsupported";
      else {
        const card = calendarCards.find((candidate) => candidate.id === message.id);
        if (card) {
          view.calendar = {
            title: card.definition.title,
            revised: card.revised,
            cancelled: card.cancelled,
            going: card.going.map((a) => a.pubkey),
            maybe: card.maybe.map((a) => a.pubkey),
            declined: card.declined.map((a) => a.pubkey),
          };
        }
      }
    }

    if (message.kind === ChatKinds.Poll) {
      const tally = buildPollTally(messages, message.id, me, now);
      view.poll = tally
        ? {
            question: tally.definition.question,
            type: tally.definition.pollType,
            ...(tally.definition.endsAt !== null ? { ends_at: tally.definition.endsAt } : {}),
            open: tally.open,
            options: tally.options.map((option) => ({ id: option.id, label: option.label, votes: option.votes })),
            participants: tally.participants,
            selection: tally.selection,
          }
        : "unsupported";
    }

    views[message.id] = view;
  }
  return views;
}

// ---- envelopes ------------------------------------------------------------------------------

function envelope(pubkey, createdAt, shape) {
  const event = { pubkey, created_at: createdAt, kind: shape.kind, tags: shape.tags, content: shape.content };
  return { id: getEventHash(event), ...event };
}

/** A stream builder: each send gets the next cursor, and the coordinator's `at` a moment after `created_at`. */
function stream() {
  const messages = [];
  return {
    messages,
    send(pubkey, shape, { createdAt, at } = {}) {
      const cursor = messages.length + 1;
      const created = createdAt ?? T0 + cursor * 60;
      const event = envelope(pubkey, created, shape);
      messages.push({ cursor, at: at ?? (T0 + cursor * 60) * 1000 + 250, event });
      return event;
    },
  };
}

const text = (content, extra = {}) => resolveOutboundMessage({ content, ...extra });
const target = (event) => ({ id: event.id, pubkey: event.pubkey, kind: event.kind });
const replyTarget = (event) => ({ ...target(event), content: event.content, tags: event.tags });
const HASH = "5a".repeat(32);
const NONCE = "0c".repeat(12);
const CALL_ID = "ca11".repeat(16);
const SECRET = "5ec2".repeat(16);

const theirCases = [];
function theirs(name, me, build, note) {
  const s = stream();
  build(s);
  theirCases.push({ name, source: SOURCE, ...(note ? { note } : {}), me, now: NOW, stream: s.messages });
}

theirs("text", BOB, (s) => {
  s.send(ALICE, text("hello, group"));
});

theirs("reply, then a reply to the reply keeps the root", BOB, (s) => {
  const root = s.send(ALICE, text("lunch?"));
  const reply = s.send(BOB, text("yes", { replyTo: replyTarget(root) }));
  s.send(CAROL, text("me too", { replyTo: replyTarget(reply) }));
});

theirs("mention picked from the composer: nostr:npub in content and a p tag", BOB, (s) => {
  const serialized = serializeChatProfileMentions("thanks @Bob!", [{ pubkey: BOB, label: "Bob" }]);
  s.send(ALICE, { kind: ChatKinds.Text, content: serialized.content, tags: serialized.tags });
});

theirs("mention typed as a bare nostr:npub", CAROL, (s) => {
  const serialized = serializeChatProfileMentions(`ask nostr:${npubEncode(CAROL)} about it`, []);
  s.send(ALICE, { kind: ChatKinds.Text, content: serialized.content, tags: serialized.tags });
});

theirs("reply to my message lights it up through its p tag", ALICE, (s) => {
  const mine = s.send(ALICE, text("anyone?"));
  s.send(BOB, text("here", { replyTo: replyTarget(mine) }));
});

theirs("edit: newest of the author's edits wins, a non-author's edit is ignored", BOB, (s) => {
  const original = s.send(ALICE, text("teh plan"));
  s.send(ALICE, resolveOutboundMessage({ content: "the plan", editTo: target(original) }));
  s.send(BOB, resolveOutboundMessage({ content: "hijacked", editTo: target(original) }));
  s.send(ALICE, resolveOutboundMessage({ content: "the plan, v2", editTo: target(original) }));
});

theirs(
  "edit from a device whose clock is behind: the later delivery wins",
  BOB,
  (s) => {
    const original = s.send(ALICE, text("draft"));
    s.send(ALICE, resolveOutboundMessage({ content: "first fix", editTo: target(original) }), { createdAt: T0 + 600 });
    s.send(ALICE, resolveOutboundMessage({ content: "second fix", editTo: target(original) }), { createdAt: T0 + 300 });
  },
  "cordn-web orders edits by the coordinator's receive time, not the sender's created_at",
);

theirs("deletion: the author's counts; a non-author's, or one naming the wrong kind, does not", BOB, (s) => {
  const a = s.send(ALICE, text("oops"));
  const b = s.send(ALICE, text("keep me"));
  const c = s.send(ALICE, text("also keep me"));
  s.send(ALICE, resolveOutboundMessage({ content: "", deleteTo: target(a) }));
  s.send(BOB, resolveOutboundMessage({ content: "", deleteTo: target(b) }));
  s.send(ALICE, { kind: ChatKinds.Deletion, content: "", tags: [["e", c.id, "", ALICE], ["k", "1111"]] });
});

theirs("a deleted message keeps no edit, no pin and no reactions", BOB, (s) => {
  const a = s.send(ALICE, text("soon gone"));
  s.send(BOB, resolveOutboundMessage({ content: "🔥", reactionTo: target(a) }));
  s.send(CAROL, resolveOutboundMessage({ content: "", pinTo: target(a), pinOp: "add" }));
  s.send(ALICE, resolveOutboundMessage({ content: "", deleteTo: target(a) }));
  s.send(ALICE, resolveOutboundMessage({ content: "back from the dead", editTo: target(a) }));
});

theirs("reactions: emoji, '+', several senders, and one taken back with a deletion", ALICE, (s) => {
  const a = s.send(ALICE, text("ship it?"));
  s.send(BOB, resolveOutboundMessage({ content: "👍", reactionTo: target(a) }));
  s.send(CAROL, resolveOutboundMessage({ content: "👍", reactionTo: target(a) }));
  s.send(CAROL, resolveOutboundMessage({ content: "+", reactionTo: target(a) }));
  const undo = s.send(BOB, resolveOutboundMessage({ content: "🎉", reactionTo: target(a) }));
  s.send(BOB, resolveOutboundMessage({ content: "", deleteTo: target(undo) }));
});

theirs("custom emoji reaction: NIP-30 emoji tag", BOB, (s) => {
  const a = s.send(ALICE, text("new logo"));
  s.send(BOB, resolveOutboundMessage({ content: ":catjam:", reactionTo: target(a), tags: [["emoji", "catjam", "https://cdn.example/catjam.gif"]] }));
});

theirs("custom emoji reaction: encrypted emoji-media tag", BOB, (s) => {
  const a = s.send(ALICE, text("new logo"));
  const media = buildMediaTag(EMOJI_MEDIA_TAG, {
    url: "https://blossom.example/" + HASH,
    mime: "image/png",
    filename: "party.png",
    plaintextHashHex: HASH,
    nonceHex: NONCE,
    version: MEDIA_VERSION,
  });
  media.splice(1, 0, "name party"); // as chatMediaStorage.svelte.ts names the shortcode it stands behind
  s.send(BOB, resolveOutboundMessage({ content: ":party:", reactionTo: target(a), tags: [media] }));
});

theirs("pin, unpin and pin again by different members", BOB, (s) => {
  const a = s.send(ALICE, text("rules: be kind"));
  const b = s.send(ALICE, text("wifi: hunter2"));
  s.send(BOB, resolveOutboundMessage({ content: "", pinTo: target(a), pinOp: "add" }));
  s.send(CAROL, resolveOutboundMessage({ content: "", pinTo: target(b), pinOp: "add" }));
  s.send(ALICE, resolveOutboundMessage({ content: "", pinTo: target(b), pinOp: "remove" }));
  s.send(BOB, { kind: ChatKinds.Pin, content: "", tags: [["e", a.id, "", ALICE], ["op", "toggle"]] });
});

theirs("poll: single choice, a changed vote, a vote for an unknown option", ALICE, (s) => {
  const poll = s.send(ALICE, resolveOutboundMessage({
    content: "Where to?",
    poll: { question: "Where to?", optionLabels: ["Pizza", "Sushi", "Tacos"], pollType: "singlechoice", endsAt: null },
  }));
  const definition = getMessagePollDefinition(poll.kind, poll.content, poll.tags);
  const vote = (voter, ids) =>
    s.send(voter, resolveOutboundMessage({ content: describePollVote(definition, ids), voteTo: { target: target(poll), optionIds: ids } }));
  vote(BOB, ["0"]);
  vote(CAROL, ["1"]);
  vote(BOB, ["2"]);
  s.send(CAROL, { kind: ChatKinds.PollResponse, content: "Voted", tags: [["e", poll.id, "", ALICE], ["p", ALICE], ["k", "1068"], ["response", "9"]] });
  vote(ALICE, ["1"]);
});

theirs("poll: multiple choice with a deadline that has passed", BOB, (s) => {
  const poll = s.send(ALICE, {
    kind: ChatKinds.Poll,
    content: "Which days?",
    tags: [["option", "mon", "Monday"], ["option", "tue", "Tuesday"], ["option", "wed", "Wednesday"], ["polltype", "multiplechoice"], ["endsAt", String(T0 + 1_800)], ["alt", "Poll: Which days?"]],
  });
  s.send(BOB, resolveOutboundMessage({ content: "Voted: Monday, Wednesday", voteTo: { target: target(poll), optionIds: ["mon", "wed"] } }));
  s.send(CAROL, resolveOutboundMessage({ content: "Voted: Tuesday", voteTo: { target: target(poll), optionIds: ["tue"] } }));
});

theirs("poll: malformed (one option) is unsupported", BOB, (s) => {
  s.send(ALICE, { kind: ChatKinds.Poll, content: "Yes?", tags: [["option", "0", "Yes"], ["polltype", "singlechoice"]] });
});

const meeting = (title, startTime) => ({
  title,
  description: "Agenda in the doc",
  location: "Room 4",
  link: "https://example.com/doc",
  image: "",
  allDay: false,
  startDate: "2026-01-02",
  endDate: "",
  startTime,
  endTime: "11:00",
});

theirs("calendar: a revision replaces the event, RSVPs fold onto it, the author cancels it", BOB, (s) => {
  const first = s.send(ALICE, createCalendarEventMessage({ d: "standup", draft: meeting("Standup", "10:00"), timeZone: "UTC" }));
  const second = s.send(ALICE, createCalendarEventMessage({ d: "standup", draft: meeting("Standup (moved)", "10:30"), timeZone: "UTC" }));
  const ref = { id: second.id, kind: second.kind, author: ALICE, d: "standup" };
  s.send(BOB, { kind: ChatKinds.CalendarRsvp, content: "", tags: createCalendarRsvpTags(ref, "accepted") });
  s.send(CAROL, { kind: ChatKinds.CalendarRsvp, content: "", tags: createCalendarRsvpTags(ref, "tentative") });
  s.send(CAROL, { kind: ChatKinds.CalendarRsvp, content: "", tags: createCalendarRsvpTags(ref, "declined") });
  s.send(ALICE, { kind: ChatKinds.Deletion, content: "", tags: createCalendarCancelTags(ref) });
  void first;
});

theirs("calendar: an all-day event, and one that fails validation", BOB, (s) => {
  s.send(ALICE, createCalendarEventMessage({
    d: "offsite",
    draft: { ...meeting("Offsite", ""), allDay: true, startDate: "2026-02-10", endDate: "2026-02-12", startTime: "", endTime: "" },
    timeZone: "UTC",
  }));
  s.send(ALICE, { kind: ChatKinds.CalendarTime, content: "", tags: [["d", "broken"], ["title", "No start"]] });
});

theirs(
  "calendar: a non-author's cancellation after the author's",
  BOB,
  (s) => {
    const event = s.send(ALICE, createCalendarEventMessage({ d: "retro", draft: { ...meeting("Retro", "15:00"), endTime: "16:00" }, timeZone: "UTC" }));
    const ref = { id: event.id, kind: event.kind, author: ALICE, d: "retro" };
    s.send(ALICE, { kind: ChatKinds.Deletion, content: "", tags: createCalendarCancelTags(ref) });
    s.send(BOB, { kind: ChatKinds.Deletion, content: "", tags: [["a", `${event.kind}:${ALICE}:retro`]] });
  },
  "cordn-web keeps the newest cancellation from anyone and only then checks its author; calendar-events.md §6 ignores a non-author's",
);

theirs("media: an image and a voice note", BOB, (s) => {
  s.send(ALICE, text("look", {
    tags: [buildImetaTag({
      url: "https://blossom.example/" + HASH,
      mime: "image/jpeg",
      filename: "sunset.jpg",
      plaintextHashHex: HASH,
      nonceHex: NONCE,
      version: MEDIA_VERSION,
      dim: "1200x800",
      blurhash: "LKO2?U%2Tw=w]~RBVZRi};RPxuwH",
      thumbhash: "1QcSHQRnh493V4dIh4eXh1h4kJUI",
      alt: "a sunset over the bay",
    })],
  }));
  s.send(ALICE, {
    kind: ChatKinds.Text,
    content: "",
    tags: [buildImetaTag({
      url: "https://blossom.example/" + "7b".repeat(32),
      mime: "audio/webm",
      filename: "voice.webm",
      plaintextHashHex: "7b".repeat(32),
      nonceHex: "1d".repeat(12),
      version: MEDIA_VERSION,
      durationMs: 4200,
      waveform: [0.1, 0.5, 1, 0.25],
    })],
  });
  s.send(ALICE, { kind: ChatKinds.Text, content: "from the future", tags: [["imeta", `url https://blossom.example/${HASH}`, "m image/png", "filename x.png", `x ${HASH}`, `n ${NONCE}`, "v cordn-em-v9"]] });
});

const photo = (n) =>
  buildImetaTag({
    url: `https://blossom.example/${String(n).repeat(64)}`,
    mime: "image/jpeg",
    filename: `photo-${n}.jpg`,
    plaintextHashHex: String(n).repeat(64),
    nonceHex: NONCE,
    version: MEDIA_VERSION,
    dim: "800x600",
  });

theirs(
  "media: two attachments in one message",
  BOB,
  (s) => {
    s.send(ALICE, { kind: ChatKinds.Text, content: "two photos", tags: [photo(1), photo(2)] });
  },
  "NIP-92 allows several imeta tags; cordn-web sends one file per message and draws only the first of a message that has more",
);

theirs(
  "media: an unknown version before a supported attachment",
  BOB,
  (s) => {
    const future = ["imeta", `url https://blossom.example/${HASH}`, "m image/png", "filename x.png", `x ${HASH}`, `n ${NONCE}`, "v cordn-em-v9"];
    s.send(ALICE, { kind: ChatKinds.Text, content: "old and new", tags: [future, photo(3)] });
  },
  "encrypted-media.md §4 rejects the unknown-version imeta; cordn-web then shows the message's media as unsupported rather than the next one",
);

theirs(
  "edit: two edits the coordinator received in the same millisecond",
  BOB,
  (s) => {
    const original = s.send(ALICE, text("draft"));
    const at = (T0 + 600) * 1000 + 7;
    s.send(ALICE, resolveOutboundMessage({ content: "first fix", editTo: target(original) }), { at });
    s.send(ALICE, resolveOutboundMessage({ content: "second fix", editTo: target(original) }), { at });
  },
  "cordn-web keeps the first edit on a receive-time tie (strict >, no cursor tie-break), where its pins and votes break ties on the cursor",
);

theirs(
  "poll: a vote received after the deadline, backdated by its sender",
  BOB,
  (s) => {
    // Hand-built: createPollMessageTags refuses a deadline that is not in the future of the
    // generator's own clock.
    const poll = s.send(ALICE, {
      kind: ChatKinds.Poll,
      content: "Lunch?",
      tags: [["option", "0", "Yes"], ["option", "1", "No"], ["polltype", "singlechoice"], ["endsAt", String(T0 + 1_800)], ["alt", "Poll: Lunch?"]],
    });
    // Both written before the deadline by their senders' clocks; the second reached the
    // coordinator after it.
    s.send(BOB, resolveOutboundMessage({ content: "Voted: Yes", voteTo: { target: target(poll), optionIds: ["0"] } }), { createdAt: T0 + 1_700, at: (T0 + 1_700) * 1000 });
    s.send(CAROL, resolveOutboundMessage({ content: "Voted: No", voteTo: { target: target(poll), optionIds: ["1"] } }), { createdAt: T0 + 1_750, at: (T0 + 1_801) * 1000 });
  },
  "cordn-web judges a poll's deadline by the coordinator's receive time (floor(at / 1000) > endsAt is late)",
);

const announcement = (overrides) => ({ callId: CALL_ID, service: "https://calls.example", status: "open", startsAt: T0 + 60, identitySecret: SECRET, title: "Design sync", ...overrides });
const callShape = (a) => ({ kind: ChatKinds.Call, content: "", tags: createCallAnnouncementTags(a) });

theirs("call: opened by one member, closed by another", BOB, (s) => {
  s.send(ALICE, callShape(announcement({})));
  s.send(BOB, callShape(announcement({ status: "closed", identitySecret: null, endsAt: T0 + 900 })));
});

theirs("call: still open", BOB, (s) => {
  s.send(ALICE, callShape(announcement({ title: undefined })));
});

theirs("call: malformed announcements keep a row that says so", BOB, (s) => {
  s.send(ALICE, { kind: ChatKinds.Call, content: "", tags: [["d", CALL_ID], ["service", "https://calls.example"], ["status", "open"], ["starts", String(T0)]] });
  s.send(ALICE, { kind: ChatKinds.Call, content: "", tags: [["d", "not-hex"], ["service", "https://calls.example"], ["status", "closed"], ["starts", String(T0)]] });
  s.send(ALICE, { kind: ChatKinds.Call, content: "", tags: [["d", "ee".repeat(32)], ["service", "https://calls.example"], ["status", "closed"], ["starts", "soon"]] });
});

theirs("attention: a reaction to my message and a vote on my poll point at what they target", ALICE, (s) => {
  const mine = s.send(ALICE, text("thoughts?"));
  const poll = s.send(ALICE, resolveOutboundMessage({ content: "Q", poll: { question: "Q", optionLabels: ["A", "B"], pollType: "singlechoice", endsAt: null } }));
  s.send(BOB, resolveOutboundMessage({ content: "❤️", reactionTo: target(mine) }));
  s.send(CAROL, resolveOutboundMessage({ content: "Voted: B", voteTo: { target: target(poll), optionIds: ["1"] } }));
});

theirs(
  "kinds cordn-web does not draw: every addressable revision and a blank one keep their rows",
  BOB,
  (s) => {
    s.send(ALICE, { kind: 30023, content: "# Notes v1", tags: [["d", "notes"]] });
    s.send(ALICE, { kind: 30023, content: "# Notes v2", tags: [["d", "notes"]] });
    s.send(ALICE, { kind: 20, content: "", tags: [] });
    s.send(ALICE, { kind: 1, content: "a short note", tags: [] });
  },
  "cordn-web renders every unknown-kind message as a row; cordn-kmp's CordnStreamRows applies NIP-01 replacement and hides blank ones",
);

// ---- ours: streams written by CordnAppEventVectorTest --------------------------------------

function round(json) {
  return `${JSON.stringify(json, null, 1)}\n`;
}

const theirsOut = {
  generator: "cordn/tools/cordn-vector-gen/app-events.mjs",
  reference: SOURCE,
  cases: theirCases.map((c) => ({ ...c, expect: viewOf(c.stream, c.me, c.now) })),
};
writeFileSync(`${out}/theirs-cordn-web.json`, round(theirsOut));
console.error(`theirs: ${theirCases.length} cases`);

if (existsSync(oursIn)) {
  const ours = JSON.parse(readFileSync(oursIn, "utf8"));
  const oursOut = {
    generator: "cordn/tools/cordn-vector-gen/app-events.mjs",
    reference: SOURCE,
    producer: ours.producer,
    cases: ours.cases.map((c) => {
      // The id is what every reference names; recompute it the way cordn-web does.
      for (const delivered of c.stream) {
        const { id, ...rest } = delivered.event;
        const hashed = getEventHash(rest);
        if (hashed !== id) throw new Error(`${c.name}: our id ${id} is not cordn-web's ${hashed}`);
      }
      return { ...c, expect: viewOf(c.stream, c.me, c.now) };
    }),
  };
  writeFileSync(`${out}/ours-read-by-cordn-web.json`, round(oursOut));
  console.error(`ours: ${ours.cases.length} cases`);
} else {
  console.error(`no ${oursIn}: run CordnAppEventVectorTest first (app-events.sh does) for ours-read-by-cordn-web.json`);
}
