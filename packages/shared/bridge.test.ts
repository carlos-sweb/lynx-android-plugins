import { afterEach, expect, test } from "bun:test";
import { ConnectorError, invoke, isAvailable } from "../js/src/bridge";

type Callback = (...args: unknown[]) => void;

function installHost(method: (requestId: string) => void) {
  const listeners = new Map<string, Set<Callback>>();
  const emitter = {
    addListener(name: string, listener: Callback) {
      const group = listeners.get(name) ?? new Set<Callback>();
      group.add(listener);
      listeners.set(name, group);
    },
    removeListener(name: string, listener: Callback) {
      listeners.get(name)?.delete(listener);
    },
    emit(name: string, payload: unknown) {
      for (const listener of listeners.get(name) ?? []) listener(payload);
    },
    count(name: string) { return listeners.get(name)?.size ?? 0; },
  };
  Object.assign(globalThis, {
    NativeModules: { TestModule: { get: method } },
    lynx: { getJSModule: () => emitter },
  });
  return emitter;
}

afterEach(() => {
  Reflect.deleteProperty(globalThis, "NativeModules");
  Reflect.deleteProperty(globalThis, "lynx");
});

test("correlates concurrent responses and removes listeners", async () => {
  const requests: string[] = [];
  const emitter = installHost((requestId) => requests.push(requestId));
  const first = invoke<{ value: number }>("TestModule", "test:event", "get");
  const second = invoke<{ value: number }>("TestModule", "test:event", "get");
  expect(isAvailable("TestModule", "get")).toBe(true);
  expect(emitter.count("test:event")).toBe(2);
  emitter.emit("test:event", { requestId: requests[1], ok: true, data: { value: 2 } });
  emitter.emit("test:event", { requestId: requests[0], ok: true, data: { value: 1 } });
  expect(await first).toEqual({ value: 1 });
  expect(await second).toEqual({ value: 2 });
  expect(emitter.count("test:event")).toBe(0);
});

test("rejects native errors and removes the listener", async () => {
  let requestId = "";
  const emitter = installHost((id) => { requestId = id; });
  const result = invoke("TestModule", "test:event", "get");
  emitter.emit("test:event", { requestId, ok: false, errorCode: "PERMISSION_DENIED", data: { message: "Denied." } });
  await expect(result).rejects.toMatchObject({ code: "PERMISSION_DENIED", message: "Denied." });
  expect(emitter.count("test:event")).toBe(0);
});

test("reports an unavailable host without registering a listener", async () => {
  expect(isAvailable("TestModule", "get")).toBe(false);
  await expect(invoke("TestModule", "test:event", "get")).rejects.toBeInstanceOf(ConnectorError);
});
