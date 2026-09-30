import { ConnectorError, invoke, isAvailable } from "./bridge";

export { ConnectorError };

/** Non-identifying Android device and OS information. */
export interface DeviceInfo {
  platform: "android";
  manufacturer: string;
  model: string;
  brand: string;
  osVersion: string;
  apiLevel: number;
}

const moduleName = "LynxDevicePlugin";
const eventName = "lynxAndroidPlugins:device";

/** Read a one-shot device information snapshot. */
export const device = {
  isAvailable: (): boolean => isAvailable(moduleName, "getInfo"),
  /** Return device model and Android version without a stable device ID. */
  get: (): Promise<DeviceInfo> => invoke(moduleName, eventName, "getInfo"),
};
