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
};
const INVALID_DURATION = { ...VALID_DURATION, durationSeconds: 0 };
const PERSONALLY_TAGGED_DURATION = { ...VALID_DURATION, uid: "uid-alice" };
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
];

// Який прогін є доказом кожного рядка.
const EVIDENCE = {
  R1: "as-is", R4: "as-is", D1: "as-is", D4: "as-is",
  R2: "open", R3: "open", R5: "open", R6: "open", R7: "open",
  R8: "open", R9: "open", R10: "open", D2: "open", D3: "open",
  T1: "as-is", T2: "open", T3: "open", T4: "open", T5: "open",
  T6: "open", T7: "as-is", C1: "as-is", C2: "open", C3: "open",
  C4: "open", C5: "open", C6: "as-is", C7: "open", C8: "open",
  F1: "as-is", F2: "open", F3: "open", F4: "open", F5: "open",
  F6: "open", F7: "as-is", F8: "open", F9: "open", F10: "open",
  F11: "open", F12: "open", F13: "open", F14: "open", F15: "open",
  F16: "open", F17: "open", F18: "open", F19: "open",
  F20: "open", F21: "open", F22: "open", F23: "open",
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
