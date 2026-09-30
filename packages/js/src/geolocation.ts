import { ConnectorError, invoke, isAvailable } from "./bridge";

export { ConnectorError };

/** An Android foreground location fix. The provider may be GPS or network. */
export interface Position {
  latitude: number;
  longitude: number;
  accuracy: number;
  timestamp: number;
  provider: string;
}

/** Options for a single foreground location request. */
export interface PositionOptions {
  highAccuracy?: boolean;
}

const moduleName = "LynxGeolocationPlugin";
const eventName = "lynxAndroidPlugins:geolocation";

/** One-shot foreground location API for an Android Lynx host. */
export const gps = {
  isAvailable: (): boolean => isAvailable(moduleName, "getCurrentPosition"),
  /** Request one position, asking Android for permission when needed. */
  get: (options: PositionOptions = {}): Promise<Position> =>
    invoke(moduleName, eventName, "getCurrentPosition", [options.highAccuracy ?? false], 35_000),
};
