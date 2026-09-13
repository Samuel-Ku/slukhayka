#!/usr/bin/env node
// #528 — прибирання спільної бази від значень, які не є тривалістями книжок.
//
// Навіщо це обов'язково. `book_durations` дозволяє лише `create`
// (`allow update, delete: if false`), а клієнт читає документ без
// `schemaVersion` як промах. Отже документ, що вже лежить у базі, блокує
// правильне значення назавжди: ні виправити, ні перезаписати. Поки 52-секундна
// врізка там, спільна тривалість цієї книжки не відновиться.
//
// Одне правило, без вигадування (ADR-0014):
//   значення проходить контракт -> лишаємо його, лише ставимо schemaVersion: 2;
//   значення не проходить     -> видаляємо, бо це не тривалість книжки.
//
// За замовчуванням — сухий прогін. Пише лише з `--apply`, і лише після того,
// як збереже резервну копію того, що збирається змінити.

import { readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { cert, initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { isContractValue, SCHEMA_VERSION } from "./inventory.mjs";

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, "..", "..");
const APPLY = process.argv.includes("--apply");
const BACKUP = process.argv.includes("--backup")
  ? process.argv[process.argv.indexOf("--backup") + 1]
  : `/tmp/slukhayka-duration-cleanup-${new Date().toISOString().replace(/[:.]/g, "-")}.json`;

function credentials() {
  const jsonPath = process.env.GOOGLE_SERVICES_JSON ?? resolve(REPO, "app", "google-services.json");
  const projectId = process.env.FIREBASE_PROJECT
    ?? JSON.parse(readFileSync(jsonPath, "utf8")).project_info?.project_id;
  if (!projectId) throw new Error(`немає project_id у ${jsonPath}`);

  const keyPath = process.env.GOOGLE_APPLICATION_CREDENTIALS;
  if (!keyPath) {
    throw new Error(
      "потрібен GOOGLE_APPLICATION_CREDENTIALS — шлях до ключа сервісного акаунта.\n" +
      "Firebase Console → Project settings → Service accounts → Generate new private key.\n" +
      "Ключ не комітити; він обходить правила, тож поводься з ним як з паролем."
    );
  }
  return { projectId, serviceAccount: JSON.parse(readFileSync(keyPath, "utf8")) };
}

async function main() {
  const { projectId, serviceAccount } = credentials();
  const db = getFirestore(initializeApp({ credential: cert(serviceAccount), projectId }));

  const stamp = [];
  const remove = [];
  const clearProfiles = [];

  for (const snapshot of await db.collection("book_durations").get()) {
    const data = snapshot.data() ?? {};
    const seconds = data.durationSeconds;
    if (data.schemaVersion === SCHEMA_VERSION && isContractValue(seconds)) continue;
    if (isContractValue(seconds)) {
      stamp.push({ id: snapshot.id, data });
    } else {
      remove.push({ id: snapshot.id, data });
    }
  }
  for (const snapshot of await db.collection("book_duration_conflicts").get()) {
    const data = snapshot.data() ?? {};
    if (!isContractValue(data.candidateSeconds)) remove.push({ path: `book_duration_conflicts/${snapshot.id}`, data });
  }
  for (const snapshot of await db.collection("book_profiles").get()) {
    const data = snapshot.data() ?? {};
    if (data.totalDurationSeconds != null && !isContractValue(data.totalDurationSeconds)) {
      clearProfiles.push({ id: snapshot.id, value: data.totalDurationSeconds });
    }
  }

  console.log(`проєкт ${projectId}`);
  console.log(`  поставити schemaVersion: 2 — ${stamp.length}`);
  console.log(`  видалити як не-тривалість — ${remove.length}`);
  console.log(`  стерти поле в профілях      — ${clearProfiles.length}`);
  for (const row of remove) console.log(`   - ${row.path ?? `book_durations/${row.id}`}  ${row.data.durationSeconds ?? row.data.candidateSeconds}s  ${row.data.source ?? "?"}`);
  for (const row of clearProfiles) console.log(`   ~ book_profiles/${row.id}  totalDurationSeconds=${row.value}`);

  if (!APPLY) {
    console.log("\nсухий прогін. Щоб застосувати: --apply");
    return;
  }

  writeFileSync(BACKUP, JSON.stringify({ projectId, stamp, remove, clearProfiles }, null, 2));
  console.log(`\nрезервна копія: ${BACKUP}`);

  const operations = [
    ...stamp.map((row) => ({
      ref: db.collection("book_durations").doc(row.id),
      update: { schemaVersion: SCHEMA_VERSION },
    })),
    ...remove.map((row) => ({
      ref: row.path ? db.doc(row.path) : db.collection("book_durations").doc(row.id),
      update: null,
    })),
    ...clearProfiles.map((row) => ({
      ref: db.collection("book_profiles").doc(row.id),
      update: { totalDurationSeconds: null },
    })),
  ];
  // Firestore приймає 500 операцій на пакет. База поки мала, але мовчазне
  // падіння на півдорозі коштує дорожче за три зайві рядки.
  for (let index = 0; index < operations.length; index += 400) {
    const batch = db.batch();
    for (const operation of operations.slice(index, index + 400)) {
      if (operation.update) batch.update(operation.ref, operation.update);
      else batch.delete(operation.ref);
    }
    await batch.commit();
  }
  console.log("застосовано.");
}

await main();
