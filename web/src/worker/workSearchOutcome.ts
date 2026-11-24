import type { SourceId } from "./types";
import type { SourceCards } from "./workFeed";

/**
 * #1508 — the pieces of a multi-source search, including the ones that FAILED.
 *
 * The route keeps using `Promise.allSettled`, because one dead Source must not
 * discard the others. What used to be missing is the second half of that
 * sentence: a rejected Source or a parser that threw is a FACT the web client
 * must see (ADR-0034 parity), not an invisible absence. This helper is the one
 * place that decides which requested id counts as failed, so the honesty rule
 * is testable without a network or a Worker fetch.
 */
export interface WorkSearchSourceResult {
  sourceId: SourceId;
  cards: SourceCards["cards"];
  /** The Source answered nothing or its parser threw. */
  failed: boolean;
}

export interface WorkSearchOutcome {
  /** Sources that answered with parseable cards (possibly zero cards). */
  sources: SourceCards[];
  /** Requested Sources that did not contribute a trustworthy answer. */
  failedSources: SourceId[];
}

export function collectWorkSearchOutcome(
  requested: readonly SourceId[],
  settled: readonly PromiseSettledResult<WorkSearchSourceResult>[]
): WorkSearchOutcome {
  const sources: SourceCards[] = [];
  const failedSources: SourceId[] = [];
  settled.forEach((result, index) => {
    const sourceId = requested[index];
    if (sourceId === undefined) return;
    if (result.status === "rejected") {
      failedSources.push(sourceId);
      return;
    }
    if (result.value.failed) {
      failedSources.push(result.value.sourceId);
      return;
    }
    sources.push({
      sourceId: result.value.sourceId,
      cards: result.value.cards,
    });
  });
  return { sources, failedSources };
}
