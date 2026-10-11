#!/usr/bin/env node
/**
 * #1224 — the CSS half of the web layout, measured by one command instead of
 * by a throwaway headless-Chromium script in `/tmp`.
 *
 * `web/package.json` has no browser dependency and CI's web job is `npm ci` +
 * `tsc` + `vitest`, so vitest can only read `theme.css` as text. Whether the
 * column is really 640 px and the list really draws three tracks is a question
 * for a layout engine, and two lanes answered it by hand before this script
 * existed — one of them built two bundles (base and head) to prove the compact
 * layout had not moved a pixel. That is the job here: build, preview, measure
 * through CDP at named widths, print numbers.
 *
 * No dependency is added: the browser is the machine's own Chromium/Chrome and
 * the CDP client is Node's built-in `WebSocket` (Node >= 22). The script leaves
 * nothing behind — preview, browser and the temporary profile are all stopped
 * in a `finally`, and the temp root is removed unless `--keep` says otherwise.
 *
 * #1207 named the boundary (600 px, Android's `ExpandedMinWidthDp`) and the
 * numbers the expanded layout is built from (320 px list column, 16 px column
 * gap, 24 px gutter, cap = 3 columns + 2 gaps + 2 gutters = 1040 px); this
 * script measures them rather than restating them. It knows no threshold of
 * its own: it reports `data-layout` as the app wrote it, so a layout that
 * failed to cross the line shows up as a stale value, not as a pass.
 *
 * Usage — see `docs/agents/local-environment.md`:
 *
 *   node scripts/web-layout-probe.mjs
 *   node scripts/web-layout-probe.mjs --widths 599,600,1280
 *   node scripts/web-layout-probe.mjs --base origin/main
 *   node scripts/web-layout-probe.mjs --base origin/main --head HEAD
 */
import { spawn, spawnSync } from 'node:child_process'
import { once } from 'node:events'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:net'
import { homedir, tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const WEB_DIR = join(REPO_ROOT, 'web')

/**
 * The widths the repository already names, plus one this probe chose:
 * 411 — the phone Android's tests pin (`w411dp-h891dp` qualifier in
 * `AchievementNoticeLifecycleTest`, and `LibraryGridPolicyTest`);
 * 599/600 — the two sides of the one line `adaptiveLayout.ts` draws
 * (`ui/adaptive/WindowLayout.kt:30` on the Android side), the pair
 * `adaptiveLayout.test.tsx` pins; 840 — Android's `PlayerPaneMinWidthDp`,
 * already in the repo; 2560 — the very wide end, named by both
 * `WindowLayoutTest` and `adaptiveLayout.test.tsx`; and 1280, which the
 * repository does NOT name — it is this probe's own, the common desktop
 * width, and the one #1218's evidence and #1224's ticket used, kept so the
 * default run stays comparable with them.
 *
 * The 599/600 pair is a snapshot of the boundary, not the boundary itself:
 * the script holds no copy of `EXPANDED_MIN_WIDTH_PX` (a constant here would
 * be exactly the second copy of the number the seam exists to prevent), so if
 * that line moves, a default run quietly stops straddling it — `--widths` is
 * how it is re-aimed, and the expansion is asked of the page either way, never
 * assumed from the number.
 */
const DEFAULT_WIDTHS = [411, 599, 600, 840, 1280, 2560]
const DEFAULT_HEIGHT = 900
const DEFAULT_ROWS = 6
const DEFAULT_LIST = '.card-list'

/**
 * The exit codes, and the point of keeping them apart: a caller that treats
 * the probe as a gate must never read "the probe could not run" as "the layout
 * moved". 1 is reserved for a measured field that differs between two
 * revisions; a run that never got as far as measuring is 2, and a bug in the
 * probe itself is 3.
 */
const EXIT_DIFFERENCE = 1
const EXIT_FAILED = 2
const EXIT_CRASH = 3

/** Every child process this run started, so cleanup can reach all of them. */
const started = []
const cleanups = []

// --- arguments -------------------------------------------------------------

const USAGE = `usage: node scripts/web-layout-probe.mjs [options]

  --widths <list>    comma-separated viewport widths (default ${DEFAULT_WIDTHS.join(',')})
  --height <px>      viewport height (default ${DEFAULT_HEIGHT})
  --list <selector>  list to measure, a single class selector (default ${DEFAULT_LIST})
  --rows <n>         rows in the injected fixture list (default ${DEFAULT_ROWS})
  --rev <rev>        revision to measure; "worktree" (default) builds the working tree
  --base <rev>       also measure this revision and diff it against the head
  --head <rev>       head of a comparison; requires --base (default "worktree")
  --chrome <path>    browser binary (default: $CHROME, google-chrome-stable, chromium, Playwright's)
  --json <path>      write the raw measurements to this file
  --timeout <ms>     how long to wait for the preview and the browser (default 30000)
  --keep             keep the temporary directory (profile, extracted revisions)
  -h, --help         this text

Exit codes: 0 — nothing differed; 1 — a measured field differed; 2 — the run
could not start (a bad revision, no browser, a failed build, an unwritable
--json path); 3 — an unexpected error in the probe itself. Only 1 means the
layout moved.
`

function parseArgs(argv) {
  const opts = {
    widths: DEFAULT_WIDTHS,
    height: DEFAULT_HEIGHT,
    rows: DEFAULT_ROWS,
    list: DEFAULT_LIST,
    rev: 'worktree',
    base: null,
    head: null,
    chrome: null,
    json: null,
    timeout: 30_000,
    keep: false,
  }
  const need = (i, flag) => {
    if (i + 1 >= argv.length) fail(`${flag} needs a value`)
    return argv[i + 1]
  }
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i]
    switch (arg) {
      case '-h':
      case '--help':
        process.stdout.write(USAGE)
        process.exit(0)
        break
      case '--widths': {
        const raw = need(i++, arg)
        opts.widths = raw.split(',').map((part) => {
          const width = Number.parseInt(part.trim(), 10)
          if (!Number.isFinite(width) || width <= 0) fail(`--widths: "${part}" is not a width`)
          return width
        })
        break
      }
      case '--height':
        opts.height = Number.parseInt(need(i++, arg), 10)
        break
      case '--rows':
        opts.rows = Number.parseInt(need(i++, arg), 10)
        break
      case '--list':
        opts.list = need(i++, arg)
        break
      case '--rev':
        opts.rev = need(i++, arg)
        break
      case '--base':
        opts.base = need(i++, arg)
        break
      case '--head':
        opts.head = need(i++, arg)
        break
      case '--chrome':
        opts.chrome = need(i++, arg)
        break
      case '--json':
        opts.json = need(i++, arg)
        break
      case '--timeout':
        opts.timeout = Number.parseInt(need(i++, arg), 10)
        break
      case '--keep':
        opts.keep = true
        break
      default:
        fail(`unknown argument "${arg}"\n\n${USAGE}`)
    }
  }
  if (!/^\.[A-Za-z0-9_-]+$/.test(opts.list)) {
    fail(`--list must be a single class selector like ${DEFAULT_LIST}`)
  }
  if (opts.head !== null && opts.base === null) {
    fail('--head needs a --base to compare against (e.g. --base origin/main)')
  }
  if (opts.head === null) opts.head = opts.base === null ? opts.rev : 'worktree'
  if (opts.base !== null && opts.base === opts.head) {
    fail(`--base and --head are both "${opts.base}" — nothing to compare`)
  }
  return opts
}

/**
 * A failure this script understands. It is thrown, not exited on: everything
 * started (preview, browser, temp profile) is released by the `finally` in the
 * entry point, and an `process.exit` here would skip exactly that.
 */
class ProbeError extends Error {}

function fail(message) {
  throw new ProbeError(message)
}

function log(message) {
  process.stdout.write(`${message}\n`)
}

// --- child processes and cleanup -------------------------------------------

/**
 * Spawn with its own process group: the browser forks zygotes and renderers,
 * and `vite preview` may fork too, so signalling one pid would leave the rest
 * running. `detached` plus a negative-pid signal reaches the whole group.
 */
function start(command, args, options = {}) {
  const child = spawn(command, args, {
    stdio: options.stdio ?? ['ignore', 'pipe', 'pipe'],
    detached: true,
    ...options,
  })
  child.stderrText = ''
  child.stdoutText = ''
  child.stdout?.on('data', (chunk) => (child.stdoutText += chunk))
  child.stderr?.on('data', (chunk) => (child.stderrText += chunk))
  started.push(child)
  return child
}

async function stop(child, name, timeoutMs = 5_000) {
  if (child.exitCode !== null || child.signalCode !== null) return
  const exited = once(child, 'exit')
  try {
    process.kill(-child.pid, 'SIGTERM')
  } catch {
    child.kill('SIGTERM')
  }
  const killer = setTimeout(() => {
    try {
      process.kill(-child.pid, 'SIGKILL')
    } catch {
      child.kill('SIGKILL')
    }
  }, timeoutMs)
  await exited
  clearTimeout(killer)
  log(`  stopped ${name} (pid ${child.pid})`)
}

/** Everything the run has to give back, newest first. Never throws. */
async function cleanup() {
  for (const task of cleanups.reverse()) {
    try {
      await task()
    } catch (error) {
      process.stderr.write(`web-layout-probe: cleanup: ${error.message}\n`)
    }
  }
  cleanups.length = 0
}

// --- revisions --------------------------------------------------------------

function git(args, options = {}) {
  const result = spawnSync('git', args, { cwd: REPO_ROOT, encoding: 'utf8', ...options })
  if (result.status !== 0) {
    fail(`git ${args.join(' ')} failed: ${(result.stderr || '').trim()}`)
  }
  return result.stdout
}

function commitOf(rev) {
  return git(['rev-parse', '--short', rev]).trim()
}

/** Does this revision carry `path`? (`git archive` fails on a missing one.) */
function revHas(rev, path) {
  return spawnSync('git', ['cat-file', '-e', `${rev}:${path}`], { cwd: REPO_ROOT }).status === 0
}

/**
 * Root files a web build imports. One today: `sourceMetadata.ts` reaches
 * three levels up for `sources.json`, so a revision extracted without it does
 * not build at all.
 */
const EXTERNAL_WEB_INPUTS = ['sources.json']

/** A revision to build: the working tree in place, or a `web/` tree from git. */
function prepareRevision(rev, workRoot) {
  if (rev === 'worktree') {
    const dirty = git(['status', '--porcelain', '--', 'web']).trim() !== ''
    return {
      label: `worktree@${commitOf('HEAD')}${dirty ? '+dirty' : ''}`,
      webDir: WEB_DIR,
    }
  }
  const label = `${rev}@${commitOf(rev)}`
  const dir = join(workRoot, `rev-${rev.replace(/[^A-Za-z0-9._-]/g, '_')}`)
  mkdirSync(dir, { recursive: true })
  // `-o <file>` rather than a pipe: the archive is binary, and a captured
  // stdout would hand it to `tar` as UTF-8 text and shred it into headers tar
  // then refuses ("Skipping to next header"). The archive holds `web/` and the
  // root files the web build imports — `web/src/worker/sourceMetadata.ts`
  // imports `../../../sources.json`, so `web/` alone does not build. The rest
  // of the checkout is fixtures and assets the probe never reads.
  const tarPath = join(workRoot, `${label.replace(/[^A-Za-z0-9._-]/g, '_')}.tar`)
  const paths = ['web', ...EXTERNAL_WEB_INPUTS.filter((path) => revHas(rev, path))]
  const archive = spawnSync('git', ['archive', '--format=tar', '-o', tarPath, rev, ...paths], {
    cwd: REPO_ROOT,
    encoding: 'utf8',
  })
  if (archive.status !== 0) fail(`git archive ${rev} failed: ${(archive.stderr || '').trim()}`)
  const untar = spawnSync('tar', ['-xf', tarPath, '-C', dir], { encoding: 'utf8' })
  if (untar.status !== 0) fail(`tar failed for ${rev}: ${untar.stderr}`)
  return { label, webDir: join(dir, 'web') }
}

/**
 * `node_modules` is not part of a revision, and a second `npm ci` per revision
 * would be most of the run. When the lockfile and manifest match the ones the
 * primary tree was installed from, the install is reused through a symlink —
 * printed, because it is an assumption about the two revisions being built by
 * the same toolchain. When they differ, the revision gets its own install.
 */
function resolveDependencies(revision, primaryWebDir) {
  const target = join(revision.webDir, 'node_modules')
  if (revision.webDir === primaryWebDir) {
    revision.dependencies = { mode: 'in place', dir: target }
    return
  }
  const sameToolchain = ['package.json', 'package-lock.json'].every((file) => {
    const a = join(primaryWebDir, file)
    const b = join(revision.webDir, file)
    return existsSync(a) && existsSync(b) && readFileSync(a, 'utf8') === readFileSync(b, 'utf8')
  })
  if (sameToolchain) {
    symlinkSync(join(primaryWebDir, 'node_modules'), target, 'dir')
    revision.dependencies = {
      mode: `reused from web/node_modules (${revision.label} has the same package.json and lockfile)`,
      dir: target,
    }
    return
  }
  log(`  ${revision.label}: package.json differs from the working tree — running npm ci`)
  const install = spawnSync('npm', ['ci'], { cwd: revision.webDir, encoding: 'utf8' })
  if (install.status !== 0) {
    fail(`${revision.label}: npm ci failed (needed because package.json differs):\n${install.stderr}`)
  }
  revision.dependencies = { mode: 'installed with npm ci (package.json differs)', dir: target }
}

// --- browser ----------------------------------------------------------------

function which(binary) {
  const found = spawnSync('which', [binary], { encoding: 'utf8' })
  return found.status === 0 ? found.stdout.trim() : null
}

/** Highest-numbered Playwright download of a matching folder. */
function playwrightBrowser(prefixes, relative) {
  const root = join(homedir(), '.cache', 'ms-playwright')
  if (!existsSync(root)) return null
  const candidates = readdirSync(root)
    .filter((name) => prefixes.some((prefix) => name.startsWith(prefix)))
    .sort((a, b) => Number(b.split('-').pop()) - Number(a.split('-').pop()))
  for (const name of candidates) {
    const binary = join(root, name, relative)
    if (existsSync(binary)) return binary
  }
  return null
}

function resolveBrowser(explicit) {
  const candidates = [
    explicit,
    process.env.CHROME,
    process.env.CHROME_PATH,
    which('google-chrome-stable'),
    which('chromium'),
    which('chromium-browser'),
    which('google-chrome'),
    playwrightBrowser(['chromium-'], join('chrome-linux', 'chrome')),
    playwrightBrowser(['chromium_headless_shell-'], join('chrome-linux', 'headless_shell')),
  ]
  return candidates.find((candidate) => candidate && existsSync(candidate)) ?? null
}

// --- CDP --------------------------------------------------------------------

class Cdp {
  constructor(socket) {
    this.socket = socket
    this.nextId = 1
    this.pending = new Map()
    this.waiters = []
    socket.addEventListener('message', (event) => this.receive(event.data))
    socket.addEventListener('close', () => {
      for (const { reject } of this.pending.values()) reject(new Error('CDP socket closed'))
      this.pending.clear()
    })
  }

  static async connect(url) {
    const socket = new WebSocket(url)
    await new Promise((resolveOpen, rejectOpen) => {
      socket.addEventListener('open', resolveOpen, { once: true })
      socket.addEventListener('error', () => rejectOpen(new Error(`cannot connect to ${url}`)), { once: true })
    })
    return new Cdp(socket)
  }

  receive(raw) {
    const message = JSON.parse(raw)
    if (message.id !== undefined) {
      const entry = this.pending.get(message.id)
      if (!entry) return
      this.pending.delete(message.id)
      if (message.error) entry.reject(new Error(`${entry.method}: ${message.error.message}`))
      else entry.resolve(message.result)
      return
    }
    for (const waiter of [...this.waiters]) {
      if (waiter.method === message.method) {
        this.waiters.splice(this.waiters.indexOf(waiter), 1)
        waiter.resolve(message.params)
      }
    }
  }

  send(method, params = {}, sessionId) {
    const id = this.nextId++
    const payload = { id, method, params }
    if (sessionId) payload.sessionId = sessionId
    return new Promise((resolveSend, rejectSend) => {
      this.pending.set(id, { resolve: resolveSend, reject: rejectSend, method })
      this.socket.send(JSON.stringify(payload))
    })
  }

  close() {
    try {
      this.socket.close()
    } catch {
      /* already closed */
    }
  }
}

const sleep = (ms) => new Promise((resolveSleep) => setTimeout(resolveSleep, ms))

async function waitFor(what, timeoutMs, check) {
  const deadline = Date.now() + timeoutMs
  let last
  for (;;) {
    last = await check()
    if (last) return last
    if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}`)
    await sleep(100)
  }
}

/** A port the preview can bind: asked of the kernel, not guessed. */
async function freePort() {
  const server = createServer()
  await new Promise((resolveListen) => server.listen(0, '127.0.0.1', resolveListen))
  const { port } = server.address()
  await new Promise((resolveClose) => server.close(resolveClose))
  return port
}

// --- the measurement --------------------------------------------------------

/**
 * Runs in the page. Everything it reports is read off the layout engine:
 * computed styles and box geometry, never a value the CSS file was parsed for.
 *
 * The list is measured where it exists. The built bundle cannot reach its API
 * from `vite preview` (the dev proxy is not a preview proxy), so a catalog
 * list is usually EMPTY, and an empty list is deliberately not a grid
 * (`:not(:empty)` in `theme.css`) — so when no list has children, a fixture of
 * `rows` `.bookrow`s is appended to the app's own `.surface` and removed
 * afterwards. It is measured by the shipped stylesheet like any other markup;
 * `list.source` says which of the two was measured, and the numbers are
 * reported the same either way.
 */
function measurementSource({ list, rows }) {
  return `(async () => {
  const LIST = ${JSON.stringify(list)};
  const ROWS = ${rows};
  const app = document.querySelector('.app');
  if (!app) return { error: 'no .app root in the document' };
  await new Promise((done) => requestAnimationFrame(() => requestAnimationFrame(done)));

  let source = 'document';
  let list = [...document.querySelectorAll('.app .surface ' + LIST)].find((el) => el.children.length > 0) ?? null;
  let injected = null;
  if (!list) {
    source = 'fixture';
    let surface = document.querySelector('.app .surface');
    if (!surface) {
      surface = document.createElement('main');
      surface.className = 'surface';
      app.appendChild(surface);
    }
    injected = document.createElement('div');
    injected.setAttribute('data-layout-probe-fixture', '');
    list = document.createElement('div');
    list.className = LIST.slice(1);
    for (let i = 0; i < ROWS; i++) {
      const row = document.createElement('div');
      row.className = 'bookrow';
      const cover = document.createElement('div');
      cover.className = 'bookrow-cover';
      const body = document.createElement('div');
      body.className = 'bookrow-body';
      const title = document.createElement('div');
      title.className = 'bookrow-title';
      title.textContent = 'Проба ' + (i + 1);
      body.appendChild(title);
      row.append(cover, body);
      list.appendChild(row);
    }
    injected.appendChild(list);
    surface.appendChild(injected);
    await new Promise((done) => requestAnimationFrame(() => requestAnimationFrame(done)));
  }

  const appStyle = getComputedStyle(app);
  const surface = document.querySelector('.app .surface');
  const listStyle = getComputedStyle(list);
  // The tokens are read off the app element, not :root: the expanded block
  // sets them there (.app[data-layout='expanded']), so a lookup on :root
  // would answer with the compact values at every width.
  const token = (name) => appStyle.getPropertyValue(name).trim();
  const boxes = [...list.children].map((child) => child.getBoundingClientRect());
  const firstTop = boxes.length ? Math.min(...boxes.map((box) => Math.round(box.top))) : 0;
  const firstRow = boxes.filter((box) => Math.round(box.top) === firstTop);
  const round = (value) => Math.round(value * 100) / 100;
  const template = listStyle.gridTemplateColumns;
  const tracks = template === 'none' ? [] : template.split(' ').map((track) => round(Number.parseFloat(track)));

  const overflow = [...document.querySelectorAll('.app *')]
    .filter((el) => el.getBoundingClientRect().right > window.innerWidth + 0.5)
    .slice(0, 5)
    .map((el) => el.tagName.toLowerCase() + (el.className && typeof el.className === 'string' ? '.' + el.className.trim().split(/\\s+/).join('.') : ''));

  const column = app.getBoundingClientRect();
  const result = {
    viewport: window.innerWidth,
    layout: app.getAttribute('data-layout'),
    column: {
      maxWidth: appStyle.maxWidth,
      maxWidthToken: token('--layout-max-width'),
      gutterToken: token('--layout-gutter'),
      gutter: surface ? getComputedStyle(surface).paddingLeft : null,
      width: round(column.width),
      left: round(column.left),
    },
    list: {
      selector: LIST,
      source,
      display: listStyle.display,
      rows: list.children.length,
      template,
      trackCount: tracks.length,
      tracks,
      width: round(list.getBoundingClientRect().width),
      columnGap: listStyle.columnGap,
      columns: firstRow.length,
      columnWidths: firstRow.map((box) => round(box.width)),
    },
    overflow: {
      horizontal: document.documentElement.scrollWidth > window.innerWidth + 0.5,
      documentScrollWidth: document.documentElement.scrollWidth,
      viewport: window.innerWidth,
      offenders: overflow,
    },
  };
  injected?.remove();
  return result;
})()`
}

/** Build, preview, and measure one revision at every width. */
async function measureRevision(revision, opts, workRoot) {
  log(`\n${revision.label}`)
  log(`  source: ${revision.webDir}`)
  const vite = join(revision.dependencies.dir, 'vite', 'bin', 'vite.js')
  if (!existsSync(vite)) {
    fail(`${revision.label}: ${vite} is missing — run "npm ci" in web/ first`)
  }

  // The build goes to the project's own `dist/`: `vite.config.ts` hands
  // `vite-plugin-pwa` `swDest: 'dist/sw.js'`, and an `--outDir` elsewhere
  // breaks the manifest injection with "unable to find a place to inject".
  // `dist/` is gitignored, and a run that created it removes it again.
  //
  // The cleanup is registered BEFORE the build runs, not after it succeeds:
  // a build that dies late (a `writeBundle` hook, the manifest injection)
  // leaves a full `dist/` behind, and registering afterwards would both keep
  // that tree and make it look pre-existing to every later run — the leak
  // would stick. What a failed build created is still this run's to remove.
  const outDir = join(revision.webDir, 'dist')
  const outDirExisted = existsSync(outDir)
  if (!outDirExisted) {
    cleanups.push(() => rmSync(outDir, { recursive: true, force: true }))
  }
  const build = spawnSync(process.execPath, [vite, 'build'], {
    cwd: revision.webDir,
    encoding: 'utf8',
  })
  if (build.status !== 0) {
    fail(`${revision.label}: vite build failed\n${build.stdout}\n${build.stderr}`)
  }
  log(`  built → ${outDir}${outDirExisted ? '' : ' (removed after the run)'}`)

  const port = await freePort()
  const preview = start(
    process.execPath,
    [vite, 'preview', '--outDir', outDir, '--port', String(port), '--strictPort', '--host', '127.0.0.1'],
    { cwd: revision.webDir },
  )
  cleanups.push(() => stop(preview, 'vite preview'))
  const origin = `http://127.0.0.1:${port}`
  await waitFor(`the preview on ${origin}`, opts.timeout, async () => {
    if (preview.exitCode !== null) {
      fail(`${revision.label}: vite preview exited (${preview.exitCode})\n${preview.stderrText}`)
    }
    try {
      const response = await fetch(origin, { signal: AbortSignal.timeout(1_000) })
      return response.ok
    } catch {
      return false
    }
  })
  log(`  preview: ${origin} (pid ${preview.pid})`)

  const browser = await launchBrowser(opts, workRoot, origin)
  try {
    return { ...revision, results: await measureWidths(browser, opts) }
  } finally {
    await browser.dispose()
  }
}

async function launchBrowser(opts, workRoot, origin) {
  const binary = resolveBrowser(opts.chrome)
  if (!binary) {
    fail('no Chromium or Chrome found — install one, or point --chrome/$CHROME at it')
  }
  const profile = join(workRoot, `profile-${started.length}`)
  // Pushed before the browser, so that the reverse order of `cleanup()` stops
  // the browser first and removes the profile it was holding second.
  cleanups.push(() => rmSync(profile, { recursive: true, force: true }))
  const headlessShell = /headless[_-]shell/.test(binary)
  const args = [
    headlessShell ? '--headless' : '--headless=new',
    '--remote-debugging-port=0',
    `--user-data-dir=${profile}`,
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-extensions',
    '--disable-background-networking',
    '--disable-sync',
    '--disable-gpu',
    '--hide-scrollbars',
    '--force-device-scale-factor=1',
    '--window-size=1280,900',
    'about:blank',
  ]
  if (process.env.PROBE_NO_SANDBOX === '1') args.unshift('--no-sandbox')
  const chrome = start(binary, args)
  cleanups.push(() => stop(chrome, 'browser'))
  log(`  browser: ${binary} (pid ${chrome.pid})`)

  const wsUrl = await waitFor('the browser’s CDP endpoint', opts.timeout, async () => {
    if (chrome.exitCode !== null) {
      fail(`browser exited (${chrome.exitCode})\n${chrome.stderrText}`)
    }
    const portFile = join(profile, 'DevToolsActivePort')
    if (!existsSync(portFile)) return null
    const [port] = readFileSync(portFile, 'utf8').split('\n')
    if (!port) return null
    try {
      const response = await fetch(`http://127.0.0.1:${port}/json/version`, {
        signal: AbortSignal.timeout(1_000),
      })
      const version = await response.json()
      return version.webSocketDebuggerUrl ?? null
    } catch {
      return null
    }
  })
  log(`  page: ${origin}`)

  const cdp = await Cdp.connect(wsUrl)
  cleanups.push(async () => cdp.close())
  const { targetId } = await cdp.send('Target.createTarget', { url: 'about:blank' })
  const { sessionId } = await cdp.send('Target.attachToTarget', { targetId, flatten: true })
  await cdp.send('Page.enable', {}, sessionId)
  await cdp.send('Runtime.enable', {}, sessionId)
  await cdp.send('Emulation.setDeviceMetricsOverride', {
    width: opts.widths[0],
    height: opts.height,
    deviceScaleFactor: 1,
    mobile: false,
  }, sessionId)
  await cdp.send('Page.navigate', { url: origin }, sessionId)
  await waitFor('the app root to render', opts.timeout, async () => {
    const { result } = await cdp.send('Runtime.evaluate', {
      expression: 'document.readyState === "complete" && !!document.querySelector(".app")',
      returnByValue: true,
    }, sessionId)
    return result.value
  })

  return {
    cdp,
    sessionId,
    async dispose() {
      try {
        await cdp.send('Browser.close')
      } catch {
        /* the browser may already be gone */
      }
      cdp.close()
    },
  }
}

async function measureWidths(browser, opts) {
  const results = []
  for (const width of opts.widths) {
    await browser.cdp.send('Emulation.setDeviceMetricsOverride', {
      width,
      height: opts.height,
      deviceScaleFactor: 1,
      mobile: false,
    }, browser.sessionId)
    await waitFor(`the viewport to report ${width} px`, opts.timeout, async () => {
      const { result } = await browser.cdp.send('Runtime.evaluate', {
        expression: 'window.innerWidth',
        returnByValue: true,
      }, browser.sessionId)
      return result.value === width
    })
    // The app reads `innerWidth` in a `resize` handler; one more frame lets
    // React commit the attribute before it is read back.
    await sleep(120)
    const { result, exceptionDetails } = await browser.cdp.send('Runtime.evaluate', {
      expression: measurementSource(opts),
      returnByValue: true,
      awaitPromise: true,
    }, browser.sessionId)
    if (exceptionDetails) fail(`measuring ${width} px failed: ${exceptionDetails.text}`)
    if (result.value?.error) fail(`measuring ${width} px failed: ${result.value.error}`)
    results.push(result.value)
  }
  return results
}

// --- reporting --------------------------------------------------------------

const COLUMNS = ['width', 'layout', 'column', 'col width', 'gutter', 'source', 'tracks', 'track widths', 'overflow']

function pad(value, width) {
  const text = String(value)
  return text.length >= width ? text : text + ' '.repeat(width - text.length)
}

function table(results) {
  const rows = results.map((result) => [
    result.viewport,
    result.layout ?? '—',
    result.column.maxWidth,
    `${result.column.width}px`,
    result.column.gutter ?? '—',
    // In the table, not only in the note below it: a table copied out of a
    // terminal has to say whether it measured the app's own list or the
    // fixture the probe appended.
    result.list.source,
    `${result.list.columns} (${result.list.display})`,
    result.list.columnWidths.length ? result.list.columnWidths.map((w) => `${w}px`).join(' + ') : '—',
    result.overflow.horizontal ? `YES (${result.overflow.documentScrollWidth} > ${result.overflow.viewport})` : 'no',
  ])
  const header = COLUMNS
  const widths = header.map((name, index) => Math.max(name.length, ...rows.map((row) => String(row[index]).length)) + 2)
  const line = (cells) => cells.map((cell, index) => pad(cell, widths[index])).join('').trimEnd()
  const output = [line(header), widths.map((width) => '-'.repeat(width - 1)).join(' ')]
  for (const row of rows) output.push(line(row))
  return output.join('\n')
}

/** The measured facts a comparison is about, flat and comparable. */
function flatten(result) {
  return {
    layout: result.layout,
    'column.maxWidth': result.column.maxWidth,
    'column.maxWidthToken': result.column.maxWidthToken,
    'column.width': result.column.width,
    'column.gutter': result.column.gutterToken,
    'column.surfacePadding': result.column.gutter,
    'list.source': result.list.source,
    'list.display': result.list.display,
    'list.trackCount': result.list.trackCount,
    'list.tracks': result.list.tracks.join('+'),
    'list.columns': result.list.columns,
    'list.columnWidths': result.list.columnWidths.join('+'),
    'list.columnGap': result.list.columnGap,
    'overflow.horizontal': result.overflow.horizontal,
  }
}

function compare(base, head) {
  const differences = []
  for (const baseResult of base.results) {
    const headResult = head.results.find((result) => result.viewport === baseResult.viewport)
    if (!headResult) {
      differences.push({ width: baseResult.viewport, field: '<missing head>', base: 'yes', head: 'no' })
      continue
    }
    const a = flatten(baseResult)
    const b = flatten(headResult)
    for (const field of Object.keys(a)) {
      if (a[field] !== b[field]) {
        differences.push({ width: baseResult.viewport, field, base: a[field], head: b[field] })
      }
    }
  }
  return differences
}

// --- main -------------------------------------------------------------------

async function main() {
  const opts = parseArgs(process.argv.slice(2))
  log('web-layout-probe — #1224')
  log(`  widths: ${opts.widths.join(', ')} px · height ${opts.height} px · list ${opts.list}`)

  const workRoot = mkdtempSync(join(tmpdir(), 'web-layout-probe-'))
  log(`  work dir: ${workRoot}`)
  if (!opts.keep) cleanups.push(() => rmSync(workRoot, { recursive: true, force: true }))

  if (!existsSync(join(WEB_DIR, 'node_modules', 'vite', 'bin', 'vite.js'))) {
    log('  web/node_modules is missing — running npm ci')
    const install = spawnSync('npm', ['ci'], { cwd: WEB_DIR, encoding: 'utf8' })
    if (install.status !== 0) fail(`npm ci failed in web/\n${install.stderr}`)
  }

  const specs = []
  if (opts.base !== null) {
    specs.push(opts.base)
    if (opts.head !== opts.base) specs.push(opts.head)
  } else {
    specs.push(opts.rev)
  }

  const runs = []
  for (const spec of specs) {
    const revision = prepareRevision(spec, workRoot)
    resolveDependencies(revision, WEB_DIR)
    if (revision.dependencies.mode !== 'in place') log(`  deps: ${revision.dependencies.mode}`)
    runs.push(await measureRevision(revision, opts, workRoot))
  }

  for (const run of runs) {
    log(`\n${run.label}`)
    log(table(run.results))
    const fixture = run.results.some((result) => result.list.source === 'fixture')
    if (fixture) {
      log(`  note: no ${opts.list} had children — a ${opts.rows}-row fixture was measured in the app's own .surface`)
    }
  }

  let differences = []
  if (runs.length === 2) {
    differences = compare(runs[0], runs[1])
    log(`\ncompare: ${runs[0].label} → ${runs[1].label}`)
    if (differences.length === 0) {
      const fields = Object.keys(flatten(runs[0].results[0])).length
      log(`  identical: ${runs[0].results.length} widths × ${fields} measured fields, 0 differences`)
    } else {
      log(`  ${differences.length} difference(s):`)
      const baseWidth = Math.max(24, ...differences.map((d) => String(d.base).length + 2))
      log(`  ${pad('width', 8)}${pad('field', 24)}${pad('base', baseWidth)}head`)
      for (const difference of differences) {
        log(`  ${pad(difference.width, 8)}${pad(difference.field, 24)}${pad(difference.base, baseWidth)}${difference.head}`)
      }
    }
  }

  if (opts.json) {
    try {
      writeFileSync(opts.json, `${JSON.stringify({ widths: opts.widths, runs, differences }, null, 2)}\n`)
    } catch (error) {
      fail(`cannot write --json ${opts.json}: ${error.message}`)
    }
    log(`\nwrote ${opts.json}`)
  }

  return differences.length > 0 ? EXIT_DIFFERENCE : 0
}

process.on('SIGINT', () => {
  cleanup().then(() => process.exit(130))
})
process.on('SIGTERM', () => {
  cleanup().then(() => process.exit(143))
})

let exitCode = EXIT_CRASH
try {
  exitCode = await main()
} catch (error) {
  if (error instanceof ProbeError) {
    process.stderr.write(`web-layout-probe: ${error.message}\n`)
    exitCode = EXIT_FAILED
  } else {
    // Not a layout difference: this is the probe breaking. It gets its own
    // exit code so that a wrapper around it cannot mistake a crash for the
    // answer it was asking for.
    process.stderr.write(
      `web-layout-probe: unexpected error (exit ${EXIT_CRASH} means the probe broke, ` +
        `not that a measurement differed):\n${error.stack ?? error.message}\n`,
    )
  }
} finally {
  const kept = process.argv.includes('--keep')
  const hadAnything = started.length > 0 || cleanups.length > 0
  await cleanup()
  const leaked = started.filter((child) => child.exitCode === null && child.signalCode === null)
  if (leaked.length > 0) {
    log(`  WARNING: ${leaked.length} process(es) still running: ${leaked.map((child) => child.pid).join(', ')}`)
  } else if (hadAnything) {
    log(`clean: preview and browser stopped, temporary directory ${kept ? 'kept' : 'removed'}`)
  }
}
process.exit(exitCode)
