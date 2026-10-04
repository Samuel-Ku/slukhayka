// Builds a reviewable candidate only. Never authenticates or deploys.
import assert from 'node:assert/strict'
import { readFileSync, writeFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

export function prepareCloudRecoveryRules(source) {
  const legacy = 'return request.appCheck.token != null;'
  assert.equal(source.split(legacy).length, 2, 'Review the changed legacy guard before preparing recovery')
  const serviceStart = source.indexOf('service cloud.firestore {')
  assert.ok(serviceStart > 0, 'Missing Firestore service')
  const body = source.slice(serviceStart)
    .replace(legacy, 'return request.auth != null\n        && !exists(/databases/$(database)/documents/security_recovery_required/$(request.auth.uid));')
    .replaceAll('isAppCheckValid()', 'isCloudProfileEligible()')
    .replace('// Legacy fail-closed guard; see ADR-0055 and the deployment note above.', '// App Check is enforced by the service; this guard isolates affected UIDs.')
  assert.ok(!body.includes('request.appCheck'), 'Unsupported App Check expression remains')
  return `rules_version = '2';

// PREPARED CANDIDATE, not the active production policy.
// Before deploying: verify genuine Android tokens, roll out the signed client,
// enable Firestore service App Check Enforcement, and create an Admin-only
// security_recovery_required/{uid} marker for EVERY affected legacy profile.
// Marked profiles cannot access private data or write. Public catalogue reads
// remain allowed by Rules, subject to service App Check Enforcement.
// Keep markers until ownership is independently verified and credentials and
// sessions have been replaced. No client can read or alter the markers.
// Passwords and cloud data are not changed by this candidate.
${body}`
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  assert.equal(process.argv.length, 3, 'Usage: node scripts/prepare-cloud-recovery-rules.mjs OUTPUT_FILE')
  const source = readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8')
  const candidate = prepareCloudRecoveryRules(source)
  writeFileSync(resolve(process.argv[2]), candidate, { flag: 'wx', mode: 0o600 })
  console.log(JSON.stringify({ sha256: createHash('sha256').update(candidate).digest('hex'), deployed: false }))
}
