# Provenance and scope

Created for Koorix on 2026-11-06. This bundle's instructions, Python tools, Java
parity harness and synthetic cases are original project-specific work under
the included MIT license. No upstream skill code/text is vendored. The rest of
the repository and models keep their own licensing.

Research/inspiration (methodology, not embedded services):

- Official Agent Skills format: https://github.com/agentskills/agentskills
- Anthropic examples: https://github.com/anthropics/skills
- Skill creation/evaluation loop (Apache-2.0 in that upstream folder):
  https://github.com/anthropics/skills/tree/main/skills/skill-creator
- Community translation -> terminology -> review -> revision workflow (MIT):
  https://github.com/senshinji/claude-translation-skill

We do not import the community skill's Claude Agent Teams, model assignments,
macOS textutil dependency, external search, or document-generation stack.
We do not copy Anthropic's differently licensed PDF/Office skills. Licenses vary
per upstream folder; the existence of a public repository is not a blanket
commercial-use license.

No affiliation with or endorsement by Anthropic is implied. This bundle is not
Claude, a trained translator, a Claude API wrapper, an API-access workaround,
or evidence of improved OPUS accuracy. It can guide an available compatible
agent or human and run its standard-library tools offline. Host-agent access
may have its own price/limits; no new paid API is required by this bundle.
