# Приватні персоналізовані рекомендації

## Problem Statement

«Слухайка» вже показує локальний ряд «Рекомендовано для вас», але поточний
механізм не навчається на досвіді слухача. Він порівнює каталог із улюбленими,
завершеними та нещодавно прослуханими Works, однак не пам'ятає, які
рекомендації людина відкрила, відхилила або попросила більше не показувати.
Користувач не може пояснити системі, чи йому не підходить одна книга, весь
схожий напрям або конкретний автор, а випадкове рішення неможливо виправити
після зникнення короткого повідомлення.

Заморожена багатомовна E5-модель уже вбудована в застосунок і працює офлайн,
але її embedding-вектори є лише семантичним представленням тексту. Модель не
була навчена командою «Слухайки», не змінює власні ваги на телефоні й сама по
собі не стає кращою з часом. До того ж каталожний embedding фактично отримує
переважно назву й автора, хоча домен має багатші Work-level метадані.

Водночас спільне поліпшення на основі досвіду багатьох слухачів створює
privacy-ризик. Постійний «анонімний номер» є псевдонімом, а не повною
анонімізацією: послідовність книжок, оцінок і прогресу може розкривати
чутливі інтереси навіть без імені. Застосунок уже має Firebase UID для
Listener Reviews і відновлення профілю, але цей UID не можна перетворювати на
ключ централізованої історії прослуховування. Потрібне рішення, яке дає сильну
локальну персоналізацію без мережі та залишає спільне навчання окремим,
добровільним і мінімізованим шаром.

## Solution

Застосунок збереже вбудовану int8 ONNX `multilingual-e5-small` як заморожений
офлайн-енкодер. Він формуватиме стабільний Work-level текст із назви, автора,
жанрів, Series та очищеного effective description, після чого будуватиме
локальний позитивний і негативний профілі смаків. Навчатиметься не важка
нейромережа, а малий, детермінований ранжувальник поверх 384-вимірних
embedding-векторів.

Позитивними сигналами стануть оцінки 4–5, favorite, completion, relisten і
чесні пороги progress. Негативними — оцінки 1–2 та явні дії користувача.
Відсутність кліку, пауза або незавершена довга книга не трактуватимуться як
негатив. Ряд повертатиме десять Works: вісім персональних і два exploration,
із обмеженнями на повтори одного автора та Series і з причиною «Схоже на …».

У overflow-меню картки з'явиться «Не рекомендувати…», яке відкриває компактне
підменю: «Цю книгу», «Менше схожих» або «Цього автора». Вибір застосовується
одразу, картка зникає, а Snackbar дозволяє скасувати точну зміну. У
`Налаштування → Рекомендації` слухач зможе відновити приховані Works та
авторів, вимкнути локальний ряд або скинути лише recommendation-specific
навчання, не видаляючи Library Entry, Listening State, reviews чи playback
history.

Майбутнє спільне поліпшення буде вимкнене за замовчуванням. Після трьох
реальних взаємодій із рекомендаціями та запуску хоча б однієї рекомендованої
книги застосунок контекстно запропонує окрему згоду. Після opt-in телефон не
надсилатиме книги або події. Раз на ISO-тиждень він зможе сформувати один
обмежений update п'яти глобальних ranking weights. Backend перевірить Auth,
App Check і чинну згоду, замінить UID тижневим HMAC-псевдонімом і прийме не
більше одного внеску на слухача за epoch. Глобальне оновлення створюється
лише з cohort щонайменше 10 різних weekly IDs; сирі внески видаляються після
закриття тижня незалежно від результату, а незворотні агреговані ваги можуть
зберігатися. Локальні рекомендації ніколи не залежать від цього backend.

## User Stories

1. As a listener, I want recommendations to improve from my own listening experience, so that the row becomes more relevant over time.
2. As a listener, I want personalization to work fully offline, so that recommendations do not disappear without a network connection.
3. As a listener, I want the semantic model available immediately after installation, so that I do not need to download a large model before first use.
4. As a listener, I want recommendations to use the Work title, author, genres, Series and available description, so that similarity reflects more than matching words in a title.
5. As a listener, I want recommendations anchored to Work identity, so that multiple Editions of the same authored book do not distort my taste profile.
6. As a listener, I want progress across several Editions of one Work to count only once, so that alternate narrations do not multiply the same preference.
7. As a listener, I want a favorite Work to be a strong positive signal, so that the app finds more Works resembling what I deliberately saved.
8. As a listener, I want completion to be a strong positive signal, so that finishing a Work improves later recommendations.
9. As a listener, I want relistening to be the strongest positive behavior signal, so that enduring favorites shape my profile clearly.
10. As a listener, I want ratings of 4 and 5 to improve similar recommendations, so that my explicit opinion matters.
11. As a listener, I want ratings of 1 and 2 to reduce similar recommendations, so that disliked Works stop shaping the row positively.
12. As a listener, I want a rating of 3 to remain neutral, so that an average experience is not misread as praise or rejection.
13. As a listener, I want honest progress milestones to influence recommendations, so that a long audiobook can teach the system before I finish it.
14. As a listener of long audiobooks, I want one month spent on a single Work to remain useful locally, so that slow listening is not mistaken for inactivity.
15. As a listener, I want only the strongest progress milestone for a Work counted, so that 30% and 70% do not double-count the same journey.
16. As a listener, I want recent weak progress signals to fade gradually, so that an old experiment does not define my taste forever.
17. As a listener, I want favorite, rating, completion and relisten signals to persist, so that deliberate durable choices do not expire silently.
18. As a listener, I want opening a card alone to remain neutral, so that curiosity is not mistaken for preference.
19. As a listener, I want absence of a click to remain neutral, so that unseen or poorly positioned cards are not treated as disliked.
20. As a listener, I want pausing or not finishing a long book to remain neutral, so that real-life interruptions do not poison recommendations.
21. As a listener, I want already-owned Works excluded from discovery recommendations, so that the row helps me find something new.
22. As a listener, I want no more than two recommendations from one author, so that one strong preference does not monopolize the row.
23. As a listener, I want no more than one recommendation from one Series, so that the row remains varied.
24. As a listener, I want two of ten slots reserved for exploration, so that personalization does not become a filter bubble.
25. As a listener, I want exploration candidates to retain positive semantic relevance, so that novelty is not random noise.
26. As a listener, I want empty exploration slots filled by good personalized candidates, so that the row never fabricates weak content to satisfy a quota.
27. As a new listener without positive signals, I want the personal row hidden, so that the app does not pretend to know my taste.
28. As a new listener, I want editorial and collection rails to remain available, so that discovery works before personalization has evidence.
29. As a listener, I want every recommendation to explain its nearest positive reason, so that I can understand why it appeared.
30. As a listener, I want negative signals excluded from reason chips, so that the app never presents a disliked Work as inspiration.
31. As a listener, I want an overflow action named «Не рекомендувати…», so that feedback is available without cluttering every card.
32. As a listener, I want a compact reason submenu, so that I can refine feedback without a disruptive dialog.
33. As a listener, I want to hide only one Work, so that a single bad fit does not block its author or genre.
34. As a listener, I want «Менше схожих» to weaken a semantic direction, so that related Works become less prominent without a blanket ban.
35. As a listener, I want to hide a specific author, so that Works by that author stop appearing.
36. As a listener, I want the author option absent when identity is unreliable, so that the app does not block the wrong person.
37. As a listener, I want feedback applied immediately, so that the row responds without a redundant confirmation step.
38. As a listener, I want the affected card to leave the row visibly, so that I know the action took effect.
39. As a listener, I want a Snackbar action to undo the exact previous state, so that accidental feedback is reversible.
40. As a listener, I want hidden Works and authors listed in settings, so that I can reverse an old decision after the Snackbar is gone.
41. As a listener, I want to restore exclusions individually, so that I do not need to reset my whole profile.
42. As a listener, I want a local personalization switch, so that I control whether the personal row is shown.
43. As a listener, I want switching personalization off to preserve my local profile, so that turning it back on restores continuity.
44. As a listener, I want a clearly scoped reset action, so that I know it resets recommendation feedback and learned weights rather than my library.
45. As a listener, I want resetting recommendations to preserve Library Entry, Listening State, Listener Reviews and playback history, so that a recommendation control never destroys core data.
46. As a privacy-conscious listener, I want a clear explanation that local learning stays on my device, so that I can use it without agreeing to network collection.
47. As a privacy-conscious listener, I want shared improvement disabled by default, so that silence is never interpreted as consent.
48. As a listener, I want the consent request delayed until recommendations have demonstrated value, so that I understand what I am being asked to improve.
49. As a listener, I want card impressions excluded from consent-trigger interactions, so that merely seeing the row does not manufacture engagement.
50. As a listener, I want a non-blocking consent card rather than a startup popup, so that the request does not interrupt listening.
51. As a listener, I want a detailed disclosure before opting in, so that I know the purpose, data, processor, retention and withdrawal behavior.
52. As a listener, I want refusal to leave all local functionality intact, so that consent is genuinely optional.
53. As a listener, I want «Не зараз» to suppress the request for 90 days, so that the app does not nag me.
54. As a listener, I want at most one repeated contextual reminder, so that declining remains respected.
55. As a listener, I want later opt-in available from settings, so that I can reconsider without waiting for a prompt.
56. As a listener, I want any new purpose or broader payload to require new consent, so that an old choice cannot authorize different processing.
57. As a listener, I want withdrawal as easy as opt-in, so that I retain control over participation.
58. As a listener, I want withdrawal to delete any unsent weekly contribution, so that data still on my device is not uploaded later.
59. As a listener, I want withdrawal to request deletion of an unaggregated current-week contribution, so that pending server data is removed when possible.
60. As a listener, I want withdrawal to leave local personalization enabled, so that privacy control does not reduce offline functionality.
61. As a listener, I want an honest warning that an individual contribution cannot be removed from an irreversible aggregate, so that consent is informed.
62. As a privacy-conscious listener, I want the app to call server-side identifiers pseudonymous rather than anonymous, so that privacy language is accurate.
63. As a privacy-conscious listener, I want Firebase UID separated from contribution data, so that reviews and listening-learning data are not joined into one profile.
64. As a privacy-conscious listener, I want a new weekly pseudonym every epoch, so that routine storage cannot build a cross-week history.
65. As a privacy-conscious listener, I want no Work IDs, titles, authors, genres, ratings or progress in the shared payload, so that the server cannot reconstruct my bookshelf.
66. As a privacy-conscious listener, I want no nickname, Android ID, device model, locale, recovery material or exact timestamp in the shared payload, so that unnecessary identifiers are not collected.
67. As a listener, I want at most one shared contribution per week, so that the backend cannot observe my daily behavior rhythm.
68. As a listener of long books, I want a durable 30% or 70% outcome to be enough for a weekly contribution, so that completion is not required.
69. As a listener, I want weeks without a durable outcome to send nothing, so that the app never fabricates a zero event.
70. As a privacy-conscious listener, I want shared learning to require at least 10 distinct weekly contributors, so that one or two people cannot determine a global update.
71. As a privacy-conscious listener, I want raw weekly contributions deleted whether or not the cohort threshold is reached, so that a behavioral archive does not accumulate.
72. As a listener, I want aggregated learning retained, so that deleting raw packages does not erase knowledge already learned by the shared ranker.
73. As a listener, I want shared uploading disabled until at least 20 active opt-ins exist, so that a cohort of 10 is operationally realistic.
74. As a listener, I want global learning limited to small ranking weights, so that the bundled E5 model is never remotely rewritten from private behavior.
75. As a listener, I want exploration and diversity guardrails protected from shared updates, so that global optimization cannot remove product safeguards.
76. As a listener, I want any global weight update bounded, so that one cohort cannot radically change recommendations.
77. As a listener, I want new global weights evaluated before publication, so that shared learning cannot silently reduce recommendation quality.
78. As a listener, I want only signed monotonic global configurations accepted, so that stale or forged weights cannot replace a valid model.
79. As a listener, I want the last known good or bundled weights used after validation failure, so that recommendations remain available.
80. As a listener, I want Firebase and network failures isolated from local ranking, so that optional infrastructure never blocks browsing.
81. As a listener, I want a missing or failed ONNX model to fall back safely, so that the recommendation surface does not crash.
82. As a listener, I want a corrupt embedding cache recomputed in the background, so that derived data can self-heal.
83. As a maintainer, I want one deep local recommendation module to own profile building, ranking and feedback commands, so that screens do not duplicate rules.
84. As a maintainer, I want UI code to consume module flows and commands rather than write Room or Firebase directly, so that data ownership stays clear.
85. As a maintainer, I want explicit recommendation preferences persisted separately from Library Entry and Listening State, so that recommendation controls do not mutate domain facts.
86. As a maintainer, I want Works to remain the identity anchor and narrator to remain an Edition property, so that personalization respects the domain model.
87. As a maintainer, I want deterministic local scoring and saved evaluation fixtures, so that quality regressions are reproducible.
88. As a maintainer, I want payload fields enforced by an allowlist, so that an accidental client change cannot begin collecting extra data.
89. As a maintainer, I want Auth and App Check required for contribution admission, so that spam and trivial poisoning are bounded.
90. As a maintainer, I want one accepted contribution per weekly pseudonym, so that retry remains idempotent.
91. As a maintainer, I want both client and server gradient clipping, so that corrupted or adversarial updates remain bounded.
92. As a maintainer, I want formal privacy parameters approved before shared rollout, so that ad-hoc noise is never marketed as differential privacy.
93. As a maintainer, I want application logs free of UID, weekly ID and gradient bodies, so that observability does not recreate the dataset the payload avoided.
94. As a maintainer, I want consent receipts stored separately and minimally, so that proof of consent cannot be used as a behavioral profile.
95. As a maintainer, I want revoked consent receipts removed after the defined audit period, so that retention is purpose-limited.
96. As a maintainer, I want the shared phase physically disabled before legal, privacy and security gates pass, so that incomplete controls cannot leak into production.
97. As a maintainer, I want staged delivery beginning with local personalization, so that user value ships without waiting for the shared backend.
98. As a maintainer, I want ABI/App Bundle and R8 optimization treated separately, so that the decision to bundle the model does not block recommendation quality work.

## Implementation Decisions

- The bundled `multilingual-e5-small` int8 ONNX model remains the production
  semantic encoder and is never fine-tuned by this feature. The existing
  keyword embedder remains the failure fallback.
- Recommendation identity is Work-level. Signals from several Editions of
  the same Work collapse to the strongest signal rather than being summed.
  Narrator is excluded from the Work embedding because it belongs to Edition.
- One `BookRecommendationText` boundary produces stable text from title,
  author, genres, Series and effective description. Effective description
  follows existing Metadata Override / Metadata Assertions precedence; the
  recommendation module does not create a new canonical metadata truth.
- Text cleanup removes HTML, source suffixes and duplicate fragments. Missing
  fields are omitted, and the description is bounded to the E5 input limit.
  A text change invalidates the versioned embedding cache.
- One deep `RecommendationPersonalization` module owns profile construction,
  scoring, diversity, recommendation flows and feedback commands. Screens
  consume its flow and commands; they do not write persistence directly.
- Positive weights are fixed initially: relisten `+1.3`, rating 5 `+1.2`,
  favorite `+1.0`, completion `+0.9`, rating 4 `+0.8`, 70% progress `+0.5`,
  and 30% progress `+0.25`.
- Negative weights are fixed initially: rating 1 `-1.2`, rating 2 `-0.8`, and
  «Менше схожих» `-1.0` toward the negative semantic centroid. Rating 3 is
  neutral.
- Only the strongest progress tier per Work counts. The total per-Work weight
  is clamped to `[-1.5, +1.5]`.
- Weak progress signals retain full strength through day 30 and decay linearly
  to zero by day 180. Favorite, rating, completion, relisten and explicit
  exclusions do not decay.
- Absence of a click, opening a detail page, a short playback start, pausing
  and non-completion are not ranking signals in the first version.
- A positive centroid and negative centroid are computed locally from
  normalized Work embeddings. Hard exclusions and Works already represented
  by a Library Entry are filtered before scoring.
- Initial scoring weights are semantic `0.60`, author affinity `0.15`, genre
  affinity `0.10`, Series affinity `0.05`, and freshness `0.10`. Unknown date
  produces zero freshness rather than a fabricated value.
- Semantic scoring subtracts `0.70` of similarity to the negative centroid
  from similarity to the positive centroid. Affinity inputs are normalized,
  and the final ranking remains deterministic for identical inputs.
- The result has ten slots: eight personalized and two exploration. There are
  no more than two Works by one author and one Work from one Series.
  Exploration requires positive semantic relevance; unused exploration slots
  return to personalized candidates.
- With no positive profile, the personal row is absent. Existing editorial,
  collection and catalog surfaces remain the cold-start experience.
- The reason chip names the closest positive Work. Negative Works are never
  displayed as reasons.
- A new local `recommendation_preferences` store persists only explicit
  feedback. It records preference kind, target key, source Work and creation
  time. It does not duplicate embeddings.
- Preference kinds are hide Work, reduce similar, and hide author. Author
  identity uses the same normalized author key as Work identity. The author
  choice is hidden when a reliable nonblank key cannot be formed.
- «Не рекомендувати…» lives in the card overflow. The primary menu is replaced
  at the same anchor by a compact reason menu rather than opening a modal.
- A choice applies immediately and removes the card visibly. A long Snackbar
  can restore the exact previous preference snapshot.
- `Налаштування → Рекомендації` owns the local enable switch, excluded Works,
  excluded authors, individual restoration, scoped reset, shared opt-in and
  privacy disclosure.
- Turning local personalization off hides the row without deleting state.
  Reset removes explicit recommendation preferences and locally adapted
  ranking weights, but Library Entry, Listening State, Listener Reviews and
  playback history remain and can rebuild the deterministic base profile.
- The consent prompt becomes eligible only after three meaningful
  recommendation interactions and one playback start through the
  recommendation path. An interaction is a detail open, explicit feedback or
  playback start; impression alone never counts.
- The consent prompt is a non-blocking contextual card. «Не зараз» creates a
  90-day cooldown. One repeated reminder is allowed; later opt-in remains in
  settings.
- Shared participation defaults off and refusal does not change local
  functionality. A new purpose, broader payload or weaker safeguard requires
  a new consent version.
- A minimal consent receipt stores only consent version, granted time and
  optional revoked time under the listener UID, separately from contributions.
  A revoked/deleted-profile receipt is retained no longer than 12 months,
  subject to legal review before production activation.
- One weekly local contribution contains schema version, base model version,
  a five-element L2-clipped gradient, consent version, ISO-week epoch and
  weekly pseudonym. It contains no Work or user-history fields.
- A weekly contribution exists only if at least one durable outcome occurred:
  30%/70%, completion, relisten, favorite, rating or explicit feedback.
  Otherwise the client sends nothing.
- The shared trainable values are the five scoring weights. They remain on a
  simplex totaling `1.0`, each bounded to `[0.02, 0.80]`. Exploration remains
  a non-trainable `0.20` guardrail, and author/Series caps are non-trainable.
- Candidate updates use learning rate `0.02`; projection prevents any one
  weekly version from moving a coefficient by more than `0.02`.
- Contribution admission requires Firebase Auth, App Check and an active
  consent receipt. Weekly pseudonym is HMAC of UID and ISO week. UID is not
  stored with the contribution or written to application logs.
- Weekly HMAC is explicitly described as pseudonymization. It reduces routine
  cross-week linkage but does not protect against a malicious controller that
  simultaneously controls Auth identities and the HMAC secret.
- One weekly pseudonym can contribute once. The epoch closes at the end of the
  ISO week, and a late payload is not carried into the next epoch.
- At least 10 distinct weekly pseudonyms are required for a global candidate
  update. Fewer than 10 produces no update. Raw contributions and weekly IDs
  are deleted at epoch close in both cases.
- Aggregated weights, model versions and privacy accounting may persist because
  no individual rows remain. Shared uploading remains off until at least 20
  active opt-ins make a cohort operationally plausible.
- Both client and server clip contribution norm to `1.0`. Formal noise
  mechanism, `epsilon`, `delta` and composition policy require a dedicated
  privacy/security approval; shared production activation is blocked until
  those values are fixed and reviewed.
- A candidate global version must preserve metric baselines, diversity caps
  and value bounds. Only signed monotonic versions are accepted. Validation
  failure uses last-known-good weights or bundled defaults.
- Withdrawal stops new uploads, deletes the unsent queue, marks the consent
  receipt revoked, and best-effort deletes the current unaggregated weekly
  contribution. It does not disable local personalization and cannot extract
  one record from an irreversible aggregate.
- Shared delivery is phased: local core, feedback UX, dormant transparency and
  consent UI, staging backend, privacy/security gate, then limited opt-in
  rollout. Production upload cannot be enabled early.
- APK size optimization is a separate build effort. The model stays bundled;
  ABI splits/App Bundle and R8 are the preferred size levers.

## Testing Decisions

- Good tests assert externally visible recommendation behavior rather than
  private implementation details: given catalog, Work metadata, Library Entry,
  Listening State and preferences, they assert the ordered recommendations,
  reasons, exclusions and restoration outcomes.
- The highest local seam is the `RecommendationPersonalization` module. Most
  profile, scoring, decay, diversity, cold-start and feedback tests exercise
  this one boundary rather than testing helpers independently.
- The embedding-text boundary receives focused deterministic tests because
  cache version and semantic input are an independently observable contract.
- Local tests cover Work-level collapse across Editions, strongest progress
  tier, signal clamp, decay boundaries, positive/negative centroids, hard
  exclusions, existing Library Entry exclusion, score order, 8+2 composition,
  author/Series caps and honest reason chips.
- Persistence integration tests verify that explicit preferences survive
  restart, undo and settings restoration, and that reset leaves Library Entry,
  Listening State, Listener Reviews and playback events untouched.
- Compose tests exercise overflow-to-reason-menu behavior, missing-author
  option, visible card removal, Snackbar undo, exclusions settings, scoped
  reset disclosure, local toggle and consent controls.
- Consent behavior tests assert no eligibility before both trigger conditions,
  impressions not counting, 90-day cooldown, one repeat maximum, no upload
  before opt-in, queue deletion on withdrawal, and independence from local
  personalization.
- The highest backend seam is weekly epoch closure. Tests submit admitted
  bounded contributions and assert one-per-pseudonym idempotency, cohort 9
  rejection, cohort 10 aggregation, raw deletion in both outcomes, bounded
  candidate weights and publication gating.
- Backend security tests reject missing Auth, missing App Check, revoked
  consent, duplicate weekly pseudonym, late epoch, oversized gradient,
  nonfinite values, wrong vector width and payload fields outside the allowlist.
- Logging tests assert application logs never contain UID, weekly ID or
  gradient bodies. Processor/edge retention is verified as an operational
  launch checklist because it is not fully testable inside the app repository.
- Configuration tests reject unsigned, stale, non-monotonic, out-of-bounds or
  quality-regressive global versions and verify last-known-good fallback.
- Recommendation quality remains gated by saved fixtures: Recall@10, NDCG@10,
  author diversity and pairwise preference accuracy. Central dismissal-rate
  telemetry is not collected.
- Existing `RecommendationEngineTest`, `RecommendationEvalTest` and
  `EmbeddingCacheTest` are prior art for deterministic JVM recommendation
  tests. Existing deep-module Room tests are prior art for migration and
  persistence behavior. Existing Compose/Roborazzi tests are prior art for
  menu, Snackbar and settings surfaces. Existing Firestore rule matrices are
  prior art for Auth/App Check and schema enforcement.
- A missing ONNX model/session, corrupt embedding cache, offline backend,
  revoked consent and invalid global configuration each receive regression
  coverage proving that local recommendations remain available or degrade to
  the existing keyword fallback.

## Out of Scope

- Fine-tuning or federated fine-tuning `multilingual-e5-small`.
- Central collection of listening history, Work sequences, exact progress,
  timestamps, titles, authors, genres, ratings or free text.
- Advertising profiles, cross-app tracking, data sale or recommendation data
  reuse for an unrelated purpose.
- A permanent telemetry ID or reuse of Firebase UID as a contribution key.
- Claiming that weekly HMAC makes data fully anonymous.
- Protection against a malicious controller with simultaneous access to Auth,
  consent records and HMAC secret; blind tokens or cryptographic secure
  aggregation require a separate design.
- Automatic negative inference from no click, pause, abandonment or slow
  progress.
- Narration-specific personalization or narrator embedding.
- Changing Library Entry, Listening State, Listener Review or playback-event
  ownership to serve recommendation implementation.
- A global popularity feed or central dismissal-rate analytics.
- Shared production rollout before the legal, processor-retention,
  privacy-budget and security gates are complete.
- Model download-on-demand; the semantic model remains bundled for offline
  first-use behavior.
- APK/App Bundle, ABI split or R8 implementation; those belong to a separate
  build-size spec.

## Further Notes

- This is a follow-up to completed Spec-19 issues #115–#120. It preserves the
  existing ONNX embedding row and adds personalization, feedback and the
  separately gated shared-learning track rather than reopening the old work.
- The server-side scheme is pseudonymization under GDPR, not irreversible
  anonymization. Disclosure and store language must use the honest term.
- European Commission guidance states that pseudonymized data remains personal
  data when it can be related back to a person:
  https://commission.europa.eu/law/law-topic/data-protection/information-business-and-organisations/application-gdpr_en
- EDPB guidance requires consent to be freely given, informed, specific,
  unambiguous and easy to withdraw:
  https://www.edpb.europa.eu/sme/be-compliant/process-personal-data-lawfully_en
- Data minimization and purpose limitation apply to both payload and logging:
  https://commission.europa.eu/law/law-topic/data-protection/rules-business-and-organisations/principles-gdpr/overview-principles/what-data-can-we-process-and-under-which-conditions_en
- Shared learning must remain dormant until the formal noise mechanism and
  privacy budget are fixed. Adding informal random noise is not sufficient to
  claim differential privacy.
- The model-bundling choice intentionally prioritizes offline first-use quality.
  Distribution splits and release shrinking should be planned independently to
  address APK size without weakening the recommendation design.
