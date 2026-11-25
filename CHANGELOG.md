# Changelog

## [0.1.1] — 2026-11-25

Fixes from a real failure in which a project saved its own instructions as deliverables.

### Fixed
- Replies that repeat the prompt are no longer accepted as answers (`Gate.echo`); the desk hands the work to someone else.
- Cut-off replies are continued from where they stopped instead of being saved (`Gate.truncated`, `Gate.join`).
- An unreadable grade no longer counts as the bar. A second rival grades; otherwise the file is marked **ungraded**.
- An unreadable or empty stand-up no longer ends a project early; tickets are planned from the file status.
- Files that fail the save gate are never shown to authors as the current version.

### Added
- `Gate` (public): `check`, `echo`, `truncated`, `join`.
- `Simulated.Profile(echoRate, cutRate, unreadableGradeRate)` to test these failures.

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
