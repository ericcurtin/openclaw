import { describe, expect, it } from "vitest";
import { describeAcpSpawnTargetParameter, resolveTargetAcpAgentId } from "./acp-spawn-target.js";

describe("resolveTargetAcpAgentId", () => {
  it.each(["", "агент✨"])("rejects explicit unrepresentable ACP agent id %j", (agentId) => {
    expect(
      resolveTargetAcpAgentId({
        requestedAgentId: agentId,
        cfg: { acp: { defaultAgent: "codex" } },
      }),
    ).toEqual({ ok: false, error: `agentId "${agentId}" was not found` });
  });

  it("keeps omitted ACP agent ids on the configured default path", () => {
    expect(
      resolveTargetAcpAgentId({
        cfg: { acp: { defaultAgent: "codex" } },
      }),
    ).toEqual({ ok: true, agentId: "codex" });
  });
});

describe("describeAcpSpawnTargetParameter", () => {
  it("describes an empty configuration and requires agentId with no default", () => {
    expect(describeAcpSpawnTargetParameter({ agents: { list: [] } })).toBe(
      "ACP harness id; none are configured yet. agentId is required; no acp.defaultAgent is configured.",
    );
  });

  it("describes the configured default when no allowlist is set", () => {
    expect(
      describeAcpSpawnTargetParameter({ agents: { list: [] }, acp: { defaultAgent: "codex" } }),
    ).toBe('ACP harness id from: codex. Omit to use the configured ACP default ("codex").');
  });

  it("describes a wildcard allowlist", () => {
    expect(
      describeAcpSpawnTargetParameter({
        agents: { list: [] },
        acp: { allowedAgents: ["*"], defaultAgent: "codex" },
      }),
    ).toBe(
      'ACP harness id; any harness is allowed. Omit to use the configured ACP default ("codex").',
    );
  });

  it("describes an explicit allowlist", () => {
    expect(
      describeAcpSpawnTargetParameter({
        agents: { list: [] },
        acp: { allowedAgents: ["codex", "claude"] },
      }),
    ).toBe(
      "ACP harness id from: claude, codex. agentId is required; no acp.defaultAgent is configured.",
    );
  });
});
