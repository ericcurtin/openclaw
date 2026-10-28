import { createComponent } from "solid-js";
import { afterEach, expect, it, vi } from "vitest";
import { mountSolid } from "../../test-helpers/mount-solid.ts";
import { flush } from "../../test-helpers/solid-settle.ts";
import { setupAnnotationCapture } from "./browser-panel-capture.test-support.ts";
import {
  createInspectedNode,
  flushBrowserResponses,
  setupBrowserPanelTestCleanup,
} from "./browser-panel-controller-test-support.ts";
import type { BrowserPanel } from "./browser-panel-owner.ts";
import { BrowserPanelChrome } from "./browser-panel-render.tsx";
import { panelScenario } from "./browser-panel-stream.test-support.ts";

setupBrowserPanelTestCleanup();
const { receive: acceptAnnotation, stubCanvas } = setupAnnotationCapture();

function pointer(type: string, id: number, x: number, y: number, pointerType: string) {
  const event = new MouseEvent(type, { bubbles: true, cancelable: true, clientX: x, clientY: y });
  return Object.assign(event, { pointerId: id, pointerType }) as PointerEvent;
}

const click = (clientX: number, clientY: number) =>
  new MouseEvent("click", { bubbles: true, clientX, clientY });

function renderInput(controller: BrowserPanel): HTMLTextAreaElement {
  const root = controller.host.renderRoot;
  const rendered = mountSolid(() =>
    createComponent(BrowserPanelChrome, {
      controller,
      dock: "right",
      height: 400,
      width: 400,
      onDockChange() {},
      onClose() {},
      embedded: false,
      tabsInHeader: false,
    }),
  );
  root.replaceChildren(rendered.container);
  flush();
  vi.spyOn(root.querySelector<HTMLElement>(".bp-stage")!, "getBoundingClientRect").mockReturnValue(
    new DOMRect(0, 0, 100, 100),
  );
  return root.querySelector<HTMLTextAreaElement>(".bp-input")!;
}

function setup<T>(mount: (controller: BrowserPanel) => T) {
  vi.useFakeTimers();
  const { controller, actions } = panelScenario("tab-a", undefined, {
    "/act": () => ({ result: true }),
  });
  const clicks = () => actions("clickCoords");
  const scrolls = () =>
    actions("evaluate").map((body) =>
      /window\.scrollBy\((-?\d+), (-?\d+)\)/.exec(String(body?.fn))?.slice(1),
    );
  return { controller, clicks, scrolls, input: mount(controller) };
}

async function settle() {
  await vi.advanceTimersByTimeAsync(150);
  await flushBrowserResponses();
}

it.each(["touch", "pen", "mouse"])("handles %s taps and drags", async (pointerType) => {
  const { input, clicks, scrolls } = setup(renderInput);
  const tap = { kind: "clickCoords", targetId: "tab-a", x: 20, y: 20 };
  const drag = { kind: "clickCoords", targetId: "tab-a", x: 50, y: 40 };

  input.dispatchEvent(pointer("pointerdown", 1, 20, 20, pointerType));
  input.dispatchEvent(pointer("pointerup", 1, 20, 20, pointerType));
  input.dispatchEvent(click(20, 20));
  await settle();
  expect(clicks()).toEqual([tap]);

  input.dispatchEvent(pointer("pointerdown", 2, 50, 70, pointerType));
  input.dispatchEvent(pointer("pointermove", 2, 50, 40, pointerType));
  input.dispatchEvent(pointer("pointerup", 2, 50, 40, pointerType));
  input.dispatchEvent(click(50, 40));
  await settle();
  input.dispatchEvent(pointer("pointerdown", 3, 50, 40, pointerType));
  input.dispatchEvent(pointer("pointermove", 3, 50, 60, pointerType));
  input.dispatchEvent(pointer("pointercancel", 3, 50, 60, pointerType));
  await settle();

  const deltas = pointerType === "mouse" ? [] : ["30", "-20"];
  expect(scrolls()).toEqual(deltas.map((dy) => ["0", dy]));
  expect(clicks()).toEqual(pointerType === "mouse" ? [tap, drag] : [tap]);
});

it("drops a pending pen scroll when capture state resets", async () => {
  const { controller, input, scrolls } = setup(renderInput);
  input.dispatchEvent(pointer("pointerdown", 1, 50, 70, "pen"));
  input.dispatchEvent(pointer("pointermove", 1, 50, 40, "pen"));
  controller.input.resetCaptureState();
  input.dispatchEvent(pointer("pointermove", 1, 50, 10, "pen"));
  await settle();
  expect(scrolls()).toEqual([]);
});

it("suppresses the click that follows an inspect pointerdown", async () => {
  stubCanvas(["strokeRect"]);
  const { controller, clicks } = setup(() => undefined);
  controller.setMode("inspect");
  controller.inspected = createInspectedNode("inspected");
  acceptAnnotation.mockImplementation((event) => event.preventDefault());
  controller.input.handleOverlayPointerDown(pointer("pointerdown", 1, 20, 20, "pen"));
  expect(controller.mode).toBe("interact");
  controller.input.handleStageClick(click(20, 20));
  controller.input.handleStageClick(click(40, 50));
  await flushBrowserResponses();
  expect(acceptAnnotation).toHaveBeenCalledTimes(1);
  expect(clicks()).toEqual([{ kind: "clickCoords", targetId: "tab-a", x: 40, y: 50 }]);
});

afterEach(() => {
  Reflect.deleteProperty(HTMLElement.prototype, "animate");
  vi.unstubAllGlobals();
});

function stubAnimate(reducedMotion = false) {
  const cancel = vi.fn();
  const animate = vi.fn(() => ({ cancel }) as unknown as Animation);
  Object.defineProperty(HTMLElement.prototype, "animate", { configurable: true, value: animate });
  vi.stubGlobal("matchMedia", () => ({ matches: reducedMotion }));
  return { animate, cancel };
}

function marker(input: HTMLElement) {
  return input.closest(".bp-stage")!.querySelector<HTMLElement>(".bp-press")!;
}

it("acknowledges a mouse press locally at pointerdown and not again on its click", async () => {
  const { animate } = stubAnimate();
  const { input, clicks } = setup(renderInput);

  input.dispatchEvent(pointer("pointerdown", 1, 20, 40, "mouse"));
  // Before any request exists the user already sees where the press registered.
  expect(animate).toHaveBeenCalledTimes(1);
  expect(marker(input).style.left).toBe("20%");
  expect(marker(input).style.top).toBe("40%");
  expect(marker(input).getAttribute("aria-hidden")).toBe("true");
  expect(clicks()).toEqual([]);

  input.dispatchEvent(pointer("pointerup", 1, 20, 40, "mouse"));
  input.dispatchEvent(click(20, 40));
  await settle();
  expect(animate).toHaveBeenCalledTimes(1);
  expect(clicks()).toHaveLength(1);
});

it("acknowledges a touch tap only when it becomes a click, never a scroll drag", async () => {
  const { animate } = stubAnimate();
  const { input } = setup(renderInput);

  input.dispatchEvent(pointer("pointerdown", 1, 20, 40, "touch"));
  expect(animate).not.toHaveBeenCalled();
  input.dispatchEvent(pointer("pointerup", 1, 20, 40, "touch"));
  input.dispatchEvent(click(20, 40));
  expect(animate).toHaveBeenCalledTimes(1);

  input.dispatchEvent(pointer("pointerdown", 2, 50, 70, "touch"));
  input.dispatchEvent(pointer("pointermove", 2, 50, 40, "touch"));
  input.dispatchEvent(pointer("pointerup", 2, 50, 40, "touch"));
  input.dispatchEvent(click(50, 40));
  await settle();
  expect(animate).toHaveBeenCalledTimes(1);
});

it("keeps the press acknowledgement motion-free under reduced motion and drops it on reset", () => {
  const { animate, cancel } = stubAnimate(true);
  const { controller, input } = setup(renderInput);

  input.dispatchEvent(pointer("pointerdown", 1, 20, 40, "mouse"));
  const [keyframes, options] = animate.mock.calls[0] as unknown as [
    Array<Record<string, unknown>>,
    KeyframeAnimationOptions,
  ];
  // No scaling motion: every transform keeps the marker at its resting size.
  expect(keyframes.flatMap((frame) => frame.transform ?? [])).toEqual([
    "translate(-50%, -50%) scale(1)",
  ]);
  expect(options.duration).toBe(300);

  controller.input.resetCaptureState();
  expect(cancel).toHaveBeenCalledTimes(1);
});
