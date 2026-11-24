import { describe, expect, it } from "vitest";
import { collectWorkSearchOutcome } from "./workSearchOutcome";
import type { SourceId } from "./types";
import type { WorkSearchSourceResult } from "./workSearchOutcome";

const ok = (
  sourceId: SourceId
): PromiseFulfilledResult<WorkSearchSourceResult> => ({
  status: "fulfilled",
  value: { sourceId, cards: [], failed: false },
});
const failed = (
  sourceId: SourceId
): PromiseFulfilledResult<WorkSearchSourceResult> => ({
  status: "fulfilled",
  value: { sourceId, cards: [], failed: true },
});
const rejected = (reason = "boom"): PromiseRejectedResult => ({
  status: "rejected",
  reason,
});

describe("#1508 — a multi-source search names the Sources that failed", () => {
  it("a complete answer has no failed Sources", () => {
    const outcome = collectWorkSearchOutcome(
      ["sluhay", "sound-books"],
      [ok("sluhay"), ok("sound-books")]
    );
    expect(outcome.failedSources).toEqual([]);
    expect(outcome.sources.map((source) => source.sourceId)).toEqual([
      "sluhay",
      "sound-books",
    ]);
  });

  it("a rejected Source is reported and the others are kept", () => {
    const outcome = collectWorkSearchOutcome(
      ["sluhay", "sound-books"],
      [ok("sluhay"), rejected()]
    );
    expect(outcome.failedSources).toEqual(["sound-books"]);
    expect(outcome.sources.map((source) => source.sourceId)).toEqual([
      "sluhay",
    ]);
  });

  it("a null transport answer or a throwing parser is a failure too", () => {
    const outcome = collectWorkSearchOutcome(
      ["sluhay", "sound-books"],
      [failed("sluhay"), ok("sound-books")]
    );
    expect(outcome.failedSources).toEqual(["sluhay"]);
    expect(outcome.sources.map((source) => source.sourceId)).toEqual([
      "sound-books",
    ]);
  });

  it("the requested ids, not the settled values, own the failure label", () => {
    const outcome = collectWorkSearchOutcome(
      ["sluhay"],
      [rejected(), rejected()]
    );
    expect(outcome.failedSources).toEqual(["sluhay"]);
  });
});
