import { ConnectorError, invoke, isAvailable } from "./bridge";

export { ConnectorError };

const moduleName = "LynxVibrationPlugin";
const eventName = "lynxAndroidPlugins:vibration";

/** Control the Android vibration motor. */
export const vibration = {
  isAvailable: (): boolean => isAvailable(moduleName, "vibrate"),
  /** Vibrate for 1–10,000 milliseconds; Android clamps values outside that range. */
  vibrate: async (durationMs: number): Promise<void> => {
    if (!Number.isFinite(durationMs)) throw new ConnectorError("INVALID_ARGUMENT", "durationMs must be finite.");
    await invoke(moduleName, eventName, "vibrate", [durationMs]);
  },
  /** Stop any vibration started by the host. */
  cancel: async (): Promise<void> => {
    await invoke(moduleName, eventName, "cancel");
  },
};
