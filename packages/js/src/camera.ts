import { ConnectorError, invoke, isAvailable } from "./bridge";

export { ConnectorError };

/** The content URI of a JPEG saved by the device camera application. */
export interface Photo {
  uri: string;
  mimeType: string;
}

const moduleName = "LynxCameraPlugin";
const eventName = "lynxAndroidPlugins:camera";

/** Capture a photo using the device camera application. */
export const camera = {
  isAvailable: (): boolean => isAvailable(moduleName, "takePhoto"),
  /** Open the camera application and resolve with the captured photo URI. */
  takePhoto: (): Promise<Photo> => invoke(moduleName, eventName, "takePhoto", [], 300_000),
};
