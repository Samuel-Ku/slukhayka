# Issue tracker: GitHub

Issues and PRDs for this repository live as GitHub issues. Use the `gh` CLI
for all operations and infer the repository from `git remote -v`.

## Conventions

- Create: `gh issue create --title "..." --body-file <file>`.
- Read: `gh issue view <number> --comments` and include labels.
- List: `gh issue list --state open --json number,title,body,labels,comments`.
- Comment: `gh issue comment <number> --body "..."`.
- Label: `gh issue edit <number> --add-label "..."`.
- Close: `gh issue close <number> --comment "..."`.
- Language: issues, their comments, pull requests and commit messages are
  written in English. The Ukrainian voice in `docs/voice.md` covers social
  posts and release notes, not the tracker.

## A ticket's premise must exist in `main`

A ticket is filed from the state of `main`, not from the working copy. The price
has already been paid: **#1261** claimed that `AGENTS.md` referenced `CODING_STANDARDS.md`. In
`main` that reference does not exist (`git grep -l CODING_STANDARDS origin/main` —
empty), while the file and the reference appeared **together with an uncommitted edit from another
machine**. The ticket had to be closed as invalid — and not because the author was
careless, but because the working copy's state looks like the repository's state.

The rule is single and machine-checked: **every path named in the issue body is either in
`origin/main` or marked as one this ticket will create.** The check is run by
`scripts/issue_premise.py` — before the issue is created, against the body draft:

```sh
git fetch origin main                                              # ref must be fresh
python3 scripts/issue_premise.py --body-file .scratch/1264.md      # draft
python3 scripts/issue_premise.py --body-file -                     # same from stdin
python3 scripts/issue_premise.py --issue 1261                      # already filed
```

Running it before the lane is not advice but a step: `scripts/new-lane.sh` calls this same
mechanism (`--issue`) on the lane copy and prints its verdict in the facts for the brief.
It closes the third kind of wrong number — "a number that USED TO be true" — **but only
in PATHS**: a path from the body disappeared from `origin/main`, or `NEW:` sits on
something that already exists. **Numbers in the ticket's PROSE are invisible to the step:** #1315 is still
green today (`python3 scripts/issue_premise.py --quiet --issue 1315` → «ПРЕМИСА Є» ("premise holds")), even though
three of its numbers went stale between writing the ticket and creating the lane. That limit
is named in the step's code too, so the document does not promise more than the mechanism. Its price
is **measured, not promised**:

```sh
python3 scripts/issue_premise.py --measure-prose-numbers
```

On the last 200 tickets (`584dda73`, 2026-10-15) this yields
**four** tickets with a number before a **registry** noun ("N guards", "N
records") — #814 (an inventory of local reports, not the tree), #1312, #1315, #1321;
three of the four really do assert the tree registry's state and **drift**. A number
before an ordinary unit noun ("N paths", "N tickets") appears in **17** of the
200 tickets (the first version of the measurement gave 172 here — a wrong figure: the alternatives
list without a group caught a bare noun without a number, and the metric check did not see that;
a review finding). The requirement "check every number in prose" would turn into parsing prose and
guessing what the number asserts; so this is **a named limit with a number**, not a silent
hole. A finding (code 1) and an unknown verdict (code 2) are warnings that
leave the lane in place; code 2 from the lane itself remains for the state when the ticket is not
read and there is no verdict at all.

The "this ticket will create it" mark is `NEW:` **inside** the backticked span,
before the path:

```markdown
- [ ] `NEW:docs/agents/premise.md` gets the premise check rule.
```

Why this is not "check more carefully":

- **The verdict comes from `git`, not from judgement.** There is one question per path:
  does `origin/main` have an entry with this name (`git cat-file -e`). The same issue body
  gives the same verdict on every machine with the same `origin/main`; the working
  copy takes no part in the verdict at all.
- **The mechanism is not noisy.** A ticket that mentions a file **to be** created
  by the same ticket does not fail: `NEW:` lifts the "is in `main`" requirement — and instead
  demands the opposite: that path must **not** be in `main`. The mark is not a
  loophole but the second arm of the same check: it also catches "declared
  something new that already exists".
- **Silence is possible only in writing.** There is one way to stay silent — put
  `NEW:` where the path is not created. That is not forgetfulness, but an assertion in the
  ticket body, visible to both the author and the reviewer; a path that does not
  exist, without the mark, is a refusal (code 1), and an unknown (code 2) does not count as green.
- **A short form does not fail.** `ci.yml` instead of `.github/workflows/ci.yml`,
  `ui/adaptive/WindowLayout.kt` instead of the full path under `app/src/main/java/…`,
  `web/README` instead of `web/README.md` — an ordinary thing in bodies; such a tail
  is looked up in the `main` tree at a segment boundary, **directories included** (`ls-tree -r
  -t`). One occurrence — it passes; several — an unknown with a hint to name the full
  path. The exact path is asked first.
- **Not a path — not a refusal.** Branch names (`feat/…`, `release/v1.3.9`,
  `spec-53/wave-1`), device paths (`filesDir/local_imports`), an ellipsis
  (`app/src/androidTest/...`), slash prose (`try/catch`), an extension on its own
  (`.png`) and a bare attachment name (`live-book-2f-screencap.png`) — not a premise about
  the tree, and the check does not fail on them.

Limits, stated plainly so the check does not pretend to know more:

- **Paths** are checked, not claims about their content. This check will not verify
  a claim that "`AGENTS.md` promises X"; #1261 failed on something else — the file the ticket
  referenced did not exist. Content claims are caught by review, and the check does not
  replace it.
- The verdict is about the **local** `origin/main`. The check makes no network requests and
  does not pull the ref secretly: a stale ref gives a verdict about an old tree. The remedy is
  `git fetch origin main` before the run; an unknown ref is code 2, not "green".
- A path **without backticks** (in a Markdown link, in prose) is not checked. This is
  a limit of premise selection, not of the check: what the author named as a path is what was checked.
  A ticket with no path in backticks at all — an **unknown** (code 2), not a green
  light: "nothing checked" does not equal "everything is fine".
- Quoting someone else's wrong claim ("the working copy mentions
  `CODING_STANDARDS.md`") also gives a refusal — the check does not read context and does not
  tell "the path exists" from "the path is absent, and that is the problem". The remedy is cheap: remove
  the backticks from the counterexample mention or mark it `NEW:`. Reading context
  would mean guessing, and a guess in the check costs more than a rare false refusal.
- A bare name **in an attachment format** (`.png`, `.jpg`, `.mp4`) is not
  considered a path: the evidence sits next to the ticket, not in the tree. A bare name with
  a document or code extension (`REPORT-continuation.md`,
  `2851-temna-materiia.html`) is considered and gives a refusal: there is no way to tell
  a local report from a repository file by its form.
- Slash prose is caught **incompletely**: `try/catch` — yes,
  `works.title/author/seriesTitle` — no (the dot in the first segment makes it
  indistinguishable from `ui/adaptive/WindowLayout.kt`).
- A branch is recognized by a head from the list (`feat`, `fix`, `release`, `spec`,
  `test`, `backup`, `chore`, …), and the numeric tail is dropped: `spec-53/wave-1` —
  the same head `spec`. A branch with a head outside the list (`my-branch/x`) stays a
  candidate and gives a refusal. A head that is also a top-level entry of
  `main` cannot be in the list — it would swallow every path beneath it. That is
  why `docs`/`doc` were removed and `test`/`tests` remain; the measurement is a
  command: `python3 scripts/issue_premise.py --measure-head docs` (78 paths in
  68 tickets in the 2026-10-13 window), `--measure-head test` (0). The window is the last
  200 tickets, so **the number drifts with the issue list**, not with the ref:
  the header of #1281 said "83 paths / 72 tickets", and it does not reproduce on
  any code — the window moved.
- A single-segment name **without an extension** whose short form matched only a
  **directory** gives an ambiguity (code 2), not "the premise holds": `main/` in the body of #982
  meant the Kotlin sources directory, while the check found `app/src/main` at an arbitrary
  depth. The verdict distinguishes a **blob** from a **tree** — the type is returned by `git ls-tree`,
  and a directory in the verdict is named as a directory. The rule's shape is **narrower** than "any directory past
  the tail is an ambiguity" on purpose, and the limits are named in the code: the exact path
  is asked first; a directory found past the tail of a **multi-segment** name
  (`ui/components`) stays a premise; the `NEW:` mark does not weaken; a name with an
  extension that is found only as a directory stays "exists".
- **The asymmetry of the OUTPUT is named, and it is not about depth but about PROOF:** a
  name with a slash that matched a directory **at the root** stays a premise (`docs/`, `web/`,
  `app/src/main/` — the exact path is asked first), while a directory **deeper** is an
  ambiguity (`drawable/`, `generator/`, `workflows/`, `mipmap-hdpi/` —
  four real directories, and all four give code 2 on the real tree).
  The discriminator is the **exact tree entry** (`main_has_path`), not depth.
  **The asymmetry cannot be removed without a cost, and every cost is named:** widening the
  shape (a bare directory matched by a single tree — a premise) means making `main/` from #982 a premise
  too, and its body writes `main/` **with the same trailing slash** as `drawable/`;
  reading the body as a whole (resolving `drawable/` through `…/drawable/img_…jpg` named in
  the same body) is a guess, and it does not even remove the asymmetry — it hides it where the body named the full path; symmetry
  downward (code 2 for the root too) **removes** the output asymmetry — both ends give 2 —
  but doubles the **false ambiguity** on the most frequent form of reference; at the level of
  names the mode prints that cost as a number, while **at the level of tickets there is no measurement** — and the number
  is not named there. The cost of what exists is named by **a number the mechanism itself derives**:
  `python3 scripts/issue_premise.py --measure-bare-trees` (on `origin/main` =
  `07194d4d`, 2026-10-24; the registry was rewritten that day because the `.husky/` directory appeared
  and the three numbers that see it grew by exactly one name — the definition did not
  change; before that, 2026-10-19 #1352 added `scripts/swarm`, 2026-10-18
  #1303 — `scripts/systemd`, the growth was the same). The definition lives in `bare_tree_names` — with the same
  predicates as the verdict, and neighbouring definitions are printed by **the same mode**.
  These numbers are **data, not prose**: the header must equal the `PRICES` registry in
  `scripts/issue_premise.py`, and it is reconciled by `scripts/doc_numbers.py` — both this file
  and the header of `issue_premise.py` are in its `SCANNED`, and the lines below are in
  `FIGURES` (source `("record", "prices", key)`), so a header that diverges from the
  registry is a finding, not silence (ticket #1308):

  - «ціна правила про теку» — 78 (single-segment directory names without an
    extension, not root, whose only short form is a tree)
  - «без крапки в імені» — 76 (the difference is exactly `.aistudio`)
  - «разом із кореневими» — 83 (root `.github`, `app`, `docs`, `gradle`,
    `web` **do not pay** today — the exact path finds them first)
  - «без крапки, разом із кореневими» — 80
  - «будь-який збіг-дерево» — 135
  - «без крапки, будь-який збіг-дерево» — 133 (the difference is again exactly `.aistudio`)

  **What the gate enforces here, and what it does not.** Header against
  registry — machine-checked; the registry itself is **data**: whether it is true is shown
  by a run of `test_issue_premise.PriceRecordTest` where the ref is readable, while in CI, where
  there is no `origin/main` (the runner fetches the merge-ref), it is not — and that is named, not
  passed off as checked. The `gh` window numbers (`--scan 200`: 91/90/10/9 on
  `f0fbc576`, 93/87/11/9 on `f81c724f`) are not in `FIGURES` — they are snapshots
  of OTHER revisions, and the `record` source reads one bare number per line, so
  they remain prose. The snapshot of the lane itself (`102/78/11/9` in the premise step of
  `new-lane.sh`) has been enrolled since #1328 with the source `("record", "scan_window", …)`
  from the `SCAN_WINDOW` registry, like `PRICES`: the gate has neither `gh` nor network, so
  the source is a RECORD, not a run. The limit of the record is that it is a SNAPSHOT of a moving window, and
  it is updated by hand by whoever re-measured `--scan 200`.
  Some of these directory names are **false ambiguities**, like the three false
  refusals named above: of the tickets that moved, the false one is **#997** — it writes "other files in
  `drawable/`", while `drawable` is the directory `app/src/main/res/drawable`. The remedy is cheap —
  name the full path. The ends are pinned by the tests
  `test_an_exact_tree_path_is_still_the_premise`,
  `test_a_bare_directory_name_is_ambiguous_through_real_git` and
  `test_the_same_trailing_slash_is_premise_at_the_root_and_ambiguous_deep`, and the
  number by
  `test_the_documented_command_counts_the_same_class_the_verdict_makes_ambiguous`.
- The mechanism remains a **process step**: it has to be invoked. It does not run in
  CI, because an issue does not live in the repository tree, and it does not replace
  review.

### Noise: a number you can re-measure

The numbers here are not claims taken on faith — the mechanism itself produces them:

```sh
python3 scripts/issue_premise.py --scan 200
```

On the last 200 tickets (ref `origin/main` = `f0fbc576`, 2026-10-13): **91** —
«премиса є» ("premise holds"), **90** — «шляхів у бектиках немає» ("no paths in backticks"), **10** —
an ambiguous name (`mutate.sh` ×3 — the tree holds two more fixture copies;
`ui/components`, `ui/adaptive`, `data/ingest`+`data/source`,
`AndroidManifest.xml` ×2 — the remedy is the same: name the full path; and three new ones under
the directory rule: `main` in #982 and #1281, and `drawable` in #997, where #997 is
a **false** ambiguity, and the reason is named in the asymmetry limit above),
**9** — a refusal:

- **#1185** and **#828** — the check's real target: the tickets reference an ADR and a plan
  (`docs/adr/0061-…`, `docs/plans/2026-10-07-…`, `docs/adr/0042-…`) that are not in
  `main` — they live only in an **unmerged** PR. The same class as
  #1261;
- **#784** — `docs/licensing.md` in the "updated" list, though the file is not in `main`;
- **#1261** — the ticket this check was born from;
- **#1272** — `NEW:docs/adr/0062-one-heavy-build-slot.md`, but the ADR is already in `main`:
  the mark's second arm fired, and the refusal is honest — the ticket went stale, not the check;
- **#816** — `web/prototype`, which the ticket **itself says** will not go into `main`:
  the refusal is honest, the remedy is backticks or `NEW:`;
- **#962**, **#1029**, **#822** — false refusals, and each is named with its reason:
  a bare local report name, a fixture name in someone else's test, prose with a dot.

So false refusals are **three out of nine**, and all three are visible in the `--scan` output.
The price of the directory rule is measured too: it moved exactly **three** tickets out of 200 from
«премиса є» to an ambiguity — #982, #997 and #1281 itself (whose body
cites `main/` as an example of the limit).

The window has moved since then, and #1329 added its own: on `584dda73` (2026-10-15) the
same `--scan 200` gives **102** «премиса є», **75** «шляхів у бектиках немає», **15**
ambiguous and **8** refusals. The four new ambiguous ones — #1264, #1270,
#1271, #1276 — are the same `mutate.sh` in the `mutate.sh --python` form, which
the mechanism did not see at all before #1329; the remedy is the same (name
`scripts/mutate.sh`), and refusals did not increase. The command reproduces the
numbers, not this paragraph.

## Pull requests as a triage surface

**PRs as a request surface: no.**

## Publishing

When a skill says to publish to the issue tracker, create a GitHub issue.
When a skill says to fetch a relevant ticket, use `gh issue view` including
comments and labels.
