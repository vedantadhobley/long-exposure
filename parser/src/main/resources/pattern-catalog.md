# Pattern Catalog (v1 draft)

The interpretive layer's source of truth. For each of the 7 scorer types, this file enumerates:

- **mechanism** — what the pattern IS at the wire level (descriptive, factual)
- **drivers** — what legitimate market activities can produce this pattern
- **inference limit** — what we explicitly DO NOT claim from wire data alone
- **canonical interpretation** — the one safe sentence Layer-0 narration may use
- **sources** — citations

## Design discipline

This catalog is the **ground truth** for Layer-0 interpretation narration. Three rules govern its content:

1. **No intent claims from wire data alone.** Market microstructure patterns can have many causes. Documented intent (e.g., spoofing) requires evidence beyond the order book — communications, profit pattern across opposite-side trades, regulator findings. We observe shape, not motive.

2. **Mechanism over interpretation.** When the choice is between describing *what is happening on the wire* vs *why someone might be doing it*, prefer the former. Mechanism descriptions are verifiable against the spec; motive descriptions are not.

3. **Multiple drivers, not "the" driver.** Every pattern in this catalog has 2+ legitimate explanations. The Layer-0 narration enumerates the plausible drivers without privileging any.

The Layer-0 verifier (`GroundingVerifier` Layer-5) enforces that interpretation prose is a token-subset of the catalog entry for the relevant scorer. Anything not in the catalog cannot appear in interpretation prose.

---

## halt

### Mechanism

A halt is a trading suspension imposed by an exchange. Three regulatory causes drive halts:

1. **News pending (T1).** The listing exchange suspends trading while material non-public information is being disseminated. Resumes after a regulatory notification period.
2. **Single-stock circuit breaker pause (LULD — Limit Up-Limit Down).** Triggers when a stock's reference price moves outside a percentage band (5% / 10% / 20% depending on the security's tier). The pause is 5 minutes followed by a reopening auction.
3. **Market-wide circuit breaker (MCB).** Level 1, 2, or 3 trip when the S&P 500 falls 7%, 13%, or 20% intraday respectively. Halts all US equity trading for 15 minutes (L1/L2) or for the remainder of the session (L3).

### Drivers

- Material news pending (M&A announcement, earnings restatement, regulatory action, etc.)
- Sudden price discontinuity tripping LULD bands
- Cross-market volatility tripping MCB
- Listing-venue auction process complications (rare)

### Inference limit

The halt event itself does not carry information about what news triggered the suspension. The reason code (T1 / IPO1 / MCB3) indicates the *category* of cause but not the specifics.

### Canonical interpretation

> "Trading halts can result from material news pending, single-stock circuit breaker volatility pauses (LULD), or market-wide circuit breakers (MCB). The wire data records the duration and reason code but not the underlying news or market condition that triggered the suspension."

### Templated interpretation (Option B)

> "This {duration} halt on {symbol} ({company_name}) was a {reason_category} suspension. Halts can result from material news pending, single-stock circuit breaker pauses (LULD), or market-wide circuit breakers (MCB); the wire data records the duration and reason code but not the underlying news or condition."

Substitution variables: `{duration}`, `{symbol}`, `{company_name}`, `{reason_category}` (mapped from breakdown.halt_reason via a small lookup: T1 → "news-pending"; LULD-* → "LULD volatility"; MCB-* → "market-wide circuit breaker"; else → "regulatory").

### Sources

- LULD Plan: SEC Rule 608 LULD National Market System Plan
- IEX commentary on LULD plan implementation: [iexexchange.io/blog/iex-comment-letter-on-luld-plan](https://www.iexexchange.io/blog/iex-comment-letter-on-luld-plan)

---

## large_trade

### Mechanism

A single execution exceeding $1M in notional value (size × price). On IEX, this can be either a routed order filling against displayed liquidity, a non-displayed match in the dark pool, or a mid-point execution against pegged order types (Midpoint Peg or Discretionary Peg — IEX's hidden-liquidity order types that price relative to the national best bid/offer, NBBO).

### Drivers

- Institutional accumulation or distribution of a position
- ETF creation/redemption-driven trading
- Index rebalancing flows
- Hedging activity following a derivative trade
- Risk-transfer block negotiated upstairs and printed on-exchange

### Inference limit

A single large print does not indicate the counterparty's identity, intent, or whether the trade is part of a larger campaign across multiple venues. ETF arbitrage and hedging produce identical wire signatures to directional accumulation.

### Canonical interpretation

> "Large single prints can reflect institutional position changes, ETF flows, index rebalances, or hedging against derivative exposure. The wire data records the size and price but not the originating strategy."

### Sources

- General market microstructure literature on block trading (Harris, *Trading and Exchanges*, 2003)
- IEX order-type documentation: [iexexchange.io/products/order-types](https://www.iexexchange.io/products/order-types)

---

## sweep

### Mechanism

A single aggressive order, executing against multiple resting price levels in rapid succession (typically within 10 ms). Visible as a burst of `OrderExecuted` messages walking the book from the best price upward (for buys) or downward (for sells), consuming displayed liquidity at each level.

### Drivers

- Urgency: the originator needs immediate fill and is willing to pay through multiple levels
- Information-based trading: belief that the price will continue moving against the order
- Index rebalancing or basket trading where execution time dominates price
- Liquidation of a hedge in response to a related-instrument move
- Algorithmic strategies designed to detect and consume thin liquidity (sometimes opportunistically before a known event)

### Inference limit

A sweep tells us the originator paid for speed by accepting price walk. It does not tell us *why* the originator was in a hurry. The same wire signature can come from informed trading, panic liquidation, or routine basket execution.

### Canonical interpretation

> "Multi-level sweeps consume displayed liquidity at multiple price levels in rapid succession, indicating the originator prioritized immediate fill over price improvement. Drivers include information-based urgency, hedge unwinding, or basket-trading execution where speed dominates price."

### Sources

- Hasbrouck, *Empirical Market Microstructure*, 2007 — sweep / aggressive-order pricing
- IEX D-Peg documentation describing sweep-and-fade interactions: [iex.io/article/breaking-down-m-peg-and-d-peg](https://www.iex.io/article/breaking-down-m-peg-and-d-peg)

---

## iceberg

### Mechanism

A pattern of repeated equal-size fills at one price level, with consistent inter-fill timing and a constant displayed size. The underlying instrument is a *reserve order* — a large order whose displayed quantity (the "tip") is a fraction of the total size. As each tip fills, the order automatically refills from the hidden reserve until the full order is satisfied.

### Drivers

- Institutional execution targeting minimal market impact: a large order revealed in pieces signals less aggressive demand than the same size shown all at once
- Algorithmic execution targeting a participation-rate or VWAP benchmark
- Market-making activity refilling displayed depth after partial consumption
- Patient liquidity provision in less-liquid securities

### Inference limit

We cannot determine the total reserve size from wire data alone — only the displayed tip + the count of refills observed. We cannot determine whether the order is being worked by a human trader, an algorithm, or an institutional execution desk.

### Canonical interpretation

> "Iceberg orders display only a fraction of their total size — the visible tip refills automatically as it fills. This pattern is associated with institutional execution seeking minimal market impact, algorithmic VWAP or participation-rate strategies, and market-making activity. The wire data records the displayed fills but not the underlying reserve."

### Sources

- IEX general reserve-order documentation
- Quantitative Brokers: [Iceberg Right Ahead — algorithmic execution and hidden liquidity](https://www.quantitativebrokers.com/blog/iceberg-right-ahead)
- Harris, *Trading and Exchanges*, 2003 — hidden liquidity mechanics

---

## layering

### Mechanism

Multiple orders posted across distinct price levels on one side of the book, followed by rapid cancellation. Detected here as `OrderAdd` events at ≥5 distinct price levels followed by `OrderDelete` events on the same orders within a short window (typically <50 ms per order, cluster spanning <2 seconds).

### Drivers

This pattern has a **wide range** of possible causes, ranging from legitimate to manipulative:

- **Market-making activity:** posting quotes at multiple levels, then adjusting in response to fast-moving prices
- **Smart order router probes:** placing test orders to detect hidden liquidity, cancelling those that don't fill
- **Algorithmic price-discovery:** systems testing what fills at various levels to inform subsequent strategy
- **Risk-management response:** rapidly withdrawing posted depth when correlated instruments move
- **Spoofing (illegal):** placing orders with no intent to execute, designed to move the displayed market in one direction so an opposite-side trade benefits. **Spoofing requires documented intent.** The wire data alone cannot distinguish it from the legitimate drivers above.

### Inference limit

The wire data shows the *shape* of the activity (count, levels, duration, cancellation rate) but not the originator's *intent*. Per the SEC and US courts, demonstrating spoofing requires evidence beyond order-book mechanics — typically communications records, profit patterns across opposite-side trades, or regulatory findings.

We narrate layering as a pattern shape. We do not characterize it as spoofing or any other intent-bearing label.

### Canonical interpretation

> "Layering describes orders posted across multiple price levels and cancelled rapidly. The same wire signature is produced by market-making quote adjustments, algorithmic price-discovery probes, risk-management responses to correlated moves, and (when documented as such by regulators) spoofing. The wire data records the shape but not the originator's intent."

### Sources

- SEC enforcement guidance on spoofing: 17 U.S.C. § 6c(a)(5)(C) (Dodd-Frank anti-spoofing provision)
- Academic survey of spoofing detection challenges: [Spoofing in US futures markets: an interdisciplinary approach](https://academic.oup.com/cmlj/article/20/3/kmaf012/8257809)
- [Steel Eye — Five prominent market abuse behaviours and how to spot them](https://www.steel-eye.com/news/five-prominent-market-abuse-behaviours-and-how-to-spot-them)

---

## post_cancel_cluster

### Mechanism

A burst of orders placed and quickly cancelled — same shape as layering but without requiring distribution across multiple price levels. Detected here as paired `OrderAdd` / `OrderDelete` events on the same `order_id`, with lifetime < 50 ms, clustered in time (consecutive cancels within ≤ 50 ms gaps), with ≥ 20 orders in the cluster.

### Drivers

The driver set is even broader than layering's:

- **Market-maker quote churn:** continuous quote updates as the NBBO moves. Each update is technically a cancel-and-replace.
- **Quote-stuffing experimentation:** algorithms probing the book to characterize match-engine response patterns.
- **High-frequency-trading (HFT) response to a crumbling-quote signal:** firms withdrawing quotes when their predictive signals fire. IEX itself runs the Crumbling Quote Indicator (CQI) for this purpose — when the CQI fires, certain order types automatically become less aggressive.
- **Smart-order-router IOC probes:** Immediate-or-Cancel orders that fail to fill convert to short-lifetime cancellations.
- **Hedging against a fast-moving correlated instrument:** rapid quote adjustment as the related instrument's price changes.
- **Manipulative activity** (when documented): one-sided rapid post-cancel can be part of spoofing or layering schemes. Same intent caveats as layering.

### Inference limit

A high cancel-to-fill ratio is normal for displayed market-making at the top of book. The pattern's presence does not indicate misconduct; absence does not indicate the absence of misconduct.

### Canonical interpretation

> "Post-cancel clusters describe rapid burst of orders posted and cancelled within tens of milliseconds. Common drivers include market-maker quote updates following NBBO changes, smart-order-router probes, and rapid hedging adjustments. The wire data records the activity shape but not whether it was strategy execution, market-making, or directed activity."

### Sources

- IEX Signal documentation describing crumbling-quote detection: [iextrading.com/trading/signal](https://iextrading.com/trading/signal)
- Hasbrouck, *Empirical Market Microstructure*, 2007 — cancel rates in displayed market-making

---

## liquidity_withdrawal

### Mechanism

A flood of cancellations on a single symbol within a narrow window — detected here as ≥ 50 `OrderDelete` events on the same symbol with < 100 ms gaps between consecutive cancels. The book's displayed depth contracts visibly during the withdrawal.

### Drivers

- **Pre-news de-risking:** market makers withdrawing quotes when their order-flow models signal incoming material information
- **LULD-band approach:** automatic quote pullback as price approaches a single-stock circuit-breaker (LULD) band
- **Correlated-instrument volatility:** withdrawal in response to fast-moving futures, ETFs, or basket components
- **Earnings-window protocols:** scheduled withdrawal around known information-release windows
- **Cross-market routing changes:** liquidity providers shifting depth to other venues
- **Manipulative withdrawal** (rare and documented): coordinated withdrawal to create artificial scarcity. Same intent caveats as layering.

### Inference limit

Liquidity withdrawal is overwhelmingly defensive market-maker behavior. It frequently *precedes* visible events (halts, large prints, sharp price moves) but does not *cause* them. The withdrawal is the market makers' rational response to anticipated adverse selection, not the cause of subsequent volatility.

### Canonical interpretation

> "Liquidity withdrawals describe a rapid contraction of displayed depth on a symbol — typically defensive market-maker behavior ahead of anticipated volatility from news, LULD bands, or correlated-instrument moves. The wire data records the cancellation pattern but not the specific information or event the market makers were responding to."

### Sources

- IEX research on slow-market price discovery: [SEC working paper — Intentional Access Delays and Market Quality](https://www.sec.gov/dera/staff-papers/working-papers/07feb18_hu_iex_becoming_an_exchange)
- General microstructure literature on market-maker inventory and adverse selection (Stoll, 1978; Glosten-Milgrom, 1985)

---

## Catalog metadata

- **Version**: v1 draft (2026-05-21)
- **Status**: pending IEX-internal review before public launch
- **Update process**: catalog entries are versioned with the codebase. Updates go through PR review like any other source file. The catalog content is the source of truth for the Layer-0 verifier (token-subset agreement against this content), so adding new claim phrases requires explicit catalog update — preventing the LLM from drifting in interpretation language over time.

### Glossing convention

Technical terms (LULD, MCB, NBBO, CQI, ATS, VWAP, HFT) are introduced with their full English expansion on first appearance in any narration, with the acronym in parentheses. Subsequent uses within the same narration may use the acronym alone. The catalog itself uses this discipline throughout — anything the model produces from catalog material will inherit it.

### Layer-0 architecture and templated interpretations

Architecture decision pending the Day-4 prototype (see [`docs/layer-0-design.md`](../../../../docs/layer-0-design.md)). If we land on Option B (code-driven Layer 0), each scorer entry gets a `Templated interpretation` field beneath the canonical interpretation, with `{placeholder}` variables that code substitutes from the breakdown. The `halt` entry above shows the format. The other 6 will be added once the architecture decision is made.
