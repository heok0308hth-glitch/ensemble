# Contributing

Thanks for taking a look. Ensemble is small on purpose, so most changes are easy to review.

## Run everything

```bash
./gradlew :ensemble:test                               # unit tests (no network, a few seconds)
./gradlew :samples:cli:run --args="--demo"             # simulated team with a live view
./gradlew :samples:bench:run                           # benchmark → docs/bench.md, docs/*.svg
```

JDK 17 or newer. No API keys are needed for any of the above.

## Guidelines

- **Keep the core dependency-free.** `kotlinx-coroutines-core` and `org.json` only, and nothing Android.
- **Measure orchestration changes.** If you touch dispatch, failover or the loops, run the benchmark and include the before/after table in the PR.
- **Tests model reality.** Prefer a scripted crew or `Simulated` members over mocks, and virtual time over sleeps.
- **Prompts are protocol.** Keep the `[ensemble:*]` tags and the JSON shapes stable. If you must change one, update `Simulated` and `docs/architecture.md` in the same PR.
- **Honest vendor profiles.** Changes to `Vendor` priors need a short reason (an eval, a public benchmark, sustained experience).

## Commit style

Short imperative subject (`dispatch: penalise members with open failures`), a body that explains why.
