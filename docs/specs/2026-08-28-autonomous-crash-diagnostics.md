# Спека: приватні звіти про збої та автономна щотижнева діагностика

> **Статус:** погоджено, готово до реалізації.
> **Tracker:** GitHub Issue #410 (`ready-for-agent`).
> **Дата:** 2026-08-28. **Архітектурне рішення:** ADR-0025.

## Problem Statement

Слухачі повідомляють, що Слухайка часто припиняє роботу, особливо під час
фонового відтворення. Соло-розробник не отримує достатньо відтворюваних
технічних доказів: звичайний fatal crash або ANR не охоплює випадки, коли
Android знищує процес чи Playback Service без exception. Ручне збирання,
очищення й аналіз кожного повідомлення не масштабується, а прихований збір
Listening State, назв Work або Edition чи Source URL суперечив би очікуванню
приватності.

Потрібен безкоштовний щодо нових сервісів, добровільний і мінімальний канал
діагностики, який щотижня сам знаходить нові групи збоїв, доводить причину
через короткий red-capable feedback loop, створює безпечний GitHub Issue і
передає лише доведені випадки агенту реалізації. Людина має залишатися
останнім бар'єром перед merge.

## Solution

Слухайка інтегрує Firebase Crashlytics із вимкненим за замовчуванням збором.
Після першого збою наступний запуск один раз просить дозволу на анонімні
технічні звіти від імені соло-розробника. Вибір переживає перезапуск і завжди
змінюється у privacy settings.

Release-збірка зі згодою надсилає fatal crashes, ANR та вузько описані
неочікувані завершення активного відтворення. Останні на Android 11+ виявляє
`ApplicationExitInfo`; нормальні, керовані й неопераційні завершення
відсікаються. Жоден звіт не містить Listener identity, Listening State,
назв Work/Edition, Source URL, назв медіа чи довільних логів.

Щоп'ятниці о 06:00 UTC GitHub Actions читає згруповані проблеми через
офіційний Crashlytics API, детерміновано очищує їх і передає дозволений JSON
OpenCode з GLM-5.3-Flash. Агент працює за `diagnosing-bugs`: спершу створює й
запускає red-capable feedback loop, а без нього не висуває реалізаційної
гіпотези та не відкриває PR. Окремі jobs ізолюють Crashlytics access, model
secret і GitHub write. Автоматизація створює або оновлює issue, запускає
окрему реалізацію лише для `ready-for-agent`, може відкрити один PR на групу
і ніколи не робить auto-merge.

## User Stories

1. As a listener, I want crash reporting to be off by default, so that no diagnostic data leaves my phone without my choice.
2. As a listener, I want to be asked only after a real failure, so that the request has understandable context.
3. As a listener, I want the request to say that one developer maintains Слухайка, so that I understand whom my report helps.
4. As a listener, I want the request to name what is not collected, so that I can make an informed decision.
5. As a listener, I want approval to send the triggering report and future reports, so that the failure I just experienced is not lost.
6. As a listener, I want refusal to be remembered, so that the app does not pressure me after every failure.
7. As a listener, I want to change my decision in privacy settings, so that an accidental tap is reversible.
8. As a listener, I want enabling reports after an earlier refusal to affect only future failures, so that previously withheld data stays withheld.
9. As a listener, I want disabling reports to stop future uploads, so that consent remains meaningful.
10. As a listener, I want book titles, source addresses and listening history excluded, so that diagnostics do not reveal what I listen to.
11. As a listener, I want only release failures reported, so that developer experiments do not pollute production diagnostics.
12. As a listener, I want background playback deaths detected even without a Java crash, so that silent interruptions can be fixed.
13. As a listener on Android 11+, I want actionable OS process deaths distinguished from normal exits, so that reports describe real defects rather than ordinary lifecycle events.
14. As a listener, I want one process death reported at most once, so that reopening the app does not duplicate evidence.
15. As a solo developer, I want fatal crashes, ANR and unexpected playback exits grouped, so that repeated symptoms produce one maintainable investigation.
16. As a solo developer, I want every new group represented in the tracker, so that lower-priority failures are visible even when the weekly diagnosis limit is reached.
17. As a solo developer, I want background playback failures prioritized, so that the reported core reliability problem is addressed first.
18. As a solo developer, I want high-impact and regressed failures prioritized next, so that limited model time follows user impact.
19. As a solo developer, I want at most three full diagnoses per run, so that weekly API cost and agent scope remain bounded.
20. As a solo developer, I want the same Crashlytics group to update the same issue, so that evidence and discussion do not fragment.
21. As a solo developer, I want only sanitized app-owned evidence in public issues, so that the public repository never becomes a crash-data archive.
22. As a solo developer, I want unknown telemetry fields to fail closed, so that an upstream schema change cannot silently expose private data.
23. As a solo developer, I want the AI to reproduce the problem before proposing a fix, so that tickets contain evidence rather than plausible guesses.
24. As a solo developer, I want 3–5 ranked falsifiable hypotheses during diagnosis, so that investigation remains disciplined and reviewable.
25. As a solo developer, I want a failing regression test before a fix, so that the resulting PR proves both the defect and its correction.
26. As a solo developer, I want blocked diagnoses labeled `needs-triage`, so that uncertainty is visible instead of being presented as certainty.
27. As a solo developer, I want proven diagnoses labeled `ready-for-agent`, so that autonomous implementation consumes only actionable contracts.
28. As a solo developer, I want diagnosis and implementation to run as separate jobs, so that a model analyzing sensitive evidence cannot also write to the repository.
29. As a solo developer, I want at most one PR per crash group, so that concurrent runs cannot compete with duplicate fixes.
30. As a solo developer, I want every generated PR to link and close its issue, so that the repair has traceable provenance.
31. As a solo developer, I want generated PRs to wait for CI and human merge, so that autonomous work cannot ship itself.
32. As a solo developer, I want a fixed group that reappears in a newer version reopened, so that regressions return to the queue with their prior repair attached.
33. As a solo developer, I want the workflow runnable manually, so that I can investigate urgent failures before Friday.
34. As a repository contributor, I want issue evidence and reproduction commands to be self-contained, so that I can verify the diagnosis without Crashlytics access.
35. As a repository contributor, I want temporary diagnostic instrumentation removed after the cause is known, so that production code does not accumulate investigation debris.
36. As a project owner, I want Crashlytics access read-only and Firebase groups left untouched, so that repository automation cannot mutate the source telemetry system.

## Implementation Decisions

- Firebase Crashlytics is the only crash-reporting vendor. BigQuery and a
  second processor are rejected; the existing paid model API is allowed.
- Automatic Crashlytics collection is disabled by default. A persisted
  tri-state consent policy represents undecided, allowed and denied.
- The first fatal crash, ANR or locally recognized actionable playback exit
  makes the next app launch eligible to show one consent dialog. Denial is
  not re-prompted.
- The consent copy is:

  > Я роблю «Слухайку» сам. Якщо хочете допомогти мені зробити застосунок
  > стабільнішим, дозвольте надсилати анонімні технічні звіти про збої. Вони
  > не містять назв книжок, адрес джерел чи історії прослуховування. Рішення
  > завжди можна змінити в налаштуваннях.

  Actions are `Надсилати звіти` and `Не надсилати`.
- Privacy settings expose `Надсилати звіти про збої` through the existing
  settings presentation and persisted-settings pattern. Approval in the
  post-failure dialog sends the held triggering report; later approval after
  a denial sends only future reports and deletes old held reports.
- Reporting is compiled and enabled only for release behavior. Debug builds
  and tests keep collection disabled regardless of saved state.
- The app-reporting boundary owns consent, report eligibility, bounded custom
  state and pending-report lifecycle. Other modules submit enums/booleans to
  that boundary and never construct arbitrary Crashlytics payloads.
- Allowed Crashlytics custom keys are exactly `app_visibility`,
  `playback_state`, `playback_service`, `audio_origin` and `cast_active`.
  Values are bounded enums or booleans.
- Firebase UID, Listener identity, Work/Edition names, Source URL, Listening
  State, media names, free-form logs and arbitrary custom keys are forbidden.
- Fatal crashes and ANR are reported through Crashlytics. Background state is
  captured before failure through bounded keys, not by serializing player
  objects or persisted listening data.
- Android 11+ inspects historical `ApplicationExitInfo` on the next launch.
  An actionable exit while playback was active becomes the controlled
  non-fatal type `UnexpectedPlaybackExit`.
- The exit record includes only reason enum, status, process importance,
  RSS/PSS, app/Android versions and a bounded process-state summary. It never
  includes `getDescription()`, raw traces, identifiers or arbitrary text.
- Normal/self exit, user requested/stopped exit, package update, permission or
  package change and other known non-actionable reasons are excluded.
  Actionable OS/resource/signal exits are included only when prior bounded
  state says playback was active. Android 10 and older emit no substitute.
- A locally persisted exit timestamp plus stable hash makes exit reporting
  idempotent. It contains no user or media identity and follows consent.
- GitHub Actions runs Fridays at 06:00 UTC and through `workflow_dispatch`.
- The collection job authenticates to Google with GitHub OIDC and Workload
  Identity Federation using a least-privilege, read-only service account.
  No long-lived service-account JSON is stored in GitHub.
- A narrow adapter owns the official Crashlytics `v1alpha` REST contract so
  API changes do not leak into diagnosis or publishing.
- A deterministic allowlist sanitizer is the sole boundary between raw
  Crashlytics data and later jobs. Unknown schema or possible personal data
  stops AI analysis and yields a `needs-triage` issue.
- Sanitized model input is limited to exception type, sanitized message,
  app-owned stack frames, aggregate occurrences/affected installs,
  app/Android/device versions, bounded keys and relevant public repository
  code. URLs, paths, tokens, email, Firebase/session/device IDs, media names,
  arbitrary logs and third-party thread dumps are removed.
- OpenCode runs GLM-5.3-Flash non-interactively. A local provider proxy owns
  the Z.ai secret; OpenCode receives no reusable raw API key. Provider wiring
  remains replaceable without changing the diagnostic contract.
- Trust is split across jobs: collection can read Crashlytics but cannot write
  GitHub; diagnosis sees sanitized JSON and checkout but cannot read Firebase
  or write GitHub; publishing/implementation can write issues, branches and
  PRs but receive neither Firebase nor Z.ai secrets.
- Every new group creates or updates an issue. A hidden marker containing the
  Crashlytics group ID provides deterministic deduplication without exposing
  the raw event.
- Public issue content is limited to sanitized app stack, aggregates,
  affected versions, reproduction command/evidence, ranked hypotheses,
  diagnosis state and next action. Raw reports and internal QA artifacts do
  not enter the repository, in accordance with ADR-0016.
- Each weekly run fully diagnoses at most three groups. All other new groups
  still create/update issues and remain queued.
- Priority order is background playback, then highest affected-install
  fatal/ANR groups, then new or regressed groups, then the remaining queue.
- The diagnosis state machine follows `diagnosing-bugs`: establish and run a
  tight red-capable loop; reproduce/minimize; form 3–5 ranked falsifiable
  hypotheses; instrument narrowly; add a failing regression test before the
  fix; clean up and record a postmortem.
- Failure to build and run a red-capable command ends autonomous diagnosis as
  `needs-triage`. It cannot produce a fix hypothesis, `ready-for-agent` or PR.
- A proven cause plus a red regression test receives only
  `ready-for-agent`; all unresolved groups receive only `needs-triage`.
- A separate implementation job rereads the issue contract, reruns the red
  command, implements the smallest fix and opens at most one PR for the group.
- Generated branches use `codex/crash-<id>`. PRs include `Fixes #<issue>`.
  CI and human review are mandatory; auto-merge is forbidden.
- If the same group occurs in an app version newer than its fix, its issue is
  reopened with `needs-triage` and a reference to the prior PR.
- Crashlytics integration stays read-only and never closes or mutates the
  Firebase-side issue.

## Testing Decisions

- Good tests assert externally observable policy: whether data is retained,
  uploaded, rejected, redacted, deduplicated, published or allowed to reach
  implementation. They do not pin Firebase SDK calls, internal class layout,
  exact prompt composition structure or OpenCode's prose.
- The primary app seam is one crash-reporting contract with fake report sink,
  clock/SDK-exit source and persisted consent store. Tests drive failures and
  user choices through this boundary and inspect only emitted safe reports.
- The primary automation seam is one fixture group passed through
  collect-normalization, sanitization, diagnosis-result validation and issue
  rendering. Network clients and the model response are recorded fixtures;
  GitHub mutation is replaced by a fake publisher until the workflow smoke
  test.
- Consent policy tests cover undecided/allowed/denied, one-time prompting,
  persistence, reversal, held triggering report, deletion after denial,
  future-only behavior after later approval and release/debug separation.
- Report-schema tests prove the exact key allowlist, bounded values and
  rejection of identity, Listening State, Work/Edition, Source and arbitrary
  fields. Property/fuzz cases verify URL, path, token, email and identifier
  redaction plus fail-closed unknown fields.
- Exit-policy tests cover Android 11+ actionable reasons, excluded lifecycle
  reasons, playback-active gating, stable deduplication and the Android 10-
  no-op boundary. Android framework records are mapped behind the seam so
  most cases remain deterministic JVM tests.
- UI behavior tests cover the dialog copy/actions, no repeated denial prompt,
  settings switch state, reversal and accessibility labels. Existing settings
  component and accessibility-test conventions are prior art.
- Playback integration tests prove that foreground/background, player state,
  Playback Service state, audio origin and cast state reach only their bounded
  diagnostic representation. Existing AudioPlayerManager, Playback Service,
  PlaybackSettings and playback-event tests are prior art.
- Sanitizer golden tests cover accepted fatal, ANR and
  `UnexpectedPlaybackExit` inputs, every forbidden field, malformed upstream
  schema and stable safe JSON output.
- Adapter contract tests use recorded, fully synthetic Crashlytics responses;
  no real listener event becomes a repository fixture.
- Workflow tests cover schedule/manual triggers, least-privilege permissions,
  three-diagnosis cap, priority ordering, issue dedupe/update, queue retention,
  regression reopening and maximum one PR per group.
- A blocked fixture without a runnable red loop must produce
  `needs-triage` and no implementation dispatch. A proven synthetic fixture
  with a real failing command must produce `ready-for-agent`, rerun red in the
  implementation job and create exactly one unmerged PR proposal.
- CI validates that no job can access two sensitive zones at once and that
  raw payload artifacts are neither uploaded nor committed.
- Existing SharedPreferences store tests, pure policy tests, Robolectric UI
  tests, accessibility tests, fake playback engine tests and workflow checks
  are the preferred prior art. New instrumentation tests are reserved for the
  smallest Android framework wiring that cannot be observed in JVM tests.

## Out of Scope

- BigQuery, a second crash-reporting vendor or any new paid data service.
- Product analytics, behavioral telemetry or Listening State synchronization.
- Titles, source addresses, playback position, arbitrary breadcrumbs or full
  device/thread dumps in reports.
- Reliable OS process-death classification on Android 10 and older.
- More than three full autonomous diagnoses per weekly run.
- Autonomous merge, release or deployment.
- Automatic mutation or closure of issues inside Firebase Crashlytics.
- Treating a model-generated explanation without a red-capable reproduction
  and failing regression test as an implementation-ready diagnosis.

## Further Notes

- This spec turns the completed `grill-with-docs` discussion into the tracker
  contract; it does not add new product decisions.
- ADR-0025 owns the durable consent, privacy, Crashlytics and process-exit
  rationale. This spec owns delivery behavior and acceptance.
- The official Crashlytics `v1alpha` API is intentionally isolated because
  it may change. Adapter failure must degrade to visible `needs-triage`, never
  broaden the data passed downstream.
- The public repository remains a sanitized coordination surface, not a home
  for raw crash evidence or internal QA binaries.
