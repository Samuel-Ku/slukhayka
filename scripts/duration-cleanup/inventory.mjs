#!/usr/bin/env node
// #528 — інвентар спільної бази: що зіпсовано і що з цього треба прибрати.
//
// Лише читання. `book_durations` і `book_profiles` відкриті на читання
// (`allow read: if true`), тож скрипту не потрібні ні сервісний акаунт, ні
// права власника — досить ключа з `app/google-services.json`, який і так
// лежить у кожному APK.
//
// Умова контракту та сама, що в `firestore.rules` і `DurationSanity`:
//   schemaVersion == 2
//   300 <= seconds <= 360000
//   seconds != 14400            (фабрикована легасі-позначка)
//
// Код виходу 1, якщо знайдено хоч одного порушника — щоб це можна було
// повісити в CI як гейт.

import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, "..", "..");

export const SCHEMA_VERSION = 2;
export const MIN_SECONDS = 300;
export const MAX_SECONDS = 360_000;
const FABRICATED_SENTINEL = 14_400;

/** Публічний контракт одного числа: чи може воно лежати в базі? */
export function isContractValue(seconds) {
  return typeof seconds === "number"
    && Number.isInteger(seconds)
    && seconds >= MIN_SECONDS
    && seconds <= MAX_SECONDS
    && seconds !== FABRICATED_SENTINEL;
}

export function violations(doc) {
  const found = [];
  if (doc.version !== SCHEMA_VERSION) found.push("немає schemaVersion: 2");
  if (!isContractValue(doc.seconds)) found.push(`значення ${doc.seconds} поза контрактом`);
  return found;
}

function credentials() {
  const path = process.env.GOOGLE_SERVICES_JSON ?? resolve(REPO, "app", "google-services.json");
  const json = JSON.parse(readFileSync(path, "utf8"));
  const projectId = json.project_info?.project_id;
  const client = json.client?.find((c) =>
    c.client_info?.android_client_info?.package_name === "com.slukhayka.audiobooks"
  ) ?? json.client?.[0];
  const apiKey = client?.api_key?.[0]?.current_key;
  if (!projectId || !apiKey) throw new Error(`немає project_id/api_key у ${path}`);
  return { projectId, apiKey };
}

const field = (f) => f == null ? null
  : "integerValue" in f ? Number(f.integerValue)
  : "doubleValue" in f ? f.doubleValue
  : "stringValue" in f ? f.stringValue
  : "booleanValue" in f ? f.booleanValue
  : null;

/** Читає всю колекцію посторінково й віддає пласкі документи. */
async function readCollection(base, coll, apiKey, fields) {
  const out = [];
  let token = null;
  do {
    const url = `${base}/${coll}?pageSize=300${token ? `&pageToken=${token}` : ""}`;
    const response = await fetch(url, { headers: { "X-Goog-Api-Key": apiKey } });
    if (!response.ok) {
      throw new Error(`${coll}: HTTP ${response.status} ${(await response.text()).slice(0, 200)}`);
    }
    const page = await response.json();
    for (const document of page.documents ?? []) {
      const raw = document.fields ?? {};
      const row = { id: decodeURIComponent(document.name.split("/").pop()), createdAt: document.createTime };
      for (const [key, out_] of Object.entries(fields)) row[out_] = field(raw[key]);
      out.push(row);
    }
    token = page.nextPageToken ?? null;
  } while (token);
  return out;
}

async function main() {
  const { projectId, apiKey } = credentials();
  const base = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents`;
  const jsonPath = process.argv.includes("--json")
    ? process.argv[process.argv.indexOf("--json") + 1]
    : null;

  const durations = await readCollection(base, "book_durations", apiKey, {
    durationSeconds: "seconds", schemaVersion: "version", source: "source",
    method: "method", derivedAt: "derivedAt",
  });
  const conflicts = await readCollection(base, "book_duration_conflicts", apiKey, {
    candidateSeconds: "seconds", source: "source",
  });
  const profiles = await readCollection(base, "book_profiles", apiKey, {
    totalDurationSeconds: "seconds",
  });

  const brokenDurations = durations
    .map((d) => ({ ...d, reasons: violations(d) }))
    .filter((d) => d.reasons.length > 0);
  const brokenConflicts = conflicts
    .filter((d) => !isContractValue(d.seconds));
  const brokenProfiles = profiles
    .filter((d) => d.seconds != null && !isContractValue(d.seconds));

  const histogram = {};
  for (const d of brokenDurations) histogram[d.seconds] = (histogram[d.seconds] ?? 0) + 1;
  const bySource = {};
  for (const d of brokenDurations) bySource[d.source] = (bySource[d.source] ?? 0) + 1;

  const lines = [
    `# Інвентар спільної бази — проєкт ${projectId}`,
    "",
    `book_durations:            усього ${durations.length}, порушників ${brokenDurations.length}`,
    `book_duration_conflicts:   усього ${conflicts.length}, порушників ${brokenConflicts.length}`,
    `book_profiles:             усього ${profiles.length}, порушників ${brokenProfiles.length}`,
    "",
    `розподіл підозрілих значень: ${JSON.stringify(Object.entries(histogram).sort((a, b) => a[0] - b[0]))}`,
    `за джерелом:                 ${JSON.stringify(Object.entries(bySource).sort((a, b) => b[1] - a[1]))}`,
    "",
  ];
  for (const d of brokenDurations) {
    lines.push(`  ${d.id}  ${d.seconds}s  ${d.source ?? "?"}/${d.method ?? "?"}  ${d.reasons.join(", ")}  ${d.createdAt}`);
  }
  lines.push("");
  lines.push("Далі: node scripts/duration-cleanup/purge.mjs --apply");

  const report = lines.join("\n");
  console.log(report);
  if (jsonPath) {
    const { writeFileSync } = await import("node:fs");
    writeFileSync(jsonPath, JSON.stringify({
      projectId, durations, conflicts, profiles,
      brokenDurations, brokenConflicts, brokenProfiles,
    }, null, 2));
    console.log(`\nJSON: ${jsonPath}`);
  }
  process.exit(brokenDurations.length + brokenConflicts.length + brokenProfiles.length > 0 ? 1 : 0);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  await main();
}
