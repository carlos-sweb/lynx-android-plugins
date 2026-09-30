import { createMapController, createMockMapProviders } from "lynx-android-plugins/maps";

/** Demo only: a straight-line mock route is not a road-routing service. */
export async function providerExample() {
  const home = { latitude: -33.532290, longitude: -71.584904 };
  const map = createMapController(createMockMapProviders([{ id: "home", label: "Home", position: home }]));
  const places = await map.search("Home");
  const nearest = await map.reverseGeocode(home);
  const routes = await map.calculateRoute({ coordinates: [home, { latitude: -33.527290, longitude: -71.584904 }], profile: "walking" });
  return { places, nearest, routes };
}
