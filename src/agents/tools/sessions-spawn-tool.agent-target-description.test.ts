// Covers the sessions_spawn agentId schema description for the requester's
// resolved target policy (default, explicit allowlist, wildcard).
import { expect, it } from "vitest";
import { createSessionsSpawnTool } from "./sessions-spawn-tool.js";

function requireAgentIdDescription(tool: ReturnType<typeof createSessionsSpawnTool>): string {
  const schema = tool.parameters as { properties: Record<string, { description?: string }> };
  const description = schema.properties.agentId?.description;
  if (!description) {
    throw new Error("expected agentId schema property with a description");
  }
  return description;
}

it("describes the requester-only default target", () => {
  const tool = createSessionsSpawnTool({ agentSessionKey: "agent:main:main" });
  const description = requireAgentIdDescription(tool);
  expect(description).toContain("Only the requester agent is allowed");
  expect(description).toContain('Omit to keep the requester agent ("main")');
});

it("describes an explicit allowlist target", () => {
  const tool = createSessionsSpawnTool({
    agentSessionKey: "agent:main:main",
    config: {
      agents: {
        defaults: { subagents: { allowAgents: ["main", "reviewer"] } },
        list: [{ id: "main" }, { id: "reviewer" }],
      },
    },
  });
  const description = requireAgentIdDescription(tool);
  expect(description).toContain("Configured agent to target: main, reviewer");
});

it("describes a wildcard allowlist target", () => {
  const tool = createSessionsSpawnTool({
    agentSessionKey: "agent:main:main",
    config: {
      agents: {
        defaults: { subagents: { allowAgents: ["*"] } },
        list: [{ id: "main" }, { id: "reviewer" }],
      },
    },
  });
  const description = requireAgentIdDescription(tool);
  expect(description).toContain("any configured agent is allowed");
});
