import assert from 'node:assert/strict'
import { readFile, writeFile } from 'node:fs/promises'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { resolve } from 'node:path'
import { performance } from 'node:perf_hooks'
import { build, createServer } from 'vite'

const root = fileURLToPath(new URL('../', import.meta.url))
const cache = resolve(root, '.perf-cache')
const current = resolve(cache, 'work-feed.mjs')
const mode = process.argv[2] ?? 'run'

if (mode === 'prepare') {
  await build({
    root,
    configFile: false,
    logLevel: 'warn',
    build: {
      outDir: cache,
      emptyOutDir: false,
      minify: false,
      lib: { entry: resolve(root, 'src/worker/workFeed.ts'), formats: ['es'], fileName: () => 'work-feed.mjs' },
    },
  })
  const server = await createServer({ root, configFile: false, optimizeDeps: { noDiscovery: true, include: [] }, server: { middlewareMode: true }, appType: 'custom' })
  try {
    const fixtures = [
      ['sluhay', 'sluhay', 'sluhayAdapter', 'sluhay-homepage.html'],
      ['sound-books', 'soundbooks', 'soundBooksAdapter', 'soundbooks-homepage.html'],
      ['audiobook-mp3', 'audiobookmp3', 'audiobookMp3Adapter', 'audiobookmp3-homepage-rich.html'],
    ]
    const inputs = []
    for (const [sourceId, module, exported, fixture] of fixtures) {
      const adapter = (await server.ssrLoadModule(`/src/worker/adapters/${module}.ts`))[exported]
      const html = await readFile(resolve(root, 'src/worker/fixtures', fixture), 'utf8')
      const cards = adapter.parseCatalog(html, adapter.baseUrl)?.sections.flatMap(section => section.cards) ?? []
      assert.ok(cards.length > 0, `No cards parsed from ${fixture}`)
      inputs.push({ sourceId, cards })
    }
    // A larger deterministic scenario, explicitly separate from captured pages.
    const expanded = inputs.map(input => ({
      sourceId: input.sourceId,
      cards: Array.from({ length: 100 }, (_, index) => ({
        ...input.cards[index % input.cards.length],
        title: `${input.cards[index % input.cards.length].title} ${index}`,
        url: `${input.cards[index % input.cards.length].url}#${index}`,
      })),
    }))
    await writeFile(resolve(cache, 'inputs.json'), JSON.stringify([
      { name: 'captured-pages', inputs },
      { name: 'synthetic-300-cards', inputs: expanded },
    ], null, 2))
    console.log(`Prepared ${current}; preserve a copy before changing the source.`)
  } finally {
    await server.close()
  }
} else if (mode === 'run' || mode === 'bench') {
  const scenarios = JSON.parse(await readFile(resolve(cache, 'inputs.json'), 'utf8'))
  const after = await import(pathToFileURL(current).href)
  let checksum = 0
  function batch(merge, inputs, repetitions) {
    const start = performance.now()
    for (let index = 0; index < repetitions; index += 1) {
      checksum += merge(inputs, index % 3 * 30, 30).works.length
    }
    return (performance.now() - start) / repetitions
  }
  if (mode === 'run') {
    for (const scenario of scenarios) batch(after.mergeWorkFeed, scenario.inputs, 3000)
    console.log(`Profile workload complete: ${checksum}`)
  } else {
    assert.ok(process.argv[3], 'Usage: node scripts/profile-work-feed.mjs bench <baseline-module>')
    const before = await import(pathToFileURL(resolve(process.argv[3])).href)
    for (const scenario of scenarios) {
      for (const offset of [0, 1, 30, 60, 300, -1]) {
        for (const limit of [0, 1, 10, 30, 200]) {
          assert.deepStrictEqual(after.mergeWorkFeed(scenario.inputs, offset, limit), before.mergeWorkFeed(scenario.inputs, offset, limit))
        }
      }
      batch(before.mergeWorkFeed, scenario.inputs, 100)
      batch(after.mergeWorkFeed, scenario.inputs, 100)
      const oldTimes = []
      const newTimes = []
      for (let index = 0; index < 9; index += 1) {
        const order = index % 2 === 0
          ? [[before.mergeWorkFeed, oldTimes], [after.mergeWorkFeed, newTimes]]
          : [[after.mergeWorkFeed, newTimes], [before.mergeWorkFeed, oldTimes]]
        for (const [merge, times] of order) times.push(batch(merge, scenario.inputs, 300))
      }
      const median = times => [...times].sort((a, b) => a - b)[Math.floor(times.length / 2)]
      console.log(JSON.stringify({ scenario: scenario.name, cards: scenario.inputs.reduce((sum, input) => sum + input.cards.length, 0), beforeMs: median(oldTimes), afterMs: median(newTimes), speedup: median(oldTimes) / median(newTimes), samples: 9, equivalence: 'passed', oldTimes, newTimes }))
    }
    assert.ok(checksum > 0)
  }
} else {
  throw new Error(`Unknown mode: ${mode}. Use prepare, run, or bench.`)
}
