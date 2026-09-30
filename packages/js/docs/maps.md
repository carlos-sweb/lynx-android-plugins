# Maps for Lynx Android

Maps is a native MapLibre Android component for Mithril/Lynx applications. It is not a WebView, Google Maps service, or JavaScript tile renderer. Import it from `lynx-android-plugins/maps`.

This guide describes the local 0.2.0 implementation. Building it does not publish it to npm or Maven Central. Use matching JavaScript and native builds; older native artifacts do not implement the expanded controller API.

## Requirements and installation

- Android API 24+, a lifecycle-aware Activity, Lynx 4.1.0 and MapLibre Android 11.8.0.
- A host registering `LynxMapsPlugin`, and a parent with an explicit size.
- `mithril-runtime` and `mithril-lynx`. The controller uses `vnode.dom.invoke()` when available and falls back to Lynx SelectorQuery for older renderers.
- Lynx Go cannot render this third-party native element. Build the generated Android host.

Select `--android-plugins maps` when creating a project, or run `create-mithril-lynx add-android-plugin maps` from an existing generated project. The generator preserves existing managed configuration and creates `android/maps.json` if missing. `bun run android:prepare` copies that file to the Android host. Generated build scripts run prepare before building.

For a manually assembled host:

```kotlin
LynxMapsPlugin.register(builder)
```

Forward `onRequestPermissionsResult` to `LynxMapsPlugin.onRequestPermissionsResult(requestCode, permissions, grantResults)`. The generated registry and the aggregate plugin do this automatically. Viewing a map never requests location permission; `map.location.start()` does.

## Quick start

```ts
import m from "mithril-runtime";
import { Maps, createMapController } from "lynx-android-plugins/maps";

const map = createMapController();
const center = { latitude: -33.532290, longitude: -71.584904 };

export function view() {
  return m("view", { style: { width: "100%", height: "100%" } },
    m(Maps, {
      controller: map,
      initialCamera: { center, zoom: 14 },
      markers: [{ id: "destination", position: center, title: "Destination" }],
      controls: { compass: true, zoom: true },
      onmarkertap: ({ id }) => console.log(id),
      onerror: (error) => console.error(error.code, error.message),
    }),
  );
}
```

Coordinates supplied as objects use `{ latitude, longitude }` in degrees. GeoJSON uses `[longitude, latitude]`. Distances and circle radii use meters. Camera pitch/bearing use degrees, animation duration uses milliseconds, and screen points/padding use Android density-independent logical pixels. Snapshot dimensions use physical pixels.

Centering the map does **not** add a marker. `initialCamera` applies once; ordinary redraws do not reset a camera moved by the user. A changed `camera` value moves the camera declaratively. Keep the same controller for the component's lifetime. Use separate controllers for separate maps.

## Component attributes

| Attribute | Type / default | Behavior |
| --- | --- | --- |
| `controller` | `MapController`, optional | Allows imperative commands; omitted creates an internal controller. |
| `initialCamera` | `CameraOptions` | Initial center, zoom, bearing, pitch and padding. |
| `camera` | `CameraOptions` | Moves the camera when its serialized value changes. |
| `packageId` | `string`, first configured package | Selects a PMTiles archive from the catalog. |
| `mapStyle` | `MapStyle` | Complete Style Specification v8 JSON; replaces the packaged base style and can run without a PMTiles catalog. |
| `markers` | `MapMarker[]`, `[]` | Stable IDs, positions, labels, icons and drag behavior. |
| `lines` | `MapLine[]`, `[]` | Coordinate sequences with color, width and opacity. |
| `polygons` | `MapPolygon[]`, `[]` | Rings: first is exterior, remaining rings are holes. Rings close automatically. |
| `circles` | `MapCircle[]`, `[]` | Geodesic circles approximated by 64 segments. |
| `sources` | `Record<string, MapSource>` | Additional GeoJSON/vector/raster/raster-dem sources. |
| `layers` | `MapLayer[]` | Ordered additional style layers, filters and expressions. |
| `images` | `Record<string, string>` | Icon ID to PNG path in Android app assets, e.g. `{ pin: "icons/pin.png" }`. |
| `cluster` | `boolean` or `{ radius?, maxZoom? }`, false | Marker clustering; defaults radius 50 and max zoom 14. |
| `selectedMarkerId` | `string` | Highlights a marker and opens its text callout. |
| `minZoom`, `maxZoom` | `number`, 0 / 22 | Camera zoom limits. |
| `maxBounds` | `Bounds` | Restricts camera target; omitting removes the restriction. |
| `gestures` | `{ scroll?, zoom?, rotate?, pitch?, doubleTap? }` | Each gesture defaults to enabled. |
| `controls` | `{ compass?, zoom? }` | Compass defaults on; zoom buttons default off. Attribution remains visible. |
| `userLocation` | `LocationPosition` or `null` | Display an externally supplied fix, without asking for GPS permission. |
| `style`, `class` | Lynx styling attributes | Defaults to filling the parent. |
| `latitude`, `longitude`, `zoom`, `variant` | Legacy shorthand | Coordinates accept numeric strings; `variant` supports only `"full"`. |

Item IDs must be nonempty and unique across markers, lines, polygons and circles. Source/layer IDs beginning with `__maps_` are reserved. Keep content declarative: update the arrays to add/remove/change items. No separate imperative marker store is maintained.

`MapMarker` has `{ id, position, title?, description?, color?, icon?, draggable? }`. Icon IDs reference `images` or the style sprite. Default markers are blue circles with white outlines. Callouts contain title/description text, follow the marker as the camera moves, and close when tapped. Arbitrary Mithril trees inside native markers/callouts are not supported.

Long-press a draggable marker and move it. `onmarkerdragend` reports the proposed position. Update your `markers` array using the returned marker to accept the move; otherwise the authoritative declarative position is retained.

`MapLine` has `{ id, coordinates, color?, width?, opacity? }`. `MapPolygon` has `{ id, coordinates: Coordinate[][], color?, outlineColor?, opacity? }`. `MapCircle` has `{ id, center, radius, color?, outlineColor?, opacity? }`.

Changing visual content reloads the composed native style; unchanged content does not. Camera position is preserved. Loading temporarily makes controller rendering/query methods unavailable. This implementation is intended for normal application updates, not high-frequency replacement of very large GeoJSON collections.

## Camera controller

Commands return Promises and reject with `MapsError`. `ready(timeout = 60000)` waits for a loaded style, not merely a mounted surface. It can time out while the user has not downloaded the selected package. Calls before mounting fail immediately; there is no unbounded queue.

```ts
await map.ready();
await map.flyTo({ center: { latitude: -33.53, longitude: -71.58 }, zoom: 15, duration: 800 });
await map.fitBounds({
  southwest: { latitude: -33.54, longitude: -71.59 },
  northeast: { latitude: -33.52, longitude: -71.57 },
}, { padding: { top: 40, bottom: 40, left: 40, right: 40 } });
const camera = await map.getCamera();
console.log(camera.center, camera.bounds);
```

| Method | Result / behavior |
| --- | --- |
| `ready(timeout?)` | `Promise<void>`; bounded readiness wait. |
| `getCamera()` | `CameraState`: center, zoom, bearing, pitch, padding, visible bounds. |
| `jumpTo(options)` | Immediate camera movement. |
| `easeTo(options)` | Smooth interpolation. |
| `flyTo(options)` | MapLibre flight animation. |
| `fitBounds(bounds, options?)` | Fits southwest/northeast bounds with padding, bearing and pitch. |
| `zoomTo(zoom, options?)` | Animated zoom. |
| `stop()` | Cancels camera transitions; affected animation Promises reject `CANCELLED`. |
| `project(coordinate)` | `ScreenPoint`, relative to the map. |
| `unproject(point)` | `Coordinate`, relative to the map. |

Camera options are `{ center?, zoom?, bearing?, pitch?, padding?, duration? }`. Native camera zoom validation accepts 0–25.5, constrained by map preferences; pitch accepts 0–60. Duration defaults to 500 ms and must be 0–30000 ms. `fitBounds` derives center/zoom from its bounds rather than using optional center/zoom values. Bounds must be ordered southwest to northeast; antimeridian-crossing bounds are rejected rather than silently fitting the wrong hemisphere. Commands resolve when animations finish, not when they start.

## Events

Public handlers use Mithril `on…` names, **not** `bindtap`. The component unwraps native event details.

| Handler | Payload |
| --- | --- |
| `onready` | No arguments; current style is loaded, including after a content/style reload. |
| `onerror` | `MapsError` with `code` and `message`. |
| `ontap`, `onlongpress` | `{ coordinate, point }`. |
| `onmarkertap`, `onmarkerdragend` | `{ id, marker, coordinate, point }`. |
| `onfeaturetap` | `{ features, coordinate, point }`; hits can include base-map features. |
| `onclustertap` | `{ sourceId, feature, coordinate, point }`. |
| `oncamerachange`, `oncameraidle` | `CameraState`. |
| `onlocationchange` | `LocationPosition`. |
| `onofflineprogress` | `OfflineStatus`. |

Continuous camera notifications are throttled to ten per second, with an additional final state on idle. Marker/feature/cluster handlers do not suppress the general map tap handler. Async application work may require `redraw()` from `mithril-lynx/mount-redraw` when updating application state.

## Clustering and feature queries

```ts
async function expandCluster(sourceId: string, feature: import("lynx-android-plugins/maps").MapFeature) {
  const zoom = await map.getClusterExpansionZoom(sourceId, feature);
  const children = await map.getClusterChildren(sourceId, feature);
  const leaves = await map.getClusterLeaves(sourceId, feature, 100, 0);
  return { zoom, children, leaves };
}
```

- `queryRenderedFeatures({ point?, rectangle?, layerIds?, filter? })` returns visible GeoJSON features. With no point/rectangle it queries the viewport. Rectangle is `{ left, top, right, bottom }`.
- `querySourceFeatures(sourceId, filter?)` queries loaded GeoJSON source features; this is not a search of an entire country's archive.
- `getClusterExpansionZoom(sourceId, feature)` returns the expansion zoom.
- `getClusterChildren(sourceId, feature)` and `getClusterLeaves(sourceId, feature, limit = 100, offset = 0)` return FeatureCollections. Limits are 1–10000; offset is nonnegative.
- Cluster methods require a clustered GeoJSON source and a cluster feature obtained from a current event/query. Do not retain cluster IDs across data updates.

For built-in marker clustering, use the `sourceId` supplied by `onclustertap`. Custom GeoJSON clustering is configured in its source using MapLibre source options.

## Advanced sources, layers and feature state

Sources support GeoJSON, vector tiles, raster imagery and raster-dem. Layers support fill, line, circle, symbol, raster, heatmap, fill-extrusion, hillshade and background, subject to MapLibre Android 11.8 support. Layer order follows your array; built-in areas, lines, markers and location are drawn above additional layers in that order.

```ts
const sources: Record<string, import("lynx-android-plugins/maps").MapSource> = {
  places: {
    type: "geojson",
    data: { type: "FeatureCollection", features: [{
      type: "Feature", id: "place", properties: { name: "Place" },
      geometry: { type: "Point", coordinates: [-71.584904, -33.532290] },
    }] },
  },
};
const layers: import("lynx-android-plugins/maps").MapLayer[] = [{
  id: "places-layer", type: "circle", source: "places",
  paint: { "circle-color": "#e11d48", "circle-radius": ["case", ["boolean", ["feature-state", "selected"], false], 14, 7] },
}];
async function selectPlace() {
  await map.setFeatureState({ sourceId: "places", featureId: "place" }, { selected: true });
}
```

Render `sources` and `layers` as component attributes. `setFeatureState(target, state)` merges temporary state and `removeFeatureState(target)` clears it. SDK 11.8 has no public Android setter for native feature state; the wrapper emulates expressions using reserved properties for **owned inline GeoJSON features with stable IDs only**. Remote GeoJSON and vector-source state updates are unsupported and reject `NOT_SUPPORTED`. State updates reload the style; they are not a high-frequency GPU state API.

Custom styles/providers must supply matching source-layer schemas, glyphs, sprites and imagery. The included style expects the Tilemaker example schema. Satellite imagery requires an authorized imagery source. Building extrusion requires heights/building data. True 3D terrain and globe projection are not included. Do not assume every MapLibre GL JS feature exists in this Android version.

## Location and permissions

```ts
async function startFollowing() {
  await map.ready();
  await map.location.setTracking("follow");
  const status = await map.location.start({ highAccuracy: true, interval: 1000, minDistance: 0 });
  console.log(status.permission);
}
```

| Method | Behavior |
| --- | --- |
| `location.start(options?)` | Requests foreground permission if necessary and subscribes to Android location updates. Resolves with status once tracking starts, not with the first fix. |
| `location.stop()` | Stops updates and returns status; does not remove the last visible fix. |
| `location.getStatus()` | `{ running, permission, providerEnabled, tracking }`. Permission is `none`, `approximate` or `precise`. |
| `location.setTracking(mode)` | `none`, `follow`, `heading` or `course`. Heading uses the device rotation sensor; course uses the reported location bearing. |
| `location.setPosition(positionOrNull)` | Draws/hides an external fix without permission or enabling device GPS. |

Location options default to low accuracy, a 1000 ms interval and zero minimum distance. Minimum interval is 250 ms. Approximate-only permission never starts a fine-only GPS provider. Disabled providers fail `POSITION_UNAVAILABLE`. Heading requires a rotation-vector sensor.

Updates stop when the host pauses or the component detaches; call `start()` again when needed after returning to the foreground. No background service, background permission or Google Play Services is used. `LocationPosition` contains latitude/longitude and optional accuracy, timestamp, bearing and speed. Native timestamps are Unix milliseconds, accuracy is meters, and speed is meters/second.

External location updates through `userLocation` or `setPosition` display a fix; they do not start a native subscription. For device fixes, handle `onlocationchange`.

## Offline packages

Configure developer-owned immutable HTTPS PMTiles files in `android/maps.json`:

```json
{
  "packages": [
    {
      "id": "chile",
      "label": "Chile",
      "url": "https://your-domain.example/maps/chile.pmtiles",
      "sha256": "REPLACE_WITH_64_HEXADECIMAL_CHARACTERS",
      "version": "2026-09",
      "size": 310920380
    }
  ]
}
```

The placeholder checksum is deliberately not valid. Replace it with the actual SHA-256. IDs allow 1–80 ASCII letters, digits, underscores or hyphens; IDs must be unique. `label` and `size` are optional. `size` is a download/storage hint in bytes. The legacy `{ url, sha256, version }` format remains accepted as package ID `chile`. The default is the first package; select another with `packageId`.

```ts
async function downloadChile() {
  const unsubscribe = map.offline.subscribe((progress) => console.log(progress.state, progress.received, progress.total));
  const status = await map.offline.download("chile");
  console.log(status.state);
  return unsubscribe;
}
```

`download()` starts work and resolves with status immediately. Subscribe or handle `onofflineprogress` for completion; it is **not** a Promise for the complete archive download. Offline methods work after mounting, even before the map style is ready.

| Method | Behavior |
| --- | --- |
| `offline.list()` | Returns catalog statuses. |
| `offline.getStatus(id)` | Status for one configured package. |
| `offline.download(id)`, `offline.resume(id)` | Starts/resumes a whole-archive transfer. |
| `offline.pause(id)` | Retains partial bytes; does not interrupt verification. |
| `offline.cancel(id)` | Stops the transfer and discards partial bytes; retains completed archives. |
| `offline.remove(id)` | Removes a completed/partial configured archive, refusing active downloads or archives displayed by any map. |
| `offline.subscribe(listener)` | Returns an unsubscribe function. Call it when no longer needed. |

Status contains `{ id, version, state, received, total, error? }`. States are `missing`, `downloading`, `paused`, `verifying`, `ready` and `error`. Zero `total` means unknown size. Transfer failures are reported in progress/status rather than rejecting an already-resolved `download()` call.

Files live in app-private storage. Downloads use HTTPS redirects only, range requests when supported, SHA-256 and PMTiles v3 header verification. A server returning 200 instead of 206 restarts the partial download. Invalid range replies fail. Archives become active only after verification and an atomic same-directory rename. Existing versions remain intact if a new version fails; older version files are not automatically removed. Immutable URLs/checksums are required for safe resumption. Downloads pause on component detach; there is no persistent background-download service.

The selected archive opens automatically after download; reopening a cached archive verifies it before use. Countries are a data choice, not a fixed list in the API. The supplied build tool produces Chile; other countries need compatible archives and styles.

PMTiles offline packs are not managed by MapLibre's offline-region API. Do not bulk-download OpenStreetMap public tile servers. Host your own permitted archives or use an authorized provider. Glyphs, sprites and remote raster sources in a custom style must also be available offline if complete offline operation is required.

## Search and routes: explicit providers

`createMapController({ geocoding, routing })` accepts independent providers:

```ts
import { createMockMapProviders } from "lynx-android-plugins/maps";

const demoMap = createMapController(createMockMapProviders([
  { id: "home", label: "Home", position: { latitude: -33.532290, longitude: -71.584904 } },
]));
async function searchAndRoute() {
  const places = await demoMap.search("Home");
  const nearest = await demoMap.reverseGeocode({ latitude: -33.53, longitude: -71.58 });
  const routes = await demoMap.calculateRoute({
    coordinates: [{ latitude: -33.53, longitude: -71.58 }, { latitude: -33.54, longitude: -71.59 }],
    profile: "walking",
  });
  return { places, nearest, routes };
}
```

- `GeocodingProvider.search(query, { signal? })` and `reverseGeocode(position, { signal? })` return `SearchResult[]`: ID, label, position, optional bounds/properties.
- `RoutingProvider.calculateRoute({ coordinates, profile?, signal? })` returns `RouteResult[]`: ID, coordinates, distance in meters, duration in seconds and optional instructions.
- Profiles are `walking`, `cycling` or `driving`. Providers decide supported profiles and must honor cancellation signals and their own service policies.
- Missing providers reject `NOT_SUPPORTED`. No public service endpoint is selected implicitly and no billing model is imposed.

`createMockMapProviders()` is a deterministic testing helper. Its routes are straight lines, **not road routes or turn-by-turn navigation**. For production, implement the interfaces against your chosen authorized service or separately integrated engine, then draw the returned coordinates using `lines`. Rendering a route does not itself calculate one.

## Snapshots and attribution

```ts
async function captureMap() {
  const snapshot = await map.takeSnapshot({ width: 800, height: 600 });
  console.log(snapshot.uri);
  return snapshot;
}
```

Returns `{ uri, width, height }` with an app-owned `content://` PNG URI, not base64. Default size is the native map surface. Dimensions must be at least 256×128 pixels, no more than 4096 per side, with a maximum of four megapixels. The current rendered surface is scaled to the requested dimensions, not rerendered with a different viewport. Native popup/toolbar overlays are not included. A wrapped MapLibre/data-source attribution footer is added; an image too small for its provider attribution is rejected. Snapshots are temporary cache files; copy them for durable storage and grant URI read permission when sharing externally.

Keep OpenStreetMap and provider attributions visible. The included OSM data uses ODbL, MapLibre uses BSD-2-Clause, and packaged Noto Sans glyphs use SIL OFL. Custom providers may impose additional terms. Software being open source does not grant unrestricted access to any third-party tile server or dataset.

## Errors and compatibility

All controller failures use `MapsError.code` and `MapsError.message`. Handle:

| Code | Meaning |
| --- | --- |
| `INVALID_ARGUMENT` | Invalid coordinates, geometry, camera options, IDs or operation parameters. |
| `MAP_NOT_READY` | Not mounted yet or current style not loaded. |
| `MAP_UNMOUNTED` | Component removed; outstanding operations reject. |
| `CONTROLLER_IN_USE` | One controller was attached to two mounted maps. |
| `PERMISSION_DENIED` | Android permission unavailable/denied/revoked. |
| `POSITION_UNAVAILABLE` | Location service/provider unavailable. |
| `DOWNLOAD_FAILED`, `CHECKSUM_MISMATCH`, `INSUFFICIENT_STORAGE` | Download/storage validation failures; also inspect offline status. |
| `NOT_SUPPORTED` | Missing provider or unavailable capability. |
| `CANCELLED` | Animation/provider operation cancelled. |
| `TIMEOUT` | Bounded operation/readiness wait expired. |
| `NATIVE_ERROR` | Other native/bridge failure. |

Legacy `m(Maps, { latitude, longitude, zoom, variant: "full" })` keeps its original implicit center marker. New camera attributes never create implicit markers. Providing `markers: []` suppresses the legacy marker. Prefer numeric object coordinates in new code.

When changing a custom style, ensure all advanced layers reference existing source IDs and source-layer names. Rebuild Android when changing asset icons, the package catalog or native dependencies. Changing only JavaScript cannot add native capabilities to Lynx Go or an older APK.

## Troubleshooting and validation

- **Blank native area:** verify host registration, parent dimensions and matched native/JS builds.
- **Map not configured:** replace catalog placeholders and run `android:prepare` before rebuilding, or provide a complete `mapStyle`.
- **No permission dialog:** viewing/centering a map does not request GPS. Call `location.start()` from a user action; an already-granted permission needs no new dialog.
- **Marker returns after dragging:** accept its returned position in the declarative `markers` array.
- **Missing labels/icons:** check glyph ranges, font stack, asset image paths and sprite resources.
- **Repeated onready:** visual content changes reload the style. Do not start recurring work unconditionally on every ready event.

Repository checks:

```sh
bun run check
bun run test:packages
bun run --filter @lynx-android-plugins/example-maps build
./gradlew :android-maps:testDebugUnitTest :demo-host:assembleDebug
```

Automated tests cover controller isolation/readiness/unmounts, both native invocation paths, typed errors, subscription cleanup, providers, serialization, GeoJSON composition, clusters, geometry, archive checksums/headers, and HTTP range-response validation. Generator tests additionally cover maps-only scaffolding, preparation, post-install add/remove, configuration preservation and permission forwarding. Hardware acceptance must additionally cover native rendering/taps/drags, precise/approximate/denied/revoked permissions, disabled GPS, lifecycle transitions, actual interrupted/resumed HTTPS transfers, insufficient storage, airplane mode and snapshot URI sharing. Compilation/unit tests alone do not certify those device scenarios.

## Official references

- [Lynx custom native elements, events and UI methods](https://lynxjs.org/guide/custom-native-component.html)
- [MapLibre Android GeoJSON guide](https://maplibre.org/maplibre-native/android/examples/geojson-guide/)
- [MapLibre Android PMTiles support and offline limitations](https://maplibre.org/maplibre-native/android/examples/data/PMTiles/)
- [MapLibre Style Specification](https://maplibre.org/maplibre-style-spec/)
- [MapLibre terrain support matrix](https://maplibre.org/maplibre-style-spec/terrain/)
- [Android foreground location permissions](https://developer.android.com/develop/sensors-and-location/location/permissions/runtime)
- [Android FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider)
- [OpenStreetMap attribution guidelines](https://osmfoundation.org/wiki/Licence/Attribution_Guidelines)
