import m from "mithril-runtime";
import { Maps, createMapController } from "lynx-android-plugins/maps";
import type { MapFeature, MapsAttributes } from "lynx-android-plugins/maps";

const map = createMapController();
const point: MapFeature = { type: "Feature", id: "place", geometry: { type: "Point", coordinates: [-71.584904, -33.532290] }, properties: { name: "Place" } };
/** Swap this component into the example to try inline GeoJSON and feature selection. */
export function advancedView() {
  return m(Maps, {
    controller: map,
    initialCamera: { center: { latitude: -33.532290, longitude: -71.584904 }, zoom: 14 },
    sources: { places: { type: "geojson", data: { type: "FeatureCollection", features: [point] } } },
    layers: [{ id: "places-layer", type: "circle", source: "places", paint: { "circle-color": "#e11d48", "circle-radius": ["case", ["boolean", ["feature-state", "selected"], false], 14, 7] } }],
    onfeaturetap: ({ features }) => { if (features.some((feature) => feature.id === "place")) void map.setFeatureState({ sourceId: "places", featureId: "place" }, { selected: true }).catch((error) => console.error(error)); },
  } satisfies MapsAttributes);
}
