import { afterEach, describe, expect, it } from "vitest";
import { browserSelectionScript } from "./browser-selection-script.ts";

const read = new Function(`${browserSelectionScript}\nreturn openclawReadBrowserSelection;`)() as (
  limit: number,
  probeOnly: boolean,
) => { length: number; text: string; editable: boolean; truncated: boolean };

function selectNodeText(doc: Document, node: Node) {
  const range = doc.createRange();
  range.selectNodeContents(node);
  doc.getSelection()!.removeAllRanges();
  doc.getSelection()!.addRange(range);
}

function field(
  tag: "input" | "textarea",
  value: string,
  start: number,
  end: number,
  type = "text",
) {
  const element = document.createElement(tag);
  if (element instanceof HTMLInputElement) {
    element.type = type;
  }
  element.value = value;
  document.body.append(element);
  element.focus();
  element.setSelectionRange(start, end);
  return element;
}

afterEach(() => {
  document.getSelection()?.removeAllRanges();
  document.body.replaceChildren();
});

describe("browser selection page script", () => {
  it("reads the page selection and reports its size without text when probing", () => {
    const paragraph = document.body.appendChild(document.createElement("p"));
    paragraph.textContent = "plain page text";
    selectNodeText(document, paragraph);

    expect(read(100, false)).toEqual({
      length: 15,
      text: "plain page text",
      editable: false,
      truncated: false,
    });
    expect(read(100, true)).toMatchObject({ length: 15, text: "" });
  });

  it("reads the range selected inside an editable field", () => {
    field("textarea", "one two three", 4, 7);
    expect(read(100, false)).toMatchObject({ text: "two", editable: true });
    document.body.replaceChildren();
    field("input", "readonly value", 0, 8).readOnly = true;
    expect(read(100, false)).toMatchObject({ text: "readonly", editable: false });
  });

  it("never reads a password field", () => {
    field("input", "hunter2", 0, 7, "password");
    expect(read(100, false)).toEqual({ length: 0, text: "", editable: false, truncated: false });
  });

  it("follows focus into shadow roots and same-origin frames", () => {
    const host = document.body.appendChild(document.createElement("div"));
    const inner = host.attachShadow({ mode: "open" }).appendChild(document.createElement("input"));
    inner.value = "shadow text";
    inner.focus();
    inner.setSelectionRange(0, 6);
    expect(read(100, false)).toMatchObject({ text: "shadow" });

    host.remove();
    const frame = document.body.appendChild(document.createElement("iframe"));
    const doc = frame.contentDocument!;
    doc.body.textContent = "frame text";
    selectNodeText(doc, doc.body);
    frame.focus();
    expect(read(100, false)).toMatchObject({ text: "frame text" });
  });

  it("cuts at the cap without splitting a surrogate pair", () => {
    field("textarea", "ab🦞cd", 0, 6);
    expect(read(3, false)).toEqual({ length: 6, text: "ab", editable: true, truncated: true });
    expect(read(4, false)).toMatchObject({ text: "ab🦞", truncated: true });
  });
});
