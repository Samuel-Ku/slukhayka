/**
 * #1236 — the detail half of the Медіатека's wide list-detail contract.
 *
 * A Library Entry is a Work (CONTEXT.md) and the web's synced row carries no
 * Source URL — that is the honest platform delta the Library screen already
 * records (`Library.tsx`: «a row body is not openable yet — a synced Library
 * Entry carries no Source URL»). The wide pane therefore shows what the row
 * really owns: the Work's title/author, the linked Edition's narrator, the
 * real status and the real progress fraction. It never fabricates a Source
 * page or a play button for audio this client cannot locate.
 */
import { useTranslate } from "../i18n/locale";
import { EmptyStateRow, MetadataChip } from "./components";

export interface LibrarySelection {
  kind: "library";
  mergeKey: string;
  title: string;
  author: string;
  narrator: string;
  status: "new" | "listening" | "completed";
  /** 0..1 only when the joined chapter durations made it real (ADR-0014). */
  progress?: number;
}

export function LibraryBookDetail({ book }: { book: LibrarySelection }) {
  const t = useTranslate();
  const status =
    book.status === "completed"
      ? t("libFilterCompleted")
      : book.status === "listening"
        ? t("libFilterListening")
        : t("libFilterNew");
  return (
    <article className="library-book-detail">
      <h1>{book.title}</h1>
      <p className="byline">{book.author}</p>
      <p className="byline">
        {t("personRolePerformer")}:{" "}
        {book.narrator === "" ? t("unknownNarrator") : book.narrator}
      </p>
      <p>
        <MetadataChip kind="plain">{status}</MetadataChip>
        {book.progress !== undefined && (
          <span className="library-detail-progress">
            {t("libraryDetailProgress", {
              percent: String(Math.round(book.progress * 100)),
            })}
          </span>
        )}
      </p>
      <EmptyStateRow message={t("libraryDetailNoSource")} />
    </article>
  );
}
