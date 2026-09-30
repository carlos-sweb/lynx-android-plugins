import { MapsError } from "./maps-types";
import type { Coordinate, MapProviders, SearchResult } from "./maps-types";

/** Deterministic test/demo providers. Routes are straight lines, never road navigation. */
export function createMockMapProviders(places: SearchResult[] = []): Required<MapProviders> {
  const check = (signal?: AbortSignal) => { if (signal?.aborted) throw new MapsError("CANCELLED", "The provider request was cancelled."); };
  const distance = (first: Coordinate, second: Coordinate) => {
    const rad = Math.PI / 180;
    const latitude = Math.sin((second.latitude - first.latitude) * rad / 2);
    const longitude = Math.sin((second.longitude - first.longitude) * rad / 2);
    return 2 * 6371008.8 * Math.asin(Math.sqrt(latitude ** 2 + Math.cos(first.latitude * rad) * Math.cos(second.latitude * rad) * longitude ** 2));
  };
  return {
    geocoding: {
      async search(query, options) { check(options?.signal); return places.filter((place) => place.label.toLowerCase().includes(query.toLowerCase())); },
      async reverseGeocode(position, options) { check(options?.signal); return [...places].sort((a, b) => distance(position, a.position) - distance(position, b.position)).slice(0, 1); },
    },
    routing: {
      async calculateRoute(request) {
        check(request.signal);
        if (request.coordinates.length < 2) throw new MapsError("INVALID_ARGUMENT", "A route needs at least two coordinates.");
        const length = request.coordinates.slice(1).reduce((total, point, index) => total + distance(request.coordinates[index], point), 0);
        return [{ id: "mock-straight-line", coordinates: request.coordinates, distance: length, duration: length / 1.4 }];
      },
    },
  };
}
