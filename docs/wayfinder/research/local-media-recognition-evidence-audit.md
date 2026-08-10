# Local media recognition & SAF evidence audit — wayfinder ticket «Local media recognition evidence audit» (#48)

Status: resolved 2026-08-10. Evidence: `LocalFolderScanner.kt`,
`AudiobookRepository.kt` (import + refresh paths), `LibraryScreen.kt`,
`LocalFolderScannerTest.kt`, `FakeDocumentFile.kt`, git history (main branch,
HEAD 3c3c731).

## What the local-import pipeline is today

1. **Pick**: `ActivityResultContracts.OpenDocumentTree()` in `LibraryScreen.kt:76` → tree `Uri`.
2. **Scan**: `LocalFolderScanner.scan(context, treeUri)` walks the SAF tree, collects mp3/m4a/ogg/m4b/aac files with their relative parent folder (`LocalFolderScanner.kt:34-83`). Pure-walk, lazily opening streams — tested against `FakeDocumentFile` (`LocalFolderScannerTest.kt`).
3. **Group & copy**: `importAudioEntries` (`AudiobookRepository.kt:413-469`) — root files become single-chapter books; each sub-folder becomes one book, chapters naturally sorted (`compareNatural`). Every audio file is **copied** into app-internal storage (`copyLocalAudioStream`, `AudiobookRepository.kt:485-494`, unique `-seq` suffix) and the book is inserted with a fresh id `local-<currentTimeMillis>-<seq>` (`insertLocalBook`, AudiobookRepository.kt:471-480).
4. **Metadata**: local books get fixed placeholders — author «Локальний аудіофайл»/folder author, narrator «Локальний аудіофайл», no cover, `totalDurationSeconds = 0` (`insertLocalBook`). Nothing parses tags, filenames, M4B chapter structures, or folder metadata.

## Findings

### 1. HIGH — no persistable SAF permission anywhere
The tree picker result is passed straight to the import (`LibraryScreen.kt:78-81`), and `takePersistableUriPermission` is **never called** (verified by grep across app/src/main). Per Android docs, a tree grant survives **until the device reboots or the process is force-stopped** — after that the stored `treeUri` is dead. Today this is masked because import *copies* files into internal storage, so playback never touches the grant. But any future "read files in place / re-scan the folder" feature (sync, M4B re-index, library restore) will silently fail after reboot unless the grant is made persistable at pick time.

### 2. HIGH — every re-import duplicates books
The local-book id is time-based: `local-<ts>-<seq>` (`AudiobookRepository.kt:471`), and the copied file gets a fresh `-seq` suffix (`:487`). There is **no dedupe on import** — no check by file path, content hash, or folder identity. Picking the same folder twice creates two books, two copies of every file (disk doubles), and two independent listening states. The folder-grouping key (`parentFolder`) is also discarded after import — nothing links a book back to its source tree.

### 3. MEDIUM — zero recognition: no tags, no M4B chapters, no folder metadata
- **No `MediaMetadataRetriever`**: it was deliberately disabled after production/emulator failures — «Media Quality Service not found» / «getEmbeddedPicture failed» errors and slowness on network streams (`AudiobookRepository.kt:798-800`, comment block). Cover extraction was the only use; it was removed, not replaced.
- **No M4B chapter parsing** despite `.m4b` being in the accepted extensions (`LocalFolderScanner.kt:37`) — an M4B is imported as a single-chapter book.
- **No filename/title heuristics** beyond `sanitizeLocalBaseName`; series/genre/narrator are never inferred; durations stay 0 until… nothing back-fills them for local books (`refreshBookCoverAndDetails` only works for `sourceUrl`-backed 4read books).
- Result: local books are the weakest-metadata rows in the library (fixed author, narrator, genre, no cover, 0 duration), while the app's own UI features (sort by duration, series, recently added) cannot rank them meaningfully.

### 4. LOW — tests cover only the walk
`LocalFolderScannerTest` covers recursion/extension filtering/parent attribution via `FakeDocumentFile` (a good pattern). There are **no tests** for `importAudioEntries` grouping, natural sorting of chapter files, the copy+id behaviour, or re-import dedupe — which is why finding 2 shipped unnoticed.

## Options ranked for a recognition ticket

| Option | Effort | Risk | Pays off |
|---|---|---|---|
| A. **Re-import dedupe by content hash** (SHA-256 of the copied file, stored on the chapter; skip/merge on re-import) | S (hash at copy time is free) | none | kills the duplicate-book and disk-doubling bug |
| B. **Persistable SAF grant** (`takePersistableUriPermission` at pick; keep the `treeUri` per book) | S | none | unlocks re-scan/refresh/restore features |
| C. **M4B chapter extraction** (on-import, from the copied file; mp4chaps-style atom parse or ExoPlayer `Mp4Extractor` chapter metadata) | M | M — M4B is an MP4 container; chapter atoms vary (CHPL vs ttxt/`chap`) | the single biggest UX gap for the most common audiobook container |
| D. **Tag reading via `MediaMetadataRetriever` for durations only** (no embedded pictures; skip on failure, keep 0) | S-M | low — retriever is already proven flaky on some devices; must be best-effort with timeout | durations for local books, which everything else in the UI depends on |
| E. **Filename/folder heuristics** (title-clean, «Автор — Назва» splits, folder-as-series) | M | low | cheaper than tags, pure JVM-testable like the scanner |

## Verdict

The scanner/grouping core is solid and testable; the recognition layer is
**absent** and the import path actively creates duplicates. A local-media
ticket should ship A + B as prerequisites (both small), then C or D+E as the
recognition feature itself — C is the highest user value but the only one with
real technical risk.
