// Loopback and literal-IP hosts are served over plain http far more often than https.
const LOCAL_HTTP_HOST =
  /^(?:localhost|[^/?#]*\.localhost|\d{1,3}(?:\.\d{1,3}){3}|\[[0-9a-f:.]+\])(?::\d+)?(?:[/?#]|$)/i;

export function normalizeBrowserUrlDraft(raw: string): string | null {
  const trimmed = raw.trim();
  if (!trimmed) {
    return null;
  }
  // A colon followed by digits is a port, not a scheme.
  const hasExplicitScheme = /^[a-z][a-z0-9+.-]*:(?![0-9])/i.test(trimmed);
  if (hasExplicitScheme && !/^https?:\/\//i.test(trimmed)) {
    return null;
  }
  // Without a scheme, whitespace means a search phrase; "https://cats funny" is not an address.
  if (!hasExplicitScheme && /\s/.test(trimmed)) {
    return null;
  }
  const candidate = hasExplicitScheme
    ? trimmed
    : `${LOCAL_HTTP_HOST.test(trimmed) ? "http" : "https"}://${trimmed}`;
  const parsed = URL.parse(candidate);
  return parsed && (parsed.protocol === "http:" || parsed.protocol === "https:")
    ? parsed.toString()
    : null;
}
