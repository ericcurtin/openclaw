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
  it("requires agentId and suggests examples with an empty configuration", () => {
    expect(describeAcpSpawnTargetParameter({ agents: { list: [] } })).toBe(
      "ACP harness id, for example: codex, claude. agentId is required; no usable acp.defaultAgent is configured.",
    );
  });

  it("describes the configured default when no allowlist is set", () => {
    expect(
      describeAcpSpawnTargetParameter({ agents: { list: [] }, acp: { defaultAgent: "codex" } }),
    ).toBe('ACP harness id, for example: codex. Omit to use the configured ACP default ("codex").');
  });

  it("does not offer a native config agent as an ACP harness", () => {
    const description = describeAcpSpawnTargetParameter({
      agents: { list: [{ id: "main" }, { id: "coder", runtime: { type: "acp" } }] },
      acp: { defaultAgent: "codex" },
    });
    expect(description).toBe(
      'ACP harness id, for example: coder, codex. Omit to use the configured ACP default ("codex").',
    );
    expect(description).not.toContain("main");
  });

  it("lists only allowlisted ids and drops a default the allowlist rejects", () => {
    expect(
      describeAcpSpawnTargetParameter({
        agents: { list: [{ id: "main" }] },
        acp: { allowedAgents: ["codex", "claude"], defaultAgent: "gemini" },
      }),
    ).toBe(
      "ACP harness id from: claude, codex. agentId is required; no usable acp.defaultAgent is configured.",
    );
  });

  it("does not advertise wildcard access the policy does not grant", () => {
    const description = describeAcpSpawnTargetParameter({
      agents: { list: [] },
      acp: { allowedAgents: ["*"], defaultAgent: "codex" },
    });
    expect(description).not.toContain("any harness");
    expect(description).not.toContain("Omit to use");
  });
});
