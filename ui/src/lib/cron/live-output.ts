import type { CronLiveOutputResult } from "@openclaw/gateway-protocol";

/** Bounds the displayed text; the Gateway tail is bounded separately by bytes. */
const LIVE_OUTPUT_MAX_LINES = 2000;

export type LiveOutputView = { text: string; truncated: boolean };

/** Folds one `cron.liveOutput` read into what the panel shows, dropping the oldest lines. */
export function applyLiveOutput(
  view: LiveOutputView,
  result: Pick<CronLiveOutputResult, "text" | "reset" | "truncated">,
): LiveOutputView {
  const text = result.reset ? result.text : view.text + result.text;
  const truncated = result.reset ? result.truncated === true : view.truncated;
  // Keep the last N lines: cut after the (N + 1)th newline counted from the end.
  let cut = text.length;
  for (let seen = 0; seen <= LIVE_OUTPUT_MAX_LINES; seen += 1) {
    cut = cut > 0 ? text.lastIndexOf("\n", cut - 1) : -1;
    if (cut < 0) {
      return { text, truncated };
    }
  }
  return { text: text.slice(cut + 1), truncated: true };
}
