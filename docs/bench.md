# Orchestration benchmark

Simulated members, virtual time, 25 seeded runs per scenario, `Difficulty.STANDARD`. Goal: *Compare the three strongest options for a budgeting app's onboarding and recommend one*.
This measures the orchestrator — wall time, spread of work, recovery — not model quality. Reproduce with `./gradlew :samples:bench:run`.

| Scenario | Wall time (min) | Fully completed | Workers used | Largest share | Hand-overs | Rival checks |
|---|---|---|---|---|---|---|
| Solo · Claude alone | 8.4 ± 1.0 | 25/25 | 1.0 | 100% | 0.0 | 0.0 |
| Ensemble · 5 vendors | 6.1 ± 0.8 | 25/25 | 4.0 | 32% | 0.0 | 5.2 |
| Ensemble · planner names Claude for all | 6.1 ± 0.8 | 25/25 | 4.0 | 32% | 0.0 | 5.2 |
| Ensemble · GPT limited, Grok fails 25% | 6.2 ± 0.9 | 25/25 | 3.8 | 37% | 0.6 | 5.2 |
| Solo · Claude hits its limit | — | 0/25 | 1.0 | 100% | 0.0 | 0.0 |
| Ensemble · the lead hits its limit | 7.0 ± 1.0 | 25/25 | 4.0 | 32% | 0.0 | 5.2 |

The five-vendor team finishes in **6.1 min vs 8.4 min** for the strongest model alone (**1.4× faster**) while adding rival cross-checks the solo run cannot have.
With a planner that names one model for everything, the market still spreads the work. With members benched by usage limits or failing a quarter of their calls the team still completes — and when the lead itself runs out, another member takes the baton, where the solo run simply stops.
