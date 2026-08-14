# Issue tracker: GitHub

Issues and PRDs for this repo live as GitHub issues in
`Samuel-Ku/slukhayka`. Use the `gh` CLI for all operations
(run inside the clone; `gh` infers the repo from `git remote -v`).

## Conventions

- **Create an issue**: `gh issue create --title "..." --body "..."`. Use a heredoc for multi-line bodies.
- **Read an issue**: `gh issue view <number> --comments`, filtering comments by `jq` and also fetching labels.
- **List issues**: `gh issue list --state open --json number,title,body,labels,comments --jq '[.[] | {number, title, body, labels: [.labels[].name], comments: [.comments[].body]}]'`
- **Comment on an issue**: `gh issue comment <number> --body "..."`
- **Apply / remove labels**: `gh issue edit <number> --add-label "..."` / `--remove-label "..."`
- **Close**: `gh issue close <number> --comment "..."`

## Pull requests as a triage surface

**PRs as a request surface: no.** This repo tracks feature work as issues;
external PRs are not run through the triage state machine.

## When a skill says "publish to the issue tracker"

Create a GitHub issue.

## When a skill says "fetch the relevant ticket"

Run `gh issue view <number> --comments`.

## Wayfinding operations

Used by `/wayfinder`. The **map** is a single issue with **child** issues as tickets.

- **Map**: create one issue labelled `wayfinder:map`; its body holds Destination, Notes, Decisions so far, Not yet specified, and Out of scope.
- **Child ticket**: create an issue labelled `wayfinder:<type>`, then attach it with `gh api --method POST repos/Samuel-Ku/slukhayka/issues/<map>/sub_issues -F sub_issue_id=<ticket-db-id>`. Obtain the numeric database id with `gh api repos/Samuel-Ku/slukhayka/issues/<ticket> --jq .id`.
- **Blocking**: use GitHub's native issue dependencies: `gh api --method POST repos/Samuel-Ku/slukhayka/issues/<ticket>/dependencies/blocked_by -F issue_id=<blocker-db-id>`. The id is the blocker's numeric database id, not its issue number or GraphQL node id.
- **Frontier query**: list the map's open sub-issues, then discard tickets with an assignee or a non-zero `issue_dependencies_summary.blocked_by` count. Preserve the map's sub-issue order.
- **Claim**: `gh issue edit <ticket> --add-assignee @me` before doing any ticket work.
- **Resolve**: comment with the answer, close the ticket, then append one linked gist to the map's Decisions-so-far section.
- **Fallback**: if sub-issues or dependencies are unavailable, use `Part of #<map>` and `Blocked by: #<issue>` lines in ticket bodies, but prefer native relationships whenever GitHub exposes them.
