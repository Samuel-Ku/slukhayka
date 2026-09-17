#!/usr/bin/env node
/**
 * Spec-40 #283 + Spec-42 #303/#311 — контрольна матриця правил Firestore.
 *
 * ДВА ПРОГОНИ (оркеструє run-all.sh):
 *   A) RULES_GATE=as-is — правила як в репо: без App Check токена не пишеться
 *      НІЧОГО навіть власником (R4, D4); читання публічні (R1, D1).
 *   B) RULES_GATE=open — вираз isAppCheckValid() підмінено на (true) лише в
 *      тексті, що заливається в емулятор цього прогону (репозиторій чистий).
 *      Ізолює володіння uid, валідацію полів і вимогу auth.
 *
 * Обмеження середовища: Node-клієнти не прикріплюють X-Firebase-AppCheck,
 * тому «ON»-рядки доказуються прогоном B, а сам гейт — OFF-рядками A.
 * Auth: довільні uid через Auth-емулятор (custom token, alg:none).
 *
 * Запуск усього: ./run-all.sh [шлях-до-звіту.md]
 */
import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { initializeApp, deleteApp } from "firebase/app";
import {
  getAuth,
  connectAuthEmulator,
  signInWithCustomToken,
} from "firebase/auth";
import {
  getFirestore,
  connectFirestoreEmulator,
  collection,
  doc,
  setDoc,
  getDoc,
  deleteDoc,
} from "firebase/firestore";
import { initializeTestEnvironment } from "@firebase/rules-unit-testing";

const here = dirname(fileURLToPath(import.meta.url));
const PROJECT_ID = "spec40-matrix";
const MODE = process.env.RULES_MODE ?? "run"; // run | merge

const VALID_BODY = {
  workId: "qa-work",
  uid: "uid-alice",
  authorName: "QA",
  rating: 5,
  body: "перевірка матриці",
  createdAt: 1700000000000,
};
const MALFORMED_BODY = { ...VALID_BODY, rating: 9 };
// викликач uid-bob, у тілі чужий uid=uid-alice — перевірка володіння
const FOREIGN_BODY = { ...VALID_BODY, uid: "uid-alice" };

const VALID_DURATION = {
  durationSeconds: 7_200,
  source: "4read",
  method: "source_metadata",
  derivedAt: 1700000000000,
  schemaVersion: 2,
};
const INVALID_DURATION = { ...VALID_DURATION, durationSeconds: 0 };
const PERSONALLY_TAGGED_DURATION = { ...VALID_DURATION, uid: "uid-alice" };
// #528 — the two shapes that let the 4read interstitial become a whole book's
// canonical duration: a stale build that predates `schemaVersion`, and the ad
// length itself, which used to clear `value > 0`.
const OLD_SCHEMA_DURATION = {
  durationSeconds: 7_200,
  source: "4read",
  method: "source_metadata",
  derivedAt: 1700000000000,
};
const INTERSTITIAL_DURATION = { ...VALID_DURATION, durationSeconds: 52 };
const VALID_DURATION_CONFLICT = {
  editionId: "edition-qa",
  candidateSeconds: 8_000,
  source: "4read",
  method: "technical_probe",
  observedAt: 1700000000000,
};
const INVALID_DURATION_CONFLICT = {
  ...VALID_DURATION_CONFLICT,
  candidateSeconds: 8_001,
  source: "x".repeat(101),
};

// #522 — one collective catalogue card: public book facts, no personal fields.
const VALID_CARD = {
  sourceId: "soundbooks",
  sourceUrl: "https://sound-books.net/kobzar",
  title: "Кобзар",
  author: "Тарас Шевченко",
  narrator: "Диктор",
  language: "uk",
  observedAt: 1700000000000,
};
const CARD_WITH_QUERY = { ...VALID_CARD, query: "шевченко" };
const OVERLONG_CARD = { ...VALID_CARD, title: "т".repeat(301) };

// #835 — one moderation candidate exactly as the app's codec writes it: a
// hashed identity, bounded text, and a fresh candidate is ALWAYS pending.
const VALID_CANDIDATE = {
  url: "https://www.youtube.com/watch?v=6XIPkMFZf-0",
  canonicalUrl: "https://www.youtube.com/watch?v=6XIPkMFZf-0",
  title: "Острів Дума",
  author: "Стівен Кінг",
  narrator: "Диктор",
  coverUrl: "https://i.ytimg.com/vi/6XIPkMFZf-0/hq.jpg",
  durationSeconds: 5400,
  chaptersCount: 2,
  sourceId: "youtube-ed1-abc",
  metadataJson: "{\"id\":\"6XIPkMFZf-0\"}",
  submitterHash: "a".repeat(64),
  playedAt: 1700000000000,
  createdAt: 1700000001000,
  state: "pending",
};
const CANDIDATE_APPROVED = { ...VALID_CANDIDATE, state: "approved" };
const CANDIDATE_WITH_DECISION = { ...VALID_CANDIDATE, decidedAt: 1700000002000 };
const OVERLONG_CANDIDATE = { ...VALID_CANDIDATE, title: "т".repeat(301) };
const CANDIDATE_WITH_QUERY = { ...VALID_CANDIDATE, query: "шевченко" };
const BLOCKLIST_ENTRY = {
  canonicalUrl: "https://youtu.be/abc",
  reason: "неаудіокнига",
  rejectedAt: 1700000000000,
  rejectedBy: "curator-bot",
};

// #691 — one published listener collection. authorId is sha256(uid): a 64-char
// hex string, never the raw uid.
const VALID_COLLECTION = {
  authorId: "a".repeat(64),
  collectionId: "c1",
  pseudonym: "Слухач",
  title: "Магія",
  description: "про зорі",
  bookIds: ["soundbooks-1", "soundbooks-2"],
  reasons: ["бо раз", "бо два"],
  publishedAt: 1700000000000
};
const COLLECTION_BAD_REASONS = { ...VALID_COLLECTION, reasons: "не список" };
const COLLECTION_WITH_QUERY = { ...VALID_COLLECTION, query: "магія" };
const OVERLONG_COLLECTION = { ...VALID_COLLECTION, title: "т".repeat(81) };
const RAW_UID_COLLECTION = { ...VALID_COLLECTION, authorId: "test-uid-1" };
// #694 — a published collection may carry the transactional rating aggregate.
const COLLECTION_WITH_RATINGS = {
  ...VALID_COLLECTION,
  ratingSum: 9,
  ratingCount: 2
};
// #694 — one anonymous vote: id is sha256(uid + collectionId), shape is fixed.
const VALID_COLLECTION_VOTE = {
  documentId: "a".repeat(64) + "-c1",
  stars: 5,
  createdAt: 1700000000000
};
const VOTE_WITH_BAD_STARS = { ...VALID_COLLECTION_VOTE, stars: 9 };
const VOTE_WITH_EXTRA_FIELD = { ...VALID_COLLECTION_VOTE, uid: "raw-uid" };


// #527 — one shared collective block: identity, provenance and ordered cards.
const VALID_BLOCK = {
  blockKey: "audiobookmp3|NEW_ARRIVALS",
  sourceId: "audiobookmp3",
  kind: "NEW_ARRIVALS",
  name: "Новинки audiobook-mp3",
  provenanceUrl: "https://audiobook-mp3.com/uk",
  fetchedAt: 1700000000000,
  staleAfter: 1700021600000,
  version: 1,
  cards: [
    {
      sourceId: "audiobookmp3",
      sourceUrl: "https://audiobook-mp3.com/uk-audio-6163-x",
      title: "Клуб боягузів",
      author: "Андрій Кокотюха",
    },
  ],
};
const BLOCK_WITH_QUERY = { ...VALID_BLOCK, query: "кокотюха" };
const EMPTY_BLOCK = { ...VALID_BLOCK, cards: [] };

function durationConflictId(conflict) {
  return `${conflict.editionId}|${conflict.candidateSeconds}|${conflict.method}`;
}

function facetId(kind, entityId, sourceId) {
  return `${kind}~${entityId}~${sourceId}`;
}

const WORK_ENTITY_ID = "лісова-пісня|леся-українка";
const FACET_SOURCE_ID = "4read";
const WORK_FACET_ID = facetId("work", WORK_ENTITY_ID, FACET_SOURCE_ID);
const EDITION_FACET_ID = facetId("edition", "edition-qa", FACET_SOURCE_ID);
const VALID_WORK_FACET = {
  schemaVersion: 1,
  assertionId: WORK_FACET_ID,
  entityKind: "work",
  entityId: WORK_ENTITY_ID,
  sourceId: FACET_SOURCE_ID,
  observedAt: 1700000000000,
  updatedAt: 41,
  author: { id: "author-lesia", name: "Леся Українка", aliases: ["Лариса Косач"] },
  genres: [
    { id: "drama", rawText: "Драма" },
    { id: "fantasy", rawText: "Фентезі" },
  ],
  seriesMemberships: [{ seriesId: "forest-cycle", position: 2 }],
};
const UPDATED_WORK_FACET = {
  ...VALID_WORK_FACET,
  updatedAt: 42,
  genres: [{ id: "drama", rawText: "Драма" }],
};
function withFacetIdentity(base, overrides) {
  const value = { ...base, ...overrides };
  return {
    ...value,
    assertionId: facetId(value.entityKind, value.entityId, value.sourceId),
  };
}
const CREATED_WORK_FACET = withFacetIdentity(VALID_WORK_FACET, {
  entityId: "work-created-by-first-client",
});
const MAX_WORK_FACET = {
  ...VALID_WORK_FACET,
  entityId: "work-max-bounds",
  assertionId: facetId("work", "work-max-bounds", FACET_SOURCE_ID),
  author: {
    ...VALID_WORK_FACET.author,
    aliases: Array.from({ length: 8 }, (_, index) => `alias-${index}`),
  },
  genres: Array.from(
    { length: 4 },
    (_, index) => ({ id: `genre-${index}`, rawText: index === 0 ? "Ж".repeat(200) : `Жанр ${index}` })
  ),
  seriesMemberships: Array.from(
    { length: 4 },
    (_, index) => ({ seriesId: `series-${index}`, position: index + 1 })
  ),
};
const VALID_EDITION_FACET = {
  schemaVersion: 1,
  assertionId: EDITION_FACET_ID,
  entityKind: "edition",
  entityId: "edition-qa",
  sourceId: "4read",
  observedAt: 1700000000000,
  updatedAt: 43,
  workId: "лісова-пісня|леся-українка",
  narrator: { id: "narrator-qa", name: "Оповідач", aliases: [] },
  language: "uk",
  durationRef: "edition-qa",
  durationBucket: "under_5h",
  chapterCount: 12,
  completeness: "full",
  availabilityAvailable: true,
  availabilityObservedAt: 1700000000000,
  availabilityTtlSeconds: 86400,
};

const MALFORMED_WORK_FACET = withFacetIdentity(VALID_WORK_FACET, {
  entityId: "work-malformed-list",
  author: { ...VALID_WORK_FACET.author, aliases: [42] },
});
const NO_APPCHECK_WORK_FACET = withFacetIdentity(VALID_WORK_FACET, {
  entityId: "work-no-appcheck",
});
const MIXED_WORK_FACET = withFacetIdentity(VALID_WORK_FACET, {
  entityId: "work-mixed-kind",
  narrator: VALID_EDITION_FACET.narrator,
});
const BAD_GENRE_WORK_FACET = withFacetIdentity(VALID_WORK_FACET, {
  entityId: "work-bad-genre",
  genres: [{ id: "fantasy", rawText: 42 }],
});
const BAD_SERIES_WORK_FACET = withFacetIdentity(VALID_WORK_FACET, {
  entityId: "work-bad-series",
  seriesMemberships: [{ seriesId: "series", position: "two" }],
});
const BAD_REF_EDITION_FACET = withFacetIdentity(VALID_EDITION_FACET, {
  entityId: "edition-bad-ref",
  durationRef: "other-edition",
});
const BAD_TTL_EDITION_FACET = withFacetIdentity(VALID_EDITION_FACET, {
  entityId: "edition-bad-ttl",
  durationRef: "edition-bad-ttl",
  availabilityTtlSeconds: 0,
});
const BAD_BUCKET_EDITION_FACET = withFacetIdentity(VALID_EDITION_FACET, {
  entityId: "edition-bad-bucket",
  durationRef: "edition-bad-bucket",
  durationBucket: "overnight",
});
const DUPLICATE_WORK_FACET_ID = `${WORK_FACET_ID}~duplicate`;
const DUPLICATE_WORK_FACET = {
  ...VALID_WORK_FACET,
  assertionId: DUPLICATE_WORK_FACET_ID,
};
const FUTURE_WORK_FACET = withFacetIdentity(VALID_WORK_FACET, {
  entityId: "work-future-cursor",
  updatedAt: Date.now() + 10 * 60 * 1000,
});
const BLANK_ID_WORK_FACET = withFacetIdentity(VALID_WORK_FACET, {
  entityId: "   ",
});
const DUPLICATE_ALIAS_WORK_FACET = withFacetIdentity(VALID_WORK_FACET, {
  entityId: "work-duplicate-alias",
  author: { ...VALID_WORK_FACET.author, aliases: ["Лариса Косач", "Лариса Косач"] },
});
const DUPLICATE_GENRE_WORK_FACET = withFacetIdentity(VALID_WORK_FACET, {
  entityId: "work-duplicate-genre",
  genres: [
    { id: "fantasy", rawText: "Фентезі" },
    { id: "fantasy", rawText: "Фантастика" },
  ],
});
const DUPLICATE_SERIES_WORK_FACET = withFacetIdentity(VALID_WORK_FACET, {
  entityId: "work-duplicate-series",
  seriesMemberships: [
    { seriesId: "forest-cycle", position: 1 },
    { seriesId: "forest-cycle", position: 2 },
  ],
});

// id | path | method | uid | тіло | очікувано | чому
const MATRIX = [
  ["R1", "book_reviews/qa_r1", "get", null, null, "ALLOW", "читання публічне"],
  ["R2", "book_reviews/qa_r2", "create", "uid-alice", VALID_BODY, "ALLOW", "свій uid (+AppCheck у проді)"],
  ["R3", "book_reviews/qa_r3", "create", "uid-bob", FOREIGN_BODY, "DENY", "чужий uid у тілі"],
  ["R4", "book_reviews/qa_r4", "create", "uid-alice", VALID_BODY, "DENY", "нема AppCheck-токена"],
  ["R5", "book_reviews/qa_r5", "create", null, FOREIGN_BODY, "DENY", "нема auth"],
  ["R6", "book_reviews/qa_r6", "update", "uid-alice", VALID_BODY, "ALLOW", "свій uid, документ існує"],
  ["R7", "book_reviews/qa_r7", "update", "uid-bob", FOREIGN_BODY, "DENY", "чужий uid, документ існує"],
  ["R8", "book_reviews/qa_r8", "delete", "uid-alice", null, "ALLOW", "свій uid, документ існує"],
  ["R9", "book_reviews/qa_r9", "delete", "uid-bob", null, "DENY", "чужий uid, документ існує"],
  ["R10", "book_reviews/qa_r10", "create", "uid-alice", MALFORMED_BODY, "DENY", "rating поза 1..5"],
  ["D1", "device_bindings/qa_d1", "get", null, null, "ALLOW", "читання публічне (до auth)"],
  ["D2", "device_bindings/qa_d2", "create", "uid-alice", { uid: "uid-alice", cred: "sealed-bytes" }, "ALLOW", "свій uid"],
  ["D3", "device_bindings/qa_d3", "create", "uid-bob", { uid: "uid-alice", cred: "sealed-bytes" }, "DENY", "чужий uid"],
  ["D4", "device_bindings/qa_d4", "create", "uid-alice", { uid: "uid-alice", cred: "sealed-bytes" }, "DENY", "нема AppCheck-токена"],
  ["T1", "book_durations/qa_t1", "get", null, null, "ALLOW", "канонічне читання публічне"],
  ["T2", "book_durations/qa_t2", "create", null, VALID_DURATION, "ALLOW", "правдоподібний create (+AppCheck у проді)"],
  ["T3", "book_durations/qa_t3", "create", null, INVALID_DURATION, "DENY", "неправдоподібна тривалість"],
  ["T4", "book_durations/qa_t4", "create", null, PERSONALLY_TAGGED_DURATION, "DENY", "зайве особисте поле"],
  ["T5", "book_durations/qa_t5", "update", null, VALID_DURATION, "DENY", "canonical update заборонений"],
  ["T6", "book_durations/qa_t6", "delete", null, null, "DENY", "canonical delete заборонений"],
  ["T7", "book_durations/qa_t7", "create", null, VALID_DURATION, "DENY", "нема AppCheck-токена"],
  ["T8", "book_durations/qa_t8", "create", null, OLD_SCHEMA_DURATION, "DENY", "старий клієнт без schemaVersion — бан по джерелу"],
  ["T9", "book_durations/qa_t9", "create", null, INTERSTITIAL_DURATION, "DENY", "52-секундна врізка не стає тривалістю книжки"],
  ["C1", "book_duration_conflicts/qa_c1", "get", null, null, "ALLOW", "conflict read публічне"],
  ["C2", `book_duration_conflicts/${durationConflictId(VALID_DURATION_CONFLICT)}`, "create", null, VALID_DURATION_CONFLICT, "ALLOW", "bounded conflict create з канонічним id"],
  ["C3", `book_duration_conflicts/${durationConflictId(INVALID_DURATION_CONFLICT)}`, "create", null, INVALID_DURATION_CONFLICT, "DENY", "завелика provenance"],
  ["C4", "book_duration_conflicts/qa_c4", "update", null, VALID_DURATION_CONFLICT, "DENY", "conflict update заборонений"],
  ["C5", "book_duration_conflicts/qa_c5", "delete", null, null, "DENY", "conflict delete заборонений"],
  ["C6", `book_duration_conflicts/${durationConflictId(VALID_DURATION_CONFLICT)}`, "create", null, VALID_DURATION_CONFLICT, "DENY", "нема AppCheck-токена"],
  ["C7", "book_duration_conflicts/alternate-duplicate-id", "create", null, VALID_DURATION_CONFLICT, "DENY", "id не відповідає Edition/value/method"],
  ["C8", `book_duration_conflicts/${durationConflictId(VALID_DURATION_CONFLICT)}`, "create", null, VALID_DURATION_CONFLICT, "DENY", "повтор не створює другий conflict"],
  ["F1", `book_facets/${WORK_FACET_ID}`, "get", null, null, "ALLOW", "facet read публічне"],
  ["F2", `book_facets/${CREATED_WORK_FACET.assertionId}`, "create", null, CREATED_WORK_FACET, "ALLOW", "bounded Work create (+AppCheck у проді)"],
  ["F3", `book_facets/${WORK_FACET_ID}`, "update", null, UPDATED_WORK_FACET, "ALLOW", "повний factual update зі сталою identity"],
  ["F4", `book_facets/${WORK_FACET_ID}`, "update", null, { ...UPDATED_WORK_FACET, entityId: "інший-work" }, "DENY", "entity identity immutable"],
  ["F5", `book_facets/${MALFORMED_WORK_FACET.assertionId}`, "create", null, MALFORMED_WORK_FACET, "DENY", "non-string alias"],
  ["F6", `book_facets/${WORK_FACET_ID}`, "delete", null, null, "DENY", "facet delete заборонений"],
  ["F7", `book_facets/${NO_APPCHECK_WORK_FACET.assertionId}`, "create", null, NO_APPCHECK_WORK_FACET, "DENY", "нема AppCheck-токена"],
  ["F8", `book_facets/${EDITION_FACET_ID}`, "create", null, VALID_EDITION_FACET, "ALLOW", "bounded Edition create"],
  ["F9", `book_facets/${BAD_REF_EDITION_FACET.assertionId}`, "create", null, BAD_REF_EDITION_FACET, "DENY", "duration ref не змінює Edition identity"],
  ["F10", `book_facets/${BAD_TTL_EDITION_FACET.assertionId}`, "create", null, BAD_TTL_EDITION_FACET, "DENY", "availability без дійсного TTL"],
  ["F11", `book_facets/${MIXED_WORK_FACET.assertionId}`, "create", null, MIXED_WORK_FACET, "DENY", "rendition fact не живе на Work"],
  ["F12", `book_facets/${BAD_GENRE_WORK_FACET.assertionId}`, "create", null, BAD_GENRE_WORK_FACET, "DENY", "genre fact має bounded id/rawText shape"],
  ["F13", `book_facets/${BAD_SERIES_WORK_FACET.assertionId}`, "create", null, BAD_SERIES_WORK_FACET, "DENY", "Series Membership shape bounded"],
  ["F14", `book_facets/${BAD_BUCKET_EDITION_FACET.assertionId}`, "create", null, BAD_BUCKET_EDITION_FACET, "DENY", "неканонічний duration bucket"],
  ["F15", `book_facets/${WORK_FACET_ID}`, "update", null, { ...UPDATED_WORK_FACET, updatedAt: 40 }, "DENY", "update cursor не рухається назад"],
  ["F16", `book_facets/${MAX_WORK_FACET.assertionId}`, "create", null, MAX_WORK_FACET, "ALLOW", "bounded maxima лишаються записуваними"],
  ["F17", `book_facets/${CREATED_WORK_FACET.assertionId}`, "get-existing", null, null, "ALLOW", "інший клієнт бачить створений assertion"],
  ["F18", `book_facets/${DUPLICATE_WORK_FACET_ID}`, "create", null, DUPLICATE_WORK_FACET, "DENY", "другий document id для тієї самої identity заборонений"],
  ["F19", `book_facets/${FUTURE_WORK_FACET.assertionId}`, "create", null, FUTURE_WORK_FACET, "DENY", "майбутній cursor не заморожує assertion"],
  ["F20", `book_facets/${BLANK_ID_WORK_FACET.assertionId}`, "create", null, BLANK_ID_WORK_FACET, "DENY", "blank entity identity заборонена"],
  ["F21", `book_facets/${DUPLICATE_ALIAS_WORK_FACET.assertionId}`, "create", null, DUPLICATE_ALIAS_WORK_FACET, "DENY", "alias values унікальні"],
  ["F22", `book_facets/${DUPLICATE_GENRE_WORK_FACET.assertionId}`, "create", null, DUPLICATE_GENRE_WORK_FACET, "DENY", "genre ids унікальні"],
  ["F23", `book_facets/${DUPLICATE_SERIES_WORK_FACET.assertionId}`, "create", null, DUPLICATE_SERIES_WORK_FACET, "DENY", "Series ids унікальні"],
  // #522 — the collective catalogue cards lane.
  ["K1", "catalog_cards/qa_k1", "get", null, null, "ALLOW", "картка — публічний факт, читання відкрите"],
  ["K2", "catalog_cards/qa_k2", "create", "uid-alice", VALID_CARD, "DENY", "#835 — каталог закрито для клієнтів"],
  ["K3", "catalog_cards/qa_k3", "create", "uid-alice", CARD_WITH_QUERY, "DENY", "зайве поле query (hasOnly)"],
  ["K4", "catalog_cards/qa_k4", "create", "uid-alice", OVERLONG_CARD, "DENY", "title поза межею"],
  ["K5", "catalog_cards/qa_k5", "create", null, VALID_CARD, "DENY", "нема auth"],
  ["K6", "catalog_cards/qa_k6", "create", "uid-alice", VALID_CARD, "DENY", "нема AppCheck-токена"],
  ["K7", "catalog_cards/qa_k7", "update", "uid-alice", VALID_CARD, "DENY", "#835 — оновлення каталогу клієнтом закрито"],
  ["K8", "catalog_cards/qa_k2", "delete", "uid-alice", null, "DENY", "client delete заборонений"],
  // #835 — модерація: черга кандидатів, блокліст і ЗАКРИТИЙ каталог.
  ["P1", "pending_submissions/qa_p1", "get", null, null, "ALLOW", "кандидат читається публічно (identity — хеш)"],
  ["P2", "pending_submissions/qa_p2", "create", "uid-alice", VALID_CANDIDATE, "ALLOW", "well-formed кандидат (+AppCheck у проді)"],
  ["P3", "pending_submissions/qa_p3", "create", "uid-alice", CANDIDATE_APPROVED, "DENY", "клієнт не схвалює: state != pending"],
  ["P4", "pending_submissions/qa_p4", "create", "uid-alice", CANDIDATE_WITH_DECISION, "DENY", "рішення бота не у формі клієнта (hasOnly)"],
  ["P5", "pending_submissions/qa_p5", "create", "uid-alice", OVERLONG_CANDIDATE, "DENY", "title поза межею"],
  ["P6", "pending_submissions/qa_p6", "create", "uid-alice", CANDIDATE_WITH_QUERY, "DENY", "зайве поле query (hasOnly)"],
  ["P7", "pending_submissions/qa_p7", "create", null, VALID_CANDIDATE, "DENY", "нема auth"],
  ["P8", "pending_submissions/qa_p8", "create", "uid-alice", VALID_CANDIDATE, "DENY", "нема AppCheck-токена"],
  ["P9", "pending_submissions/qa_p2", "update", "uid-alice", VALID_CANDIDATE, "DENY", "стан міняє лише бот"],
  ["P10", "pending_submissions/qa_p2", "delete", "uid-alice", null, "DENY", "client delete заборонений"],
  ["P11", "rejected_submissions/qa_p11", "get", null, null, "ALLOW", "блокліст читається, щоб відмовити чесно"],
  ["P12", "rejected_submissions/qa_p12", "create", "uid-alice", BLOCKLIST_ENTRY, "DENY", "блокліст пише лише бот"],
  ["P13", "rejected_submissions/qa_p11", "update", "uid-alice", BLOCKLIST_ENTRY, "DENY", "блокліст пише лише бот"],
  ["P14", "catalog_cards/qa_p14", "create", "uid-alice", VALID_CARD, "DENY", "#835 — каталог закрито для клієнтів"],
  // #527 — the shared collective blocks.
  ["L1", "catalog_blocks/qa_l1", "get", null, null, "ALLOW", "блок — публічний факт, читання відкрите"],
  ["L2", "catalog_blocks/qa_l2", "create", "uid-alice", VALID_BLOCK, "ALLOW", "bounded block (+AppCheck у проді)"],
  ["L3", "catalog_blocks/qa_l3", "create", "uid-alice", BLOCK_WITH_QUERY, "DENY", "зайве поле query (hasOnly)"],
  ["L4", "catalog_blocks/qa_l4", "create", "uid-alice", EMPTY_BLOCK, "DENY", "порожній блок не публікується"],
  ["L5", "catalog_blocks/qa_l5", "create", null, VALID_BLOCK, "DENY", "нема auth"],
  ["L6", "catalog_blocks/qa_l6", "create", "uid-alice", VALID_BLOCK, "DENY", "нема AppCheck-токена"],
  ["L7", "catalog_blocks/qa_l2", "delete", "uid-alice", null, "DENY", "client delete заборонений"],
  // #691 — published listener collections.
  ["M1", "curator_collections/qa_m1", "get", null, null, "ALLOW", "опублікована добірка — публічний факт, читання відкрите"],
  ["M2", "curator_collections/qa_m2", "create", "uid-alice", VALID_COLLECTION, "ALLOW", "валідна форма (+AppCheck у проді)"],
  ["M3", "curator_collections/qa_m3", "create", "uid-alice", COLLECTION_WITH_QUERY, "DENY", "зайве поле query (hasOnly)"],
  ["M4", "curator_collections/qa_m4", "create", "uid-alice", OVERLONG_COLLECTION, "DENY", "назва поза межею 80"],
  ["M5", "curator_collections/qa_m5", "create", "uid-alice", RAW_UID_COLLECTION, "DENY", "authorId мусить бути sha256 (64), не сирий uid"],
  ["M6", "curator_collections/qa_m6", "create", null, VALID_COLLECTION, "DENY", "нема auth"],
  ["M7", "curator_collections/qa_m7", "create", "uid-alice", VALID_COLLECTION, "DENY", "нема AppCheck-токена"],
  ["M8", "curator_collections/qa_m8", "create", "uid-alice", COLLECTION_BAD_REASONS, "DENY", "reasons не список"],
  ["M9", "curator_collections/qa_m9", "create", "uid-alice", COLLECTION_WITH_RATINGS, "ALLOW", "#694 — агрегат оцінок дозволений"],
  ["M10", "curator_collection_votes/qa_m10", "create", "uid-alice", VALID_COLLECTION_VOTE, "ALLOW", "#694 — один анонімний голос"],
  ["M11", "curator_collection_votes/qa_m11", "create", "uid-alice", VOTE_WITH_BAD_STARS, "DENY", "#694 — зірки поза 1..5"],
  ["M12", "curator_collection_votes/qa_m12", "create", "uid-alice", VOTE_WITH_EXTRA_FIELD, "DENY", "#694 — сирий uid у документі голосу (hasOnly)"],
  ["M13", "curator_collection_votes/qa_m13", "create", null, VALID_COLLECTION_VOTE, "DENY", "#694 — нема auth"],
];

// Який прогін є доказом кожного рядка.
const EVIDENCE = {
  R1: "as-is", R4: "as-is", D1: "as-is", D4: "as-is",
  R2: "open", R3: "open", R5: "open", R6: "open", R7: "open",
  R8: "open", R9: "open", R10: "open", D2: "open", D3: "open",
  T1: "as-is", T2: "open", T3: "open", T4: "open", T5: "open",
  T6: "open", T7: "as-is", C1: "as-is", C2: "open", C3: "open",
  // #528 — the ban is only meaningful with the App Check gate OPEN, otherwise
  // the row would pass for the wrong reason (no token, not a refused shape).
  T8: "open", T9: "open",
  C4: "open", C5: "open", C6: "as-is", C7: "open", C8: "open",
  F1: "as-is", F2: "open", F3: "open", F4: "open", F5: "open",
  F6: "open", F7: "as-is", F8: "open", F9: "open", F10: "open",
  F11: "open", F12: "open", F13: "open", F14: "open", F15: "open",
  F16: "open", F17: "open", F18: "open", F19: "open",
  F20: "open", F21: "open", F22: "open", F23: "open",
  K1: "as-is", K2: "open", K3: "open", K4: "open", K5: "open",
  // #691 — curator_collections: read and the App Check gate are as-is;
  // the shape/limit/auth rows are proven with the gate open.
  M1: "as-is", M7: "as-is",
  M2: "open", M3: "open", M4: "open", M5: "open", M6: "open", M8: "open",
  // #694/#696 — the rating aggregate, the vote and the report shapes (M9–M17):
  // allowed/denied shapes are proven with the App Check gate OPEN; the
  // "no auth" rows (M13, M16) are proven there too, like M6.
  M9: "open", M10: "open", M11: "open", M12: "open", M13: "open",
  M14: "open", M15: "open", M16: "open", M17: "open",
  // #835 — the queue's public read and the App Check gate are proven as-is;
  // the shape/auth/closed-catalogue rows need the gate OPEN to be meaningful.
  P1: "as-is", P8: "as-is", P11: "as-is",
  P2: "open", P3: "open", P4: "open", P5: "open", P6: "open", P7: "open",
  P9: "open", P10: "open", P12: "open", P13: "open", P14: "open",
  K6: "as-is", K7: "open", K8: "open",
  L1: "as-is", L2: "open", L3: "open", L4: "open", L5: "open",
  L6: "as-is", L7: "open",
};

function b64(o) {
  return Buffer.from(JSON.stringify(o)).toString("base64url");
}

let appSeq = 0;
async function makeDb(uid) {
  const name = `qa-${++appSeq}`;
  const app = initializeApp(
    { projectId: PROJECT_ID, appId: `1:0:web:${name}`, apiKey: "qa-key" },
    name
  );
  const db = getFirestore(app);
  connectFirestoreEmulator(db, "127.0.0.1", 8080);
  if (uid) {
    const auth = getAuth(app);
    connectAuthEmulator(auth, "http://127.0.0.1:9099", { disableWarnings: true });
    const nowSec = Math.floor(Date.now() / 1000);
    const jwt = [
      b64({ alg: "none", typ: "JWT" }),
      b64({
        sub: uid,
        user_id: uid,
        aud: "https://identitytoolkit.googleapis.com/google.identity.identitytoolkit.v1.IdentityToolkit",
        iss: "qa@spec40-matrix.iam.gserviceaccount.com",
        iat: nowSec,
        exp: nowSec + 3600,
      }),
      "",
    ].join(".");
    await signInWithCustomToken(auth, jwt);
  }
  return { db, done: () => deleteApp(app).catch(() => {}) };
}

async function attempt(row) {
  const [, path, method, uid, body] = row;
  const [collectionName, docId] = path.split("/");
  const client = await makeDb(uid);
  try {
    const ref = doc(collection(client.db, collectionName), docId);
    if (method === "get") await getDoc(ref);
    else if (method === "get-existing") {
      const snapshot = await getDoc(ref);
      if (!snapshot.exists()) return "ERROR:not-found";
    }
    else if (method === "create") await setDoc(ref, body);
    else if (method === "update") await setDoc(ref, body, { merge: true });
    else if (method === "delete") await deleteDoc(ref);
    return "ALLOW";
  } catch (e) {
    if (e?.code === "permission-denied") return "DENY";
    return `ERROR:${e?.code ?? e?.message ?? "?"}`;
  } finally {
    await client.done();
  }
}

async function runPass(gateLabel) {
  let rules = readFileSync(join(here, "firestore.rules"), "utf8");
  if (gateLabel === "open") {
    const substituted = rules.replace(/(?<!function )isAppCheckValid\(\)/g, "(true)");
    if (substituted === rules) throw new Error("гейт isAppCheckValid() не знайдено");
    rules = substituted;
  }
  const testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: { rules, host: "127.0.0.1", port: 8080 },
  });

  // Сід для update/delete — лише в open-прогоні (в as-is гейт чесно
  // забороняє будь-який запис, легального сіду немає).
  if (gateLabel === "open") {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      const seedDb = context.firestore();
      for (const id of ["qa_r6", "qa_r7", "qa_r8", "qa_r9"]) {
        await setDoc(doc(collection(seedDb, "book_reviews"), id), VALID_BODY);
      }
      for (const id of ["qa_t5", "qa_t6"]) {
        await setDoc(doc(collection(seedDb, "book_durations"), id), VALID_DURATION);
      }
      for (const id of ["qa_c4", "qa_c5"]) {
        await setDoc(doc(collection(seedDb, "book_duration_conflicts"), id), VALID_DURATION_CONFLICT);
      }
      for (const id of [WORK_FACET_ID]) {
        await setDoc(doc(collection(seedDb, "book_facets"), id), VALID_WORK_FACET);
      }
    });
  }

  const results = [];
  for (const row of MATRIX) {
    const actual = await attempt(row);
    results.push({
      id: row[0], path: row[1], method: row[2],
      uid: row[3] ?? "signed-out", expect: row[5], actual,
    });
    // Інкрементальне збереження: навіть пізній крах фонових ретраїв не
    // знищить результати прогону.
    writeFileSync(
      process.env.OUT_JSON ?? `results-${gateLabel}.json`,
      JSON.stringify({ gate: gateLabel, results }, null, 2)
    );
    console.log(
      `${actual === row[5] ? "✅" : "❌"} [${gateLabel}] ${row[0]} ${row[2].toUpperCase()} ${row[1]} · ${row[3] ?? "signed-out"} → ${actual} (${row[5]})`
    );
  }
  return { gate: gateLabel, results };
}

function merge(a, b, reportPath) {
  const byGate = {
    "as-is": Object.fromEntries(a.results.map((r) => [r.id, r])),
    open: Object.fromEntries(b.results.map((r) => [r.id, r])),
  };
  let mismatches = 0;
  const linesFull = MATRIX.map(([id, path, method, uid, , expect]) => {
    const src = EVIDENCE[id];
    const r = byGate[src][id];
    const ok = r.actual === expect;
    if (!ok) mismatches++;
    return `| ${ok ? "✅" : "❌"} | ${id} | \`${method.toUpperCase()} ${path}\` | ${uid ?? "signed-out"} | ${src === "as-is" ? "OFF" : "ON*"} | ${expect} | ${r.actual} |`;
  });

  const date = new Date().toISOString().slice(0, 10);
  const md = [
    "# Матриця правил Firestore — spec-40 #283 + spec-42 #303/#311",
    "",
    `Дата: ${date}. Firebase Emulator Suite (firestore + auth); правила —`,
    "`firestore.rules` із репозиторію на момент прогону.",
    "",
    "**Методологія.** Node-клієнти емулятора не передають X-Firebase-AppCheck,",
    "тому матриця виміряна двома прогонами одного файлу правил:",
    "- **OFF** — правила як в репозиторії: гейт чесно забороняє будь-який запис",
    "  без токена навіть власнику (це і є перевірка `isAppCheckValid()`);",
    "- **ON\\*** — вираз `isAppCheckValid()` підмінено на `(true)` лише в копії,",
    "  яку заливано в емулятор цього прогону: ізолюються володіння uid,",
    "  валідація полів та вимога auth. У проді гейт стоїть першим в усіх",
    "  create/update/delete — його обхід без токена неможливий, що і",
    "  підтверджують OFF-рядки.",
    "",
    "| OK | № | Операція | auth | AppCheck | Очікувано | Факт |",
    "|---|---|---|---|---|---|---|",
    ...linesFull,
    "",
    `Підсумок: **${MATRIX.length - mismatches}/${MATRIX.length}**, розбіжностей: ${mismatches}.`,
    "",
    "Повторний прогін: `./run-all.sh` із цього каталогу (потрібен Java).",
  ].join("\n");
  writeFileSync(reportPath, md + "\n");
  console.log(md.split("\n").slice(15).join("\n"));
  console.log(`\nЗвіт: ${reportPath}`);
  return mismatches;
}

async function main() {
  if (MODE === "run") {
    await runPass(process.env.RULES_GATE ?? "as-is");
    process.exit(0);
  }
  const a = JSON.parse(readFileSync(join(here, "results-as-is.json"), "utf8"));
  const b = JSON.parse(readFileSync(join(here, "results-open.json"), "utf8"));
  const mismatches = merge(a, b, process.argv[2] ?? join(here, "rules-matrix-report.md"));
  process.exit(mismatches === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error(`[${MODE}] матриця не виконалась:`, e);
  process.exit(2);
});
