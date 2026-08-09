# Strategy and risk

StartEx uses deterministic, versioned rules. It does not ask an LLM or remote model whether to trade and makes no profit claim. A candidate must pass every mandatory data, transaction, and risk gate; a high score cannot compensate for a hard failure.

## Observation

New-token and migration events are persisted immediately. During a bounded observation window the app collects multiple timestamped snapshots for mint/program metadata, authorities and extensions, creator/top-holder concentration, buyers/trades/volume, organic/liquidity trends, suspicious wallets, price impact, buy route, immediate full-sell route, round-trip cost, age, and provider agreement.

Snapshots have explicit freshness. Missing, stale, contradictory, or schema-invalid critical fields block Live entry. A candidate expires when its observation window, pre-pump ceiling, routing availability, or retention budget is exceeded.

## Hard filters

Before scoring, reject candidates with any of the following:

- unsupported mint or token program/extension;
- active or unknown dangerous mint/freeze/delegate/transfer authority;
- unreviewed program or unresolved address lookup table;
- excessive creator/top-holder concentration after documented exclusions;
- inadequate unique holders, buyers, organic activity, volume, or liquidity;
- suspicious wallet activity or material provider disagreement;
- stale health/data or unavailable provider quota;
- unavailable buy route or unavailable immediate full-position sell route;
- round-trip loss, price impact, fees, slippage, age, or pre-pump movement above cap;
- reserve, exposure, daily-loss, fee, cooldown, or position limit failure.

Every rejection records stable reason codes and human-readable evidence.

## Score

After mandatory filters, the versioned scorer maps sufficient input to 0–100. Positive factors include diverse participation, improving organic volume/liquidity, stable routing, low concentration and price impact, and agreement across fresh providers. Negative factors include concentration, bursty/suspicious activity, worsening liquidity, routing fragility, high round-trip cost, and pre-pump movement.

The decision stores:

- strategy version;
- input snapshot IDs and timestamps;
- total score and factor contributions;
- completeness and confidence;
- mandatory pass/fail results and rejection reasons.

No random, time-dependent, or UI-owned rule may alter the result.

## Independent hard risk layer

The risk layer runs after the strategy and cannot be bypassed by Live mode or manual candidate approval. It enforces integer/decimal caps for:

- per-trade SOL/EUR and total exposure;
- open positions (default one, maximum two);
- rolling-day trade count, realized loss, fees, and consecutive losses;
- cooldowns after loss, provider failure, or uncertain transaction;
- spendable balance and fee/exit reserve;
- slippage, priority fee, network/DEX/aggregator cost, rent, and total fee ratio;
- candidate age, pre-pump movement, holding time, and data freshness;
- battery/thermal/network/provider health.

A circuit breaker blocks entries. It does not disable reconciliation, Sell Now, emergency exit attempts, or monitoring of an open position. Reset is authenticated and cannot erase the underlying accounting.

## Future Live entry

The intended serialized coordinator would refresh caps, balance, freshness and round-trip quotes; decode and validate a provider transaction; simulate; authenticate; sign once; and reconcile confirmation. This path is not composed in production and Live remains locked.

## Paper execution

Paper mode uses the same candidates, decisions, risk gates, quotes, positions, and exit rules. Only execution changes. The simulator accounts for quote latency/requote, failed routes, slippage, DEX/aggregator fees, network/priority fees, rent, and configurable latency. Paper and Live records never mix.

## Position value and exits

Current value and net P&L come from a fresh executable full-position sell quote, not a spot price. P&L includes entry and exit costs, network/priority fees, rent/reclaim effects, and supported token transfer fees.

Entry freezes an exit-rule version. Triggers include take profit, stop loss, trailing drawdown from highest executable value, maximum holding time, worsening momentum/liquidity/creator/holder risk, freeze/routing risk, Sell Now, and Emergency exit.

Paper exits refresh executable quotes, apply bounded latency/retry and absolute cost caps, and persist either the fill or visible `EXIT_BLOCKED` state. A future Live exit must additionally validate and simulate the exact transaction before one submission. Stop-loss is an exit attempt, never a guarantee.
