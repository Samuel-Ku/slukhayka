# Agent work reservations

Before implementing, delegating or continuing any ticket:

Steps 2–4 below are done by one command — and by it, not by three hands:
`scripts/new-lane.sh <number> <slug>`. It takes a fresh `origin/main`, reserves
the ticket with the existing `agent-work-claim.py` (if it is taken — code 2 and no copy
created), refuses if a lane copy already exists, checks the ticket premise with
the existing `scripts/issue_premise.py` (a stale ticket is named in the facts,
and the lane is still created — but not silently) and prints the facts for the
brief from commands, not from memory. It removes the copy after the merge with
`scripts/new-lane.sh --release <number>` — it never deletes the branch. The
steps are still worth reading: the command executes them, it does not cancel
them.

1. Read the current issue with its comments and check the open PRs.
2. Run `python3 scripts/agent-work-claim.py status <number>`.
   If the script is missing in an old worktree, check `agent-work-claims/`
   in the shared directory from `git rev-parse --path-format=absolute --git-common-dir`.
3. If there is another chat's reservation or a comment about current work,
   do not start this ticket and do not hand it to another implementer. Contact the
   coordinator within the coordination the user allows.
4. If the ticket is free, reserve it atomically before the first change:
   `python3 scripts/agent-work-claim.py acquire <number> --owner <thread-id> --reservation <name> [--plan <path-to-existing-plan>]`.
   Exit code 2 means a reservation already exists. `--plan` is optional:
   without it the record holds `null`, and that is more honest than an invented path — the
   lane's plan does not exist yet when the lane is created (`scripts/new-lane.sh` does exactly
   that, #1337). Do not bypass the reservation even
   for the same owner: reread the record and get a concrete assignment.
5. Before coding starts, mark the reservation with a comment on GitHub within
   the coordination the user allows. If the local record and GitHub
   contradict each other, resolve the contradiction first.

All local worktrees of this Git repository read one registry in the shared
Git directory. Separate clones and remote agents must check GitHub:
the local file registry does not sync between machines.

A reservation has no automatic expiry. A stopped process or a
prepared plan does not mean the ticket has been released. Only the chat coordinator
can hand over or drop a reservation, having verified that the work is finished,
updated the plan and the comment on GitHub. The script deliberately has no
force-release command. Do not delete records taken by other chats, or corrupted records.

The `prepared-reserved` state means: the owner is set, implementation is not
started yet. The `implementing` state carries the assigned implementer, branch,
worktree and base commit; the reservation stays with the coordinator.
Subordinate implementers work only on a concrete assignment from the
coordinator; one ticket has one implementation owner. The same holds for
`subagent`: a repeated dispatch into the same lane is a violation, so first
confirm that the previous implementer is inactive. Independent reviews
can be delegated separately, with an explicitly stated read-only mode.

Prepared group #699 / #487 / #934:
`docs/plans/2026-10-04-parallel-699-487-934.md`.

## Open PR snapshot (#1228)

Before choosing work, one read-only call shows all open PRs, their exact
base/head SHAs, checks state, linked issues, local reservations and the latest
comment:

```
python3 scripts/pr-snapshot.py                       # compact output
python3 scripts/pr-snapshot.py --full-json /tmp/prs.json   # full API data separately
python3 scripts/pr-snapshot.py --expect-head 1415=<sha>    # whether the candidate moved
```

Example:

```
snapshot: 2026-10-24T11:00:00+00:00 repo=Samuel-Ku/slukhayka prs=2
#1412 ready base=main@3d32e9c1… head=1c77ab5a… checks=18ok/0fail/0pend/1skip issues=#1410 reservation=absent last-comment=#1410:Samuel-Ku@2026-10-24 divergence=comment-only
#1415 draft base=main@85da0577… head=ab169264… checks=17ok/0fail/1pend issues=#1242 reservation=alice:prepared-reserved last-comment=#1242:alice@2026-10-24 divergence=both flags=changes-requested
```

(The SHA in the example is shortened for readability; the script prints the full 40 characters.)

- `checks=unknown` means GitHub returned no checks — that is not "all green".
- `reservation=absent` means "there is no local record", not "the ticket is free":
  check the GitHub comments before starting anyway (see above).
- `divergence=comment-only` — GitHub already has some comment on the linked
  issue while the local registry is silent; `local-only` — the opposite; `both` — both
  sides know; `unknown` — GitHub would not let the comment be read.
- `truncated:` — `gh` returned exactly the limit (1000) PRs, so the list may be incomplete.
- `head-drift:` and exit code 4 — the candidate moved after your decision.
- Code 2 — invalid `--expect-head`; code 3 — GitHub unreachable; an empty
  state is never passed off as "no PR".
- The script changes nothing: neither git, nor the index, nor the worktree, nor reservations,
  nor issues/PRs. The only write is an explicitly passed `--full-json`.

## Builds in the parallel group

Heavy local Gradle builds have a single slot owner. The coordinator writes
`agent-build-slot.json` into the Git common dir; before every run or
retry, check that record and the concrete assignment. When a failed build
finishes, that does not grant an automatic right to retry: the slot may already have passed to
another implementer. The coordinator hands it over after checking the terminal
result. Clean small host tests can run in parallel with a build.

## Review verdict and merge

A merge is not "no check fails" but `scripts/merge-pr.sh <number>`. The script
refuses if at least one check has failed, been cancelled or is pending, if the PR has no
**machine-readable review verdict**, and if the verdict is older than a new commit,
a new CI run or a branch force-push. Merging is `--squash` only; the script
never deletes the branch.

The gate also looks at the working copy it is run from, and refuses if that copy
still holds an **unfinished rebase of the branch being merged** (`rebase-merge` /
`rebase-apply` — in the shared `.git` or in any worktree; the gate reads the branch from
`head-name`), or if the **PR branch tip does not contain the patches of the whole local branch**
of the same name: a commit whose patch is missing from the tip is something that was
reviewed but not merged. That is exactly how #1239 was merged — the rebase failed
on a conflict, one pick out of four commits reached the remote, and the
original version of the script landed in `main`; it was repaired by a separate PR #1245.

The limits are stated plainly, and each of them is a WARN, not a refusal, because a refusal here would
cost more than the check itself: a rebase of **someone else's** branch (the repository has parallel
lanes, and another copy is not our business: its author either finishes or cancels the
operation); a local branch whose patches are already in the tip per `git cherry` (it was rebased and
pushed from another clone — a refusal advising `force-push` would bring pre-rebase
history back on top of the rebased one); and a local branch that is missing altogether
(a PR from another clone or a fork) — "no branch" is not proof of truncation. A limit of the
comparison itself: it follows the patches of **non-merge** commits, so content that lives only in a
merge commit (a conflict resolution) stays invisible — which is why the WARN says to
check the content before trusting green.

The review verdict is a `review:pass` label on the PR. It is set by the **review agent** —
whoever was delegated the independent review (Spec + Standards) in read-only mode
— and **never by the PR author**: a verdict given to oneself is not independent.
The coordinator only runs the gate and reads its refusal.

It sets the label **after** both sides of the review have passed and CI on the
current HEAD is fully green. The order is not cosmetic: the gate compares the label's
timestamp with the time of the last commit and the last check run, so a label
set before the end of CI will force it to be set again.

A review copy and an **explicit base revision** come from `scripts/review-clone.sh <branch>`: it
takes `origin/<branch>`, makes a read-only copy and prints the base
(`merge-base` with `origin/main`, full sha) together with a ready diff command.
Without it, the review slides onto the coordinator's `HEAD`, and `git diff origin/main...HEAD`
silently shows someone else's files: that is exactly how #1323 got a diff against another branch. An
unknown (no branch, no merge-base, no network) the command names as code 2 and leaves no copy
behind.

```sh
scripts/review-clone.sh <branch>          # review copy + NAMED base revision
gh label create 'review:pass' --description 'Independent review passed (Spec + Standards)' --color 0E8A16  # once
scripts/review-pass.sh <number>   # verdict: both the first one and a re-issue — not `gh pr edit --add-label`

scripts/merge-pr.sh <number>            # merge (squash only, does not touch the branch)
scripts/merge-pr.sh <number> --dry-run  # see the gate verdict without merging anything
```

`scripts/review-pass.sh <number>` re-sets the verdict: it removes the label and sets it
back **in one operation**, so the `labeled` event is always created, and it prints the new
timestamp together with the HEAD revision it attests. This is not cosmetic:
`gh pr edit --add-label` on a PR that **already** has this label is a silent no-op,
no event is created, the verdict time stays old, and the gate rightly refuses
("«після вердикту огляду з'явилися нові коміти»" — "new commits appeared after the review verdict"). That is exactly how #1259 cost the
coordinator a whole round. The script does not trust that the event was created: it reads the
timeline **before and after** and refuses if `labeled` events did not increase, and
if the label does not exist in the repository, it says so and shows `gh label create`.

Why a label and not a GitHub review approval: the PR author cannot set `Approve`
on themselves, and every PR in this repository is opened by the same account, so
`reviewDecision` will never become `APPROVED`. The label is a process signal, not a
cryptographic proof; that is why the gate checks its **time**, not the mere
presence, and requires it to be newer than everything it attests.

The label does not replace the review: findings, as before, live as a comment in the PR or
ticket, and the label only attests that they are absent on this revision. A gate refusal
always names the reason and the next step; there is no need to re-set the label "just in
case" — the gate itself says when it is stale, and it is re-set by
`scripts/review-pass.sh`, not `gh pr edit --add-label`.

Bringing a branch up to `main` — by rebase or by merging `main` into it, not by merging the PR —
is done by the **branch author**: they know what the branch must carry, and they will notice what was lost.
The coordinator only pushes a finished branch; they do not rewrite history.

The exception is when the author has already finished the work. Then the coordinator
does the rebase, but before pushing they **must** verify that the rebase finished. The
conditions: a clean tree (a conflict leaves paths uncommitted, so the same condition
catches it), no `rebase-merge` or `rebase-apply` in the tree's Git directory, no `MERGE_HEAD`
from an interrupted merge of `main`. The check is valid only in the same worktree where
the rebase was done: `--git-path` points to this tree's Git directory, so from another
one it stays silent. And the hashes in the PR body are different after a rebase — they are
updated by whoever did the rebase (`docs/agents/local-environment.md`, "Worktrees").

```sh
[ -z "$(git status --porcelain)" ] \
  && test ! -e "$(git rev-parse --git-path rebase-merge)" \
  && test ! -e "$(git rev-parse --git-path rebase-apply)" \
  && test ! -e "$(git rev-parse --git-path MERGE_HEAD)" \
  && echo 'rebase finished — safe to push' \
  || { echo 'STOP: rebase did not finish — do not push'; false; }
```

The price has already been paid: PR #1239 (the web layout probe) merged with the wrong
content. The coordinator rebased the branch, the rebase failed on a conflict in
`docs/agents/local-environment.md`, and a truncated tree went to the remote; CI on
it passed (there was nothing to check), and the script itself, without the review
fixes, landed in `main`. Recovery cost a separate PR #1245.

The manual check above and the machine gate are not a contradiction but two moments: the author
checks themselves **before pushing**, and their `--git-path` is valid only in the tree
where the rebase was done; the gate looks **before merging** and scans **all** worktree
copies (the "Review verdict and merge" section) — which is exactly why it catches a rebase the
author left in their own.

## Branches after a merged PR

`scripts/merge-pr.sh` does not delete the branch — and must not: the local copy may carry
safety patches that are not pushed yet. Order in remote branches is kept by the nightly
`branch-hygiene` workflow (`docs/runbooks/branch-hygiene.md`). It deletes a
branch only when its content is proven to be in `main` already: the tip is an ancestor of `main`, the PR
was merged into `main` with the same tip, or there are zero unique patches against `main`. Branches
with an open PR, `main`, `legal/*`, `backup/*` and release lines without a `v*` tag are
never touched. An implementer does not need to clean up their branch by hand, but
must not rely on a branch with an open PR disappearing.

## Reviewer brief

A review is not "read the diff and say ok". It has a scope, and that scope is
named, because without it the reviewer either repeats CI or checks nothing:

- **The copy and the base.** `scripts/review-clone.sh <branch>` — a read-only copy and a
  **named** base revision (`merge-base` with `origin/main`). The diff is built from it,
  not from the coordinator's `HEAD`: otherwise someone else's files land in the diff silently (#1323).
- **Local evidence.** The target class or partition the change touches, and
  **two or three mutations** through `scripts/mutate.sh` with a run-id from `.mutations/runs.jsonl`
  (the Python half — `--python`, the JVM half — `--class`). The full partition set is not
  run locally — that is CI's job ("Testing and verification" in `AGENTS.md`); the reviewer
  checks what CI does **not** prove: witnesses, limits and claims.
- **Claims.** Every number or promise in the PR body is reproduced by a command or a
  test. A breadth-first walk without a witness where depth-first fails is not a claim but a
  wish; that is exactly how the review of PR #1491 found four discrepancies between the
  PR body and the code.
- **CI green before the verdict.** The `review:pass` label is set only on a HEAD where all
  non-skipped checks are green, and only through `scripts/review-pass.sh` (not
  `gh pr edit --add-label`). Red infrastructure (adb/emulator) is not
  repainted as "almost green": rerun first.
- **Findings — as a comment in the PR**, with file and line; the verdict attests that they are
  absent on this revision, and does not replace them.

This does not replace judgement: which mutations to write and what counts as a limit is decided by the
reviewer. The brief narrows what can be checked mechanically, and leaves the reviewer what
is not mechanically checked.
