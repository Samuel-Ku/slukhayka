# UI System Module

<!-- Generated: 2026-07-30 | Files scanned: 5 | Token estimate: ~500 -->

## Purpose

Design tokens (colors, typography), Material 3 theme wrapper, and reusable Compose components (bookmark dialog, book cover image). Foundation that all screens depend on.

## Key Files

```
app/src/main/java/com/example/ui/theme/Color.kt           17 lines
app/src/main/java/com/example/ui/theme/Theme.kt          36 lines
app/src/main/java/com/example/ui/theme/Type.kt           36 lines
app/src/main/java/com/example/ui/components/BookmarkDialog.kt     124 lines
app/src/main/java/com/example/ui/components/BookCoverImage.kt     105 lines
```

## Theme Structure

```kotlin
// Color.kt — Material 3 ColorScheme + custom palette
val CyberPrimary, CyberSurface, CyberCardBorder  // brand accents

// Theme.kt
@Composable fun AudiobookTheme(content: @Composable () -> Unit)
  └─ MaterialTheme(colorScheme = ..., typography = AppTypography)

// Type.kt — Typography definitions
val AppTypography = Typography(displayLarge, headlineMedium, bodyLarge, ...)
```

## Components

| Component | Purpose | Used by |
|---|---|---|
| `BookmarkDialog` | Modal dialog to add a bookmark at current position | PlayerScreen |
| `BookCoverImage` | Async cover loader (Coil) with placeholder + error states | HomeScreen, LibraryScreen, BookDetailScreen, PlayerScreen |

## Public API

```kotlin
@Composable
fun AudiobookTheme(content: @Composable () -> Unit)

@Composable
fun BookmarkDialog(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (note: String) -> Unit
)

@Composable
fun BookCoverImage(
    coverDrawableRes: Int?,
    coverImageUrl: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier
)
```

## Dependencies

- **Inbound:** Every screen in `ui/screens/`, every component in `ui/components/`, `MainActivity`
- **Outbound:** `androidx.compose.material3.*`, `androidx.compose.material.icons.*`, `coil.compose.AsyncImage`

## Common Tasks

| Task | Touch |
|---|---|
| Change brand colors | `Color.kt` |
| Change typography | `Type.kt` |
| Add new design token | `Color.kt` + `Theme.kt` |
| Add reusable component | new file in `ui/components/`, follow naming convention |

## Known Issues (Phase 2 candidates)

- Color palette uses brand-specific names (`Cyber*`) — verify consistency across all usages
- No light/dark theme separation in `Theme.kt` — currently dark-only (verify if intentional)
- Components use deprecated Material Icons (`Icons.Filled.*`) — see ui/screens codemap for full list
- No accessibility (semantics, contentDescription) audit yet on components
