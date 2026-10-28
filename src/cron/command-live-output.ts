/**
 * Bounded in-memory tail of a running cron command's combined stdout/stderr.
 *
 * `runCronCommandJob` is the only writer: it opens an entry before spawning and closes it in its
 * `finally`, so every end path (exit, timeout, abort, spawn error) discards the buffer. The
 * recorded run result stays the source of truth afterwards. Appending only copies bytes into a
 * bounded ring; sanitizing and redaction run when a client reads, so unwatched jobs pay nothing.
 */
import { stripAnsiSequences } from "../../packages/terminal-core/src/ansi.js";
import { redactToolPayloadText } from "../logging/redact.js";
import { resolveGlobalSingleton } from "../shared/global-singleton.js";

const COMMAND_LIVE_OUTPUT_MAX_BYTES = 256 * 1024;
/** A partial line longer than this is flushed so a prompt without a newline still shows up. */
const MAX_PENDING_LINE_BYTES = 4096;

type LiveOutputEntry = {
  runAtMs: number;
  maxBytes: number;
  chunks: Buffer[];
  head: number;
  retained: number;
  /** Absolute byte offset after everything ever appended; cursors survive trimming. */
  end: number;
};

export type CronCommandLiveOutputRead = {
  active: boolean;
  runAtMs?: number;
  cursor: number;
  text: string;
  /** The cursor was absent, stale, or from another run; `text` replaces what the caller holds. */
  reset?: true;
  /** Older output was dropped before the first returned byte. */
  truncated?: true;
};

const LIVE_OUTPUT_KEY = Symbol.for("openclaw.cron.commandLiveOutput");

function entries(): Map<string, LiveOutputEntry> {
  return resolveGlobalSingleton(LIVE_OUTPUT_KEY, () => new Map<string, LiveOutputEntry>());
}

/** Opens the live tail for one run. `append` never throws; `end` is idempotent. */
export function beginCronCommandLiveOutput(params: {
  jobId: string;
  runAtMs: number;
  outputMaxBytes?: number;
}) {
  const maxBytes = Math.max(
    1,
    Math.min(params.outputMaxBytes ?? COMMAND_LIVE_OUTPUT_MAX_BYTES, COMMAND_LIVE_OUTPUT_MAX_BYTES),
  );
  const entry: LiveOutputEntry = {
    runAtMs: params.runAtMs,
    maxBytes,
    chunks: [],
    head: 0,
    retained: 0,
    end: 0,
  };
  entries().set(params.jobId, entry);
  return {
    append(chunk: Buffer) {
      if (chunk.length === 0) {
        return;
      }
      entry.end += chunk.length;
      const kept = chunk.length > maxBytes ? chunk.subarray(chunk.length - maxBytes) : chunk;
      // The runner's own capture may keep a reference to the chunk; copy what we retain.
      entry.chunks.push(Buffer.from(kept));
      entry.retained += kept.length;
      while (entry.retained > maxBytes) {
        const first = entry.chunks[entry.head];
        if (!first) {
          break;
        }
        const excess = entry.retained - maxBytes;
        if (first.length <= excess) {
          entry.head += 1;
          entry.retained -= first.length;
        } else {
          entry.chunks[entry.head] = first.subarray(excess);
          entry.retained -= excess;
        }
      }
      if (entry.head > 1024 && entry.head * 2 > entry.chunks.length) {
        entry.chunks = entry.chunks.slice(entry.head);
        entry.head = 0;
      }
    },
    end() {
      const registry = entries();
      if (registry.get(params.jobId) === entry) {
        registry.delete(params.jobId);
      }
    },
  };
}

function sliceFrom(entry: LiveOutputEntry, from: number): Buffer {
  const wanted = entry.end - from;
  const parts: Buffer[] = [];
  let have = 0;
  for (let i = entry.chunks.length - 1; i >= entry.head && have < wanted; i -= 1) {
    const chunk = entry.chunks[i];
    if (chunk) {
      parts.push(chunk);
      have += chunk.length;
    }
  }
  const joined = Buffer.concat(parts.toReversed());
  return joined.subarray(joined.length - wanted);
}

/** Index after the last line terminator; a trailing `\r` may still be half of `\r\n`. */
function endOfLastLine(data: Buffer): number {
  for (let i = data.length - 1; i >= 0; i -= 1) {
    if (data[i] === 0x0a || (data[i] === 0x0d && i < data.length - 1)) {
      return i + 1;
    }
  }
  return 0;
}

/** Drop a trailing incomplete UTF-8 sequence so a forced flush never splits a character. */
function endOfCompleteUtf8(data: Buffer): number {
  for (let back = 1; back <= Math.min(3, data.length); back += 1) {
    const byte = data[data.length - back] ?? 0;
    if ((byte & 0xc0) === 0x80) {
      continue;
    }
    const needed = byte >= 0xf0 ? 4 : byte >= 0xe0 ? 3 : byte >= 0xc0 ? 2 : 1;
    return needed > back ? data.length - back : data.length;
  }
  return data.length;
}

/**
 * Strips terminal escapes and controls, then redacts with the tool-payload policy used for other
 * cron diagnostics. Accepted edge: each read redacts its own slice, so a multi-line secret (a PEM
 * block) split across two reads is not recognized as one block.
 */
function presentLiveOutput(raw: string): string {
  const text = stripAnsiSequences(raw)
    .replace(/\r\n?/g, "\n")
    .replace(/\p{Cc}/gu, (control) => (control === "\n" || control === "\t" ? control : ""));
  return redactToolPayloadText(text);
}

/** Reads the active run's tail from `cursor`; inactive jobs report `active: false`. */
export function readCronCommandLiveOutput(
  jobId: string,
  request: { cursor?: number; runAtMs?: number } = {},
): CronCommandLiveOutputRead {
  const entry = entries().get(jobId);
  if (!entry) {
    return { active: false, cursor: 0, text: "" };
  }
  const start = entry.end - entry.retained;
  const { cursor } = request;
  const continuing =
    cursor !== undefined &&
    request.runAtMs === entry.runAtMs &&
    cursor >= start &&
    cursor <= entry.end;
  const from = continuing ? cursor : start;
  const reset = !continuing;
  const base = {
    active: true as const,
    runAtMs: entry.runAtMs,
    ...(reset ? { reset: true as const } : {}),
    ...(reset && start > 0 ? { truncated: true as const } : {}),
  };
  if (from === entry.end) {
    return { ...base, cursor: from, text: "" };
  }
  let data = sliceFrom(entry, from);
  let skipped = 0;
  if (from === start && start > 0) {
    // Head trimming can leave the first retained byte inside a multi-byte character.
    while (skipped < 3 && skipped < data.length && ((data[skipped] ?? 0) & 0xc0) === 0x80) {
      skipped += 1;
    }
    data = data.subarray(skipped);
  }
  let cut = endOfLastLine(data);
  if (cut === 0 && data.length > MAX_PENDING_LINE_BYTES) {
    cut = endOfCompleteUtf8(data);
  }
  return {
    ...base,
    cursor: from + skipped + cut,
    text: presentLiveOutput(data.subarray(0, cut).toString("utf8")),
  };
}
