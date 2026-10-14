// Hostname normalization tests cover DNS trailing dots, empty input, and
// bracketed IPv6 literal handling.
import { describe, expect, it } from "vitest";
import { formatUrlHost, normalizeHostname } from "./hostname.js";

describe("normalizeHostname", () => {
  it.each([
    { input: " Example.COM... ", expected: "example.com" },
    { input: "   ", expected: "" },
    { input: " [FD7A:115C:A1E0::1]... ", expected: "fd7a:115c:a1e0::1" },
    { input: "[fd7a:115c:a1e0::1", expected: "[fd7a:115c:a1e0::1" },
    { input: "fd7a:115c:a1e0::1]", expected: "fd7a:115c:a1e0::1]" },
  ])("normalizes %j", ({ input, expected }) => {
    expect(normalizeHostname(input)).toBe(expected);
  });
});

describe("formatUrlHost", () => {
  it.each([
    { input: "example.com", expected: "example.com" },
    { input: "192.0.2.1", expected: "192.0.2.1" },
    { input: "2001:db8::10", expected: "[2001:db8::10]" },
    { input: "[2001:db8::10]", expected: "[2001:db8::10]" },
  ])("formats %j", ({ input, expected }) => {
    expect(formatUrlHost(input)).toBe(expected);
  });
});
