import { ConnectorError, invoke, isAvailable } from "./bridge";

export { ConnectorError };

/** A single battery snapshot. `level` is -1 if Android cannot determine it. */
export interface BatteryStatus {
  level: number;
  charging: boolean;
  chargingTimeKnown: boolean;
  dischargingTimeKnown: boolean;
}

const moduleName = "LynxBatteryPlugin";
const eventName = "lynxAndroidPlugins:battery";

/** One-shot battery status API for an Android Lynx host. */
export const battery = {
  isAvailable: (): boolean => isAvailable(moduleName, "getStatus"),
  /** Read the current battery level and charging state. */
  get: (): Promise<BatteryStatus> => invoke(moduleName, eventName, "getStatus"),
};
