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

- **One-sidedness asymmetry** · |buy−sell| order/volume share within the cluster · orders · "the withdrawal was 95% bid-side — makers pulled only their buy quotes" · — · 🟡
  > ⚠️ **Degenerate for `layering` / `post_cancel_cluster`** (confirmed 2026-05-27). Both scorers cluster by `(symbol, side)` — every order in an emitted event is the *same* side by construction, so one-sidedness ≡ 1.0, a constant artifact of the clustering key, not a signal. The side-imbalance idea's real home is **`liquidity_withdrawal`** (clusters by symbol only, so a withdrawal can be bid-side / ask-side / two-sided) — but `orders_delete` rows don't carry side, so it needs an `order_lifecycle` side-join. That's the genuinely valuable version ("makers pulled only the bid 200 ms before the halt"); logged as a post-launch fast-follow (todo). The original "100% sell-side layering" example was wrong — it was reading the clustering key back out.
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
| `layering` | depth-weighted distance from touch, order-to-trade ratio, cancel latency, **burstiness/Fano** (one-sidedness is degenerate here — clustered by side) |
| `post_cancel_cluster` | **burstiness/Fano**, cancel-after-opposite-move, periodicity (one-sidedness is degenerate here — clustered by side) |
| `liquidity_withdrawal` | % of displayed book removed, **two-sidedness** (the real home for the one-sidedness metric — needs an `order_lifecycle` side-join), **recovery time** (depth restoration) |
| `volume_deviation` | robust z + percentile rank (not just the ratio); intraday *timing* of the surge (open/midday/close); accompanying spread/OFI behavior |

## 3. What data goes to which LLM call, and why

> The catalog (§2) is the *supply*. This is the *allocation* — and it's not primarily a
> rationing problem, it's a **relevance + zoom** problem. Reframed 2026-05-27.

### 3.0 The reframe: compute all · show all · pass rich · narrate selectively

The earlier framing ("pick a tight ~8-field whitelist to save budget") was wrong on its own
terms: per-event breakdowns are cheap (a few hundred tokens), and the tiers that *do* have a
context budget read **prose**, not breakdowns. And **correctness is not a force here** — the
verifier accepts anything in the breakdown, so passing more never *breaks* grounding. The
real decisions are:

- **Compute everything** that's relevant + affordable → `scored_events` (the analytics are cheap; have them all).
- **Show everything** in the UI drill-down ("every figure traces to IEX data" — the full quant readout; analytics are never wasted, the reader sees all of them).
- **Pass a rich, *relevant* set** to each LLM call — generously, because it's safe and cheap.
- **Narrate selectively** — the prose *leads* with the defining metrics and weaves in the notable; selectivity lives in the *sentence* (a prompt/structure concern), not in *withholding data from the system*. A paragraph that lists 25 stats is a CSV, not journalism — but that's fixed by a priority hint + the structured slots, not by starving the model.

So "what to pass" reduces to two real questions: **relevance** (which metrics are meaningful for *this event type*) and **zoom** (which belong at *this call's altitude*). Both follow from the call's *job*.

### 3.1 The organizing principle — data follows the call's job (a zoom ladder)

The four LLM calls form a **ladder of zoom**, and each wants data at *its* altitude. Passing
finer data up the ladder is noise; passing coarser data down is irrelevant.

| Call | Job | Zoom | Data it wants | Data it must NOT get |
|---|---|---|---|---|
| **DESCRIBE** | state what happened, factually | this *event*, zoomed in | the event's **defining facts + shape** (magnitude, geometry, identity, time) | surrounding-window or cross-event data (it's not structured to verify claims about them) |
| **INTERPRET** | place the event in its **neighborhood** — sequence, cause-shape, cost | this event **+ its ±60 s surround** | event facts **+ the contextual analytics** (reversion, OFI around it, pre/post windows) **+ the catalog entry** (mechanism + drivers) | other days; period-level aggregates |
| **SYNTHESIZE** | the **day's** cross-event themes | this *day*, zoomed out | the day's per-event **interpretation prose** + **day-level** aggregates (concentration, breadth, scorer-mix, time-of-day) | per-event raw analytics (already summarized in the prose); other days |
| **AGGREGATE** | **period vs history** — the trend | this *period*, zoomed way out | the period's daily/weekly **rollup prose** + **period-level** metrics + the **prior-period** window | per-event or per-day detail (wrong altitude) |

This is why the right answer isn't one breakdown — it's **the event's breakdown for the
zoomed-in calls (DESCRIBE/INTERPRET), and progressively coarser aggregates for the
zoomed-out calls (SYNTHESIZE/AGGREGATE)**, which is already how the pipeline is built; the
analytics just make each altitude *richer*.

### 3.2 Why some data is deliberately withheld (per call)

Not everything computed belongs in every call. Four exclusion classes, each for a concrete reason:

1. **Raw / wire-internal** (`ts_nanos`, `trade_id`, `order_id`, `sale_condition_flags`, the wire `side` enum) — excluded from *all* LLM calls. These leaked into prose before ("trade ID 173670…", `"side": "8"` → "sell"); they're identifiers, not facts a reader wants. Kept in `source_refs`/`scored_events` for joins + drill-down.
2. **Redundant encodings** of the same quantity (`notional_dollars` *and* `notional_million_dollars`; a raw value *and* its formatted string) — pass *one* canonical form per call. Passing both invites the model to render them inconsistently across events.
3. **Irrelevant-to-type** — iceberg-reserve on a halt, LULD-band on a sweep. Structurally `null`; passing null/NA fields is pure noise that dilutes attention. Each scorer emits only the metrics that *mean* something for its event type (§3.3).
4. **Wrong-altitude** — per-event analytics in SYNTHESIZE, per-day detail in AGGREGATE. Not because of budget alone, but because the call's *job* is at a coarser zoom; finer data is a distraction from the cross-event/longitudinal pattern it exists to find.

### 3.3 Per-scorer data spec, with the *why*

For each scorer: the financial meaning → what DESCRIBE *states* → what INTERPRET can *infer*
(the contextual analytic is the fuel) → what bubbles to SYNTHESIZE/AGGREGATE → the **wow
metric** (the one that reveals structure a human couldn't see in the raw feed). Bold = net-new analytics.

> **Wiring status (2026-05-27).** This section is the *full design*; only a thin
> **cheap-now tranche** is wired pre-launch (pure arithmetic over data the scorer already
> holds, or one bulk baseline read — no new computation infrastructure):
> - ✅ `large_trade` → `pct_of_baseline_volume` (% of trailing-median IEX volume)
> - ✅ `sweep` → `slippage_bps` + `slippage_direction`
> - ✅ `volume_deviation` → `robust_z` + `percentile_rank`
> - (✅ `post_cancel` / `layering` already carry `median_lifetime_ms`; `iceberg` already carries size-CV)
>
> Everything else here — OFI, post-event reversion, depth-weighted distance from touch,
> implied reserve, the `liquidity_withdrawal` side-split + %-of-book + recovery, the halt
> pre-event signature — needs **new compute** (book-state replay, post-event price/depth
> windows, side-joins, tier baselines) and is the **post-launch analytics wave** (§6, todo
> #26–28). The genuinely-cheap next extensions are **burstiness (Fano)** on post_cancel +
> layering and **inter-fill cadence CV** on iceberg (both pure math over timestamps already
> in the cluster).

- **halt** — *trading suspended; market-wide, the one thing we see fully.* DESCRIBE: company, duration, reason (T1/LULD/MCB), session phase. INTERPRET infers regime from the **pre-halt OFI + spread behavior** ("spreads had already tripled and OFI gone one-sided in the minute before — the book was bracing") and **time-to-resume vs the LULD-tier norm** ("resumed unusually fast for a tier-2 name"). Up: halt *count* + reason mix per day/week. **Wow:** the pre-halt microstructure signature — the book reacting *before* the suspension.
- **large_trade** — *a block changed hands.* DESCRIBE: company, notional, size, price. INTERPRET infers significance from **% of the symbol's ADV** ("a single print = 18% of its typical *daily* IEX volume") + **price vs VWAP** (premium/discount) + **post-print reversion** ("price held — informed, not liquidity-driven"). Up: block count, $ concentration. **Wow:** % of ADV — turns "10,582 shares" into "a fifth of the day in one trade."
- **sweep** — *one aggressive order walked the book.* DESCRIBE: company, notional swept, levels, shares. INTERPRET infers the *cost + nature* from **slippage** ("paid 11 bps walking 8 levels") + **post-sweep reversion** ("80% reverted in 30 s — transient impact, the aggressor overpaid into thin liquidity, not informed flow") + **effective spread**. Up: aggressive-flow share of the day. **Wow:** slippage + reversion — the *cost of impatience* and whether it was *informed*, neither visible raw.
- **post_cancel_cluster** — *a burst of orders posted and yanked in ms.* DESCRIBE: company, orders, total shares, median lifetime (already wired). INTERPRET infers shape from the **order-to-trade ratio** ("131 orders, 0 fills") + **burstiness** (Fano — cheap, all add-timestamps in hand) + **cancel-after-opposite-move** ("84% pulled within 20 ms of an offer-side print"), framed against the catalog's *multiple drivers* (market-making, SOR probing, risk, …). Up: cluster frequency, symbols. **Wow:** order-to-trade ∞ + cancel-after-opposite-move — the spoof *shape* made undeniable, without the intent claim. *(one-sidedness is degenerate here — clustered by side, see §2.8.)*
- **layering** — *post_cancel across many price levels.* DESCRIBE: company, orders × distinct levels, price range (bps), median lifetime (already wired). INTERPRET adds **depth-weighted distance from touch** ("the fake depth sat 8–40 bps off — decorative, not competing") + **burstiness** + order-to-trade. Up: layering frequency. **Wow:** depth-weighted distance — *where* the manufactured depth sat tells you what it was for. *(one-sidedness is degenerate here — clustered by side, see §2.8.)*
- **iceberg** — *a hidden order revealing itself in equal fills.* DESCRIBE: company, fills, total shares, price. INTERPRET infers the *hidden* size from the **implied reserve estimate** ("the displayed 200-share tip implies a ~50k-share reserve worked over 8 min") + **refill-cadence regularity** (machine vs human). Up: iceberg presence by symbol. **Wow:** implied reserve — *seeing the part of the order that's designed to be invisible.*
- **liquidity_withdrawal** — *a cancel storm; market-makers pulling quotes.* DESCRIBE: company, deletes, duration, rate/sec. INTERPRET infers from **% of the displayed book removed** ("pulled 70% of top-5 depth in 11 s") + **two-sidedness** (both sides = de-risking ahead of news/vol; one side = directional) + **recovery time** ("depth restored within 4 s — a flicker, not a flight"). Up: withdrawal clustering across correlated names. **Wow:** % of book removed + recovery — the *severity and persistence* of the liquidity vacuum.
- **volume_deviation** — *today is unusual for this symbol* (inter-day). DESCRIBE: company, deviation ×, today vs baseline volume. INTERPRET adds **robust z + percentile rank** ("6σ above its fortnight norm — the busiest in the window") + **intraday timing** ("the surge was all in the final 20 min") + accompanying **spread/OFI**. Up: how many names broke their norm (breadth). **Wow:** robust-z + timing — *how* anomalous and *when*, not just a bare multiple.

### 3.4 The point of all this — grounded *inference*, not a stats dump

The aspiration (yours): the LLM should output **inference about what's happening, beyond the
raw data.** This is exactly what the right analytics at the right zoom *enable* — and the
reason the work is worth it. DESCRIBE *can't* infer (it has only the event). The inference
lives one rung up, at **INTERPRET** and above, and the analytics are its raw material:

- "reverted 80% in 30 s → **transient liquidity impact, not informed flow**" — inference, grounded in the reversion stat + the catalog's driver list.
- "70% of the book pulled, two-sided, no recovery → **a genuine liquidity flight, not a flicker**" — inference, grounded in the withdrawal analytics.
- "OFI one-sided and spread tripling *before* the halt → **the book was pricing the news in early**" — inference, grounded in pre-event flow.

**The guardrail that keeps inference safe** (carried from `pattern-catalog.md` §5): inference
must (1) cite a *computed signal* it stands on, (2) stay within the catalog's **multiple
drivers** — describe the *kind* of event ("transient vs informed", "decorative vs competing
depth"), never the trader's *intent* ("this was spoofing"), and (3) qualify "on IEX." The
analytics are what let the model say something *true and non-obvious*; the catalog + verifier
are what stop it from saying something *confident and unprovable*. That combination — sharp,
grounded inference that never overclaims — is the thing that actually blows people away,
because it reads like an analyst who can *see the order book*, not a model guessing.

### 3.5 Settled mechanics (no longer open)

- **Compute lazily for selected events** (~90–170/day), not all 660 K — most analytics only matter for narrated events; a `SelectTopEvents`→enrich step (or in-scorer for selected rows).
- **Shared `com.longexposure.analytics.*` package** — OFI/z/Fano/slippage are reused across scorers; existing inline stats migrate in too (§6, byte-identical).
- **Frontend renders the full set generically** + tolerant of absent fields — the drill-down *is* the "show all" surface.
- **Still genuinely open:** the exact per-scorer field lists above are a *first draft* to react to (which to headline, which to cut); and the **prose-density knob** — how dense a paragraph we want (terse-journalist lead vs a richer "by the numbers" line) — is a prompt decision separate from how much we compute.

## 4. Prioritization — what's actually worth implementing (value × effort)

Opinionated. **Don't build all 40** — several are finance-canonical but fragile or
marginal on a 2–5% volume slice.

- **Tier 1 — do first (high signal, 🟢/🟡, computable cleanly):** order-to-trade ratio · OFI · sweep slippage [✅ wired 2026-05-27] + post-event reversion · time-in-book distribution drift (`TimeInBookDriftScorer`) · robust z-score + percentile rank [✅ wired on `volume_deviation`] · realized volatility · participation rate (% of baseline) [✅ wired as `pct_of_baseline_volume` on `large_trade`] · one-sidedness [⚠️ degenerate on the cluster scorers — re-home to `liquidity_withdrawal`, see §2.8]. These are the ones that turn "what happened" into "what it cost / what it implies." **Wired pre-launch = slippage + robust-z/percentile + pct-of-baseline; the rest is the post-launch wave** (needs book replay / post-event windows / side-joins).
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
