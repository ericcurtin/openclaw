import { expect, it } from "vitest";
import { applyLiveOutput } from "./live-output.ts";

// Mirrors the panel's documented line bound.
const LIVE_OUTPUT_MAX_LINES = 2000;

const empty = { text: "", truncated: false };

it("appends follow reads and replaces the view on a reset", () => {
  const first = applyLiveOutput(empty, { text: "a\nb\n", reset: true });
  expect(first).toEqual({ text: "a\nb\n", truncated: false });
  expect(applyLiveOutput(first, { text: "c\n" })).toEqual({ text: "a\nb\nc\n", truncated: false });
  expect(applyLiveOutput(first, { text: "z\n", reset: true, truncated: true })).toEqual({
    text: "z\n",
    truncated: true,
  });
});

it("keeps only the last lines and remembers that older output is gone", () => {
  const lines = Array.from({ length: LIVE_OUTPUT_MAX_LINES + 5 }, (_, index) => `line ${index}`);
  const result = applyLiveOutput(empty, { text: `${lines.join("\n")}\n`, reset: true });
  const kept = result.text.split("\n");
  expect(kept).toHaveLength(LIVE_OUTPUT_MAX_LINES + 1);
  expect(kept[0]).toBe("line 5");
  expect(kept.at(-2)).toBe(`line ${LIVE_OUTPUT_MAX_LINES + 4}`);
  expect(result.truncated).toBe(true);
  const exact = applyLiveOutput(empty, {
    text: `${lines.slice(0, LIVE_OUTPUT_MAX_LINES).join("\n")}\n`,
    reset: true,
  });
  expect(exact.truncated).toBe(false);
});

it("handles leading blank lines without miscounting", () => {
  expect(applyLiveOutput(empty, { text: "\n\n\n", reset: true })).toEqual({
    text: "\n\n\n",
    truncated: false,
  });
});
