// mithril-runtime is intentionally a tiny runtime package without its own
// declarations; the project uses @types/mithril for component authoring.
declare module "mithril-runtime";

declare const NativeModules: Record<string, any>;
declare const lynx: {
  getJSModule(name: "GlobalEventEmitter"): {
    addListener(eventName: string, listener: (event: any) => void): void;
    removeListener(eventName: string, listener: (event: any) => void): void;
  };
};

interface ImportMeta { url: string; }
