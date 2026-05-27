# Analytics Catalog — computations the code layer can feed the LLM

> **Status: design / menu (2026-05-27).** This is the catalog of *computations* the
> code layer (scorers + breakdown enrichment) can calculate and hand to the LLM as
> grounded facts. None of the Tier-2/3 items below are built yet; the Tier-1 set is
> the proposed next scorer/enrichment wave (post-launch — it requires a full re-run,
> which the 2-week scope + content-addressed skip keep tractable). Companion to
> `scoring-and-narration.md` (the scorers + the breakdown contract),
> `pattern-catalog.md` (the *prose-side* interpretation vocabulary), and
> `interpretation-design.md`.

## 0. Why this exists + the architectural contract (unchanged)

The division of labor is fixed and load-bearing: **code computes every number; the
LLM only wraps numbers that are already in the `breakdown`; the pure-code verifier
rejects any figure the prose introduces that isn't there.** This doc does *not*
change that — it expands the *left* side (what code computes). We are explicitly
**not** giving the LLM tool calls: a tool call is a slower, non-deterministic,
unverifiable version of a scorer, and it would dissolve the verifier moat (see
`scoring-and-narration.md` "Modern LLM query patterns" — tool calling rejected).

**Why expand the menu.** Today's breakdowns carry fairly flat stats — counts, sums,
`log10` scores, a few ratios, one CV, basis points. With order-by-order DEEP+ data we
can compute genuinely sophisticated microstructure metrics that turn the prose from
"187 orders across 116 levels in 166 ms" (shape) into "…an order-to-trade ratio of ∞
and a one-sided book imbalance that swung the OFI sharply negative just before the
spread widened 3×" (quantified insight). That is the most on-brand growth there is:
deep analytics from the order-level feed nobody else makes legible.

**Worth the re-run?** Yes, for Tier 1–2 (see §4). Each new metric is a new derived
`breakdown` field or a new scorer; deploying it = a re-run, made incremental by the
`event_hash` skip. The 2-week dataset is the reason the re-run stays hours-not-days.

## 1. The data we actually compute from (and its limits)

| Source | Grain | Carries |
|---|---|---|
| `orders_add` / `orders_modify` / `orders_delete` | per displayed order | side, price, size, ns timestamp, order id |
| `orders_executed` | per displayed-order fill | size, price, trade id |
| `trades` | per execution | size, price, sale-condition flags |
| `order_lifecycle` | per order | add↔terminal pairing + `lifetime_ns` |
| derived `OrderBook` / BBO | reconstructable at any ns | full displayed book, every level |
| `daily_volume_by_symbol` cagg | per symbol per day, ~1 yr | volume, trade count, price hi/lo/avg (the baseline) |
| `symbols` | per ticker | company, exchange, ETF flag, round lot, prev close, LULD tier |

**Inference limits (must qualify every metric).** IEX is ~2–5% of US volume, so
everything is **"on IEX"** — not the consolidated tape. We have **no NBBO**, **no
participant IDs** (anonymized), **no off-exchange/dark prints**. Consequence: any
metric that wants *consolidated* flow (true VPIN, true Kyle's λ, true effective
spread vs the *national* mid) is an **IEX-slice approximation** and must be narrated
as such. Low-volume names make several flow metrics noisy — see the effort tiers.

## 2. The catalog

Each entry: **what it is · sketch · data · narration it unlocks · inference limit · effort**.
Effort: 🟢 cheap (arithmetic over what we already scan) · 🟡 moderate (a windowed/dist
computation or a second pass) · 🔴 research-grade (estimation, fragile on the slice).

### 2.1 Liquidity & order-book state

- **Bid-ask spread (abs + bps), time-weighted** · `ask−bid`, integrate over the window ÷ duration · BBO · "spread averaged 4.2 bps, 3× its session norm" · IEX-only BBO ≠ NBBO · 🟢
- **Quoted depth & depth imbalance** · sum displayed size top-N levels each side; `(bid−ask)/(bid+ask)` · book · "bid depth was 5× ask depth at the touch" · displayed only · 🟢
- **Order Flow Imbalance (OFI)** · per book update: `Δbid_size(adds−cancels) − Δask_size(adds−cancels)`, summed over window · adds/modifies/deletes with side · *the* canonical short-horizon price predictor — "OFI swung sharply negative in the 2 s before the drop" · IEX-slice OFI · 🟡
- **Book-shape slope** · regress cumulative depth on price distance from touch · book · "liquidity was unusually thin beyond the touch — a steep book" · displayed only · 🟡
- **Effective / realized spread & price impact** · effective `=2·|exec−mid|`; realized = mid move over Δt after; permanent vs temporary decomposition · trades + BBO · "the block paid 9 bps effective spread; 6 bps was permanent impact" · vs IEX mid, not NBBO · 🟡
- **Kyle's λ (price impact per unit signed volume)** · slope of `Δmid` on signed volume · trades + book · "price moved 1 bp per $50k of one-sided flow" · slice-noisy on thin names · 🔴

### 2.2 Order- & trade-flow

- **Order-to-trade ratio / cancel ratio** · `orders_submitted / trades` (and cancels/trades) · orders + trades · the regulator's manipulation-shape metric, and it quantifies the spoof shape *without intent* — "187 orders posted, 0 filled → order-to-trade ratio ∞" · on IEX · 🟢
- **Signed order flow / net aggressor imbalance** · classify each trade buy/sell (tick or Lee-Ready vs mid), net them · trades + BBO · "82% of aggressor volume hit the offer" · slice · 🟢
- **Trade-sign autocorrelation** · lag-1 autocorr of signed trades · trades · "trades clustered persistently on the sell side (autocorr 0.4)" · slice · 🟡
- **Trade-size distribution / round-lot clustering** · histogram; share at 100-share multiples · trades · "fills clustered tightly at 100-share lots — retail-shaped" · — · 🟢
- **VPIN (flow toxicity)** · volume-bucket `|buy−sell|/total`, rolling · trades · "flow toxicity spiked into the top decile before the halt" · needs consolidated volume to be true VPIN; IEX-slice proxy only · 🔴
- **Participation rate** · symbol volume ÷ its baseline (or ÷ day total) · trades + cagg · "traded at 4× its typical IEX participation" · slice · 🟢

### 2.3 Temporal point-process (event arrival timing)

- **Burstiness (Fano factor)** · `Var/Mean` of event counts in fixed sub-windows; 1 = Poisson, ≫1 = bursty · any event stream · "cancels arrived 12× more clustered than random" · — · 🟡
- **Inter-arrival CV / distribution** · CV of gaps; exponential vs heavy-tailed · timestamps · "near-constant inter-arrival — machine-paced" · — · 🟢
- **Periodicity / cadence (autocorrelation or small FFT)** · peak in the arrival-time autocorrelogram · timestamps · "orders on a fixed 5 ms beat — an algorithmic signature" · — · 🟡
- **Hawkes self-excitation intensity** · fit branching ratio (events triggering events) · timestamps · the rigorous model behind "bursty/cascading" · estimation-fragile · 🔴

### 2.4 Order lifecycle (from `order_lifecycle`)

- **Time-in-book distribution + drift** · median/quantiles of `lifetime_ns`; KL-divergence or quantile shift vs the trailing baseline · `order_lifecycle` + baseline · the planned `TimeInBookDriftScorer` — "median order lifetime on SPY collapsed 800 ms → 90 ms, a regime change" · slice · 🟡
- **Fill rate / fill ratio** · executed size ÷ posted size · lifecycle · "only 3% of posted size ever executed" · displayed · 🟢
- **Cancel-latency distribution** · time from add → delete, distribution · lifecycle · "half of all orders were pulled within 8 ms of posting" · — · 🟢
- **Resubmission / pinging pattern** · same (size,price) re-posted after cancel · lifecycle · "the same 200-share order was re-posted 14 times" · no participant id — pattern only · 🟡

### 2.5 Volatility & price dynamics (from the derived mid series)

- **Realized volatility** · √Σ(squared mid returns) over the window, annualized · BBO mid · "realized vol in the window ran 5× the day's average" · IEX mid · 🟡
- **Range / true-range (ATR-like)** · high−low; max(H−L, |H−Cprev|, |L−Cprev|) · prices · "a 2.1% intra-window range" · — · 🟢
- **Variance ratio (Lo–MacKinlay)** · `Var(k-period)/(k·Var(1-period))`; <1 mean-reverting, >1 trending · mid series · "prices mean-reverted hard after the sweep (VR 0.4)" · slice · 🟡
- **Jump detection (bipower variation)** · realized variance vs bipower variation gap · mid series · "a discrete price jump, not continuous drift" · slice · 🔴
- **Post-event reversion / MAE** · mid move N seconds after the event; max adverse excursion · prices · "price reverted 80% of the sweep's impact within 30 s — transient" · slice · 🟡

### 2.6 Inter-day deviation (needs the cagg / baselines — generalizes `volume_deviation`)

- **Robust z-score (median + MAD)** · `(today − median)/(1.4826·MAD)` of any daily metric · cagg · "today's cancel rate was 6σ above its 2-week norm" · — · 🟢
- **Percentile rank in trailing window** · rank of today within the window · cagg · "the busiest day for halts in the trailing fortnight" · — · 🟢
- **EWMA control band** · is today outside μ ± kσ of an exponentially-weighted baseline · cagg · "broke its upper control band for the first time in N days" · — · 🟡
- **Change-point (CUSUM)** · cumulative-sum detector for a regime shift · cagg/series · "a sustained shift in order lifetime starting ~14:00" · — · 🟡

### 2.7 Concentration & breadth (day-level → SYNTHESIZE / AGGREGATE)

- **HHI / Gini on volume concentration** · Σ(share²) across symbols · cagg/day · "the day's flow was unusually concentrated — top 3 names = 60% (HHI 0.18)" · IEX slice · 🟢
- **Event-type / symbol entropy** · Shannon entropy of the day's event mix · scored_events · "an unusually one-note day — 80% liquidity-withdrawal events" · — · 🟢
- **Sector / ETF-family clustering** · group events by sector/family · needs sector data (symbol enrichment gap) · "semiconductors carried the day's layering" · — · 🟡 (blocked on sector metadata)

### 2.8 Manipulation-*shape* quantification (shape, never intent)

> These quantify the *geometry* of a pattern. Per the `pattern-catalog.md` discipline:
> **no intent claims from wire data alone** — patterns have many legitimate drivers.
> These are inputs that make the *shape* precise, not a verdict.

- **One-sidedness asymmetry** · |buy−sell| order/volume share within the cluster · orders · "entirely one-sided — 100% sell-side layering" · — · 🟢
- **Depth-weighted distance from touch** · mean price distance of the cluster's orders from BBO, size-weighted · orders + BBO · "the layered depth sat 8–40 bps off the touch" (near=aggressive, far=decorative) · — · 🟡
- **Cancel-after-opposite-move** · fraction of a side's orders cancelled within Δt of an opposite-side trade/price move · orders + trades · "92% of the bids were pulled within 20 ms of an offer-side print" · the spoof *shape*, not the claim · 🟡
- **Quote-stuffing rate** · message rate vs baseline message rate · all messages · "message rate spiked 30× — quote-stuffing-shaped" · — · 🟢

### 2.9 Per-scorer-type bespoke metrics ("type-specific")

| Scorer | Bespoke metrics worth pre-computing |
|---|---|
| `halt` | LULD band width at halt; time-to-resume vs the LULD-tier-typical; pre-halt OFI / spread behavior; was it news (T1) vs LULD vs MCB |
| `large_trade` | % of the symbol's ADV; price vs session VWAP (block premium/discount); aggressor side; post-print reversion |
| `sweep` | **slippage** (last fill vs first), levels consumed, $ liquidity removed, effective spread paid, post-sweep reversion (transient vs permanent) |
| `iceberg` | implied **reserve size** (extrapolated from refill cadence), display ratio, refill-cadence regularity |
| `layering` | one-sidedness, depth-weighted distance from touch, order-to-trade ratio, cancel latency |
| `post_cancel_cluster` | burstiness/Fano, cancel-after-opposite-move, periodicity |
| `liquidity_withdrawal` | % of displayed book removed, two-sidedness, **recovery time** (depth restoration) |
| `volume_deviation` | robust z + percentile rank (not just the ratio); intraday *timing* of the surge (open/midday/close); accompanying spread/OFI behavior |

## 3. What we actually pass to the LLM — the breakdown-contract discussion

> This is the part to decide together. The catalog is the supply; this is the *ration*.

**The tension.** ~40 candidate analytics ≠ 40 breakdown fields. Three forces fight:
1. **Attention dilution** — a 40-field breakdown produces unfocused, kitchen-sink prose; the LLM narrates whatever's shiny, not what matters.
2. **Token cost** — every field is prompt tokens × every event × every re-run; and SYNTHESIZE/AGGREGATE already brush joi's `n_ctx`.
3. **Correctness is *not* a force here** — the verifier accepts any field that's in the breakdown, so adding fields never *breaks* grounding; it only affects prose *quality* and cost.

**Three design options:**

| Option | How | Pro | Con |
|---|---|---|---|
| **A. Pass-all, prompt curates** | put every computed metric in the breakdown; prompt says "lead with the most significant" | simplest; LLM has everything | dilution + cost + inconsistent field choice across events |
| **B. Per-scorer-type curated set** | each scorer emits a tight **narration set** (~8–15 fields) chosen as the most narration-worthy for *that* pattern; the full analytic set lands in `scored_events` for drill-down/API | focused prose, bounded cost, type-appropriate | a curation decision per scorer; the "best" field set is a judgment |
| **C. Two-tier breakdown** | `headline` (must-use, ~5) + `detail` (may-use appendix) in one breakdown | LLM leads with headline, enriches from detail | prompt has to respect the tiering; more structure to verify against |

**Recommendation: B, with a dash of C.** Per-scorer-type curated narration sets keep
prose focused and costs bounded; the *full* analytics still get computed and stored in
`scored_events` (and surfaced in the frontend drill-down — that's where the "show the
grounding" wow item lives), but only the curated subset enters the LLM-facing breakdown.
Within that subset, a light headline/detail split (C) helps DESCRIBE lead with the
1–2 metrics that *define* the event. The verifier haystack = whatever's in the breakdown,
so this is purely a quality/cost choice, reversible per scorer.

**Per-stage rationing** (they don't all want the same fields):
- **DESCRIBE** — the curated narration set (the event's defining metrics).
- **INTERPRET** — the narration set + the *surrounding-window* deltas (it already reads ±60 s; the new flow/vol metrics computed on those windows are its natural fuel).
- **SYNTHESIZE / AGGREGATE** — *not* per-event analytics; the **day/period-level** ones (§2.7 concentration/breadth/entropy) + the distribution of which metrics fired. Keep per-event detail out (context-budget).

**Open questions for the discussion:**
- Which ~8–15 metrics are the "narration set" per scorer? (§2.9 is the first draft.)
- Do we compute analytics *eagerly* for every scored event, or *lazily* only for the ~90–170 *selected* events? (Lazy is far cheaper — most analytics only matter for narrated events. Likely lazy, in a `SelectTopEvents`→enrich step or in the scorer for selected rows only.)
- A shared `com.longexposure.analytics.*` package the scorers call, vs inline per scorer? (Shared — OFI, z-score, burstiness are reused across scorers.)
- Frontend: the full analytic set behind the drill-down badge ("every figure traces to IEX data") is a strong showcase — the *un*-narrated metrics become the "expand for the full microstructure readout."

## 4. Prioritization — what's actually worth implementing (value × effort)

Opinionated. **Don't build all 40** — several are finance-canonical but fragile or
marginal on a 2–5% volume slice.

- **Tier 1 — do first (high signal, 🟢/🟡, computable cleanly):** order-to-trade ratio · OFI · sweep slippage + post-event reversion · time-in-book distribution drift (`TimeInBookDriftScorer`) · robust z-score + percentile rank (generalizes `volume_deviation`) · realized volatility · one-sidedness · participation rate (% of baseline). These are the ones that turn "what happened" into "what it cost / what it implies."
- **Tier 2 — strong, slightly more work:** burstiness (Fano) · periodicity/cadence · effective/realized spread · depth & depth-imbalance · concentration (HHI) · cancel-after-opposite-move · iceberg reserve estimation.
- **Tier 3 — defer or skip (🔴, research-grade or slice-fragile):** VPIN, Kyle's λ, Hawkes, jump detection, change-point. Worth *knowing* we considered them; each needs careful estimation and is noisy on the IEX slice. Revisit only if a specific narration demands it.

## 5. Inference-limit discipline (carried from `pattern-catalog.md`)

Every metric narrates with three guardrails: **(1)** "on IEX" qualifier (we see the slice);
**(2)** shape, not intent — a high order-to-trade ratio or one-sided OFI is a *geometry*,
not a verdict (market-making, SOR probing, risk de-risking, and documented spoofing all
produce overlapping shapes); **(3)** multiple drivers enumerated, never "the" driver.
The analytics make the shape *precise*; the catalog's no-intent rule makes the prose *safe*.

## 6. Build path (post-launch)

1. **Shared `analytics/` layer** — pure functions (OFI, robust-z, Fano, slippage, …) the
   scorers/enrichment call. Unit-tested in isolation (these are deterministic math — easy
   to test, unlike prose). **Migrate the existing inline stats here too** (`orders_per_level`,
   `size_cv`, `basis_points`, `deviation_x`, `notional_per_level`, …) so there's *one* home for
   every computation, not "new ones in the package, old ones scattered in scorers." Done right
   this is a **byte-identical refactor** (same values out → same `breakdown` bytes → same
   `event_hash` → **no re-run**); do it alongside the new stats, not as a separate churn.
2. **Compute lazily for selected events** — enrich only the ~90–170 narrated events
   (a step after `SelectTopEvents`, or in-scorer for selected rows), not all 660 K.
3. **Per-scorer narration sets** (§3 option B) into the breakdown; full set into
   `scored_events` for drill-down.
4. **`TimeInBookDriftScorer`** as the first net-new *scorer* built on this layer (it's
   also the §2.6 of `tiered-baselines-design.md`).
5. **One re-run** to deploy — incremental via the `event_hash` skip; 2-week scope keeps it
   to hours. This is the deliberate cache-bust the whole "stick to 2 weeks for now" call
   was protecting.

Nothing here touches the verifier or the LLM-as-renderer contract — it only makes the bag
of facts richer. That's the entire point.
