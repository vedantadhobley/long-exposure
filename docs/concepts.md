# Concepts — primer

A grounded explanation of what Long Exposure actually does, written for someone who hasn't worked in finance before. The implementation specifics are in `docs/scoring-and-narration.md` and `docs/temporal-design.md`; this doc is the conceptual primer that underlies all of those.

## 1. The order book

Every stock exchange maintains an **order book** — a list of all the orders waiting to trade, sorted by price. It looks like this:

```
                          AAPL order book at one moment
       ASKS  (people who want to SELL)
       ─────────────────────────────────────
        500 shares  at $200.05   ← 3rd best ask
        800 shares  at $200.03   ← 2nd best ask
       1200 shares  at $200.01   ← BEST ASK (lowest seller)
       ─────  the "spread"   ─────
        900 shares  at $199.99   ← BEST BID (highest buyer)
       1500 shares  at $199.97   ← 2nd best bid
        700 shares  at $199.95   ← 3rd best bid
       BIDS  (people who want to BUY)
```

- **BIDS** = orders to buy. The "best bid" is the highest price someone is currently willing to pay.
- **ASKS** (also called "offers") = orders to sell. The "best ask" is the lowest price someone is currently willing to accept.
- The gap between best bid and best ask is the **spread**. A tight spread (a penny or two) means the market is liquid; a wide spread means it's not.
- A **trade** happens when an incoming order crosses the spread. If you submit a "buy at market," your order chews through the best ask first (1,200 shares at $200.01), then the 2nd-best ask if you need more (800 at $200.03), and so on — paying progressively worse prices.

Orders can be added, modified, cancelled, or executed. Every one of those things is an **event**, and every event has a nanosecond timestamp.

## 2. What "the IEX data" actually represents (IMPORTANT)

IEX is **one** US stock exchange. There are about 16. The big ones are NYSE, Nasdaq, Cboe (BZX, BYX, EDGA, EDGX), and IEX. Each one has its own order book for every US-listed stock.

When you "buy 100 shares of AAPL," your broker can route that order to any of the 16 exchanges based on cost, speed, and where the best price sits. IEX gets a single-digit percent of total US equity volume — usually 2-5%.

**This matters because it limits what we can and can't observe:**

| What we can see | Why |
|---|---|
| Every order placed/cancelled/executed on IEX | DEEP+ publishes the full lifecycle |
| Every IEX-only trade and price level | Same feed |
| Trading halts and Reg SHO short-sale restrictions | These are **market-wide** by SEC mandate — same on every exchange |
| Opening / closing auction prices for IEX-listed symbols | IEX runs its own auctions for IEX-listed names |

| What we CAN'T see | Why |
|---|---|
| Trades on NYSE / Nasdaq / Cboe / etc. | Those are different exchanges with their own data feeds (each licensed separately, expensive) |
| The full national best bid/offer (NBBO) across all exchanges | Same — we'd need to combine all 16 feeds |
| Off-exchange ("dark pool") trades | Reported to the SIP only; not in IEX's free feeds |
| What an order's intent was | Order originators are anonymous in the public feed |

So when we say "IWM saw 4,895 cancellations in 11.7 seconds," we mean **on IEX**. The same trader was probably also cancelling on NYSE and Nasdaq during those same 11.7 seconds — we just can't see it. Our narrations should always be read as describing the IEX slice, which is a representative but partial view.

IEX is interesting *despite* being a small slice because:

- They publish the full order-by-order feed for free (most exchanges charge tens of thousands of dollars per month for this).
- They built their exchange around transparency — the "speed bump" that made them famous (and the *Flash Boys* book about it) was specifically about not letting HFTs front-run other traders' orders.
- The microstructure patterns we narrate (sweeps, layering, post-cancel clusters) reflect strategies traders run across the whole market — IEX is just where we get to **see** them.

## 3. The DEEP+ feed — what we actually consume

IEX publishes three different historical feeds for every trading day:

| Feed | What's in it | What you can do with it |
|---|---|---|
| **TOPS** | "Top of book" — best bid + best ask + executed trades + halts | Build a price chart, count trades; can't see individual orders |
| **DEEP** | Aggregate depth — all price levels, but aggregated (e.g. "$200.01 has 1,200 shares") | See the shape of the book; still can't see individual orders |
| **DEEP+** (DPLS in filenames) | Order-by-order — every individual order's add, modify, delete, execute | Reconstruct the full book to nanosecond precision; see who's posting and cancelling, when, in what patterns |

Long Exposure uses **DEEP+** because that's the only feed where the microstructure stories live. A "spoof-shaped post-cancel cluster" is only visible if you can see individual orders getting posted and yanked within milliseconds — which requires DEEP+ data.

DEEP+ has only been published since January 2025, so historical depth is limited to about a year. It's also the first one Long Exposure parses to open-source — no other free implementations exist as of this writing.

## 4. The four layers of data

This is the load-bearing mental model. Real numbers from 2026-05-08:

```
LAYER 0  — atomic events from the wire (~360 M per day)
─────────────────────────────────────────────────────
  orders_add        161,937,752   new orders posted to the book
  orders_delete     160,364,854   orders cancelled
  orders_modify      32,137,038   orders re-priced or re-sized
  orders_executed     2,352,875   orders filled (matched)
  trades              5,397,924   completed transactions (different shape than executed)
  status_events          12,755   halts + trading-status changes

LAYER 1  — patterns the scorer detects (~660 K per day)
─────────────────────────────────────────────────────
  post_cancel_cluster   343,563   bursts of short-lived orders (sub-50-ms lifetime)
  liquidity_withdrawal  233,179   cancellation storms on one symbol
  layering               73,456   synthetic depth across many price levels
  sweep                   9,703   single aggressive order walking through levels
  iceberg                   794   hidden order being worked through repeated fills
  large_trade               149   blocks ≥ $1 M notional
  halt                      105   actual trading suspensions

LAYER 2  — top-N per scorer, narrated (90 per day)
─────────────────────────────────────────────────────
  90 events selected for narration
  90 LLM-written prose paragraphs

LAYER 3  — daily synthesis  (NOT YET BUILT)
─────────────────────────────────────────────────────
  1 LLM-written paragraph per day reading all 90 Layer-2 narratives
  Output: "today's themes" — e.g. "heavy halt activity at the open in
  small caps; IWM and TQQQ had coordinated liquidity events at 14:00 ET"

LAYER 4  — inter-day  (NOT YET BUILT, requires 30-day backfill)
─────────────────────────────────────────────────────
  1 LLM-written paragraph per week / month, reading multiple Layer-3s
  Output: "this week's themes" — e.g. "halt frequency on biotech doubled
  Wednesday–Friday alongside FDA event volatility"
```

About **1 in every 4 million** Layer-0 wire events ends up in a Layer-2 narration. We're aggressively filtering signal from noise.

## 5. What "events within events" really looks like

A useful example. We narrate this AMD layering event:

> "AMD saw a layering event unfold across 116 distinct price levels between $428.91 and $431.00, involving 187 orders for a total of 34,100 shares. The activity spanned just 166.0 ms within the order book..."

Underneath that one Layer-2 paragraph, the supporting Layer-0 data is:

- **187 rows** in `orders_add`, each with the exact price, size, side, and nanosecond timestamp of one order
- **187 rows** in `orders_delete`, each cancelling one of those orders within 50ms of its placement
- All within a 166ms window
- Across 116 distinct price levels between $428.91 and $431.00

So the LAYER 1 → LAYER 2 transition involves **massive summarization**: 374 atomic rows collapse to a 5-field breakdown JSON. The LLM only ever sees the summary. The 374 individual rows are still in the database — we just don't pass them to the LLM.

This is the point where your "recursive deeper dive" idea applies. We could:

1. Take the Layer-2 event (the layering narration)
2. Pull the 374 Layer-0 rows that compose it
3. Pull *surrounding* context — trades on AMD in the 1-minute window, status of the book before/after
4. Make a second LLM call that produces an expansion: "...the layering came as AMD was trading at $429.50, just before a 8,000-share market buy executed at $431.00 — consistent with the pattern of layering ahead of an aggressive incoming order"

That second pass costs another LLM call per event but produces narrations that *explain why the event mattered*, not just *what it was*.

We haven't built this yet. The current Layer-2 narrations describe shape, not causation.

### 5a. Events at multiple time scales — nested vs adjacent (2026-05-20)

There's a related but separate phenomenon worth naming: the **same market dynamic** shows up across the scorer catalog at multiple time scales simultaneously.

- A market maker pulling liquidity manifests at the sec-scale as `liquidity_withdrawal` (≥50 cancels with <100 ms gaps; the event might span 11 seconds).
- The same activity manifests at the ms-scale as `post_cancel_cluster` events (≥20 orders posted-and-cancelled within tight windows; each cluster spans 10-50 ms).
- And as `layering` events when those rapid cancellations span multiple price levels.
- All three scorer types fire on the same wire-level events, just summarizing them at different resolutions.

We tried initially to merge these into a single "combined" event via interval-overlap. **It overshot:** an 11-second liquidity_withdrawal would absorb 28+ post_cancel/layering events nested inside it, producing a 28-constituent "combined" event whose narration was unreadable. See decisions.md 2026-05-20.

The right architectural distinction is **nested** vs **adjacent**:

- **Nested**: a long-duration event contains many short-duration events of *different* scorer types. They're the same phenomenon at different zoom levels — one is the *mechanism* of the other. *Solution*: enrich the long event's breakdown with summary counts of the short ones (`co_occurring` block), suppress the short events from selection. One narrative, deterministic context.

- **Adjacent**: same-symbol same-scorer events happening at different points in the day (e.g., TQQQ's 11 morning liquidity_withdrawals vs three isolated midday/afternoon ones). These are *different* phenomena that happen to be on the same symbol and scorer — markets behave structurally differently at different sessions. *Solution*: narrate each per-event independently. The day-level story is composed at Layer 3 daily synthesis, which uses an LLM with full-day context to thread the narrative dynamically without hardcoded gap thresholds.

This distinction is the architectural correction that came out of the 2026-05-08 combine experiment.

## 6. The hierarchical context-cascade architecture

```
                       ┌─────────────────────────────────┐
LAYER 4  ←── inter-day │ this-week themes (LLM call)     │
                       └─────────────────────────────────┘
                                       ▲
                       ┌─────────────────────────────────┐
LAYER 3  ←── synthesis │ today's themes (LLM call)       │
                       │  reads all 90 Layer-2 prose     │
                       └─────────────────────────────────┘
                                       ▲
                       ┌─────────────────────────────────┐
LAYER 2  ←── per-event │ 90× event prose (LLM call each) │
                       │  each reads its scored breakdown│
                       │  + optionally Layer-0 expansion │
                       └─────────────────────────────────┘
                                       ▲
                       ┌─────────────────────────────────┐
LAYER 1  ←── scoring   │ 660 K events from 7 scorers     │
                       │  pure code — no LLM             │
                       └─────────────────────────────────┘
                                       ▲
                       ┌─────────────────────────────────┐
LAYER 0  ←── raw       │ 360 M atomic events on the wire │
                       │  parsed binary DEEP+ feed       │
                       └─────────────────────────────────┘
```

Each layer's LLM call gets **only the structured digest of the layer immediately below**, never the raw firehose. That's the discipline that keeps prompts small, grounded, and verifiable.

Currently built: Layer 0, Layer 1, Layer 2.
Currently NOT built: Layer 3 (daily synthesis), Layer 4 (inter-day), the per-event Layer-0 expansion ("events within events").

## 7. Current scoring methodology

We have **7 scorers**, each detecting a specific market microstructure pattern from Layer-0 data. All are intra-day right now — they look only at one trading day's events, no historical context.

| Scorer | Detects | Input | Score formula |
|---|---|---|---|
| `halt` | Trading suspensions | `status_events` rows where status='H' | duration in seconds |
| `large_trade` | Block trades > $1M notional | `trades` rows with size × price > $1M | log10(notional) |
| `sweep` | Aggressive order walking through book | `orders_executed` cluster spanning ≥3 distinct prices in 10ms | log10(notional) × levels |
| `post_cancel_cluster` | Burst of orders cancelled within 50ms | `orders_add` ⨝ `orders_delete` short-lived pairs clustered in time | log10(shares) × order count |
| `layering` | Multi-level short-lived depth | Same as post-cancel + ≥5 distinct prices | log10(shares) × levels |
| `iceberg` | Repeated equal-size fills at same price | `orders_executed` runs at one price with low size variance | log10(shares) × fills |
| `liquidity_withdrawal` | Cancel storm on a symbol | `orders_delete` ≥50 cancels with <100ms gaps | log10(cancels) × cancels |

Each scorer is a Java class implementing `EventScorer` (push-model: emit one `ScoredEvent` at a time so memory is bounded). Adding a new scorer = one new class + one entry in `EventScorerRegistry.ALL` + one entry in `SelectTopEventsActivity.PER_SCORER_CAPS`.

**What's missing**:

- **Cross-event linking**: if two scorers fire on the same symbol within ~1 minute (the IWM case: `liquidity_withdrawal` + `post_cancel_cluster` 300ms apart), we currently narrate them as 2 separate events. Should be 1.
- **Threshold-based selection instead of top-N**: currently we cap at 10-20 events per scorer per day. On dramatic days that's too few; on quiet days that's too many low-quality picks. Should be score > threshold instead.
- **Inter-day scorers** (`VolumeDeviationScorer`, `TimeInBookDriftScorer`): need 30-day backfill first.

## 8. Current LLM methodology — two-pass + verifier

Each Layer-2 narration is produced by **two** LLM calls plus **one** pure-code check:

```
Layer 1 scored event  →  ExtractFacts (LLM #1)  →  blueprint JSON
                                                        ▼
                          RenderProse (LLM #2)  →  prose
                                                        ▼
                          GroundingVerifier (pure code) → pass/fail
                                                        ▼
                          row written to narratives table
```

**Extract** asks the model: "given this structured event, list the facts that should appear in narration, each mapped to its source field in the breakdown." Output is JSON.

**Render** asks the model: "given this blueprint, write 2-3 sentences using ONLY these facts. Don't add new numbers." Output is prose.

**Verify** is **pure Java code**: extract every number from the prose, check each one appears in either the blueprint or the breakdown (with `BigDecimal.stripTrailingZeros()` normalization so "$431.00" matches "431.0"). Flag any number that doesn't trace back.

Why this shape (and not just one LLM call):

- **Grounding is enforceable**. If the prose says "5h 34m" and the blueprint says "5h 34m" and the breakdown says `halt_duration: "5h 34m"`, the verifier passes. If the prose hallucinates "$13.60" and that number doesn't exist in either layer, the verifier flags it. The two-pass separation makes the verifier's job tractable — it doesn't have to do natural-language understanding, just substring/numeric matching against a known set.
- **The pure-code verifier is the moat**. Modern LLMs are amazing at prose. They're also confident liars. A deterministic verifier that says "every claim in the output must trace to a known fact" is the only correctness mechanism that doesn't get worse as models get bigger.
- **No tool calls, no RAG at the per-event level**. The breakdown is the contract. If the LLM wants context, the scorer (Layer 1 code) puts it in the breakdown. The LLM never reaches outside.

### Known gaps

- **Symbol fabrication**: one narration said "ODDTX" when the symbol was "ODTX" (extra D). The verifier checks numbers, not named entities. Easy fix: also pass-list the symbol string.
- **Thin breakdowns produce thin prose**: MOBI's halt narration only knows the duration and timestamps. It can't say "MOBI is a Chinese EV battery company" because we don't pass that context. Symbol enrichment (next section) fixes this.

## 9. Intra-day vs inter-day

| | Intra-day | Inter-day |
|---|---|---|
| **What it compares** | Things within one day | Today vs typical-day baseline |
| **Example claim** | "the sweep walked 11 distinct price levels in 18 ms" | "AAPL's volume was 4.7× its 30-day median" |
| **What we have** | All 7 scorers + narration pipeline | Designed but not built |
| **What's needed** | (done) | 30-day historical backfill + baseline cagg + inter-day scorers + breakdown enrichment |

Backfill order matters: we proved the pipeline works on 1 day first (2026-05-08), specifically *so we wouldn't waste 30 × 45-min parses if there was a bug). Now that pipeline is stable, backfilling 30 days is unattended background work.

## 10. Where the design is going

Three improvements lined up, in rough priority order:

### A. Cross-event linking + threshold-based selection (~half day)

Replace the rigid "top 10 per scorer" cap with:
1. Group events that fire on the same symbol within 1-2 minutes — they're likely the same underlying market event seen by different scorers.
2. Use a score threshold per scorer instead of a count cap. Most days will produce 30-50 narrations; dramatic days produce more, quiet days produce fewer.

Net effect: each narration is richer (because related events combine), the daily output volume varies naturally with how dramatic the day was, and the narration prose can mention multiple aspects ("IWM saw simultaneous liquidity withdrawal AND a coordinated post-cancel cluster").

### B. Symbol enrichment (~half day)

Add a `symbols` reference table with per-ticker metadata:

```
symbol  | company_name           | sector              | is_etf | last_close
─────── ────────────────────── ─────────────────── ───────── ──────────────
AAPL    | Apple Inc.             | Information Tech    | false  | 213.04
MOBI    | Mobiv Acquisition      | Financial Services  | false  | 9.87
IWM     | iShares Russell 2000   | (ETF — small caps)  | true   | 218.55
VTWO    | Vanguard Russell 2000  | (ETF — small caps)  | true   | 99.12
```

Source: a one-time scrape of public listings + the IEX SecurityDirectory messages we already parse from the feed. Joined into the breakdown at scoring time. Narrations gain "the iShares Russell 2000 ETF IWM" / "small-cap acquisition vehicle MOBI" / "Apple Inc. (AAPL)" without the LLM having to guess.

### C. The hierarchical context cascade (~multi-day)

Build Layer 3 (daily synthesis) and the per-event Layer-0 expansion ("events within events"):

1. **Layer-0 expansion** per Layer-2 event: another LLM call that reads the Layer-2 narration + a slice of surrounding Layer-0 data (price 5 min before/after, surrounding events on same symbol, day's volume context) and produces an "expansion" — "the layering came right before an 8,000-share market buy at the same price." Costs +1 LLM call per event.
2. **Layer 3 daily synthesis**: one LLM call per day reading all 90 narrations + their expansions, producing a top-of-page paragraph ("today saw heavy halt activity at the open; coordinated IWM/TQQQ liquidity events at 14:00 ET; large blocks concentrated in semiconductors").
3. **Layer 4 inter-day** (later, needs backfill): periodic LLM call producing weekly/monthly summaries.

Each layer reads the *structured outputs* of the layer below, never the raw firehose.

## 11. What we can and can't show on the website right now

Given just the data already in `narratives` (90 narrations on 2026-05-08):

| | Can show today | Needs more work |
|---|---|---|
| All 90 narrations for a day, grouped by scorer | ✓ live | |
| Chronological view (sorted by event_ts) | trivial — API tweak | |
| Per-symbol view (all events for one ticker across days) | ✓ live but sparse (only 1 day loaded) | rich with backfill |
| Drill-down: show the raw breakdown + blueprint per event | ✓ API has it; UI doesn't expose yet | UI component |
| Daily-synthesis paragraph at top of page | | needs Layer 3 |
| Cross-day comparison ("today vs typical") | | needs backfill + Layer 4 |
| Price/volume chart overlaid with event markers | | needs chart component + per-symbol intraday aggregation |
| Symbol search / autocomplete | | needs the `symbols` reference table from Phase B |
| "Today saw heavy semi activity" rollup | | needs symbol enrichment with sector data |

The frontend `long-exposure-browser.tsx` is intentionally minimal v1 — it proves end-to-end connectivity. Polishing it can happen incrementally as the underlying data gets richer.

## 12. TL;DR for new agents reading this doc

- IEX is 1 of 16 US exchanges, ~2-5% of US equity volume. We see only IEX's slice. Halts are market-wide; trades / orders / quotes are IEX-only.
- We consume the **DEEP+** feed — order-by-order, ~360M atomic events per trading day.
- Pipeline: parse → 7 scorers → top-N selection → 2-pass LLM narration with pure-code grounding verifier.
- Currently 100% intra-day; inter-day designed but requires 30-day backfill to enable.
- Top open improvements: cross-event linking, symbol enrichment, daily synthesis pass, per-event Layer-0 expansion.
- The breakdown JSON is the LLM's only context — every claim in the prose must trace to a field in it. Symbol enrichment is the cheapest lift in narration quality because it directly improves what's in the breakdown.
