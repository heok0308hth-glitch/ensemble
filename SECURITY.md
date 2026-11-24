# Security policy

Please report vulnerabilities **privately** through *Security → Report a vulnerability* on this repository, not in a public issue.

Areas that matter most:

- **Approval gate** — an action with an outside effect must never run without a valid, unexpired, unused token whose digest matches the exact call.
- **Prompt injection through tools** — tool output reaches models; anything that lets it trigger an unapproved external action is in scope.
- **Secrets** — API keys must never be logged, put into prompts, or written to the workspace or the checkpoint.
- **Paths** — `ProjectFiles.clean` must keep every write inside the workspace.

Supported version: the latest release.
