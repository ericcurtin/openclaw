import { findCodeOwnership } from "../shared/text/code-regions.js";
import { createConditionalTextProjector, type TextFilter } from "../shared/text/text-projection.js";
import { stripInlineDirectiveTagsForDisplay } from "./directive-tags-batch.js";

/** Raw offsets for literal directives whose Markdown code ownership is settled. */
export type StreamDirectiveCodePrefix = {
  end: number;
  checkedRawLength: number;
};

function hasDirectiveCodePrefixOpportunity(source: string, delta: string): boolean {
  if (!delta) {
    return false;
  }
  const deltaStart = source.length - delta.length;
  const start = Math.max(0, deltaStart - 4);
  let separator = -1;
  // A following line can close block code without a blank separator; ownership proves stability.
  for (const match of source.slice(start).matchAll(/(?:\r\n|\n|\r)[^\S\r\n]*\S/g)) {
    if (start + match.index + match[0].length > deltaStart) {
      separator = start + match.index;
    }
  }
  if (separator === -1) {
    return false;
  }
  const lastMarker = source.lastIndexOf("[[");
  return lastMarker !== -1 && separator > lastMarker;
}

export function findDirectiveCodePrefix(
  source: string,
  delta: string,
): StreamDirectiveCodePrefix | undefined {
  if (!hasDirectiveCodePrefixOpportunity(source, delta)) {
    return undefined;
  }
  const { regions, retainStart, completedParagraphs } = findCodeOwnership(source);
  let regionIndex = 0;
  let paragraphIndex = 0;
  let end = 0;
  for (
    let marker = source.indexOf("[[");
    marker !== -1;
    marker = source.indexOf("[[", marker + 1)
  ) {
    let region = regions[regionIndex];
    while (region && region.end <= marker) {
      region = regions[++regionIndex];
    }
    let paragraph = completedParagraphs[paragraphIndex];
    while (paragraph && paragraph.end <= marker) {
      paragraph = completedParagraphs[++paragraphIndex];
    }
    if (!region || region.start > marker || region.end < marker + 2) {
      return undefined;
    }
    if (region.block) {
      if (region.end > retainStart) {
        return undefined;
      }
      end = region.end;
      continue;
    }
    if (
      !paragraph ||
      paragraph.hasReferenceCandidate ||
      paragraph.start > region.start ||
      paragraph.end < region.end
    ) {
      return undefined;
    }
    end = paragraph.end;
  }
  return end ? { end, checkedRawLength: source.length } : undefined;
}

const stripDirectives = (text: string) => stripInlineDirectiveTagsForDisplay(text).text;

/** A `[[` whose closing `]]` is not yet settled can still become a directive. */
function hasUnsettledMarker(text: string, end: number): boolean {
  const start = text.lastIndexOf("[[", end - 1);
  if (start === -1) {
    return false;
  }
  // Every directive form ends at the first `]` after its head, so a `]]` ends the question.
  const close = text.indexOf("]", start + 2);
  return close === -1 || close + 1 >= end;
}

/**
 * Strip directives from the unsettled tail only. A stray `[[` that never closes keeps its
 * tail parsed per delta; that rare shape costs what it did before checkpoints.
 */
export const inlineDirectiveDisplayTextFilter: TextFilter = {
  transform: stripDirectives,
  create: () => {
    let hasMarker = false;
    let previousChar = "";
    return createConditionalTextProjector(
      stripDirectives,
      (input) => {
        const delta = input.delta ?? input.text;
        hasMarker ||= (previousChar + delta).includes("[[");
        if (delta) {
          previousChar = delta.slice(-1);
        }
        return hasMarker;
      },
      (text, boundary, context) => {
        const safe = boundary > context.contentStart && !hasUnsettledMarker(text, boundary);
        return {
          text: stripDirectives(text),
          retainStart: safe ? boundary : context.contentStart,
          retainedOutputStart: safe
            ? stripDirectives(text.slice(0, boundary)).length
            : context.contentStart,
        };
      },
    );
  },
};
