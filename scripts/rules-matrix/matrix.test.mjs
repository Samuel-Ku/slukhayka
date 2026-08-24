#!/usr/bin/env node
/**
 * Spec-40 #283 — контрольна матриця правил Firestore на емуляторі.
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
];

// Який прогін є доказом кожного рядка.
const EVIDENCE = {
  R1: "as-is", R4: "as-is", D1: "as-is", D4: "as-is",
  R2: "open", R3: "open", R5: "open", R6: "open", R7: "open",
  R8: "open", R9: "open", R10: "open", D2: "open", D3: "open",
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
  await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: { rules, host: "127.0.0.1", port: 8080 },
  });

  // Сід для update/delete — лише в open-прогоні (в as-is гейт чесно
  // забороняє будь-який запис, легального сіду немає).
  if (gateLabel === "open") {
    const seeder = await makeDb("uid-alice");
    for (const id of ["qa_r6", "qa_r7", "qa_r8", "qa_r9"]) {
      await setDoc(doc(collection(seeder.db, "book_reviews"), id), VALID_BODY);
    }
    await seeder.done();
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
    "# Матриця правил Firestore — spec-40 #283",
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
    "Глосарій (CONTEXT.md: «Відгук», «Код відновлення», примітка до Source",
    "Binding) і розкриття приватності в README закриті комітом 81c8fa8.",
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
