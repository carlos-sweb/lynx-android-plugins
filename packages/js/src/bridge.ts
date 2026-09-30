type NativeModule = Record<string, (...args: unknown[]) => void>;
type NativeModulesMap = Record<string, NativeModule | undefined>;

type EventEmitter = {
  addListener(name: string, listener: (...args: unknown[]) => void): void;
  removeListener(name: string, listener: (...args: unknown[]) => void): void;
};

declare const NativeModules: NativeModulesMap | undefined;
declare const lynx: { getJSModule(name: "GlobalEventEmitter"): EventEmitter } | undefined;

type PluginEvent = {
  requestId: string;
  ok: boolean;
  data?: Record<string, unknown>;
  errorCode?: string;
};

let nextRequest = 0;

/** An Android connector failure with a stable machine-readable code. */
export class ConnectorError extends Error {
  readonly code: string;

  constructor(code: string, message: string) {
    super(message);
    this.name = "ConnectorError";
    this.code = code;
  }
}

function nativeModule(name: string): NativeModule | undefined {
  return typeof NativeModules === "undefined" ? undefined : NativeModules?.[name];
}

/** Whether the current Lynx host registered the requested native method. */
export function isAvailable(moduleName: string, methodName: string): boolean {
  return typeof nativeModule(moduleName)?.[methodName] === "function";
}

/** Invoke one native operation and correlate its GlobalEventEmitter response. */
export function invoke<T>(
  moduleName: string,
  eventName: string,
  methodName: string,
  args: unknown[] = [],
  timeoutMs = 10_000,
): Promise<T> {
  const module = nativeModule(moduleName);
  if (module == null) {
    return Promise.reject(new ConnectorError("UNAVAILABLE", `${moduleName} is not registered in this Android host.`));
  }
  const method = module[methodName];
  if (typeof method !== "function") {
    return Promise.reject(new ConnectorError("UNSUPPORTED_METHOD", `${moduleName}.${methodName} is unavailable. Update the Android host.`));
  }
  if (typeof lynx === "undefined") {
    return Promise.reject(new ConnectorError("UNAVAILABLE", "The Lynx event emitter is unavailable."));
  }

  const requestId = `${eventName}-${Date.now()}-${++nextRequest}`;
  return new Promise<T>((resolve, reject) => {
    let timer: ReturnType<typeof setTimeout> | undefined;
    let emitter: EventEmitter;
    try {
      emitter = lynx.getJSModule("GlobalEventEmitter");
      if (emitter == null || typeof emitter.addListener !== "function" || typeof emitter.removeListener !== "function") {
        throw new Error("The Lynx event emitter does not support listeners.");
      }
    } catch (error) {
      reject(new ConnectorError("UNAVAILABLE", error instanceof Error ? error.message : "The Lynx event emitter is unavailable."));
      return;
    }
    const listener = (...args: unknown[]) => {
      const event = args[0] as PluginEvent | null;
      if (event == null || event.requestId !== requestId) return;
      cleanup();
      if (event.ok === true && event.data != null && typeof event.data === "object") {
        resolve(event.data as T);
      } else if (event.ok === false) {
        const code = event.errorCode ?? (typeof event.data?.code === "string" ? event.data.code : "NATIVE_ERROR");
        const message = typeof event.data?.message === "string" ? event.data.message : `${moduleName} failed.`;
        reject(new ConnectorError(code, message));
      } else {
        reject(new ConnectorError("INVALID_RESPONSE", `${moduleName} returned an invalid event.`));
      }
    };
    const cleanup = () => {
      if (timer != null) clearTimeout(timer);
      try { emitter.removeListener(eventName, listener); } catch { /* The host may be tearing down. */ }
    };
    try {
      emitter.addListener(eventName, listener);
      timer = setTimeout(() => {
        cleanup();
        reject(new ConnectorError("TIMEOUT", `${moduleName}.${methodName} did not respond.`));
      }, timeoutMs);
      method.call(module, requestId, ...args);
    } catch (error) {
      cleanup();
      reject(new ConnectorError("NATIVE_ERROR", error instanceof Error ? error.message : String(error)));
    }
  });
}
