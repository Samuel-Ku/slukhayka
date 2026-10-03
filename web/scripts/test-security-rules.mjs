// Local-only authorization regression suite. Never connects to a live project.
// App Check is NOT emulated. Default: prove the legacy deny gate, then open
// it only in emulator memory. auth-candidate: test the prepared Auth guard;
// it still requires real service Enforcement before any production deploy.
import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import assert from 'node:assert/strict'
import { initializeApp, deleteApp } from 'firebase/app'
import { getFirestore, connectFirestoreEmulator, doc, setDoc, getDoc, getDocs, collection, updateDoc, deleteDoc, writeBatch, terminate, serverTimestamp } from 'firebase/firestore'

const project = 'demo-slukhayka-security'
const endpoint = 'http://127.0.0.1:8185'
const rules = readFileSync(new URL('../../firestore.rules', import.meta.url), 'utf8')
const phase = process.env.SECURITY_RULES_PHASE ?? 'legacy'
assert.ok(['legacy', 'auth-candidate'].includes(phase), 'Unknown security rules phase')
assert.ok(rules.includes('return request.appCheck.token != null;'), 'Legacy guard changed: review restoration explicitly')
const authCandidate = rules.replace('return request.appCheck.token != null;', 'return request.auth != null; // AUTH CANDIDATE: service App Check must be enforced')
const clients = []
const hash = (s) => createHash('sha256').update(s).digest('hex')
let passed = 0
const client = (uid) => {
  const app = initializeApp({ projectId: project, apiKey: 'emulator-only' }, `security-${clients.length}`)
  const db = getFirestore(app)
  connectFirestoreEmulator(db, '127.0.0.1', 8185, uid ? { mockUserToken: { sub: uid, user_id: uid } } : undefined)
  clients.push({ app, db })
  return db
}
const alice = client('alice'), bob = client('bob'), carol = client('carol'), dave = client('dave'), anon = client(null)
const ref = (db, path) => doc(db, path)
async function load(content) {
  const result = await fetch(`${endpoint}/emulator/v1/projects/${project}:securityRules`, {
    method: 'PUT', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ rules: { files: [{ name: 'firestore.rules', content }] } }),
  })
  assert.equal(result.status, 200, await result.text())
}
async function check(name, allowed, operation) {
  try {
    await operation()
    assert.ok(allowed, `${name}: unexpectedly allowed`)
  } catch (error) {
    if (allowed || error.code !== 'permission-denied') throw error
  }
  passed++
  console.log(`PASS ${name}`)
}
const authorId = hash('alice'), documentId = `${authorId}-c1`, path = `curator_collections/${documentId}`
const base = { authorId, collectionId: 'c1', pseudonym: 'Слухач', title: 'Моя добірка', description: '', bookIds: ['w1'], reasons: [''], publishedAt: 1 }
async function vote(db, uid, stars, sum, count, key = hash(uid + 'c1')) {
  const batch = writeBatch(db)
  batch.set(ref(db, `curator_collection_votes/${key}`), { documentId, stars, createdAt: 1 })
  batch.update(ref(db, path), { ratingSum: sum, ratingCount: count })
  await batch.commit()
}
async function report(db, uid, count, hidden, key = hash(uid + 'c1')) {
  const batch = writeBatch(db)
  batch.set(ref(db, `curator_collection_reports/${key}`), { documentId, createdAt: 1 })
  batch.update(ref(db, path), { reportCount: count, hidden })
  await batch.commit()
}
try {
  const cleared = await fetch(`${endpoint}/emulator/v1/projects/${project}/databases/(default)/documents`, { method: 'DELETE' })
  assert.equal(cleared.status, 200)
  await load(phase === 'auth-candidate' ? authCandidate : rules)
  await check('legacy binding unauthenticated get denied', false, () => getDoc(ref(anon, 'device_bindings/device-id')))
  await check('legacy binding authenticated get denied', false, () => getDoc(ref(alice, 'device_bindings/device-id')))
  await check('legacy binding enumeration denied', false, () => getDocs(collection(alice, 'device_bindings')))
  await check('legacy binding credential upload denied', false, () => setDoc(ref(alice, 'device_bindings/device-id'), { uid: 'alice', cred: 'opaque' }))
  if (phase === 'legacy') {
    await check('legacy guard denies ordinary emulator write', false, () => setDoc(ref(alice, path), base))
    await load(rules.replace('return request.appCheck.token != null;', 'return true; // EMULATOR ONLY'))
  } else {
    await check('Auth candidate denies unauthenticated writes', false, () => setDoc(ref(anon, path), base))
    await check('Auth candidate denies unauthenticated shared cache writes', false, () => setDoc(ref(anon, 'book_durations/probe'), { durationSeconds: 3600, source: '4read', method: 'source_metadata', derivedAt: 1, schemaVersion: 2 }))
  }
  await check('retired binding remains closed with gate open', false, () => setDoc(ref(alice, 'device_bindings/device-id'), { uid: 'alice', cred: 'opaque' }))
  await check('anonymous collection creation denied', false, () => setDoc(ref(anon, path), base))
  await check('author impersonation denied', false, () => setDoc(ref(bob, path), base))
  await check('prepopulated rating counts denied', false, () => setDoc(ref(alice, path), { ...base, ratingSum: 500, ratingCount: 100 }))
  await check('noncanonical collection id denied', false, () => setDoc(ref(alice, 'curator_collections/other-id'), base))
  await check('owner publishes collection', true, () => setDoc(ref(alice, path), base))
  await check('public collection read allowed', true, () => getDoc(ref(anon, path)))
  await check('foreign content update denied', false, () => updateDoc(ref(bob, path), { title: 'replacement' }))
  await check('foreign delete denied', false, () => deleteDoc(ref(bob, path)))
  await check('owner content update allowed', true, () => updateDoc(ref(alice, path), { title: 'Оновлена' }))
  await check('owner cannot forge aggregate', false, () => updateDoc(ref(alice, path), { ratingSum: 999, ratingCount: 99 }))
  await check('caller cannot directly hide collection', false, () => updateDoc(ref(bob, path), { reportCount: 3, hidden: true }))
  await check('orphan vote denied', false, () => setDoc(ref(bob, `curator_collection_votes/${hash('bobc1')}`), { documentId, stars: 5, createdAt: 1 }))
  await check('arbitrary vote key denied', false, () => vote(bob, 'bob', 5, 5, 1, 'arbitrary'))
  await check('forged vote sum denied', false, () => vote(bob, 'bob', 5, 50, 1))
  await check('atomic vote accepted', true, () => vote(bob, 'bob', 5, 5, 1))
  await check('own vote readable', true, () => getDoc(ref(bob, `curator_collection_votes/${hash('bobc1')}`)))
  await check('foreign vote unreadable', false, () => getDoc(ref(alice, `curator_collection_votes/${hash('bobc1')}`)))
  await check('vote enumeration denied', false, () => getDocs(collection(bob, 'curator_collection_votes')))
  await check('foreign vote overwrite denied', false, () => vote(alice, 'alice', 1, 1, 1, hash('bobc1')))
  await check('revote replaces previous stars', true, () => vote(bob, 'bob', 3, 3, 1))
  await check('second unique vote counted once', true, () => vote(carol, 'carol', 4, 7, 2))
  await check('orphan complaint denied', false, () => setDoc(ref(bob, `curator_collection_reports/${hash('bobc1')}`), { documentId, createdAt: 1 }))
  await check('wrong complaint count denied', false, () => report(bob, 'bob', 3, true))
  await check('first complaint accepted', true, () => report(bob, 'bob', 1, false))
  await check('foreign complaint unreadable', false, () => getDoc(ref(alice, `curator_collection_reports/${hash('bobc1')}`)))
  await check('complaint enumeration denied', false, () => getDocs(collection(bob, 'curator_collection_reports')))
  await check('duplicate complaint cannot increment', false, () => report(bob, 'bob', 2, false))
  await check('second complaint accepted', true, () => report(carol, 'carol', 2, false))
  await check('third unique complaint hides', true, () => report(dave, 'dave', 3, true))
  await check('owner cannot unhide', false, () => updateDoc(ref(alice, path), { hidden: false }))
  await check('owner content update preserves votes and moderation', true, () => setDoc(ref(alice, path), { title: 'Зміна назви' }, { merge: true }))
  const stored = (await getDoc(ref(alice, path))).data()
  assert.equal(stored.ratingSum, 7); assert.equal(stored.ratingCount, 2); assert.equal(stored.hidden, true)
  const review = { workId: 'w1', uid: 'alice', authorName: 'Слухач', rating: 5, createdAt: 1 }
  await check('duplicate review under arbitrary id denied', false, () => setDoc(ref(alice, 'book_reviews/random'), review))
  await check('canonical review accepted', true, () => setDoc(ref(alice, 'book_reviews/w1_alice'), review))
  await check('review cannot carry secret fields', false, () => updateDoc(ref(alice, 'book_reviews/w1_alice'), { password: 'secret' }))
  await check('foreign review update denied', false, () => updateDoc(ref(bob, 'book_reviews/w1_alice'), { rating: 1 }))
  const progress = { uid: 'alice', editionId: 'e1', chapterIndex: 0, positionSeconds: 5, isCompleted: false, updatedAt: serverTimestamp() }
  await check('foreign progress key cannot be occupied', false, () => setDoc(ref(alice, 'listening_state/bob_e1'), progress))
  await check('canonical progress accepted', true, () => setDoc(ref(alice, 'listening_state/alice_e1'), progress))
  await check('foreign progress unreadable', false, () => getDoc(ref(bob, 'listening_state/alice_e1')))
  const narration = { workId: 'w1', uid: 'alice', editionId: 'e1', rating: 5, createdAt: 1 }
  await check('duplicate narration rating denied', false, () => setDoc(ref(alice, 'edition_ratings/random'), narration))
  await check('canonical narration rating accepted', true, () => setDoc(ref(alice, 'edition_ratings/w1_alice_e1'), narration))
  await check('narration rating cannot carry secret fields', false, () => updateDoc(ref(alice, 'edition_ratings/w1_alice_e1'), { cookie: 'secret' }))
  const relationship = { uid: 'alice', mergeKey: 'w1', state: 'entry', title: 'Книга', author: 'Автор', updatedAt: serverTimestamp() }
  await check('foreign work relationship key denied', false, () => setDoc(ref(alice, 'work_relationships/bob_w1'), relationship))
  await check('canonical work relationship accepted', true, () => setDoc(ref(alice, 'work_relationships/alice_w1'), relationship))
  await check('foreign work relationship unreadable', false, () => getDoc(ref(bob, 'work_relationships/alice_w1')))
  const canonicalUrl = 'https://youtu.be/test-public-video'
  const candidate = { url: canonicalUrl, canonicalUrl, title: 'Книга', chaptersCount: 1, sourceId: 'youtube', submitterHash: hash('alice'), playedAt: 1, createdAt: 1, state: 'pending' }
  await check('submission author impersonation denied', false, () => setDoc(ref(bob, `pending_submissions/${hash(canonicalUrl)}`), candidate))
  await check('submission arbitrary key denied', false, () => setDoc(ref(alice, 'pending_submissions/random'), candidate))
  await check('canonical submission accepted', true, () => setDoc(ref(alice, `pending_submissions/${hash(canonicalUrl)}`), candidate))
  await check('client cannot approve submission', false, () => updateDoc(ref(alice, `pending_submissions/${hash(canonicalUrl)}`), { state: 'approved' }))
  await check('owner delete allowed', true, () => deleteDoc(ref(alice, path)))
  // Separate, deployable emergency policy: no dependence on the unsupported
  // legacy App Check expression and no opportunity for a broad rule to win.
  const containment = readFileSync(new URL('../../firestore.containment.rules', import.meta.url), 'utf8')
  const seeded = await fetch(`${endpoint}/v1/projects/${project}/databases/(default)/documents/person_bookmarks/alice_p1`, {
    method: 'PATCH', headers: { authorization: 'Bearer owner', 'content-type': 'application/json' },
    body: JSON.stringify({ fields: { uid: { stringValue: 'alice' } } }),
  })
  assert.equal(seeded.status, 200)
  await load(containment)
  for (const lane of ['search_results', 'book_durations', 'book_covers', 'book_profiles', 'universe_resolutions', 'book_facets', 'book_tombstones', 'book_duration_conflicts', 'source_refusals', 'source_refusal_votes', 'book_reviews', 'edition_ratings', 'catalog_blocks', 'catalog_cards', 'pending_submissions', 'rejected_submissions', 'curator_collections']) {
    await check(`containment public ${lane} read`, true, () => getDoc(ref(anon, `${lane}/public-item`)))
    await check(`containment ${lane} write frozen`, false, () => setDoc(ref(alice, `${lane}/public-item`), { uid: 'alice' }))
  }
  for (const privatePath of ['listening_state/alice_e1', 'work_relationships/alice_w1', 'person_bookmarks/alice_p1']) {
    await check(`containment owner ${privatePath} read`, true, () => getDoc(ref(alice, privatePath)))
    await check(`containment foreign ${privatePath} read denied`, false, () => getDoc(ref(bob, privatePath)))
    await check(`containment anonymous ${privatePath} read denied`, false, () => getDoc(ref(anon, privatePath)))
    await check(`containment owner ${privatePath} write frozen`, false, () => updateDoc(ref(alice, privatePath), { uid: 'alice' }))
  }
  await check('containment binding read denied', false, () => getDoc(ref(alice, 'device_bindings/device-id')))
  await check('containment binding list denied', false, () => getDocs(collection(alice, 'device_bindings')))
  await check('containment binding write denied', false, () => setDoc(ref(alice, 'device_bindings/device-id'), { uid: 'alice' }))
  await check('containment unmapped private collection denied', false, () => getDoc(ref(alice, 'unmapped/private-item')))
  console.log(`Security rules: ${passed} checks passed. App Check enforcement is not verified by this emulator.`)
} finally {
  await load(rules).catch(() => {})
  await Promise.all(clients.map(async ({ app, db }) => { await terminate(db); await deleteApp(app) }))
}
