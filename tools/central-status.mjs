import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";

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

const deploymentId = process.argv[2];
const credentials = readPortalCredentials();

if (!deploymentId) {
  throw new Error("Provide a deployment ID: bun run central:status -- <deployment-id>");
}
if (!credentials) {
  throw new Error(
    "Set CENTRAL_USERNAME and CENTRAL_PASSWORD to the Maven Central Portal user-token credentials.",
  );
}

const endpoint = new URL("https://central.sonatype.com/api/v1/publisher/status");
endpoint.searchParams.set("id", deploymentId);

const response = await fetch(endpoint, {
  method: "POST",
  headers: {
    Authorization: `Bearer ${Buffer.from(`${credentials.username}:${credentials.password}`).toString("base64")}`,
  },
});
const body = (await response.text()).trim();

if (!response.ok) {
  throw new Error(`Maven Central status request failed (${response.status}): ${body}`);
}

console.log(body);
