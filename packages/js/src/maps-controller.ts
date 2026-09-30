import { MapsError } from "./maps-types";
import type { Bounds, CameraOptions, CameraState, Coordinate, FeatureCollection, FeatureQuery, FeatureTarget, LocationOptions, LocationPosition, LocationStatus, LocationTracking, MapFeature, MapProviders, OfflineStatus, RequestOptions, RouteRequest, RouteResult, ScreenPoint, SearchResult, SnapshotOptions, SnapshotResult } from "./maps-types";
export type MapTransport = (operation: string, params: unknown) => Promise<unknown>;
interface Binding { transport: MapTransport; ready: boolean; revision: number; pending: Set<(error: MapsError) => void>; waiters: Set<() => void>; progress: Set<(status: OfflineStatus) => void> }
const bindings = new WeakMap<MapController, Binding>();

/** One controller per mounted map; native transport is managed by Maps. */
export class MapController {
  private disposed = false;
  constructor(private readonly providers: MapProviders = {}) {}
  private call<T>(operation: string, params: unknown = {}, needsReady = true): Promise<T> {
    const binding = bindings.get(this);
    if (!binding) return Promise.reject(new MapsError(this.disposed ? "MAP_UNMOUNTED" : "MAP_NOT_READY", "Mount the controller on Maps first."));
    if (needsReady && !binding.ready) return Promise.reject(new MapsError("MAP_NOT_READY", "Wait for map.ready() or onready."));
    return new Promise<T>((resolve, reject) => {
      let settled = false;
      const finish = (error?: unknown, value?: T) => {
        if (settled) return; settled = true; clearTimeout(timer); binding.pending.delete(cancel);
        if (error) reject(error); else resolve(value as T);
      };
      const cancel = (error: MapsError) => finish(error);
      const timer = setTimeout(() => cancel(new MapsError("TIMEOUT", `${operation} timed out.`)), 60_000);
      binding.pending.add(cancel);
      void Promise.resolve().then(() => { if (settled) throw new MapsError("MAP_UNMOUNTED", "The map operation was already cancelled."); return binding.transport(operation, params); }).then((response) => {
        const envelope = response as { ok?: boolean; data?: T; code?: MapsError["code"]; message?: string };
        if (envelope?.ok === false) finish(new MapsError(envelope.code ?? "NATIVE_ERROR", envelope.message ?? "Native operation failed."));
        else finish(undefined, envelope?.ok === true ? envelope.data : response as T);
      }, (error: unknown) => finish(error instanceof MapsError ? error : new MapsError("NATIVE_ERROR", error instanceof Error ? error.message : String(error))));
    });
  }
  /** Wait for a loaded style, with a bounded wait; downloads do not imply readiness. */
  ready(timeout = 60_000): Promise<void> {
    const binding = bindings.get(this);
    if (!binding) return Promise.reject(new MapsError(this.disposed ? "MAP_UNMOUNTED" : "MAP_NOT_READY", "Mount Maps before awaiting readiness."));
    if (binding.ready) return Promise.resolve();
    return new Promise((resolve, reject) => {
      const finish = () => { cleanup(); resolve(); };
      const cancel = (error: MapsError) => { cleanup(); reject(error); };
      const timer = setTimeout(() => cancel(new MapsError("TIMEOUT", "Map readiness timed out.")), timeout);
      const cleanup = () => { clearTimeout(timer); binding.waiters.delete(finish); binding.pending.delete(cancel); };
      binding.waiters.add(finish); binding.pending.add(cancel);
    });
  }
  getCamera = (): Promise<CameraState> => this.call("getCamera");
  jumpTo = (camera: CameraOptions): Promise<void> => this.call("jumpTo", camera);
  easeTo = (camera: CameraOptions): Promise<void> => this.call("easeTo", camera);
  flyTo = (camera: CameraOptions): Promise<void> => this.call("flyTo", camera);
  fitBounds = (bounds: Bounds, options: CameraOptions = {}): Promise<void> => this.call("fitBounds", { ...options, bounds });
  zoomTo = (zoom: number, options: Omit<CameraOptions, "zoom"> = {}): Promise<void> => this.easeTo({ ...options, zoom });
  stop = (): Promise<void> => this.call("stop");
  project = (position: Coordinate): Promise<ScreenPoint> => this.call("project", position);
  unproject = (point: ScreenPoint): Promise<Coordinate> => this.call("unproject", point);
  queryRenderedFeatures = (query: FeatureQuery = {}): Promise<MapFeature[]> => this.call("queryRenderedFeatures", query);
  querySourceFeatures = (sourceId: string, filter?: unknown[]): Promise<MapFeature[]> => this.call("querySourceFeatures", { sourceId, filter });
  getClusterExpansionZoom = (sourceId: string, feature: MapFeature): Promise<number> => this.call("getClusterExpansionZoom", { sourceId, feature });
  getClusterChildren = (sourceId: string, feature: MapFeature): Promise<FeatureCollection> => this.call("getClusterChildren", { sourceId, feature });
  getClusterLeaves = (sourceId: string, feature: MapFeature, limit = 100, offset = 0): Promise<FeatureCollection> => this.call("getClusterLeaves", { sourceId, feature, limit, offset });
  setFeatureState = (target: FeatureTarget, state: Record<string, unknown>): Promise<void> => this.call("setFeatureState", { ...target, state });
  removeFeatureState = (target: FeatureTarget): Promise<void> => this.call("removeFeatureState", target);
  takeSnapshot = (options: SnapshotOptions = {}): Promise<SnapshotResult> => this.call("takeSnapshot", options);
  readonly location = {
    start: (options: LocationOptions = {}): Promise<LocationStatus> => this.call("location.start", options),
    stop: (): Promise<LocationStatus> => this.call("location.stop", {}, false),
    getStatus: (): Promise<LocationStatus> => this.call("location.getStatus", {}, false),
    setTracking: (mode: LocationTracking): Promise<void> => this.call("location.setTracking", { mode }),
    setPosition: (position: LocationPosition | null): Promise<void> => this.call("location.setPosition", { position }),
  };
  readonly offline = {
    list: (): Promise<OfflineStatus[]> => this.call("offline.list", {}, false),
    getStatus: (id: string): Promise<OfflineStatus> => this.call("offline.getStatus", { id }, false),
    download: (id: string): Promise<OfflineStatus> => this.call("offline.download", { id }, false),
    pause: (id: string): Promise<OfflineStatus> => this.call("offline.pause", { id }, false),
    resume: (id: string): Promise<OfflineStatus> => this.call("offline.resume", { id }, false),
    cancel: (id: string): Promise<OfflineStatus> => this.call("offline.cancel", { id }, false),
    remove: (id: string): Promise<OfflineStatus> => this.call("offline.remove", { id }, false),
    subscribe: (listener: (status: OfflineStatus) => void): (() => void) => {
      const binding = bindings.get(this); if (!binding) throw new MapsError("MAP_NOT_READY", "Mount Maps before subscribing.");
      binding.progress.add(listener); return () => binding.progress.delete(listener);
    },
  };
  search(query: string, options?: RequestOptions): Promise<SearchResult[]> {
    return this.providers.geocoding?.search(query, options) ?? Promise.reject(new MapsError("NOT_SUPPORTED", "Configure a geocoding provider."));
  }
  reverseGeocode(position: Coordinate, options?: RequestOptions): Promise<SearchResult[]> {
    return this.providers.geocoding?.reverseGeocode(position, options) ?? Promise.reject(new MapsError("NOT_SUPPORTED", "Configure a geocoding provider."));
  }
  calculateRoute(request: RouteRequest): Promise<RouteResult[]> {
    return this.providers.routing?.calculateRoute(request) ?? Promise.reject(new MapsError("NOT_SUPPORTED", "Configure a routing provider."));
  }
  /** @internal */
  attach(transport: MapTransport): void {
    if (bindings.has(this)) throw new MapsError("CONTROLLER_IN_USE", "Use a separate controller for each mounted map.");
    this.disposed = false;
    bindings.set(this, { transport, ready: false, revision: 0, pending: new Set(), waiters: new Set(), progress: new Set() });
    void this.call<{ ready: boolean }>("getState", {}, false).then((state) => { const binding = bindings.get(this); if (binding?.transport === transport && binding.revision === 0 && typeof state.ready === "boolean") this.setReady(state.ready); }, () => {});
  }
  /** @internal */
  setReady(ready: boolean): void { const binding = bindings.get(this); if (binding) { binding.revision++; binding.ready = ready; if (ready) for (const finish of [...binding.waiters]) finish(); } }
  /** @internal */
  reportProgress(status: OfflineStatus): void { for (const listener of bindings.get(this)?.progress ?? []) listener(status); }
  /** @internal */
  detach(): void { const binding = bindings.get(this); bindings.delete(this); this.disposed = true; if (binding) { for (const cancel of [...binding.pending]) cancel(new MapsError("MAP_UNMOUNTED", "The map was removed.")); binding.progress.clear(); } }
}
/** Optional providers supply search and routing without coupling the map to a service. */
export function createMapController(providers: MapProviders = {}): MapController { return new MapController(providers); }
