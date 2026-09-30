import { ConnectorError, invoke, isAvailable } from "./bridge";

export { ConnectorError };

/** A snapshot of Android's active network. */
export interface NetworkInfo {
  online: boolean;
  type: "none" | "wifi" | "cellular" | "ethernet" | "vpn" | "other";
  metered: boolean;
  validated: boolean;
}

const moduleName = "LynxNetworkPlugin";
const eventName = "lynxAndroidPlugins:network";

/** Read the active network without exposing its address or identifier. */
export const network = {
  isAvailable: (): boolean => isAvailable(moduleName, "getInfo"),
  /** Return a one-shot network snapshot. */
  get: (): Promise<NetworkInfo> => invoke(moduleName, eventName, "getInfo"),
};
