# Lynx Android Plugins

Use Android device features from JavaScript in a Lynx app. The package provides
Promise-based APIs for battery status, camera capture, device information,
foreground location, network status, vibration, and native maps.

## Quick Start

The quickest way to try a plugin is to create a Mithril-Lynx app with its
Android host. The generator adds the JavaScript package and configures the
selected native plugins for you.

```sh
npx create-mithril-lynx my-app --blank --android --android-plugins geolocation --no-install
cd my-app
npm install
```

Replace `geolocation` with one or more of `battery`,
`camera`, `device`, `network`, `vibration`, or `maps` to select other plugins.
See the [`create-mithril-lynx` Android connector guide](https://github.com/carlos-sweb/create-mithril-lynx#with-android-connectors).

## Use your first plugin

In `src/index.ts`, replace the starter view with this small geolocation screen:

```ts
import m from "mithril-runtime";
import { redraw } from "mithril-lynx/mount-redraw";
import { gps } from "lynx-android-plugins/geolocation";
import "./style.css";

let result = "Tap below to request your location.";

async function locate() {
  try {
    const position = await gps.get({ highAccuracy: true });
    result = `${position.latitude}, ${position.longitude}`;
  } catch (error) {
    result = error instanceof Error ? error.message : String(error);
  }
  redraw();
}

export function view() {
  return m("view", { class: "Page" }, [
    m("text", { class: "Title" }, "Geolocation"),
    m("view", { class: "Title", ontap: locate },
      m("text", {}, "Request location"),
    ),
    m("text", {}, result),
  ]);
}
```

Build, install, and launch the app on your connected device or emulator:

```sh
npm run android
```

Android asks for location permission the first time you tap **Request location**.

## Enable a plugin in an existing app

For an app created with `create-mithril-lynx`, run this from the app directory:

```sh
npx create-mithril-lynx add-android-plugin geolocation
npm run android
```

This adds the plugin's JavaScript package and configures the Android app to
register it. You can enable several plugins in one command:

```sh
npx create-mithril-lynx add-android-plugin battery,geolocation,network
```

If you manage your own Android host, installing the JavaScript package alone is
not enough: the host must also include and register the matching native plugin.

## JavaScript API

The location example above imports one capability from its subpath. Import only
the features your app uses. Connector methods return Promises and reject with
`ConnectorError` when the native operation fails. Each connector, such as
`gps` or `battery`, has an `isAvailable()` method to check whether the Android
host registered it.

| Import | API | What it does |
| --- | --- | --- |
| `lynx-android-plugins/battery` | `battery.get()` | Reads battery level and charging state. |
| `lynx-android-plugins/camera` | `camera.takePhoto()` | Opens the camera app and returns a photo URI. |
| `lynx-android-plugins/device` | `device.get()` | Reads non-identifying device information. |
| `lynx-android-plugins/geolocation` | `gps.get({ highAccuracy? })` | Requests one foreground location. |
| `lynx-android-plugins/network` | `network.get()` | Reads current connection status and type. |
| `lynx-android-plugins/vibration` | `vibration.vibrate(ms)`, `vibration.cancel()` | Starts or cancels a bounded vibration. |
| `lynx-android-plugins/maps` | `Maps` | Renders a native offline map. [Maps setup and API](docs/maps.md). |

### Read battery status

Call `battery.get()` to read the current level and whether the device is
charging:

```ts
import { battery } from "lynx-android-plugins/battery";

async function readBattery() {
  try {
    const status = await battery.get();
    const level = status.level < 0
      ? "Unknown"
      : `${Math.round(status.level * 100)}%`;

    console.log(`Battery: ${level}`);
    console.log(status.charging ? "Charging" : "Not charging");
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error));
  }
}
```

## Try an example from this repository

To build and install the geolocation example, clone this repository and run
these commands from its root. This path needs Bun, Java 17, the Android SDK,
and a connected device or emulator.

```sh
git clone https://github.com/carlos-sweb/lynx-android-plugins.git
cd lynx-android-plugins
bun install
bun run build:packages
bun tools/prepare-demo.mjs geolocation
./gradlew :demo-host:installDebug
```

Open **Lynx Android Plugins** on the device to launch the installed demo. You
can substitute `geolocation` with `battery`, `camera`, `device`, `network`, or
`vibration`. Maps needs its own archive configuration; follow the [Maps guide](docs/maps.md).

## Compatibility

- Requires a Lynx runtime inside an Android app that registers the selected
  native plugins.
- Lynx Go / Lynx Explorer can preview the JavaScript interface, but does not
  include these third-party native plugins. Use the Android app to test native
  calls.
- Location is a one-time, foreground request. Camera opens the device's camera
  app and returns a content URI. Maps requires a configured offline archive.

## License

MIT
