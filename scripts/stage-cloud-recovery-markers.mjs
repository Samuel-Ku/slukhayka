// Read-only by default. --apply creates Admin-only markers, never changes Auth.
// Run only after the owner accepts the access impact in the App Check runbook.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'

const requireAdmin = createRequire(new URL('./duration-cleanup/package.json', import.meta.url))
const { initializeApp, applicationDefault } = requireAdmin('firebase-admin/app')
const { getFirestore, FieldValue } = requireAdmin('firebase-admin/firestore')

async function main() {
  const args = process.argv.slice(2)
  assert.ok(args.length === 1 || (args.length === 2 && args[1] === '--apply'),
    'Usage: node scripts/stage-cloud-recovery-markers.mjs INVENTORY_JSON [--apply]')
  const apply = args[1] === '--apply'
  const inventory = JSON.parse(readFileSync(args[0], 'utf8'))
  assert.equal(inventory.projectId, 'slukhayka', 'Wrong inventory project')
  assert.equal(inventory.summary.foundUsers, 156, 'Review a changed profile count')
  assert.equal(inventory.summary.notFound, 0, 'Inventory contains missing users')
  const uids = inventory.users.map(user => user.uid).sort()
  assert.equal(uids.length, 156, 'Incomplete inventory')
  assert.equal(new Set(uids).size, 156, 'Duplicate inventory UID')
  assert.ok(uids.every(uid => typeof uid === 'string' && /^[-A-Za-z0-9_]{1,128}$/.test(uid)),
    'Invalid inventory UID')

  const db = getFirestore(initializeApp({ projectId: 'slukhayka', credential: applicationDefault() }))
  // Project only uid. Never read the encrypted recovery-code field.
  const bindings = await db.collection('device_bindings').select('uid').get()
  const liveUids = [...new Set(bindings.docs.map(doc => doc.get('uid')))].sort()
  assert.equal(bindings.size, 156, 'Live binding count changed; stop and review')
  assert.ok(JSON.stringify(liveUids) === JSON.stringify(uids),
    'Live binding UID set differs; stop and review')

  const refs = uids.map(uid => db.collection('security_recovery_required').doc(uid))
  const existing = await db.getAll(...refs)
  assert.ok(existing.every(doc => !doc.exists || doc.get('reason') === 'legacy_binding_exposure'),
    'An existing marker has a different reason; stop and review')
  const missing = existing.filter(doc => !doc.exists)
  const summary = {
    project: 'slukhayka', reviewedProfiles: 156,
    wouldCreate: missing.length, wouldKeep: 156 - missing.length,
    authChanges: 0, cloudDataDeleted: 0, applied: false,
  }
  if (apply && missing.length) {
    // All or nothing. create() also detects a concurrent marker creation.
    const batch = db.batch()
    for (const doc of missing) {
      batch.create(doc.ref, { reason: 'legacy_binding_exposure', createdAt: FieldValue.serverTimestamp() })
    }
    await batch.commit()
  }
  summary.applied = apply
  console.log(JSON.stringify(summary))
}

main().catch(error => {
  // Do not print SDK errors containing document paths or private UID values.
  console.error(JSON.stringify({ errorCode: error.code ?? 'validation_failed',
    message: error.code === 'ERR_ASSERTION' ? error.message.split('\n')[0] : 'Admin preparation failed' }))
  process.exitCode = 1
})
