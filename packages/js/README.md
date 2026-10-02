# lynx-android-plugins

Promise-based JavaScript APIs for Android features in a Lynx app. Each feature
uses a native plugin registered by the app's Android host.

## Install

```sh
npm install lynx-android-plugins
```

For an app created with `create-mithril-lynx`, enable the matching native
plugin from the app directory:

```sh
npx create-mithril-lynx add-android-plugin battery
```

## Read battery status

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

## Other features

Import only the subpaths your app needs:

- `lynx-android-plugins/camera` — `camera.takePhoto()`
- `lynx-android-plugins/device` — `device.get()`
- `lynx-android-plugins/geolocation` — `gps.get({ highAccuracy? })`
- `lynx-android-plugins/network` — `network.get()`
- `lynx-android-plugins/vibration` — `vibration.vibrate(ms)` and `vibration.cancel()`
- `lynx-android-plugins/maps` — native Maps component; see the [Maps guide](docs/maps.md)

Connector methods return Promises and reject with `ConnectorError` on failure.
Each connector has `isAvailable()` to check whether the Android host registered
it. A JavaScript package install by itself does not add native plugins to a
custom Android host.

The Maps component also needs a configured offline archive and a sized parent
element. See the [Maps guide](docs/maps.md) for setup and examples.
