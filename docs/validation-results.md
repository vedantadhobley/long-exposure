# Validation Results

Empirical correctness of the parser stack, validated against itself
and against IEX's TOPS feed. Append new entries as new days are run.

The core correctness claim is the **DPLS ↔ DEEP price-level cross-check**:
two independent parsers (DPLS order-level book vs DEEP price-level
book), derived from two independent IEX wire formats, agree on every
displayed price level to within noise-floor (~10⁻⁸). If either parser
had a real bug, the numbers would diverge.

Per-day, the triangle runs three validators:

1. **DPLS → TOPS BBO** — derive round-lot-protected best bid/ask from the
   DPLS order book, compare to every TOPS QuoteUpdate.
2. **DEEP → TOPS BBO** — same shape, but the book is DEEP's price-level
   aggregate. Should agree with DPLS→TOPS to the share if both parsers
   are correct.
3. **DPLS ↔ DEEP price-level** — at every DEEP transaction-complete PLU,
   compare its declared aggregate to the sum of DPLS orders at the
   same `(symbol, side, price)`. TOPS-independent.

There's also a one-shot trade-level check (sum of TradeReport sizes per
symbol across feeds) — both feeds passed that perfectly on the first
trading day we tried.

---

## 2026-05-08 (trading day 1)

File sizes: TOPS 10.2 GB, DEEP 11.1 GB, DPLS 10.4 GB. Runs with
`IEX_PRIOR_CLOSE_CSV=/storage/prior_close_20260507.csv` (per-symbol
round-lot from prior-day close).

| Validation | Match rate | Elapsed |
|---|---|---|
| Trade aggregates (DPLS sum vs TOPS sum, per symbol) | **9,134 / 9,134 symbols, 0 share delta** | n/a |
| DPLS → TOPS BBO | **99.4184 %** — 283,495,958 / 1,658,391 mismatched of 285,154,349 QUs | 9.5 min |
| DEEP → TOPS BBO | **99.4184 %** — identical numbers to DPLS→TOPS | 8.8 min |
| DPLS ↔ DEEP price-level | **100.0000 %** — 346,549,876 / 4 mismatched of 346,549,880 PLUs | 11.7 min |

Same-ns multi-transaction PLUs squashed on the price-level run: **79**.

Mismatch concentration on the BBO side: the residual ~0.6% is dominated
by ~30 high-volume symbols (VGT, AMZN, BKNG, MGK, QQQM, AMD, FTEC, IWF,
VUG, QTEC), all symbols where TOPS uses a smaller round-lot tier than
our (level-price-based) derivation. See the discussion under "Residual
root cause" below.

## 2026-05-07 (trading day 2)

File sizes: TOPS 13.4 GB, DEEP 13.9 GB, DPLS 13.2 GB (≈ 30% larger
than day 1 — busier session). Runs without prior-close (we don't have
2026-05-06 TOPS to extract from), so the BBO derivations use the
level-price-tier fallback.

| Validation | Match rate | Elapsed |
|---|---|---|
| DPLS → TOPS BBO | **99.3886 %** — 385,170,309 / 2,369,372 mismatched of 387,539,681 QUs | 12.9 min |
| DEEP → TOPS BBO | **99.3886 %** — identical numbers to DPLS→TOPS | 11.4 min |
| DPLS ↔ DEEP price-level | **100.0000 %** — 469,432,998 / 14 mismatched of 469,433,012 PLUs | 14.4 min |

Same-ns multi-transaction PLUs squashed on the price-level run: **215**.

Day 2 had ≈ 36% more comparisons than day 1, matching the ≈ 30% larger
file sizes. The 14-mismatch DPLS↔DEEP residual is in the same noise
floor as day 1's 4 (both ≈ 3 × 10⁻⁸ rate). The 0.6% BBO residual has
the same shape: ETFs + high-priced names, TOPS using a smaller round
lot than our derivation.

---

## What the two days together establish

1. **Parsers are bug-equivalent across the wire-format boundary.**
   DPLS→TOPS and DEEP→TOPS produced *identical* match/mismatch counts
   on both days. If either parser had a real implementation bug, the
   two numbers would diverge. Symmetric on both days = strong evidence.

2. **Book reconstructions agree to noise-floor.** The TOPS-independent
   DPLS↔DEEP check hit 100.0000% on both days. The handful of
   mismatches are same-ns multi-transaction artifacts at the
   nanosecond-resolution boundary — engine-internal sub-ns timing
   collapsed by the wire format. Documented as a class, not a bug.

3. **The residual is reproducible.** 0.6% mismatch rate on both days,
   concentrated in the same symbols (with cross-day overlap on
   VGT/AMZN/MGK/QQQM/QTEC/IWF/VUG/VOOG/BKNG and similar names). A
   per-day fluke would not reproduce the exact symbol set.

---

## Residual root cause (the ~0.6% BBO gap)

The 1.6 M – 2.4 M BBO mismatches per day all look like this:

```
LNG  derived: 0×0          / 0×0         TOPS: 0×0          / 40 × $25.97
IWF  derived: 0×0          / 0×0         TOPS: 40 × $11.94 / 0×0
HUBS derived: 0×0          / 0×0         TOPS: 40 × $17.48 / 0×0
VO   derived: 0×0          / 0×0         TOPS: 40 × $7.49 / 0×0
```

Our book *has* the 40-share liquidity TOPS is reporting. The
disagreement is over whether 40 shares is enough to qualify as the
round-lot-protected BBO at these symbols' prices. Our derivation uses
the SEC MDI tier table on either the **level's current price** (no
prior-close) or the **symbol's prior IEX last-trade price** (with
prior-close). Either way, these symbols land in tier 1 (≤ $250),
which says round lot = 100. TOPS qualifies their 40-share levels
anyway, so TOPS must be using a smaller threshold for these symbols.

Three working hypotheses, untested for lack of external data:

1. **The SEC MDI tiered round-lot rule isn't fully deployed at IEX in
   May 2026.** Implementation has been delayed multiple times. If
   round-lot is universally smaller (say 1 or 40) the LNG/VO/HUBS/IWF
   cases qualify — but the GME case (40 shares at $422.40 should
   qualify if the threshold is small, but TOPS skips it) doesn't fit.
2. **TOPS uses the *consolidated* prior close from SIP**, not IEX's
   own last trade. For ~boundary symbols (close near $250), IEX last
   trade and SIP consolidated close can diverge enough to put a stock
   in a different tier. We don't have SIP data via HIST alone.
3. **A different rule altogether** — stub-quote filtering, NMS
   protected-quote semantics layered on top of the tiered round-lot
   table, or per-symbol overrides we can't see.

Closing this gap requires external data — either an SIP consolidated
close feed, the listing exchange's official daily summary, or some
other source we don't have through HIST. The *path-2 framework*
(per-symbol round-lot threaded through `ProtectedBbo`) is in place
and reusable for any future data source; the limit is the input.

---

## How to reproduce

DPLS → TOPS:
```bash
docker compose -f docker-compose.dev.yml run --rm \
  -e IEX_PCAP_FILE=/storage/raw/<YYYYMMDD>_IEXTP1_DPLS1.0.pcap.gz \
  -e IEX_BBO_VALIDATE_AGAINST=/storage/raw/<YYYYMMDD>_IEXTP1_TOPS1.6.pcap.gz \
  -e IEX_PRIOR_CLOSE_CSV=/storage/prior_close_<PRIOR_YYYYMMDD>.csv \
  worker
```

DEEP → TOPS: swap the `IEX_PCAP_FILE` to the DEEP feed. DPLS ↔ DEEP:
set `IEX_BBO_VALIDATE_AGAINST` to the DEEP file.

Extracting a prior-close CSV from a prior-day TOPS file:
```bash
docker compose -f docker-compose.dev.yml run --rm \
  -e IEX_PCAP_FILE=/storage/raw/<PRIOR_YYYYMMDD>_IEXTP1_TOPS1.6.pcap.gz \
  -e IEX_EXTRACT_PRIOR_CLOSE_TO=/app/prior_close_<PRIOR_YYYYMMDD>.csv \
  worker
# then move /home/.../parser/prior_close_<...>.csv into
# /home/vedanta/workspace/data/long-exposure/ where /storage points
```
