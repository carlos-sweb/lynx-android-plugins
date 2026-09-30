# lynx-android-plugins

Typed JavaScript facades for the Android modules in this repository. Install this package in a Lynx app whose Android host registers the matching native connector.

```bash
bun add lynx-android-plugins
```

Import only the capability you need:

```ts
import { gps } from "lynx-android-plugins/geolocation";

const position = await gps.get({ highAccuracy: true });
console.log(position.latitude, position.longitude);
```

Other connector subpaths are `battery`, `camera`, `device`, `network`, and `vibration`. Each exports a named capability object and `ConnectorError`. Every connector capability has `isAvailable()` to check native registration. Methods return Promises and reject with a coded `ConnectorError` on failure.

`gps.get()` requests one foreground position and Android location permission when needed. The provider can be GPS or network. The published native module does not expose permission/provider status separately, so this package does not offer `gps.getStatus()` or continuous tracking.

The separate `lynx-android-plugins/maps` subpath exports a Mithril component for the native Android map surface:

Read the [complete Maps API guide](docs/maps.md) for controllers, events, layers,
foreground location, offline catalogs, snapshots and optional providers.

```ts
import m from "mithril-runtime";
import { Maps } from "lynx-android-plugins/maps";

m(Maps, { latitude: -33.532290, longitude: -71.584904, variant: "full" });
```

It needs an Android host with the `lynx-android-maps` Maven artifact, a sized parent element, and a configured `android/maps.json` containing an HTTPS archive URL, SHA-256, and version. The app prompts for the download in its native map surface.
