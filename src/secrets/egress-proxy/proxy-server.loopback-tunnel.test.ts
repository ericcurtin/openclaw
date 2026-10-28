import { once } from "node:events";
import { createServer, type Server } from "node:http";
import net from "node:net";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { useAutoCleanupTempDirTracker } from "../../../test/helpers/temp-dir.js";
import { mintSecretSentinel } from "../sentinel.js";
import { startSecretEgressProxyServer, type SecretEgressProxyHandle } from "./proxy-server.js";

type AuditEvent = Parameters<Parameters<typeof startSecretEgressProxyServer>[0]["onAudit"]>[0];

describe("secret egress cleartext loopback tunnel", () => {
  const tempDirs = useAutoCleanupTempDirTracker(afterAll);
  const audit: AuditEvent[] = [];
  const observed: string[] = [];
  let proxy: SecretEgressProxyHandle;
  let origin: Server;
  let port: number;

  beforeAll(async () => {
    proxy = await startSecretEgressProxyServer({
      caDir: tempDirs.make("openclaw-egress-tunnel-"),
      onAudit: (event) => audit.push(event),
    });
    origin = createServer((request, response) => {
      observed.push(request.url ?? "");
      response.end("loopback-ok");
    });
    origin.listen(0, "127.0.0.1");
    await once(origin, "listening");
    port = (origin.address() as net.AddressInfo).port;
  });

  beforeEach(() => {
    audit.length = 0;
    observed.length = 0;
  });

  afterAll(async () => {
    await proxy.stop();
    origin.closeAllConnections();
    await new Promise<void>((resolve) => {
      origin.close(() => resolve());
    });
  });

  // Node's fetch sends HTTP origins through CONNECT; Bun's does not, so speak it directly.
  async function tunnelRequest(env: Record<string, string>, apiKey: string) {
    const endpoint = new URL(env.HTTP_PROXY!);
    const auth = Buffer.from(`openclaw:${endpoint.password}`).toString("base64");
    const socket = net.connect(Number(endpoint.port), endpoint.hostname);
    let received = "";
    let tunneled = false;
    socket.setEncoding("latin1");
    socket.on("data", (chunk: string) => {
      received += chunk;
      if (!tunneled && received.includes("\r\n\r\n")) {
        tunneled = true;
        received = received.slice(received.indexOf("\r\n\r\n") + 4);
        socket.write(
          `GET /ok HTTP/1.1\r\nHost: 127.0.0.1:${port}\r\nx-api-key: ${apiKey}\r\nConnection: close\r\n\r\n`,
        );
      }
    });
    socket.write(
      `CONNECT 127.0.0.1:${port} HTTP/1.1\r\nHost: 127.0.0.1:${port}\r\nProxy-Authorization: Basic ${auth}\r\n\r\n`,
    );
    await once(socket, "close");
    const [head = "", ...body] = received.split("\r\n\r\n");
    return {
      status: Number(/^HTTP\/1\.1 (\d+)/u.exec(head)?.[1]),
      body: body.join("\r\n\r\n"),
    };
  }

  it("forwards cleartext HTTP sent through a CONNECT tunnel", async () => {
    const grant = proxy.registerProcess();
    try {
      expect(await tunnelRequest(grant.env, "plain")).toEqual({ status: 200, body: "loopback-ok" });
      expect(observed).toEqual(["/ok"]);
      expect(audit).toEqual([{ kind: "forwarded", host: "127.0.0.1", substituted: false }]);
    } finally {
      grant.revoke();
    }
  });

  it("refuses a sentinel inside a CONNECT tunnel", async () => {
    const sentinel = mintSecretSentinel("synthetic-tunnel-secret", { label: "tunnel-refusal" });
    const grant = proxy.registerProcess([
      { name: "SERVICE_API_KEY", sentinel, allowedHosts: ["127.0.0.1"] },
    ]);
    try {
      expect(await tunnelRequest(grant.env, sentinel)).toEqual({
        status: 502,
        body: "Secret egress proxy refused the request.\n",
      });
      expect(observed).toEqual([]);
      expect(audit).toEqual([
        { kind: "refused", host: "127.0.0.1", substituted: false, reason: "non-https-request" },
      ]);
    } finally {
      grant.revoke();
    }
  });
});
