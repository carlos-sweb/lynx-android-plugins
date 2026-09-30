import m from "mithril-runtime";
import type { Component, VnodeDOM } from "mithril";
import { MapController } from "./maps-controller";
import { MapsError } from "./maps-types";
import type { Bounds, CameraOptions, CameraState, ClusterEvent, Coordinate, FeatureEvent, LocationPosition, MapCircle, MapLayer, MapLine, MapMarker, MapPolygon, MapSource, MapStyle, MapTapEvent, MarkerEvent, OfflineStatus } from "./maps-types";
export * from "./maps-types";
export { MapController, createMapController } from "./maps-controller";
export { createMockMapProviders } from "./maps-mock-providers";

/** Declarative content. Commands and native transport belong to the controller. */
export interface MapsAttributes {
  controller?: MapController;
  initialCamera?: CameraOptions;
  camera?: CameraOptions;
  latitude?: number | `${number}`;
  longitude?: number | `${number}`;
  zoom?: number;
  variant?: "full";
  packageId?: string;
  mapStyle?: MapStyle;
  markers?: MapMarker[];
  lines?: MapLine[];
  polygons?: MapPolygon[];
  circles?: MapCircle[];
  sources?: Record<string, MapSource>;
  layers?: MapLayer[];
  /** PNG images in app assets, keyed by the icon-image ID. */
  images?: Record<string, string>;
  cluster?: boolean | { radius?: number; maxZoom?: number };
  selectedMarkerId?: string;
  minZoom?: number;
  maxZoom?: number;
  maxBounds?: Bounds;
  gestures?: { scroll?: boolean; zoom?: boolean; rotate?: boolean; pitch?: boolean; doubleTap?: boolean };
  controls?: { compass?: boolean; zoom?: boolean };
  userLocation?: LocationPosition | null;
  style?: Record<string, string | number>;
  class?: string;
  onready?: () => void;
  onerror?: (error: MapsError) => void;
  ontap?: (event: MapTapEvent) => void;
  onlongpress?: (event: MapTapEvent) => void;
  onmarkertap?: (event: MarkerEvent) => void;
  onmarkerdragend?: (event: MarkerEvent) => void;
  onfeaturetap?: (event: FeatureEvent) => void;
  onclustertap?: (event: ClusterEvent) => void;
  oncamerachange?: (event: CameraState) => void;
  oncameraidle?: (event: CameraState) => void;
  onlocationchange?: (event: LocationPosition) => void;
  onofflineprogress?: (event: OfflineStatus) => void;
}
interface MapState { controller: MapController; elementId: string; attrs: MapsAttributes }
type SelectorRuntime = { createSelectorQuery(): { select(selector: string): { invoke(options: unknown): { exec(): void } } } };
declare const lynx: SelectorRuntime | undefined;
let nextId = 0;

function validateCoordinate(position: Coordinate): void {
  if (!Number.isFinite(position.latitude) || !Number.isFinite(position.longitude) || Math.abs(position.latitude) > 90 || Math.abs(position.longitude) > 180) throw new MapsError("INVALID_ARGUMENT", "Coordinates must be finite latitude [-90, 90] and longitude [-180, 180].");
}
/** @internal String attributes avoid native DOM object coercion. */
export function serializeMaps(attrs: MapsAttributes): string {
  const { controller, style, class: className, ...model } = attrs;
  for (const coordinate of [attrs.camera?.center, attrs.initialCamera?.center, attrs.userLocation]) if (coordinate) validateCoordinate(coordinate);
  if (attrs.latitude !== undefined || attrs.longitude !== undefined) validateCoordinate({ latitude: Number(attrs.latitude), longitude: Number(attrs.longitude) });
  const ids = new Set<string>();
  for (const item of [...(attrs.markers ?? []), ...(attrs.lines ?? []), ...(attrs.polygons ?? []), ...(attrs.circles ?? [])]) {
    if (!item.id || ids.has(item.id)) throw new MapsError("INVALID_ARGUMENT", "Map item IDs must be nonempty and unique."); ids.add(item.id);
  }
  for (const marker of attrs.markers ?? []) validateCoordinate(marker.position);
  for (const line of attrs.lines ?? []) { if (line.coordinates.length < 2) throw new MapsError("INVALID_ARGUMENT", "Lines need two coordinates."); line.coordinates.forEach(validateCoordinate); }
  for (const polygon of attrs.polygons ?? []) { if (!polygon.coordinates.length || polygon.coordinates.some((ring) => ring.length < 3)) throw new MapsError("INVALID_ARGUMENT", "Polygon rings need three coordinates."); polygon.coordinates.flat().forEach(validateCoordinate); }
  for (const circle of attrs.circles ?? []) { validateCoordinate(circle.center); if (!Number.isFinite(circle.radius) || circle.radius <= 0) throw new MapsError("INVALID_ARGUMENT", "Circle radius must be positive meters."); }
  for (const id of Object.keys(attrs.sources ?? {})) if (id.startsWith("__maps_")) throw new MapsError("INVALID_ARGUMENT", "The __maps_ source prefix is reserved.");
  for (const layer of attrs.layers ?? []) if (layer.id.startsWith("__maps_")) throw new MapsError("INVALID_ARGUMENT", "The __maps_ layer prefix is reserved.");
  return JSON.stringify(model);
}

/** @internal Normalizes modern Element.invoke and older SelectorQuery responses. */
export function nativeTransport(dom: unknown, elementId: string) {
  return async (operation: string, params: unknown): Promise<unknown> => {
    const payload = { operation, payload: JSON.stringify(params) };
    const element = dom as { invoke?: (name: string, params: unknown) => Promise<unknown> };
    if (typeof element.invoke === "function") return element.invoke("command", payload).catch((error) => { throw transportError(error); });
    const runtime = typeof lynx === "undefined" ? undefined : lynx;
    if (!runtime) throw new MapsError("NOT_SUPPORTED", "Maps requires an Android Lynx runtime.");
    return new Promise((resolve, reject) => runtime.createSelectorQuery().select(`#${elementId}`).invoke({ method: "command", params: payload, success: (response: { code?: number; data?: unknown; ok?: boolean }) => resolve(response.ok !== undefined ? response : response.data ?? response), fail: (error: unknown) => reject(transportError(error)) }).exec());
  };
}
function transportError(error: unknown): MapsError {
  if (error instanceof MapsError) return error;
  const failure = error as { code?: number; data?: unknown; message?: string };
  return new MapsError(failure?.code === 3 ? "NOT_SUPPORTED" : failure?.code === 2 ? "MAP_UNMOUNTED" : failure?.code === 4 ? "INVALID_ARGUMENT" : "NATIVE_ERROR", failure?.message ?? (typeof failure?.data === "string" ? failure.data : "The native map UI method failed."));
}

/** Android-only map with declarative content and per-instance commands. */
export const Maps: Component<MapsAttributes, MapState> = {
  oninit(vnode) { this.controller = vnode.attrs.controller ?? new MapController(); this.elementId = `lynx-map-${++nextId}`; this.attrs = vnode.attrs; },
  onbeforeupdate(vnode) { if (vnode.attrs.controller && vnode.attrs.controller !== this.controller) throw new MapsError("INVALID_ARGUMENT", "Remount Maps to replace its controller."); this.attrs = vnode.attrs; },
  onremove() { this.controller.detach(); },
  view(vnode) {
    const attrs = vnode.attrs;
    const event = <T>(callback: ((value: T) => void) | undefined) => (value: { detail?: T } & T) => callback?.(value.detail ?? value);
    return m("lynx-android-map", {
      id: this.elementId, model: serializeMaps(attrs), style: attrs.style ?? { width: "100%", height: "100%" }, class: attrs.class,
      oncreate: (node: VnodeDOM) => this.controller.attach(nativeTransport(node.dom, this.elementId)),
      onready: () => { this.controller.setReady(true); this.attrs.onready?.(); },
      onloading: () => this.controller.setReady(false),
      onerror: (value: { detail: { code: MapsError["code"]; message: string } }) => this.attrs.onerror?.(new MapsError(value.detail.code, value.detail.message)),
      ontap: event(attrs.ontap), onlongpress: event(attrs.onlongpress), onmarkertap: event(attrs.onmarkertap),
      onmarkerdragend: event(attrs.onmarkerdragend), onfeaturetap: event(attrs.onfeaturetap), onclustertap: event(attrs.onclustertap),
      oncamerachange: event(attrs.oncamerachange), oncameraidle: event(attrs.oncameraidle), onlocationchange: event(attrs.onlocationchange),
      onofflineprogress: (value: { detail: OfflineStatus }) => { this.controller.reportProgress(value.detail); this.attrs.onofflineprogress?.(value.detail); },
    });
  },
};
