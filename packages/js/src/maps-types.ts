/** Numeric degrees. GeoJSON uses [longitude, latitude] instead. */
export interface Coordinate { latitude: number; longitude: number }
export interface ScreenPoint { x: number; y: number }
export interface Bounds { southwest: Coordinate; northeast: Coordinate }
export interface Padding { top: number; right: number; bottom: number; left: number }
export interface CameraOptions { center?: Coordinate; zoom?: number; bearing?: number; pitch?: number; padding?: Partial<Padding>; duration?: number }
export interface CameraState extends CameraOptions { center: Coordinate; zoom: number; bearing: number; pitch: number; bounds: Bounds }
export interface MapMarker { id: string; position: Coordinate; title?: string; description?: string; color?: string; icon?: string; draggable?: boolean }
export interface MapLine { id: string; coordinates: Coordinate[]; color?: string; width?: number; opacity?: number }
export interface MapPolygon { id: string; coordinates: Coordinate[][]; color?: string; outlineColor?: string; opacity?: number }
export interface MapCircle { id: string; center: Coordinate; radius: number; color?: string; outlineColor?: string; opacity?: number }
export interface GeoJSONGeometry { type: string; coordinates?: unknown; geometries?: GeoJSONGeometry[] }
export interface MapFeature { type: "Feature"; id?: string | number; geometry: GeoJSONGeometry | null; properties: Record<string, unknown> | null }
export interface FeatureCollection { type: "FeatureCollection"; features: MapFeature[] }
export type GeoJSON = GeoJSONGeometry | MapFeature | FeatureCollection;
/** MapLibre Style Specification source. Remote resources must belong to an authorized provider. */
export interface MapSource { type: "geojson" | "vector" | "raster" | "raster-dem"; data?: GeoJSON | string; url?: string; tiles?: string[]; cluster?: boolean; clusterRadius?: number; clusterMaxZoom?: number; [key: string]: unknown }
export interface MapLayer { id: string; type: "fill" | "line" | "circle" | "symbol" | "raster" | "heatmap" | "fill-extrusion" | "hillshade" | "background"; source?: string; "source-layer"?: string; filter?: unknown[]; paint?: Record<string, unknown>; layout?: Record<string, unknown>; minzoom?: number; maxzoom?: number }
export interface MapStyle { version: 8; sources: Record<string, MapSource>; layers: MapLayer[]; [key: string]: unknown }
export interface MapTapEvent { coordinate: Coordinate; point: ScreenPoint }
export interface MarkerEvent extends MapTapEvent { id: string; marker: MapMarker }
export interface FeatureEvent extends MapTapEvent { features: MapFeature[] }
export interface ClusterEvent extends MapTapEvent { sourceId: string; feature: MapFeature }
export interface LocationPosition extends Coordinate { accuracy?: number; timestamp?: number; bearing?: number; speed?: number }
export type LocationTracking = "none" | "follow" | "heading" | "course";
export interface LocationOptions { highAccuracy?: boolean; interval?: number; minDistance?: number }
export interface LocationStatus { running: boolean; permission: "none" | "approximate" | "precise"; providerEnabled: boolean; tracking: LocationTracking }
export interface OfflineStatus { id: string; version: string; state: "missing" | "downloading" | "paused" | "verifying" | "ready" | "error"; received: number; total: number; error?: string }
export interface MapPackageDefinition { id: string; url: string; sha256: string; version: string; label?: string; size?: number }
export interface SnapshotOptions { width?: number; height?: number }
export interface SnapshotResult { uri: string; width: number; height: number }
export interface FeatureQuery { point?: ScreenPoint; rectangle?: { left: number; top: number; right: number; bottom: number }; layerIds?: string[]; filter?: unknown[] }
export interface FeatureTarget { sourceId: string; featureId: string | number }
export interface RequestOptions { signal?: AbortSignal }
export interface SearchResult { id: string; label: string; position: Coordinate; bounds?: Bounds; properties?: Record<string, unknown> }
export interface GeocodingProvider { search(query: string, options?: RequestOptions): Promise<SearchResult[]>; reverseGeocode(position: Coordinate, options?: RequestOptions): Promise<SearchResult[]> }
export interface RouteRequest extends RequestOptions { coordinates: Coordinate[]; profile?: "walking" | "cycling" | "driving" }
export interface RouteResult { id: string; coordinates: Coordinate[]; distance: number; duration: number; instructions?: { text: string; position: Coordinate }[] }
export interface RoutingProvider { calculateRoute(request: RouteRequest): Promise<RouteResult[]> }
export interface MapProviders { geocoding?: GeocodingProvider; routing?: RoutingProvider }
export type MapsErrorCode = "INVALID_ARGUMENT" | "MAP_NOT_READY" | "MAP_UNMOUNTED" | "CONTROLLER_IN_USE" | "PERMISSION_DENIED" | "POSITION_UNAVAILABLE" | "DOWNLOAD_FAILED" | "CHECKSUM_MISMATCH" | "INSUFFICIENT_STORAGE" | "NOT_SUPPORTED" | "CANCELLED" | "TIMEOUT" | "NATIVE_ERROR";
/** Stable errors independent of Lynx UI-method numeric status codes. */
export class MapsError extends Error {
  constructor(public readonly code: MapsErrorCode, message: string) { super(message); this.name = "MapsError"; }
}
