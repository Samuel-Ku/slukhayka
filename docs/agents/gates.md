# Gates

Two exceptions, and they are gates rather than records. The first is the empty
shape of a `case` decision (`"" | "detached HEAD"` — `#1254`, where a test pinned
the literal instead of the condition). `scripts/empty_shape_mutants.py` writes that
one mutation itself, runs the `scripts/tests` modules that name the script, and
refuses when the witness suite stays green — i.e. when no test produces the empty
shape at all. It runs inside the `script-tests` job, has no switch to turn it off,
and covers only `case` arms: a literal alternative such as `-h|--help` is not
pinned by it. `python3 scripts/empty_shape_mutants.py --list` names the shapes it
would mutate.

The second such gate is about the fields a verdict is made of (`#1260`): `errors`
was pinned by no test on either half — a mutation zeroing it left 20/20 green in
Python, and the stand-in `gradlew` wrote `errors=0` always in the JVM half.
`scripts/verdict_field_mutants.py` finds every place a deciding field (`tests`,
`failures`, `errors`, `skipped`, `artifactSha256`) enters the chain — the JUnit
attribute the producer writes, the read out of the XML, the transport into the
record, the record's own field, a read of that field, the same field in a `status`
answer, and the comparison that makes the verdict — builds a mutant that leaves the
field its zero at each one, and runs the witness tests. A green suite means that place holds nothing (`1`, naming the
field, the place and the witness); an unknown (a field with no place, no witness
module, a red suite *without* a mutation, a run that never finished, a missing tool
part, a mutant that does not parse) is `2`.
`python3 scripts/verdict_field_mutants.py --list` names the places and the zero it
would write. Its named limits: the JVM producer is Gradle and is not in the tree,
so that half is pinned through the XML read; a mention of the field in prose (a
`detail` string, a comment) is not a place; `skipped` decides a verdict but never
travels into the record at all; and a field with no verdict-rule place at all is an
unknown (`2`), not a pass — moving the decision onto another variable would leave
every remaining mutant alive.

The third such gate is about the guards themselves (`#1270`). The eighth case of
"a check that does not check" was the guard written against that very class:
`test_measure_head_exists_because_the_docs_cite_it` held a substring of *someone
else's* help text (`--scan-limit … for --measure-head`), so cutting the option
left `--help` still containing the substring and the guard green on a broken tree.
`scripts/guard_witnesses.py` keeps a registry of `guard + mutation` pairs
(`GUARDS`, data in the diff, not a sentence in a PR description): for each entry it
builds a copy of the tree, applies the mutation, runs the named test through the
same runner `mutate.sh` uses, and requires it to go red. Green under its own
mutation is a refusal (`1`, naming the guard); an unknown — the mutation no longer
applies, the mutant does not parse, the baseline is red or executed no test at all,
the guard does not resolve, the mechanism is inside itself — is `2`. A mutation may
not edit the lines of the guard's *own* test method: "break yourself to prove you
are seen" is not a witness (its helpers may be mutated — that is how a rule inside
a helper gets pinned). It runs inside the `script-tests` job and has no switch.

Its named limit, and the limit is in the code: **enrolling a new guard is manual.**
No static rule tells "looks like a guard" from "is a guard" — this repository has
already paid for that attempt in `test_fixture_environments`, six review rounds of
AST inference — so **old guards are not enrolled retroactively**, and the mechanism
does not pretend otherwise: it says how many it covers and prints that on every
green run. In practice the review of a new guard narrows to one question: **where
is its entry?** `python3 scripts/guard_witnesses.py --list` names every entry,
`--guard <target>` runs one.

`#1279` asked the question those six rounds left open — can that witness be
**derived from the AST** rather than named? — and the answer is no, proven rather
than assumed. `scripts/tests/test_witness_derivation.py` runs one file twice
against a stand-in whose branch is unnamed (so the tool refuses): without an
external switch the test is green, because it never ran the tool; with the switch
it is red, because it ran the tool and saw the refusal. The source is
byte-identical between the two runs, so **no total function of the text can be
right about both** — the truth is a fact of the run, not of the code. A function
allowed to answer *unknown* does survive both runs; what it buys is exactly what
the named list already is, because it has to answer *unknown* where the automation
was wanted — on the witness — and derive nothing. The same module keeps the six
rounds' trail as a regression: five shapes that a criterion "the test mentions the
stand-in" accepts, and not one of them runs the tool. That trail is evidence, not
the proof — a ninth shape would not change the answer, and the module says so.

The price of naming is in the code next to the guard it costs:
`EnvironmentTest.test_the_guard_sees_only_the_runs_it_is_named` puts an
inheriting stand-in in a copy of the `empty_shape` fixture and runs it three
ways. Named and stripped, the run is green. Named and poisoned, it is red with
`AssertionError: 0 != 1` — the poison reached the stand-in, which is what the
guard exists to catch. Unnamed and poisoned, it is green *because the run never
happens*: a test outside `ENVIRONMENT_TARGETS` is not executed under either
environment. So a new stand-in is covered only when someone names its run, and
nothing reddens when they forget; the registry catches the unregistered — a file
or an embedded program with no record — and cannot catch the unnamed. That is the
whole price, and it is a run, not a paragraph. The compensation landed as `#1294`:
`EnvironmentTest.test_every_named_run_reddens_when_every_stand_in_is_broken`
breaks every registered stand-in in a copy of the tree and requires every named run
to go red — no verdict read from the AST. It also named the runs that executed no
stand-in at all, and those left `ENVIRONMENT_TARGETS`, because the environment guard
was watching an empty place for them. What survives is the *named* pairing — "this
run exercises *this* stand-in" — which would cost one mutation per stand-in; the
guard's docstring names that limit in the code.

The next review round (`#1341`) closed **one** hole in that compensation and only
**named** a second — an earlier version of this paragraph said "closed two holes", and
that was one word too many: one was named, not closed. The closed one: a run that goes
*skipped* under the broken stand-ins used to pass, because only `green` counted as "did
not redden"; now anything but `red` is a finding (`runs_that_did_not_redden`), on the
named runs and on the fixture modules alike. The named one: the execution proof held
for the embedded programs only — a fixture file is invoked as `bash gate.sh`, so
breaking it appeared to mean replacing its text, and a run that merely *reads* that
text reddened too; for the six file stand-ins under `fixtures/` the guard could not
tell a reader from an executor. That was stated where the mutation lives
(`broken_tool_text`) as a missed hole rather than a safe refusal.

That hole is closed too, and as a **choice**, not a limit of the possible. The
mutation now keeps the original text byte-for-byte — first line and whole body — and
inserts a killer line after the shebang (`broken_tool_text`), so a run that merely
*reads* the file stays green and the guard names it a finding, while the executor
reddens. `test_a_reader_of_a_file_stand_in_is_named_and_not_accepted` shows both sides
through the guard's own verdict and runs all six file stand-ins intact (exit zero) and
broken (non-zero). The limit that remains is narrower and also named: a run that
compares the whole text byte-for-byte notices the inserted line — a comparison, not a
read.

The fourth gate is about the records themselves (`#1276`). One target produced
**six** switches of a single form: a registry entry could *declare* something about
itself — a marker, a mode, a witness, an exemption, a shape — and nothing checked
that the declaration was true. The cure, proven three times, is not a seventh check
but removing the ability to declare: `runs=False` went by deleting the field,
`witness` by deleting it together with its inference, `shape_pin` because the pin
proved it *names* rather than *compares*. The same rule now applies to every field —
derive it, or delete it. `verdict_field_mutants.Field.decides` is gone: it declared
what the field decides, and nothing read it. And `test_partitions` no longer declares
the web check's steps as a literal — it reads the command out of
`scripts/test-changed.sh`, the script that runs it, so the record can no longer drift
from the executor in silence.

What is left is the pin on the field **set**: `scripts/declared_fields.py` holds the
list of declaration records in the verification machinery (`RECORDS`), and a new
field in any of them reddens `test_declared_fields` until its author names who holds
it or removes it. The mechanism does not ask what a field means, and does not pretend
to. Its named limit lives in its docstring, and it is the same limit as the guards':
a new *record* is not noticed on its own — the list is extended by hand — and the
prose in the comments beside `RECORDS` is prose, checked by nothing. Its guard is
enrolled in `guard_witnesses.py` with the live example: the mutation **adds** a field
to a record, and the pin has to go red.

The fifth gate is about the numbers themselves (`#1277`). Seven numbers in the
docs turned out to be checked by nothing, and every one of them was found by an
independent review, not the author: false alarms on the real history ("3 of 10",
not "none left"); "931 paths in the tree" (858/881/0/970 — none of them 931); a
**command that did not exist** (`--measure-head`; `git log --all -S` had one
occurrence — the doc line itself); "no network" (`ubuntu-latest` has egress; the
load-bearing half was a missing `GH_TOKEN`); "326 tests" (327); "74.7 MiB"
(76.2, corrected in `#1270`); and `The seventh case of "a check that does not
check"` itself — the line the third gate describes, whose number this mechanism's
first run showed to be a different one. `scripts/doc_numbers.py` draws the line the repo can actually
hold: **a number that claims something about the tree today must be derived from
a record in the tree, or it does not exist.** `FIGURES` names (file, anchor,
source), where the source is `("count", registry)` or `("position", series,
key)`; the line the anchor names must carry exactly one number of that source,
and it must be one of the spellings the record derives. `SCANNED` lists the files
the mechanism *searches*: a number of a listed shape in them that no entry claims
is a refusal, so a new number cannot pass silently — it is derived, or it is
gone.

What it found on its own first run is the evidence, not the idea.
`local-environment.md` called that case `The seventh case of`, while the series
gives `#1270` the **eighth** place; the mechanism that named the class opened
with `Дев'ятий випадок` and ``до цього вісім разів`` over a list of **seven**
issues — both numbers off by one against the two enumerations that exist.
`CONTRIBUTING.md`
(the list of five, written at the time) puts the review finding **#1218** third —
the pins held *where* the width comes from and did not hold *which* layout follows
— and adds that the layout is measured by another instrument, the probe `#1239`;
the `#1270` body puts `#1239` third. They are one case under two keys: `#1239` is
the web layout probe (`scripts/web-layout-probe.mjs`), not a case of the class,
and both lists put `#1254` **fifth**, which can only be true if the case they put
third is the same one. So `CASES` keys it `#1218` and names both references, and the
series has **eight** cases; reading the two lists as nine counts one case twice.
And the same header charged ``шість записів`` for a `GUARDS` that held
**seven** — and eight the moment this change enrolled its own guard. The case
ordinal had no ground truth anywhere in the tree, which is why nothing could
derive it: `CASES` puts the eight in data, and `python3 scripts/doc_numbers.py
--list` prints each one with the place it holds. So the ordinal was not
"corrected" to another prose number — it got a command; and the three prose
numbers that disagreed with the record (`local-environment.md`'s, and the
header's two) died. If the series really has a ninth, that is a one-line edit
to `CASES`, and the mechanism will name every number that moves.

Its named limits are in its docstring, and the first one is the price of the
whole idea: **the mechanism sees only the shapes and files it lists.** A scan
that demanded a command beside *every* number would drown — `python3
scripts/doc_numbers.py --audit` counts 197 figures of "number + unit" across
`docs/agents/*.md` and `scripts/**/*.py`, and 190 of them sit on a line with no
date, most being records of the past (the seconds and gigabytes of one build on
one machine on 2026-10-12) that no command reproduces at HEAD. So
bytes and seconds are outside `SCANNED`; where such a number must stay, what is
pinned is the **relation** that survives the drift, by the test that measures it
(`#1270`: `naive > 10 × copied`, not "76.2 MiB"). Enrolling a figure is manual,
like enrolling a guard, and the mechanism says how many it covers rather than
how many it should. `python3 scripts/doc_numbers.py` is the check (0 — every
enrolled number derives, every cited command exists and declares its options, and
nothing is unclaimed; 1 — a finding; 2 — an unknown:
a missing file, an anchor that is not alone, a line that no longer carries its
number, a line that does not name its own key, a key that left the series, a
number past the spelling table), and its guards are enrolled in
`guard_witnesses.py` with live mutations: cut a place out of `CASES`, or fabricate
an option in a cited command, and the run reddens.

The gates build a copy of the tree for every mutant, so the temp filesystem is
their budget as well as their workplace (`#1271`). Only `scripts/` is copied and
every other top-level entry is a symlink: one tree costs 1.4 MiB against 76.2 MiB
if everything were copied, and 0.01 s against 0.32 s (measured with
`tmp_budget.tree_bytes` on the tree `d6c35cdf`; the number grows with the tree, so
what is pinned is the *ratio* —
"the full copy is more than ten times the cheap one" — not the number). The field
gate builds 38 trees, so that is 38 × 1.4 MiB = 53.2 MiB against
38 × 76.2 MiB = 2.8 GiB. At most `--jobs` trees are alive at once, because each is
released right after its run: the measured peak is 5.3 MiB, against 46.9 MiB
before the change, when all 38 trees lived to the end of the run.
`scripts/tmp_budget.py` measures the copy
before the first one, adds headroom for the workers and a floor for the witnesses'
own scratch, and refuses with `2` and both numbers ("needs X, has Y") rather than
filling the filesystem: an exhausted `/tmp` does not fail that one run, it fails
*every* process start in the session — `bash` will not run even `true`, and
`ripgrep` does not start, so the work looks like broken tools rather than a spent
quota. The same module reclaims temp trees left by earlier crashes (loudly, naming
each one and the bytes it returned) and refuses a temp directory on a small
RAM-backed tmpfs — `TMPDIR` on a tmpfs below 2 GiB needs `TMPDIR` or
`SLUKHAYKA_MUTANTS_TMPDIR` pointed at a disk path, and an explicit move is
announced on every run. There is no switch to turn any of it off, and the check
lives in exactly one place per gate so that removing it reddens a test.
