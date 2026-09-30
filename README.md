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
| `:android-all` | Opt-in convenience aggregate | All feature contributions |

`android-all` preserves the original all-in-one registration API for the demo
host. Production applications should depend on individual feature modules.

## Maven coordinates

The first release uses group `io.github.carlos-sweb` and version `0.1.0`.
These coordinates are defined and verified with `mavenLocal()`; they are not
published to Maven Central until the signing and Portal-upload stage is
complete.

| Gradle module | Maven artifact |
| --- | --- |
| `:android-core` | `io.github.carlos-sweb:lynx-android-core:0.1.0` |
| `:android-battery` | `io.github.carlos-sweb:lynx-android-battery:0.1.0` |
| `:android-camera` | `io.github.carlos-sweb:lynx-android-camera:0.1.0` |
| `:android-device` | `io.github.carlos-sweb:lynx-android-device:0.1.0` |
| `:android-geolocation` | `io.github.carlos-sweb:lynx-android-geolocation:0.1.0` |
| `:android-network` | `io.github.carlos-sweb:lynx-android-network:0.1.0` |
| `:android-vibration` | `io.github.carlos-sweb:lynx-android-vibration:0.1.0` |
| `:android-all` | `io.github.carlos-sweb:lynx-android-plugins:0.1.0` |

For local consumption after running `./gradlew publishToMavenLocal`:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("io.github.carlos-sweb:lynx-android-geolocation:0.1.0")
}
```

## Included plugins

| Lynx module | Initial operation | Permission / boundary |
| --- | --- | --- |
| `LynxBatteryPlugin` | `getStatus()` | None; level, charging state, and known timing state |
| `LynxCameraPlugin` | `takePhoto()` | Launches the camera application; returns a `content://` URI, never image bytes |
| `LynxDevicePlugin` | `getInfo()` | None; does not expose an ID, IMEI, serial number, or advertising ID |
| `LynxGeolocationPlugin` | `getCurrentPosition()` | Foreground approximate/precise location with a runtime permission request |
| `LynxNetworkPlugin` | `getInfo()` | Transport, validated connectivity, and metering snapshot; no SSID/BSSID/IP address |
| `LynxVibrationPlugin` | `vibrate()` / `cancel()` | `VIBRATE`; a single vibration is capped at 10 seconds |

The modules do not return Kotlin `Promise` objects directly. Each call has a
`requestId` and returns through `GlobalEventEmitter`, which gives immediate
calls, permission requests, and Activity results one consistent contract.

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

Replace `geolocation` with `battery`, `camera`, `device`, `network`, or
`vibration`. The host copies the selected bundle into assets and registers all
six modules.

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

## JavaScript contract

Every event receives an object with this shape:

```ts
type PluginEvent = {
  requestId: string;
  ok: boolean;
  data: Record<string, unknown> & { code?: string; message?: string };
  errorCode?: string;
};
```

GPS example (the complete example is in
[`examples/geolocation`](examples/geolocation)):

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
