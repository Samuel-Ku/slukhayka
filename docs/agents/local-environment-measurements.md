# Build-slot measurements

The numbers behind `local-environment.md`'s build slot: what the ceiling costs, and
what the memory guard stops. They live here because the slot's rules are procedure
— read them before a run — while these are the measurements that chose them.

### The measured ceiling

The one-run rule is not caution, it is arithmetic. On 2026-10-12 this machine
(i5-7300HQ, 4 cores / 4 threads, 16 GB) ran one identical build — the two tasks
`:app:validateTestPartitions :app:testPureJvm` narrowed to
`com.slukhayka.audiobooks.data.achievements.AchievementEngineTest` through
`-Ptest.selectedClasses` — in detached worktrees off `origin/main` (`d46bf54b`):

```sh
git worktree add --detach <dir> origin/main
GRADLE_USER_HOME=<per-run dir> GRADLE_RO_DEP_CACHE=$HOME/.gradle/caches \
  TMPDIR=<dir on disk, not tmpfs> SLUKHAYKA_GRADLE_ARGS_FILE=<args-file> \
  scripts/test-all.sh
# <args-file>: :app:validateTestPartitions / :app:testPureJvm / -Ptest.selectedClasses=<FQCN>
```

Those runs deliberately did **not** go through the slot above: no `flock`, no
`--offline`, and each run had its own writable `GRADLE_USER_HOME` over a shared
read-only dependency cache. That is what the experiment measured — what happens
*without* the slot. Every run was `--no-daemon` (as `test-all.sh` always is),
and `TMPDIR` was pointed at a directory on disk because `$HOME` is read-only
here and tmpfs competes with the build for the same memory.

Memory was sampled once a second: `MemAvailable` from `/proc/meminfo`, the sum
of `Used` in `/proc/swaps`, and the summed resident size of the JVM processes —
the first runs picked those out by command line, the later ones by an `exe` that
resolves to `*/java`; the difference is a few megabytes of wrapper processes.
Each row is the extreme its own run reached inside its own wall-clock window
(`end − start`, rounded down); the warm row reads 62 s where Gradle itself
printed `1m 01s`.

| Runs | Wall | Peak JVM RSS (sum) | MemAvailable min | swap used max |
|---|---|---|---|---|
| 1 cold (full compile) | 662 s | 4.74 GB | 2.66 GB | 0.18 GB |
| 1 warm, nothing changed | 62 s | not sampled | — | — |
| 1 incremental (one file edited) | 213 s | 3.61 GB | 3.86 GB | 0.68 GB |
| 2 warm, concurrent | 123 s | 1.99 GB | 5.99 GB | 1.93 GB |
| 3 incremental, concurrent | **killed at 613 s** | 8.10 GB | 1.13 GB | 3.91 GB |

Two warm, compile-free builds fit, and the second buys nothing: 123 s side by
side against 124 s one after another (2 × 62 s). Three do not fit at all —
memory fell monotonically (5.3 → 4.0 → 3.0 → 2.1 → 1.1 GB), swap grew to
3.91 GB, `load average` reached 23.5 on four threads, and an external watchdog
killed the runs at 613 s rather than leave the machine wedged. The slot itself
only serialises; the guard that stops a single run is
`scripts/build-slot-guard.py`, described below. One incremental run over the
same work takes 213 s.

So the slot serialises builds because a third one has nowhere to live, and
because four threads shared by three Gradle JVMs plus in-process Kotlin
compilation spend their time switching. What stays parallel is what does not
build. The cloud alternatives considered and closed on their own evidence —
not on this table — are recorded in ADR-0062.

### The memory guard

The slot serialises builds; it does not stop the one already running. A single
Gradle run can still take the machine down, and the row above is what that
looks like. `scripts/build-slot-guard.py` is the outside that now lives inside
the repository:

```sh
.worktrees/gt ./gradlew --no-daemon :app:testPureJvm
```

It refuses to start when `MemAvailable` is below `--floor-mb` (exit `3`), and it
stops the **process group it created for the run** — never anyone else's — when
free memory falls under `--critical-mb` or swap grows past `--swap-growth-mb`
during the run (exit `4`). The command's own exit code passes through untouched;
`128+N` means the guard or the command died from signal `N`. The floor sitting
above the critical line is deliberate: a run that has already started may
finish, one that has not started should not begin.

The thresholds are defaults taken from the numbers above, not constants:

- `--floor-mb 2000` — a wedge line, not a comfort line. The measured peaks are
  3.61 GB (incremental) and 4.74 GB (cold), so a run starting at 2 GB free will
  still swap; what it should not do is take the machine with it. The state
  #1272 recorded had 1.13 GB free, and that is refused.
- `--critical-mb 800` — below this, the run is already the problem.
- `--swap-growth-mb 2000` — **growth during this run, not the absolute figure**:
  zram does not drain by itself, so an absolute ceiling would eventually stop
  every later run forever, and "free some memory" would not clear it.

Those are the code's defaults. The wrapper on this machine picks a floor from the
command instead, because the two kinds of work want different room:

- `--floor-mb 1500` for light work — a scripted run, `mutate.sh status` (with or
  without `--class`, since status never starts Gradle), and a `--python` mutation
  run. `MemAvailable` sampled every 5 s for 100 s sat between 1.93 and 2.10 GB
  free, and the flat 2000 MB default — a wedge line, as the code defines it —
  refused work that needs no build's room twice in a row.
- `--floor-mb 3500` for anything that starts Gradle: `gradlew`, `test-all.sh`,
  `test-changed.sh`, `pre-release.sh`, and a mutation run carrying both `--patch`
  and `--class`. This is a policy
  choice, not a derived constant. The only sizing evidence is #1272's peak JVM
  RSS — 3.61 GB incremental, 4.74 GB cold — and 3500 sits under the smaller one;
  where swap actually begins against starting headroom was never measured, and
  the code still documents the floor as a wedge line, not a swap line. The choice
  says: a build with less room than its smallest measured peak is not worth
  starting.

The rule keys on the command rather than on the task: `gradlew --version` is
refused like any other Gradle call, and a command that merely names a build script
among its arguments is refused too — the wrapper cannot know how heavy the run
will be.

End to end through the wired wrapper on 2026-10-13, under the flat floor of the
time: a refusal returned `3` naming the real number, a real `./gradlew --version`
ran to `0` in about a second, and a supervised run whose reading fell returned `4`
with its process gone. That last check ran against a fake memory file, because
this machine has no honest way to fall that low on request. Under the matcher,
light paths still run — `mutate.sh status --class …` exits `0` — while `gradlew`,
`bash scripts/test-all.sh`, `scripts/pre-release.sh` and a `--patch … --class`
mutation run are each refused with `3500 MB` in the message. The guard is not
free: a ten-run bench of an empty command through the slot lands in single
milliseconds without it and around 0.1 s with it — two benches on this machine
agreed on the order of magnitude, not on the digits, so treat it as ~0.1 s
against builds measured in minutes.

A code collision is worth naming: because the command's own exit code passes
through untouched, a command that legitimately exits `3` or `4` cannot be told
from a refusal or a stop by the code alone — the reason is on stderr.

Three honest limits. The guard watches a process **group**, not every JVM a
build may own: a Gradle daemon outlives the group, which is one more reason
`test-all.sh` runs `--no-daemon`. A one-second sampler reports a machine that is
already going under, not one about to; it stops the run, it does not predict.
And the heavy build has not run under the guard here: the case the guard exists
for needs 3.61–4.74 GB, while this machine has sat at 1.9–2.1 GB since the
wiring, so the command-aware floor now refuses it outright, and what is proven
end to end is light commands and the stop path.

Both branches are pinned by `scripts/tests/test_build_slot_guard.py` against
fake memory files — refusal, stop, SIGTERM escalation, swap growth and the
untouched outsider — and every stop test also asserts that the run's processes
are **gone**, so a guard that signals nothing cannot pass.

The wrapper itself is machine-local and this repository does not carry it, so
the wiring is a change to that file, not to this one:

```sh
# .worktrees/gt, abbreviated: the executable copy is that file, not this snippet.
root=$(git rev-parse --show-toplevel)
if [ -x "$root/scripts/build-slot-guard.py" ]; then
  floor=1500                        # light work; the machine idles at 1.9-2.1 GB
  patch=no; klass=no
  for arg in "$@"; do               # a heavy path can hide behind `bash` too
    case "$arg" in
      gradlew|*/gradlew|test-all.sh|*/test-all.sh|test-changed.sh|*/test-changed.sh|pre-release.sh|*/pre-release.sh) floor=3500 ;;
      --patch) patch=yes ;;
      --class) klass=yes ;;
    esac
  done
  [ "$patch" = yes ] && [ "$klass" = yes ] && floor=3500   # mutate.sh run mode
  exec flock -w 7200 <slot-file> "$root/scripts/build-slot-guard.py" --floor-mb "$floor" -- "$@"
fi
exec flock -w 7200 <slot-file> "$@"
```

On this machine the wrapper is wired to it: a lane whose worktree carries
`scripts/build-slot-guard.py` builds under the guard, and a lane that predates
it keeps working through the plain `exec flock` in the snippet above.
