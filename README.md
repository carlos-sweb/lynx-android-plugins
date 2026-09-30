# lynx-android-plugins

Native Android connectors for Lynx. This repository is intentionally
Android-only: Lynx remains multi-platform, but these modules depend on Android
APIs and do not attempt to create a misleading cross-platform abstraction.

## Module layout

Every capability is an independent Android library. Add only the modules an
application needs; this keeps Android manifest permissions and runtime code
bounded to the selected capabilities.

| Gradle module | Capability | Manifest contribution |
| --- | --- | --- |
| `:android-core` | Shared Lynx event bridge | None |
| `:android-battery` | Battery level and charging state | None |
| `:android-camera` | External camera capture | `FileProvider` only |
| `:android-device` | Non-identifying device information | None |
| `:android-geolocation` | One-shot foreground location | Coarse and fine location |
| `:android-network` | Network snapshot | None |
| `:android-vibration` | Bounded vibration | `VIBRATE` |
| `:android-maps` | Native maps, geometries, location and offline archives | `INTERNET` for downloads; foreground location only on request |
| `:android-all` | Opt-in convenience aggregate | All feature contributions |

`android-all` preserves the original all-in-one registration API for the demo
host. Production applications should depend on individual feature modules.

## Maven coordinates

Version `0.1.0` is published; the Maps integration is prepared locally as
`0.2.0` and is not yet on Maven Central or npm. The coordinates below are for
the next release. Test them with `mavenLocal()` before publishing. Releases to
Maven Central use the manual Portal workflow documented below.

| Gradle module | Maven artifact |
| --- | --- |
| `:android-core` | `io.github.carlos-sweb:lynx-android-core:0.2.0` |
| `:android-battery` | `io.github.carlos-sweb:lynx-android-battery:0.2.0` |
| `:android-camera` | `io.github.carlos-sweb:lynx-android-camera:0.2.0` |
| `:android-device` | `io.github.carlos-sweb:lynx-android-device:0.2.0` |
| `:android-geolocation` | `io.github.carlos-sweb:lynx-android-geolocation:0.2.0` |
| `:android-network` | `io.github.carlos-sweb:lynx-android-network:0.2.0` |
| `:android-vibration` | `io.github.carlos-sweb:lynx-android-vibration:0.2.0` |
| `:android-maps` | `io.github.carlos-sweb:lynx-android-maps:0.2.0` |
| `:android-all` | `io.github.carlos-sweb:lynx-android-plugins:0.2.0` |

For local consumption after running `./gradlew publishToMavenLocal`:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("io.github.carlos-sweb:lynx-android-geolocation:0.2.0")
}
```

## Manual Maven Central release

This repository deliberately prepares and uploads a deployment but does not
publish it automatically. The last publish action remains a deliberate action
in the Maven Central Portal.

Before a release, create a Maven Central Portal user token and upload the
corresponding public PGP key to a public key server. Keep the private key and
Portal token out of the repository. The uploader reads a single `<server>`
entry from the ignored `maven.xml` by default; environment variables remain an
alternative.

```sh
export GPG_PRIVATE_KEY="$(gpg --armor --export-secret-keys <key-id>)"
export GPG_PASSPHRASE="<private-key-passphrase>"
export CENTRAL_USERNAME="<portal-token-username>"
export CENTRAL_PASSWORD="<portal-token-password>"

bun run central:bundle
bun run central:upload
```

When the release key is already in the local GPG keyring, do not export it.
Use the local GPG command instead:

```sh
export GPG_USE_COMMAND=true
bun run central:bundle
```

For a local `maven.xml`, use this shape (the file is ignored by Git):

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>portal-token-username</username>
      <password>portal-token-password</password>
    </server>
  </servers>
</settings>
```

`central:bundle` stages all nine Android artifacts in a temporary local
repository, produces source and Javadoc JARs, signs every Maven artifact, adds
checksums, and writes a Portal bundle to
`build/central-bundle/lynx-android-plugins-<version>.zip`.

`central:upload` submits the bundle with `publishingType=USER_MANAGED`. It
prints the deployment ID without publishing it. Check validation with:

```sh
bun run central:status -- <deployment-id>
```

When the deployment status is `VALIDATED`, open it in the Maven Central Portal
and use its Publish action. Do not run the upload script with an account
password: `CENTRAL_USERNAME` and `CENTRAL_PASSWORD` must be the two values of a
Portal user token.

## Included plugins

| Lynx module | Initial operation | Permission / boundary |
| --- | --- | --- |
| `LynxBatteryPlugin` | `getStatus()` | None; level, charging state, and known timing state |
| `LynxCameraPlugin` | `takePhoto()` | Launches the camera application; returns a `content://` URI, never image bytes |
| `LynxDevicePlugin` | `getInfo()` | None; does not expose an ID, IMEI, serial number, or advertising ID |
| `LynxGeolocationPlugin` | `getCurrentPosition()` | Foreground approximate/precise location with a runtime permission request |
| `LynxNetworkPlugin` | `getInfo()` | Transport, validated connectivity, and metering snapshot; no SSID/BSSID/IP address |
| `LynxVibrationPlugin` | `vibrate()` / `cancel()` | `VIBRATE`; a single vibration is capped at 10 seconds |
| `LynxMapsPlugin` | `<lynx-android-map>` custom element | Explicit verified HTTPS PMTiles download, then offline native rendering |

The modules do not return Kotlin `Promise` objects directly. Each call has a
`requestId` and returns through `GlobalEventEmitter`, which gives immediate
calls, permission requests, and Activity results one consistent contract.

## JavaScript facades

The `packages/js` directory contains one JavaScript/TypeScript package with
seven import subpaths. The six connector subpaths hide the native request ID and event
listener, returns a typed Promise, and exports `ConnectorError` with a `code`
field. Those six connector APIs work with the Android modules already
published as Maven version `0.1.0`. Maps requires the new `0.2.0` native AAR.

| Import path | API |
| --- | --- |
| `lynx-android-plugins/battery` | `battery.get()` |
| `lynx-android-plugins/camera` | `camera.takePhoto()` |
| `lynx-android-plugins/device` | `device.get()` |
| `lynx-android-plugins/geolocation` | `gps.get({ highAccuracy?: boolean })` |
| `lynx-android-plugins/network` | `network.get()` |
| `lynx-android-plugins/vibration` | `vibration.vibrate(durationMs)`, `vibration.cancel()` |
| `lynx-android-plugins/maps` | `m(Maps, { controller?, initialCamera?, markers?, ... })`; [full API](docs/maps.md) |

Every facade also offers `isAvailable()` to check whether the current Android
host registered its native module. For example:

```ts
import { gps } from "lynx-android-plugins/geolocation";

try {
  const position = await gps.get({ highAccuracy: true });
  console.log(position.latitude, position.longitude);
} catch (error) {
  console.log(error instanceof Error ? error.message : String(error));
}
```

`gps.get()` requests one foreground position and prompts for Android location
permission when required. Its provider can be GPS or network. The existing
native module cannot report permission and provider status separately, so
there is no `gps.getStatus()` yet. Continuous tracking (`start`/`stop`) is also
outside this one-shot API.

Build the npm package locally with `bun run build:packages`. Its `dist/`
directory contains seven bundled ESM entrypoints and TypeScript declarations.
npm publication is manual; inspect its contents with `npm pack --dry-run`.

For the first npm release, run `bun run check` and `bun run build:examples`,
then publish the single package from `packages/js`. `prepack` rebuilds its
JavaScript and declarations:

```sh
cd packages/js
npm pack --dry-run
npm publish --access public
```

npm may request an OTP. Publish this package before releasing a
`create-mithril-lynx` version that installs it automatically.

## Offline Maps

See the dedicated [Maps API and Android integration guide](docs/maps.md) for
camera commands, multiple markers, clustering, geometries, advanced layers,
foreground location, offline catalogs, snapshots, providers and errors.

The Maps API is a native Lynx element backed by MapLibre Android, not a web
map or a Google Maps service. It renders a Chile PMTiles archive from the
app's private storage with locally packaged style and Noto Sans glyphs. The
archive itself is not committed to Git or bundled into the APK. The host shows
an explicit **Download Chile map** button until the archive is installed; after
the download, panning and zooming work offline. No location permission or
Google Maps key is required for viewing maps. Explicit `map.location.start()`
requests foreground location permission. The legacy coordinates position a marker; they
do not read the device's GPS.

```ts
import m from "mithril-runtime";
import { Maps } from "lynx-android-plugins/maps";

export function view() {
  return m("view", { class: "MapFrame" },
    m(Maps, { latitude: -33.532290, longitude: -71.584904, zoom: 14, variant: "full" })
  );
}
```

Give the parent a real width and height (`.MapFrame { width: 100%; height:
100%; }`). `variant: "full"` fills that parent in place; it does not launch
another Activity. The only supported variant in this first version is `full`.
`Maps` is Android-only and needs an Android host with `LynxMapsPlugin` registered;
Lynx Go cannot render this custom native element.

Build a map file with Docker or Podman and an OpenStreetMap extract:

```sh
bun tools/build-chile-map.mjs --url https://your-domain.example/maps/chile.pmtiles --version 2026-09
```

The script fetches Geofabrik's Chile extract once, produces
`build/maps/chile.pmtiles` and a SHA-256-pinned `build/maps/maps.json`, and
requires substantial disk space and time. Upload `chile.pmtiles` to **your**
HTTPS host; it must be accessible by the app without authentication. Copy the
generated `maps.json` into the app's `android/maps.json`, then run
`bun run android:prepare` or any build. For an existing `.osm.pbf` file, place
it inside `build/maps/` and pass its path with `--input`. The checked-in
`examples/maps` bundle demonstrates the UI; the host must also be configured
with `android/maps.json`.

The builder uses a pinned Tilemaker image and its example vector schema,
covering roads, buildings, waterways, and place names at zoom levels 4–14.
It does not include a global ocean coastline layer. Higher zoom levels
overzoom the local data. Glyph assets cover Basic Latin, Latin-1, Latin Extended-A, and
general punctuation, including Spanish accents. Regenerate them with
`bun tools/fetch-map-glyphs.mjs` when needed.

The downloader requires HTTPS, checks every redirect remains HTTPS, verifies
the expected SHA-256 and PMTiles header, and saves the archive in app-private
storage. The renderer does not use remote glyphs or tile endpoints. Keep the
visible OpenStreetMap attribution when modifying the style. The bundled Noto
Sans glyphs retain their SIL Open Font License in the AAR.

## Quick start

```sh
bun install
cd examples/geolocation
bun run dev
```

The development server shows a QR code for the bundle. You can scan it with
Lynx Go to inspect every example screen. However, **Lynx Go does not contain
third-party native modules**, so pressing a button shows the explicit fallback
message. This is not an example limitation: the application binary that runs a
bundle must register its Android modules.

To test a real plugin, prepare the selected bundle and build the demonstration
host:

```sh
bun tools/prepare-demo.mjs geolocation
./gradlew :demo-host:installDebug
```

Replace `geolocation` with `battery`, `camera`, `device`, `network`,
`vibration`, or `maps`. The host copies the selected bundle into assets and
registers all seven modules. The Maps example additionally needs
`lynx_maps/config.json` in the host assets; use a generated app for the full
`android/maps.json` configuration flow.

## Host integration

Add selected feature libraries as Gradle modules. For example, an application
that needs geolocation and network uses:

```kotlin
dependencies {
    implementation(project(":android-geolocation"))
    implementation(project(":android-network"))
}
```

Then register the selected plugins when you create the `LynxView`:

```kotlin
val builder = LynxViewBuilder()
LynxGeolocationPlugin.register(builder)
LynxNetworkPlugin.register(builder)
val lynxView = builder.build(this)
```

The host must forward only the Activity callbacks required by selected
features. Geolocation requires the permission callback:

```kotlin
override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray,
) {
    if (!LynxGeolocationPlugin.onRequestPermissionsResult(requestCode, permissions, grantResults)) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
}
```

Camera additionally requires the external Activity result:

```kotlin
@Deprecated("Needed for the external camera intent result")
override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    if (!LynxCameraPlugin.onActivityResult(requestCode, resultCode, data)) {
        super.onActivityResult(requestCode, resultCode, data)
    }
}
```

The selected feature manifests merge only their own requirements. Camera adds
the `FileProvider` needed for a captured photo; it does not request `CAMERA`:
the first version uses the device camera application through
`ACTION_IMAGE_CAPTURE`, and that application owns the permission. Applications
that need their own preview or capture control can add CameraX in a later
iteration.

## Low-level JavaScript contract

Every event receives an object with this shape:

```ts
type PluginEvent = {
  requestId: string;
  ok: boolean;
  data: Record<string, unknown> & { code?: string; message?: string };
  errorCode?: string;
};
```

The facades above own this low-level protocol. Direct native access remains
available for consumers that need to implement a different wrapper:

```ts
const requestId = `geo-${Date.now()}`;
const emitter = lynx.getJSModule("GlobalEventEmitter");
const listener = (event: PluginEvent) => {
  if (event.requestId !== requestId) return;
  emitter.removeListener("lynxAndroidPlugins:geolocation", listener);
  if (event.ok) console.log(event.data.latitude, event.data.longitude);
};
emitter.addListener("lynxAndroidPlugins:geolocation", listener);
NativeModules.LynxGeolocationPlugin.getCurrentPosition(requestId, true);
```

## Scope and privacy

- Geolocation is one-shot and foreground-only; v1 has no background tracking
  or persistent subscriptions.
- Network returns a snapshot. It does not try to imitate every unsupported
  Network Information API field.
- Plugins accept small, bounded inputs; they do not accept arbitrary URIs or
  return stable identifying data.
- The host is responsible for explaining the purpose of each permission before
  requesting it and for complying with Google Play policies.

## Verification

```sh
./gradlew :android-all:assembleDebug :demo-host:assembleDebug
./gradlew publishToMavenLocal
bun run check
bun run build:examples
```

The design is based on the official Lynx native module/event APIs and Android
`BatteryManager`, `LocationManager`, `ConnectivityManager`, `VibratorManager`,
and intent-based camera capture APIs.
