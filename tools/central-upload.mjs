import { existsSync, readFileSync } from "node:fs";
import { basename, resolve } from "node:path";

function readElement(block, name) {
  return block.match(new RegExp(`<${name}>([\\s\\S]*?)</${name}>`))?.[1]?.trim();
}

function readPortalCredentials() {
  if (process.env.CENTRAL_USERNAME && process.env.CENTRAL_PASSWORD) {
    return { username: process.env.CENTRAL_USERNAME, password: process.env.CENTRAL_PASSWORD };
  }

  const settingsPath = resolve(process.env.MAVEN_SETTINGS_PATH ?? "maven.xml");
  if (!existsSync(settingsPath)) return undefined;
  const serverBlocks = readFileSync(settingsPath, "utf8").match(/<server>[\s\S]*?<\/server>/g) ?? [];
  const servers = serverBlocks.map((block) => ({
    id: readElement(block, "id"),
    username: readElement(block, "username"),
    password: readElement(block, "password"),
  }));
  const selectedId = process.env.CENTRAL_SERVER_ID;
  const server = selectedId ? servers.find(({ id }) => id === selectedId) : servers.length === 1 ? servers[0] : undefined;
  if (!server?.username || !server.password) {
    throw new Error(
      "Set CENTRAL_USERNAME/CENTRAL_PASSWORD, or provide one credential server in maven.xml. Use CENTRAL_SERVER_ID when it has multiple servers.",
    );
  }
  return server;
}

const properties = readFileSync(resolve("gradle.properties"), "utf8");
const version = properties.match(/^VERSION_NAME=(.+)$/m)?.[1];
const defaultBundle = `build/central-bundle/lynx-android-plugins-${version}.zip`;
const bundlePath = resolve(process.argv[2] ?? defaultBundle);
const credentials = readPortalCredentials();

if (!credentials) {
  throw new Error(
    "Set CENTRAL_USERNAME and CENTRAL_PASSWORD to the Maven Central Portal user-token credentials.",
  );
}
if (!existsSync(bundlePath)) {
  throw new Error(`Bundle not found: ${bundlePath}. Run \"bun run central:bundle\" first.`);
}

const deploymentName = process.env.CENTRAL_DEPLOYMENT_NAME ?? `lynx-android-plugins-${version}`;
const endpoint = new URL("https://central.sonatype.com/api/v1/publisher/upload");
endpoint.searchParams.set("name", deploymentName);
endpoint.searchParams.set("publishingType", "USER_MANAGED");

const form = new FormData();
form.append(
  "bundle",
  new Blob([readFileSync(bundlePath)], { type: "application/octet-stream" }),
  basename(bundlePath),
);

const response = await fetch(endpoint, {
  method: "POST",
  headers: {
    Authorization: `Bearer ${Buffer.from(`${credentials.username}:${credentials.password}`).toString("base64")}`,
  },
  body: form,
});
const body = (await response.text()).trim();

if (!response.ok) {
  throw new Error(`Maven Central upload failed (${response.status}): ${body}`);
}

console.log(`Maven Central deployment uploaded: ${body}`);
console.log(`Check it with: bun run central:status -- ${body}`);
