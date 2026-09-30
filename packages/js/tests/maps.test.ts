import { describe, expect, test } from "bun:test";
import { createMapController, createMockMapProviders, MapsError, serializeMaps, nativeTransport } from "../src/maps";

const camera = { center: { latitude: -33, longitude: -71 }, zoom: 14 };
describe("Maps controller", () => {
  test("requires mounting and readiness", async () => {
    const map = createMapController();
    await expect(map.getCamera()).rejects.toMatchObject({ code: "MAP_NOT_READY" });
    map.attach(async () => ({ ok: true, data: { ready: false } }));
    await expect(map.getCamera()).rejects.toMatchObject({ code: "MAP_NOT_READY" });
    const ready = map.ready(); map.setReady(true); await ready; map.detach();
  });
  test("isolates controllers and unwraps native envelopes", async () => {
    const first = createMapController(); const second = createMapController();
    first.attach(async (operation) => ({ ok: true, data: operation === "getState" ? { ready: true } : camera }));
    second.attach(async (operation) => ({ ok: true, data: operation === "getState" ? { ready: true } : { ...camera, zoom: 8 } }));
    first.setReady(true); second.setReady(true);
    expect((await first.getCamera()).zoom).toBe(14); expect((await second.getCamera()).zoom).toBe(8);
    expect(() => first.attach(async () => null)).toThrow(MapsError);
    first.detach(); second.detach();
  });
  test("rejects outstanding operations on unmount", async () => {
    const map = createMapController(); map.attach(async () => new Promise(() => {})); map.setReady(true);
    const result = map.flyTo(camera); map.detach();
    await expect(result).rejects.toMatchObject({ code: "MAP_UNMOUNTED" });
    await expect(map.getCamera()).rejects.toMatchObject({ code: "MAP_UNMOUNTED" });
  });
  test("rejects readiness timeout and native coded errors", async () => {
    const map = createMapController(); map.attach(async () => ({ ok: false, code: "PERMISSION_DENIED", message: "Denied" }));
    await expect(map.ready(1)).rejects.toMatchObject({ code: "TIMEOUT" });
    map.setReady(true); await expect(map.location.start()).rejects.toMatchObject({ code: "PERMISSION_DENIED" }); map.detach();
  });
  test("offline commands work before the style is ready", async () => {
    const map = createMapController(); const calls: string[] = [];
    map.attach(async (operation) => { calls.push(operation); return { ok: true, data: [] }; });
    await map.offline.list(); expect(calls).toContain("offline.list");
    let progress = 0; const unsubscribe = map.offline.subscribe(() => progress++);
    const status = { id: "chile", version: "1", state: "paused" as const, received: 1, total: 2 };
    map.reportProgress(status); unsubscribe(); map.reportProgress(status); expect(progress).toBe(1); map.detach();
  });
  test("forwards camera, cluster and snapshot parameters", async () => {
    const map = createMapController(); const calls: [string, unknown][] = [];
    map.attach(async (operation, params) => { calls.push([operation, params]); return { ok: true, data: {} }; }); map.setReady(true);
    await map.zoomTo(9, { duration: 100 }); await map.takeSnapshot({ width: 400, height: 300 });
    expect(calls).toContainEqual(["easeTo", { zoom: 9, duration: 100 }]); expect(calls).toContainEqual(["takeSnapshot", { width: 400, height: 300 }]); map.detach();
  });
});
describe("Maps declarative contract", () => {
  test("supports Element.invoke and SelectorQuery without leaking native envelopes", async () => {
    let invoked: unknown;
    const direct = nativeTransport({ invoke: async (method: string, params: unknown) => { invoked = [method, params]; return { ok: true, data: camera }; } }, "first");
    expect(await direct("getCamera", {})).toEqual({ ok: true, data: camera });
    expect(invoked).toEqual(["command", { operation: "getCamera", payload: "{}" }]);
    const globals = globalThis as unknown as { lynx?: unknown };
    const original = globals.lynx;
    globals.lynx = { createSelectorQuery: () => ({ select: (selector: string) => ({ invoke: (options: { success: (result: unknown) => void }) => ({ exec: () => { expect(selector).toBe("#legacy"); options.success({ code: 0, data: { ok: true, data: camera } }); } }) }) }) };
    try { expect(await nativeTransport({}, "legacy")("getCamera", {})).toEqual({ ok: true, data: camera }); }
    finally { if (original === undefined) delete globals.lynx; else globals.lynx = original; }
  });
  test("maps old native method-not-found errors to NOT_SUPPORTED", async () => {
    await expect(nativeTransport({ invoke: async () => { throw { code: 3, data: "Method not found" }; } }, "old")("getCamera", {})).rejects.toMatchObject({ code: "NOT_SUPPORTED" });
  });
  test("serializes stable content without functions/controllers or style", () => {
    const input = { controller: createMapController(), initialCamera: camera, markers: [], style: { width: "100%" }, ontap() {} };
    const serialized = serializeMaps(input);
    expect(JSON.parse(serialized)).toEqual({ initialCamera: camera, markers: [] });
    expect(serializeMaps({ ...input })).toBe(serialized);
  });
  test("validates coordinates, unique IDs and geometry", () => {
    expect(() => serializeMaps({ latitude: "NaN", longitude: 0 })).toThrow(MapsError);
    expect(() => serializeMaps({ markers: [{ id: "x", position: { latitude: 100, longitude: 0 } }] })).toThrow(MapsError);
    expect(() => serializeMaps({ markers: [{ id: "x", position: camera.center }, { id: "x", position: camera.center }] })).toThrow(MapsError);
    expect(() => serializeMaps({ circles: [{ id: "x", center: camera.center, radius: -1 }] })).toThrow(MapsError);
    expect(() => serializeMaps({ sources: { __maps_markers: { type: "geojson" } } })).toThrow(MapsError);
  });
});
describe("Optional providers", () => {
  test("does not silently select a public service", async () => { await expect(createMapController().search("Chile")).rejects.toMatchObject({ code: "NOT_SUPPORTED" }); });
  test("supports mock search, reverse lookup, routing and cancellation", async () => {
    const providers = createMockMapProviders([{ id: "home", label: "Home", position: camera.center }]);
    const map = createMapController(providers);
    expect((await map.search("home"))[0].id).toBe("home"); expect((await map.reverseGeocode(camera.center))[0].id).toBe("home");
    expect((await map.calculateRoute({ coordinates: [camera.center, { latitude: -34, longitude: -71 }] }))[0].distance).toBeGreaterThan(100000);
    const abort = new AbortController(); abort.abort(); await expect(map.search("Home", { signal: abort.signal })).rejects.toMatchObject({ code: "CANCELLED" });
  });
});
