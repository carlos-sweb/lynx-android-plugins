import m from "mithril-runtime";
import { redraw } from "mithril-lynx/mount-redraw";
import { Maps, createMapController, createMockMapProviders } from "lynx-android-plugins/maps";
import type { CameraState, LocationTracking, MapMarker, MapLine, MapsAttributes, OfflineStatus, SnapshotResult } from "lynx-android-plugins/maps";
import "./style.css";

const center = { latitude: -33.532290, longitude: -71.584904 };
const north = { latitude: -33.527290, longitude: -71.584904 };
const map = createMapController(createMockMapProviders([
  { id: "home", label: "Home", position: center }, { id: "north", label: "North", position: north },
]));
const tabs = ["Camera", "Markers", "Layers", "GPS", "Offline", "Capture", "Providers"];
let tab = "Camera", status = "Loading offline map…", selectedMarkerId = "", packageId = "chile";
let clustered = false, shapes = true, customLayer = true, gestures = true, blankStyle = false;
let initialized = false, removalConfirmation = "";
let camera: CameraState | null = null;
let packages: OfflineStatus[] = [];
let snapshot: SnapshotResult | null = null;
let route: MapLine | null = null;
let tracking: LocationTracking = "none";
let gpsText = "Tap Start GPS to request location permission.";
let events: string[] = [];
let markerSequence = 3;
const initialMarkers: MapMarker[] = [
  { id: "destination", position: center, title: "Destination", draggable: true, color: "#e26635" },
  { id: "north", position: north, title: "North", color: "#167d8d" },
  { id: "east", position: { latitude: -33.532290, longitude: -71.579904 }, title: "East", color: "#7f52aa" },
];
let markers = [...initialMarkers];
function report(message: string) { status = message; events = [message, ...events].slice(0, 4); redraw(); }
async function action(label: string, work: () => Promise<unknown>) {
  report(`${label}…`);
  try { const result = await work(); report(`${label}: ${result === undefined ? "done" : JSON.stringify(result)}`); }
  catch (error) { report(`${label}: ${error instanceof Error ? error.message : String(error)}`); }
  finally { redraw(); }
}
function button(label: string, work: () => void, active = false) {
  return m("view", { class: `MapButton${active ? " MapButtonActive" : ""}`, ontap: work }, m("text", label));
}
function command(label: string, work: () => Promise<unknown>) { return button(label, () => { void action(label, work); }); }
function update(message: string, work: () => void) { work(); report(message); }
async function refreshPackages() { packages = await map.offline.list(); redraw(); return packages; }
function recordOffline(value: OfflineStatus) { packages = [...packages.filter((item) => item.id !== value.id), value]; redraw(); }
function panel() {
  switch (tab) {
    case "Camera": return [
      m("text", { class: "MapHint" }, "Pan, pinch, rotate and tilt. Camera telemetry updates when movement stops."),
      command("Center", () => map.flyTo({ center, zoom: 14, bearing: 0, pitch: 0 })),
      command("Zoom +", async () => map.zoomTo(Math.min(20, (await map.getCamera()).zoom + 1))),
      command("Zoom −", async () => map.zoomTo(Math.max(0, (await map.getCamera()).zoom - 1))),
      command("Tilt / rotate", () => map.easeTo({ bearing: 45, pitch: 50, duration: 900 })),
      command("Fit points", () => map.fitBounds({ southwest: { latitude: -33.54, longitude: -71.59 }, northeast: { latitude: -33.52, longitude: -71.57 } }, { padding: { top: 36, bottom: 36, left: 36, right: 36 } })),
      command("Stop motion", () => map.stop()), command("Read camera", () => map.getCamera()),
      button(gestures ? "Lock gestures" : "Unlock gestures", () => update("Gestures changed", () => { gestures = !gestures; })),
      command("Project / unproject", async () => { const point = await map.project(center); return { point, coordinate: await map.unproject(point) }; }),
    ];
    case "Markers": return [
      m("text", { class: "MapHint" }, `${markers.length} markers. Tap to select; long-press orange to drag. Zoom out to see clusters.`),
      button("Add marker", () => update("Marker added", () => { const n = ++markerSequence; markers = [...markers, { id: `point-${n}`, title: `Point ${n}`, position: { latitude: center.latitude + n * 0.0002, longitude: center.longitude + n * 0.0002 }, color: "#167d8d", draggable: true }]; })),
      button("Remove selected", () => update(selectedMarkerId ? `Removed ${selectedMarkerId}` : "Select a marker first", () => { markers = markers.filter((item) => item.id !== selectedMarkerId); selectedMarkerId = ""; })),
      button(clustered ? "Disable clusters" : "Enable clusters", () => update("Clustering changed", () => { clustered = !clustered; }), clustered),
      button("Reset markers", () => update("Markers reset", () => { markers = [...initialMarkers]; selectedMarkerId = ""; })),
      m("text", { class: "MapHint" }, `Selected: ${selectedMarkerId || "none"}`),
    ];
    case "Layers": return [
      m("text", { class: "MapHint" }, "Line, 150 m circle, polygon with a hole and purple GeoJSON point north-east of center."),
      button(shapes ? "Hide shapes" : "Show shapes", () => update("Shapes changed", () => { shapes = !shapes; }), shapes),
      button(customLayer ? "Hide GeoJSON" : "Show GeoJSON", () => update("GeoJSON changed", () => { customLayer = !customLayer; }), customLayer),
      command("Select feature", () => map.setFeatureState({ sourceId: "test-points", featureId: "test-point" }, { selected: true })),
      command("Clear selection", () => map.removeFeatureState({ sourceId: "test-points", featureId: "test-point" })),
      command("Query visible", async () => ({ count: (await map.queryRenderedFeatures()).length })),
      command("Query source", async () => ({ count: (await map.querySourceFeatures("test-points")).length })),
      button(blankStyle ? "Use offline map" : "Use blank canvas", () => update("Map style changed", () => { blankStyle = !blankStyle; })),
    ];
    case "GPS": return [
      m("text", { class: "MapHint" }, gpsText),
      command("Start GPS", async () => { const value = await map.location.start({ highAccuracy: true }); gpsText = JSON.stringify(value); return value; }),
      command("Stop GPS", async () => { const value = await map.location.stop(); gpsText = JSON.stringify(value); return value; }),
      command("GPS status", async () => { const value = await map.location.getStatus(); gpsText = JSON.stringify(value); return value; }),
      ...(["none", "follow", "heading", "course"] as LocationTracking[]).map((mode) => button(mode, () => { void action(`Tracking ${mode}`, async () => { await map.location.setTracking(mode); tracking = mode; }); }, tracking === mode)),
      command("External demo fix", async () => { if ((await map.location.getStatus()).running) throw new Error("Stop GPS before injecting a demo fix."); await map.location.setPosition({ ...center, accuracy: 25, bearing: 45 }); gpsText = "EXTERNAL DEMO FIX — not a GPS reading"; }),
      command("Clear demo fix", () => map.location.setPosition(null)),
    ];
    case "Offline": return [
      m("text", { class: "MapHint" }, "Transfers require a real HTTPS URL in android/maps.json. Local Chile archive is preserved; no automatic download or deletion."),
      ...packages.map((item) => button(`${item.id}: ${item.state} ${item.total ? Math.round(item.received / item.total * 100) + "%" : ""}`, () => update(`Package ${item.id}`, () => { packageId = item.id; removalConfirmation = ""; }), packageId === item.id)),
      command("Refresh catalog", refreshPackages),
      ...(["download", "pause", "resume", "cancel"] as const).map((operation) => command(operation, async () => { const value = await map.offline[operation](packageId); recordOffline(value); return value; })),
      button(removalConfirmation === packageId ? "Confirm removal" : "Remove package", () => {
        if (!blankStyle) { report("Switch to blank canvas in Layers before removing the displayed map."); return; }
        if (removalConfirmation !== packageId) { removalConfirmation = packageId; report("Tap Confirm removal to delete the cached package."); return; }
        removalConfirmation = ""; void action("Remove", async () => { const value = await map.offline.remove(packageId); recordOffline(value); return value; });
      }),
      ...packages.filter((item) => item.error).map((item) => m("text", { class: "MapHint" }, item.error)),
    ];
    case "Capture": return [
      m("text", { class: "MapHint" }, "Snapshot includes attribution; native control overlays are not included."),
      command("Capture 800 × 600", async () => { snapshot = await map.takeSnapshot({ width: 800, height: 600 }); return snapshot; }),
      command("Capture viewport", async () => { snapshot = await map.takeSnapshot(); return snapshot; }),
      ...(snapshot ? [m("text", { class: "MapHint" }, `${snapshot.width} × ${snapshot.height}\n${snapshot.uri}`), m("image", { class: "MapSnapshot", src: snapshot.uri, mode: "aspectFit", onerror: () => report("Snapshot saved; this host cannot display its content URI.") })] : []),
    ];
    case "Providers": return [
      m("text", { class: "MapHint" }, "DEMO only: two local places and a straight-line route, not road directions."),
      command("Search North", () => map.search("North")), command("Reverse center", () => map.reverseGeocode(center)),
      command("Mock walking route", async () => { const routes = await map.calculateRoute({ coordinates: [center, north], profile: "walking" }); route = { id: "mock-route", coordinates: routes[0].coordinates, color: "#e26635", width: 6 }; return { demo: true, distance: routes[0].distance, duration: routes[0].duration }; }),
      button("Clear route", () => update("Mock route cleared", () => { route = null; })),
    ];
    default: return [];
  }
}
export function view() {
  return m("view", { class: "MapScreen", oncreate: () => { void Promise.resolve().then(refreshPackages).catch((error: Error) => report(error.message)); } }, [
    m("view", { key: "header", class: "MapHeader" }, [m("text", { class: "MapTitle" }, "Maps / visual lab"), m("text", { class: "MapSubtitle" }, blankStyle ? "Blank canvas · no basemap" : "Chile · offline basemap")]),
    m("view", { key: "map", class: "MapFrame" }, m(Maps, {
      controller: map, initialCamera: { center, zoom: 14 }, packageId, markers, selectedMarkerId,
      cluster: clustered, controls: { compass: true, zoom: true },
      gestures: { scroll: gestures, zoom: gestures, rotate: gestures, pitch: gestures, doubleTap: gestures },
      mapStyle: blankStyle ? { version: 8, sources: {}, layers: [{ id: "canvas", type: "background", paint: { "background-color": "#f1f5f4" } }] } : undefined,
      lines: [...(shapes && markers.length > 1 ? [{ id: "connection", coordinates: markers.map((marker) => marker.position), color: "#167d8d", width: 3 }] : []), ...(route ? [route] : [])],
      circles: shapes ? [{ id: "area", center, radius: 150, color: "#167d8d", opacity: 0.18 }] : [],
      polygons: shapes ? [{ id: "zone", coordinates: [
        [[-71.59, -33.537], [-71.587, -33.537], [-71.587, -33.534], [-71.59, -33.534], [-71.59, -33.537]].map(([longitude, latitude]) => ({ longitude, latitude })),
        [[-71.5895, -33.5365], [-71.5895, -33.535], [-71.588, -33.535], [-71.588, -33.5365], [-71.5895, -33.5365]].map(([longitude, latitude]) => ({ longitude, latitude })),
      ], color: "#e26635", outlineColor: "#ae4a22", opacity: 0.35 }] : [],
      sources: customLayer ? { "test-points": { type: "geojson", data: { type: "FeatureCollection", features: [{ type: "Feature", id: "test-point", properties: { label: "Test point" }, geometry: { type: "Point", coordinates: [-71.5829, -33.5302] } }] } } } : {},
      layers: customLayer ? [{ id: "test-point-layer", type: "circle", source: "test-points", paint: { "circle-color": "#7f52aa", "circle-radius": ["case", ["boolean", ["feature-state", "selected"], false], 18, 8], "circle-stroke-width": 2, "circle-stroke-color": "#ffffff" } }] : [],
      onready: () => { if (!initialized) { initialized = true; report("Map ready. Choose a test below."); } },
      onerror: (error) => report(`${error.code}: ${error.message}`),
      ontap: ({ coordinate }) => report(`Tap: ${coordinate.latitude.toFixed(6)}, ${coordinate.longitude.toFixed(6)}`),
      onlongpress: ({ coordinate }) => report(`Long press: ${coordinate.latitude.toFixed(6)}, ${coordinate.longitude.toFixed(6)}`),
      onmarkertap: ({ id }) => { selectedMarkerId = id; report(`Selected ${id}`); },
      onmarkerdragend: ({ id, marker }) => { markers = markers.map((item) => item.id === id ? marker : item); report(`Dragged ${id}: ${JSON.stringify(marker.position)}`); },
      onfeaturetap: ({ features }) => report(`Features: ${features.map((feature) => feature.id ?? "unnamed").join(", ")}`),
      onclustertap: ({ sourceId, feature, coordinate }) => { void action("Expand cluster", async () => { const children = await map.getClusterChildren(sourceId, feature); const leaves = await map.getClusterLeaves(sourceId, feature); await map.flyTo({ center: coordinate, zoom: await map.getClusterExpansionZoom(sourceId, feature) }); return { children: children.features.length, leaves: leaves.features.length }; }); },
      oncameraidle: (value) => { camera = value; redraw(); },
      onlocationchange: (position) => { gpsText = `GPS: ${position.latitude.toFixed(6)}, ${position.longitude.toFixed(6)} · accuracy ${position.accuracy ?? "unknown"} m`; redraw(); },
      onofflineprogress: recordOffline,
    } satisfies MapsAttributes)),
    m("text", { key: "telemetry", class: "MapTelemetry" }, camera ? `Z ${camera.zoom.toFixed(1)} · B ${camera.bearing.toFixed(0)}° · P ${camera.pitch.toFixed(0)}° · ${camera.center.latitude.toFixed(5)}, ${camera.center.longitude.toFixed(5)}` : "Waiting for camera…"),
    m("view", { key: "tabs", class: "MapTabs" }, tabs.map((name) => button(name, () => { tab = name; removalConfirmation = ""; redraw(); }, tab === name))),
    m("scroll-view", { key: tab, class: "MapPanel", "scroll-orientation": "vertical" }, m("view", { class: "MapPanelContent" }, [
      m("text", { class: "MapSectionTitle" }, tab), m("view", { class: "MapButtons" }, panel()),
      m("text", { class: "MapStatus" }, status), m("text", { class: "MapHint" }, `Recent events\n${events.slice(1).join("\n")}`),
    ])),
  ]);
}
