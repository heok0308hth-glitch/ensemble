# Changelog

## [0.1.0] — 2026-11-24

First public release, split out of the HJTK Android app.

### Added
- `Ensemble.team { … }` builder with `conduct()` (minutes) and `project()` (hours, resumable).
- Conductor: center planning, dispatch market, shared board, background rival cross-checks, gap filling, compose and review.
- Company: charter with a project-specific rubric, sprints, rival grading with rework, stand-ups, final QA, report with work log, checkpoints.
- Failover across members, benching on usage limits and auth errors, center takeover, `Outcome.partial`.
- AEGIS approval gate: canonical JSON, policy, one-time HMAC tokens bound to the exact action.
- Ports for Anthropic, OpenAI, Gemini, DeepSeek, xAI, OpenRouter, Ollama and any OpenAI-compatible server.
- English prompts with automatic output language (`Language.AUTO`).
- `Simulated` members for keyless demos, tests and virtual-time benchmarks.
- Terminal sample with a live view; orchestration benchmark that renders the README figures.
