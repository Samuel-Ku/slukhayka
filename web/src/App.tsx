import { useEffect, useMemo, useRef, useState } from 'react'
import type { ListenerProfile } from './identity/listenerIdentity'
import { Catalog } from './ui/Catalog'
import { Listen } from './ui/Listen'
import { Library } from './ui/Library'
import { Settings } from './ui/Settings'
import { BookPage } from './ui/BookPage'
import { AudioEngine } from './player/audioEngine'
import { MiniPlayer } from './ui/MiniPlayer'
import { PlayerSheet } from './ui/PlayerSheet'
import type { BookDetail, SourceId, UnifiedEdition, UnifiedWork } from './worker/types'
import { LocalListeningStateStore, BrowserStorage } from './player/localState'
import { HybridListeningStateStorage } from './local/hybridListeningState'
import { IdbListeningStateStore } from './local/listeningState'
import { DomainStore } from './local/domain'
import { EditionLinkStore } from './local/editionLinks'
import { ListenPrefsStore } from './local/listenPrefs'
import { RecommendationPrefsStore } from './local/recommendationPrefs'
import { RecommendationParticipation } from './recommend/participation'
import { PlayerBookmarksStore } from './player/bookmarks'
import { BrowserProgressSyncLedger } from './sync/ledger'
import { ProgressSyncSettings } from './sync/settings'
import { FirestoreProgressSyncStore } from './sync/store'
import { ProgressSyncController } from './sync/controller'
import { FirestoreNarrationRatingsStore, FirestoreReviewsStore } from './reviews/store'
import { WorkRelationshipController } from './sync/workRelationshipController'
import { FirestoreWorkRelationshipStore } from './sync/workRelationshipStore'
import { PersonBookmarkSyncController, LocalPendingPersonBookmarkDeletes } from './sync/personBookmarkController'
import { FirestorePersonBookmarkStore } from './sync/personBookmarkStore'
import { getFirestoreForEnv } from './firebase/firestore'
import { editionIdFor, mergeKeyFor } from './sync/edition'
import { setUiLocale, useTranslate, useUiLocale } from './i18n/locale'
import { translate } from './i18n/strings'
import type { StringKey } from './i18n/strings'
import { loadSelectedTab, saveSelectedTab, SELECTED_TAB_ORDER, type SelectedTab } from './ui/selectedTab'

/**
 * #583 W1.1 (R-W7) — the bottom bar is Android's `SelectedTab` verbatim:
 * Слухати / Огляд / Медіатека / Налаштування, in the enum's order. The
 * chosen tab persists (ui/selectedTab); «Профіль» is not a tab — the
 * recovery code and the sync switch live in the Налаштування direction
 * (ui/Settings). The interim landing tab is Огляд.
 */

/** One label per tab, in the same i18n keys the Android resources mirror. */
const TAB_LABELS: Record<SelectedTab, StringKey> = {
  listen: 'tabListen',
  explore: 'tabCatalog',
  library: 'tabLibrary',
  settings: 'tabSettings',
}

export function App({ profile: initialProfile }: { profile: ListenerProfile | null }) {
  const t = useTranslate()
  const locale = useUiLocale()
  const [profile, setProfile] = useState(initialProfile)
  useEffect(() => setProfile(initialProfile), [initialProfile])

  const [tab, setTab] = useState<SelectedTab>(() => loadSelectedTab())
  const selectTab = (next: SelectedTab): void => {
    setTab(next)
    saveSelectedTab(next)
  }

  const [book, setBook] = useState<{ url: string; source: SourceId } | null>(null)
  const bookUrl = book?.url ?? null
  const audioRef = useRef<HTMLAudioElement | null>(null)
  const engineRef = useRef<AudioEngine | null>(null)
  const [playerOpen, setPlayerOpen] = useState(false)
  const [, forceUpdate] = useState(0)

  // Shared local store for engine + sync mirror: the synchronous StorageLike
  // the engine already consumes, now backed by IndexedDB (R-W8) with a boot
  // gate + buffered pre-boot writes (StrictMode double-effect safe).
  const idbStore = useMemo(() => new IdbListeningStateStore(), [])
  const hybrid = useMemo(
    () =>
      new HybridListeningStateStorage(
        idbStore,
        new BrowserStorage(window.localStorage),
      ),
    [idbStore],
  )
  // #584 W1.2 — the mergeKey → Edition join the Медіатека reads.
  const linkStore = useMemo(() => new EditionLinkStore(), [])
  // #585 W2.1 — the Слухати shelves' local-only order/hide prefs.
  const listenPrefsStore = useMemo(() => new ListenPrefsStore(), [])
  // #586 W2.2 — «Не цікаво»: the local Recommendation Preference store
  // (HIDE_WORK dictionary, local-only, reversible from Рекомендації).
  const recommendationPrefsStore = useMemo(() => new RecommendationPrefsStore(), [])
  // #592 W6.1 — the participation consent (Settings → Рекомендації):
  // local-only, off by default; the server layer joins later (ADR-0030).
  const participation = useMemo(() => new RecommendationParticipation(window.localStorage), [])
  // #590 W5.1 — the player's bookmarks (Android's BookmarkEntity, local
  // only — never synced), shared by the engine's auto-bookmark and the
  // player sheet's list.
  const bookmarksStore = useMemo(() => new PlayerBookmarksStore(), [])
  const localStore = useMemo(() => new LocalListeningStateStore(hybrid), [])
  const [boot, setBoot] = useState<{ snapshots: number; evicted: boolean } | null>(null)
  useEffect(() => {
    let cancelled = false
    void hybrid.whenBooted().then((outcome) => {
      if (!cancelled) setBoot({ snapshots: outcome.snapshots.length, evicted: outcome.evicted })
    })
    return () => {
      cancelled = true
    }
  }, [hybrid])
  const ledger = useMemo(() => new BrowserProgressSyncLedger(window.localStorage), [])
  const settings = useMemo(() => new ProgressSyncSettings(window.localStorage), [])
  const firestore = useMemo(() => getFirestoreForEnv(import.meta.env), [])
  const syncStore = useMemo(() => (firestore ? new FirestoreProgressSyncStore(firestore) : null), [firestore])
  // W4.1 — the reviews block exists only when Firebase is configured (Android's
  // `listenerReviews != null` gate): no config → no «Відгуки» block at all.
  const reviewsStore = useMemo(() => (firestore ? new FirestoreReviewsStore(firestore) : null), [firestore])
  const narrationRatingsStore = useMemo(
    () => (firestore ? new FirestoreNarrationRatingsStore(firestore) : null),
    [firestore],
  )

  // Identity reads current profile (updated after restore) — no writes before binding.
  const profileRef = useRef(profile)
  useEffect(() => {
    profileRef.current = profile
  }, [profile])

  // #582 W0.4 — person-bookmark sync rides the same delivery as the Work
  // relationships (the R-W10 seam): deterministic ids, local-first toggle,
  // pull/merge at the same moments, refusing sync never deletes local rows.
  const pendingPersonDeletes = useMemo(() => new LocalPendingPersonBookmarkDeletes(window.localStorage), [])
  const personBookmarkStore = useMemo(
    () => (firestore ? new FirestorePersonBookmarkStore(firestore) : null),
    [firestore],
  )

  // #581 W0.3 — the Work-relationship sync (entry/tombstone mirror, LWW):
  // pull on boot for a returning bound session, merge at linking, push at
  // the honest moments via the callbacks below.
  const domainStore = useMemo(() => new DomainStore(), [])
  const relationshipStore = useMemo(
    () => (firestore ? new FirestoreWorkRelationshipStore(firestore) : null),
    [firestore],
  )
  const relationships = useMemo(
    () =>
      new WorkRelationshipController(
        () => profileRef.current?.uid ?? null,
        domainStore,
        relationshipStore,
        () => settings.isEnabled(),
      ),
    [domainStore, relationshipStore, settings],
  )
  const personBookmarks = useMemo(
    () =>
      new PersonBookmarkSyncController(
        () => profileRef.current?.uid ?? null,
        domainStore,
        personBookmarkStore,
        () => settings.isEnabled(),
        pendingPersonDeletes,
      ),
    [domainStore, personBookmarkStore, settings, pendingPersonDeletes],
  )
  useEffect(() => {
    void relationships.pullAndApply().catch(() => [])
  }, [relationships])
  useEffect(() => {
    // #582 W0.4 — the same boot pull as the relationships: a returning bound
    // session finds the phone's bookmarks; a null store is a no-op.
    void personBookmarks.sync().catch(() => undefined)
  }, [personBookmarks])
  const syncController = useMemo(() => {
    const mirror = {
      editionIdForSync: (bookId: string) => bookId,
      progressByEdition: (editionId: string) => {
        const snap = localStore.load(editionId)
        if (!snap) return null
        return {
          editionId,
          chapterIndex: snap.chapterIndex,
          positionSeconds: snap.positionSeconds,
          isCompleted: snap.isCompleted,
          preferredSpeed: snap.preferredSpeed,
          updatedAtServerMs: 0,
        }
      },
      applyRemoteProgress: (_bookId: string, remote: import('./sync/policy').RemoteListeningState) => {
        localStore.save({
          editionId: remote.editionId,
          chapterIndex: remote.chapterIndex,
          positionSeconds: remote.positionSeconds,
          isCompleted: remote.isCompleted,
          preferredSpeed: remote.preferredSpeed,
          lastPausedAtEpochMs: null,
        })
      },
    }
    return new ProgressSyncController(
      { getUid: () => profileRef.current?.uid ?? null },
      mirror,
      syncStore,
      ledger,
      () => settings.isEnabled(),
    )
  }, [localStore, ledger, settings, syncStore])

  if (!engineRef.current) {
    engineRef.current = new AudioEngine({ relayBase: '/api', store: localStore, bookmarks: bookmarksStore })
  }
  const engine = engineRef.current
  // Keep engine's sync controller in sync with current profile/settings.
  // Set synchronously so the first loadBook after restore sees the bound controller.
  engine.setSyncController(syncController)

  useEffect(() => {
    document.title = translate(locale, 'docTitle')
    const unsub = engine.subscribe(() => forceUpdate((n) => n + 1))
    return unsub
  }, [engine, locale])

  useEffect(() => {
    if (audioRef.current) engine.attachAudio(audioRef.current)
  }, [engine])

  // W4.1 — «Запит після завершення книги відкриває ту саму форму»: the
  // player remembers the last played Work so the finish prompt can open the
  // SAME review form the book page uses (the AC's single entry — no second
  // buttons anywhere).
  const lastPlayedRef = useRef<{ title: string; author: string; narrator?: string; language?: string; url: string } | null>(null)
  const handlePlay = async (detail: BookDetail, chapterIndex: number): Promise<boolean> => {
    lastPlayedRef.current = {
      title: detail.title,
      author: detail.author,
      narrator: detail.narrator,
      language: detail.language,
      url: detail.url,
    }
    const mergeKey = mergeKeyFor(detail.title, detail.author)
    const editionId = editionIdFor(mergeKey, detail.url, detail.narrator ?? '')
    // #584 W1.2 — write the edition link at the moment the app knows both
    // sides: the Медіатека's hairline and Нові/Слухаю/Завершені join on it.
    // Never guessed: durations count only when every chapter declared one.
    const chapterDurations = detail.chapters.map((chapter) => chapter.durationSeconds ?? Number.NaN)
    const durationsKnown = detail.chapters.length > 0 && chapterDurations.every((seconds) => Number.isFinite(seconds) && seconds > 0)
    void linkStore.link({
      editionId,
      mergeKey,
      narrator: detail.narrator ?? '',
      language: detail.language ?? '',
      durationSeconds: detail.totalDurationSeconds ?? (durationsKnown ? chapterDurations.reduce((sum, seconds) => sum + seconds, 0) : null),
      chapterDurations: durationsKnown ? chapterDurations : null,
    })
    const playing = await engine.loadBookAndAwaitPlaying(
      { title: detail.title, chapters: detail.chapters, editionId, workId: mergeKey },
      chapterIndex,
    )
    if (playing) setPlayerOpen(true)
    return playing
  }

  /** #584 W1.2 — «зберегти» on an Огляд card creates the Library Entry. */
  const handleSaveWork = (work: UnifiedWork, edition: UnifiedEdition): void => {
    void domainStore.addLibraryEntry({ title: work.title, author: work.author })
      .then(() => linkStore.link({
        editionId: edition.id,
        mergeKey: work.mergeKey,
        narrator: edition.narrator ?? '',
        language: edition.language ?? '',
        durationSeconds: edition.durationSeconds ?? null,
        chapterDurations: null,
      }))
      .then(() => relationships.pushAfterChange(work.mergeKey))
      .catch(() => undefined)
  }
  return (
    <>
      <audio ref={audioRef} preload="metadata" style={{ display: 'none' }} />
      <header className="app-header">
        {bookUrl !== null ? (
          <button className="back" onClick={() => setBook(null)}>
            {t('back')}
          </button>
        ) : (
          <>
            <h1 className="app-title">Слухайка</h1>
            <p className="app-subtitle">{t('appSubtitle')}</p>
          </>
        )}
        <button
          onClick={() => setUiLocale(locale === 'uk' ? 'en' : 'uk')}
          aria-label={t('langSwitchAria')}
          style={{ marginLeft: 'auto', background: 'none', border: '1px solid var(--line)', borderRadius: 999, padding: '4px 10px', color: 'var(--fg)' }}
        >
          {locale === 'uk' ? 'EN' : 'UA'}
        </button>
      </header>
      <main className="surface">
        {book !== null ? (
          <BookPage
            url={book.url}
            source={book.source}
            onOpenBook={(url, source) => setBook({ url, source })}
            onPlay={handlePlay}
            profile={profile}
            reviewsStore={reviewsStore}
            narrationRatingsStore={narrationRatingsStore}
            domainStore={domainStore}
            personBookmarks={personBookmarks}
          />
        ) : boot === null ? (
          // The hydration gate: no screen reads listener data before IDB boot
          // (migration + hydration) has settled — R-W8's loss-free bar.
          <div className="placeholder">{t('storageLoading')}</div>
        ) : tab === 'listen' ? (
          <Listen
            domainStore={domainStore}
            linkStore={linkStore}
            listening={idbStore}
            prefsStore={listenPrefsStore}
            recommendationPrefs={recommendationPrefsStore}
          />
        ) : tab === 'explore' ? (
          <Catalog
            onOpenBook={(url, source) => setBook({ url, source })}
            onPlay={handlePlay}
            onSaveWork={handleSaveWork}
            domainStore={domainStore}
            linkStore={linkStore}
            listening={idbStore}
            recommendationPrefs={recommendationPrefsStore}
            participation={participation}
            personBookmarks={personBookmarks}
            showFirstLanguageChoice
          />
        ) : tab === 'library' ? (
          <Library
            domainStore={domainStore}
            linkStore={linkStore}
            listening={idbStore}
            pushAfterChange={(mergeKey) => relationships.pushAfterChange(mergeKey)}
            personBookmarks={personBookmarks}
          />
        ) : (
          <Settings
            profile={profile}
            onProfileChange={setProfile}
            evicted={boot.evicted}
            onLinked={(uid) => {
              relationships.setUid(uid)
              void relationships.mergeAtLinking().catch(() => undefined)
              personBookmarks.setUid(uid)
              void personBookmarks.mergeAtLinking().catch(() => undefined)
            }}
            recommendationPrefs={recommendationPrefsStore}
            domainStore={domainStore}
            participation={participation}
            hybrid={hybrid}
            idbStore={idbStore}
            storage={window.localStorage}
          />
        )}
      </main>
      <MiniPlayer engine={engine} onExpand={() => setPlayerOpen(true)} />
      {playerOpen && (
        <PlayerSheet
          engine={engine}
          onClose={() => setPlayerOpen(false)}
          lastPlayed={lastPlayedRef.current}
          profile={profile}
          reviewsStore={reviewsStore}
          bookmarksStore={bookmarksStore}
        />
      )}
      {book === null && (
        <nav className="tab-bar" role="tablist">
          {SELECTED_TAB_ORDER.map((id) => (
            <button key={id} role="tab" aria-selected={tab === id} onClick={() => selectTab(id)}>
              {t(TAB_LABELS[id])}
            </button>
          ))}
        </nav>
      )}
    </>
  )
}
