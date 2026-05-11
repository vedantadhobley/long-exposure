# IEX Protocol Notes

Working notes from reading IEX's market data specifications, distilled to what we actually need to implement. Companion to the spec PDFs themselves, which live at `~/workspace/data/long-exposure/specs/`:

- `tops-1.66.pdf` — TOPS v1.66 (Oct 2021)
- `tops-1.5.pdf` — TOPS v1.5 (legacy, for historical backfill if we go back pre-1.6)
- `deep-1.08.pdf` — DEEP v1.08 (Oct 2021)
- `deep-plus-1.02.pdf` — DEEP+ v1.02 (Jan 2025)
- `tops-snap-1.5.pdf` / `deep-snap-1.5.pdf` / `deep-plus-snap-1.03.pdf` — live-recovery protocols, **out of scope** (we read .pcap.gz from HIST T+1, not live multicast — SNAP is a request-response TCP service for mid-day-joining live consumers)

The README has the narrative overview; this doc is the implementation reference.

---

## HIST access

All HIST data is freely downloadable, no auth required, via:

```
https://iextrading.com/api/1.0/hist                # all dates, JSON listing
https://iextrading.com/api/1.0/hist?date=YYYYMMDD  # single date
```

Response is a JSON array of `{date, feed, version, protocol, link, size}` entries — one per feed available for that date. The `link` is a `googleapis.com/download/storage/v1/b/iex/...` URL pointing at the actual `.pcap.gz`. Filenames follow `YYYYMMDD_IEXTP1_<FEED><VERSION>.pcap.gz` where `<FEED>` ∈ `{TOPS, DEEP, DPLS}`.

### Feed availability over time

- **TOPS** (1.6 currently, 1.5 historically): available 2017→present
- **DEEP** (1.0 / 1.08): available 2017→present
- **DPLS / DEEP+** (1.0): available **Jan 2025→present** (~16 months as of project start)

DEEP+ only has ~1 year of history. This is fine for v1 (TOPS) and for phase-2 30-day rolling baseline (well within DEEP+ window), but rules out multi-year DEEP+ analysis.

### Quick recipe

```bash
# 1. Pick a date
curl -s 'https://iextrading.com/api/1.0/hist?date=20250502' \
  | jq '.[] | select(.feed == "TOPS")'

# 2. Download
mkdir -p ~/workspace/data/long-exposure/raw
curl -L -o ~/workspace/data/long-exposure/raw/<filename>.pcap.gz '<link from JSON>'
```

A full trading day is 7–9 GB compressed in 2025. Half-days (Black Friday, day-before-holiday) are smaller — useful for early parser development iteration.

---

## Reference implementations to cross-check against

These open-source IEX parsers exist; we use them as **decoder correctness oracles** in addition to the daily-totals validator. Parse a sample .pcap.gz with one of these, dump the message stream, run our parser against the same file, diff outputs message-by-message. Any disagreement is a bug, usually ours.

| Repo | Language | Coverage | URL |
|---|---|---|---|
| `WojciechZankowski/iextrading4j-hist` | Java | TOPS + DEEP | https://github.com/WojciechZankowski/iextrading4j-hist |
| `rob-blackbourn/iex_parser` | Python | TOPS + DEEP | https://github.com/rob-blackbourn/iex_parser |
| `Anirudhsekar96/IEX_DEEP_HISTORICAL_DATA_PARSER` | C++ | DEEP | https://github.com/Anirudhsekar96/IEX_DEEP_HISTORICAL_DATA_PARSER |
| `dhsilv/iex_deep_parser` | C++ | DEEP | https://github.com/dhsilv/iex_deep_parser |
| `B1tWhys/iextool` | Python | TOPS + DEEP | https://github.com/B1tWhys/iextool |

**Primary cross-check**: `iextrading4j-hist` (same language as us, covers both v1 feeds). Used in CI-style validation: every TOPS .pcap.gz we backfill against gets parsed by both implementations and the message streams diffed.

**Phase 2 caveat**: no DEEP+ parser exists in any language as of project start. DEEP+ decoder correctness rests entirely on careful implementation against the spec + book-state validation. Long Exposure's DEEP+ parser would be the open-source community's first reference for that feed — a positioning angle worth leaning into.

---

## Transport stack

```
.pcap.gz                       compressed pcap file from HIST
  └─ pcap                      standard network capture format
       └─ UDP packet           multicast on live; in HIST we just unwrap the UDP payload
            └─ IEX-TP header   40 bytes — sequencing + session info
                 └─ Message Block
                      ├─ 2-byte length
                      └─ N-byte message body (first byte = type)
```

Three feeds share this transport, distinguished only by **Message Protocol ID** in the IEX-TP header:

| Feed | Protocol ID | Channel ID |
|---|---|---|
| TOPS 1.6 | 0x8003 | 1 |
| DEEP 1.08 | 0x8004 | 1 |
| DEEP+ 1.02 | 0x8005 | 1 |

A given .pcap.gz from HIST contains exactly one feed (filename: `YYYYMMDD_IEXTP1_<FEED><VERSION>.pcap.gz`).

## IEX-TP header (40 bytes)

| Offset | Field | Size | Type | Notes |
|---:|---|---:|---|---|
| 0 | Version | 1 | Byte | |
| 1 | Reserved | 1 | Byte | |
| 2 | Message Protocol ID | 2 | Integer | identifies TOPS / DEEP / DEEP+ |
| 4 | Channel ID | 4 | Integer | always 1 in HIST for these feeds |
| 8 | Session ID | 4 | Integer | day-scoped session |
| 12 | Payload Length | 2 | Integer | message block length in bytes |
| 14 | Message Count | 2 | Integer | number of messages in block |
| 16 | Stream Offset | 8 | Long | byte offset in stream |
| 24 | First Sequence Number | 8 | Long | sequence number of first message |
| 32 | Send Time | 8 | Timestamp | nanoseconds since POSIX epoch UTC |

All binary fields are **little-endian**. In Java: `ByteBuffer.order(ByteOrder.LITTLE_ENDIAN)`.

## Common data types (all three feeds)

- **String** — fixed-length ASCII, left-justified, space-padded right
- **Long** — 8 bytes, signed integer
- **Price** — 8 bytes, signed integer, **4 implied decimals**. `$103.25` is stored as `1032500`. Decode `price / 10_000.0` only at presentation; keep storage as integer.
- **Integer** — 4 bytes, unsigned
- **Byte** — 1 byte, unsigned
- **Timestamp** — 8 bytes, signed integer, **nanoseconds since POSIX epoch UTC**
- **Event Time** — 4 bytes, unsigned integer, seconds since POSIX epoch UTC

### Timestamp semantics

Timestamps establish a `happened-before` ordering. For a given `<Message Type, Symbol>` pair, timestamps are monotonic (non-decreasing). Across different symbols, no monotonicity is guaranteed. The one exception: a `Security Event` message for a symbol implies all preceding `Price Level Update` messages for that symbol have been transmitted.

---

## Administrative messages — IDENTICAL across all three feeds

These 7 messages are byte-for-byte compatible. Decoding code is shared.

| Type | ID | Bytes | Carried by | What it represents |
|---|---|---:|---|---|
| `S` System Event | 0x53 | 10 | TOPS, DEEP, DEEP+ | market open/close, start/end of regular hours, end of messages |
| `D` Security Directory | 0x44 | 31 | TOPS, DEEP, DEEP+ | per-symbol metadata: round lot, prior-close-adjusted POC price, LULD tier, ETP flag, etc. |
| `H` Trading Status | 0x48 | 22 | TOPS, DEEP, DEEP+ | halted / paused / OAP / trading — with reason codes |
| `I` Retail Liquidity Indicator | 0x49 | 18 | TOPS, DEEP, DEEP+ | retail buy/sell/both interest indicator |
| `O` Operational Halt Status | 0x4f | 18 | TOPS, DEEP, DEEP+ | IEX-specific operational halt (vs regulatory) |
| `P` Short Sale Price Test Status | 0x50 | 19 | TOPS, DEEP, DEEP+ | Reg SHO Rule 201 short-sale restriction |
| `E` Security Event | 0x45 | 18 | **DEEP, DEEP+** only | Opening Process Complete / Closing Process Complete per symbol |

### Common admin field layouts

All admin messages start with: `Message Type (1B)` + (in some) `Flags or Status (1B)` + `Timestamp (8B at offset 2)` + `Symbol (8B at offset 10)`.

Symbol is Nasdaq Integrated symbology, 8-byte fixed-length ASCII space-padded.

### S — System Event sub-types

| Value | Name | Meaning |
|---|---|---|
| `O` (0x4f) | Start of Messages | First message of any trading session (outside heartbeat) |
| `S` (0x53) | Start of System Hours | IEX is open and ready to accept orders |
| `R` (0x52) | Start of Regular Market Hours | DAY/GTX/market/pegged orders available for execution |
| `M` (0x4d) | End of Regular Market Hours | DAY orders no longer accepted |
| `E` (0x45) | End of System Hours | IEX closed, no new orders this session |
| `C` (0x43) | End of Messages | Always the last message in any trading session |

### H — Trading Status sub-types and reasons

| Status | Hex | Meaning |
|---|---|---|
| `H` | 0x48 | Trading halted across all US equity markets |
| `O` | 0x4f | Halt released into Order Acceptance Period (IEX-listed only) |
| `P` | 0x50 | Trading paused / OAP on IEX (IEX-listed only) |
| `T` | 0x54 | Trading on IEX |

Reason codes (4-byte string) populated for `H` and `O` statuses:

- Halt reasons: `T1` (News Pending), `IPO1` (IPO Not Yet Trading), `IPOD` (IPO Deferred), `MCB3` (Market-Wide Circuit Breaker L3), `NA`
- OAP reasons: `T2` (Halt News Dissemination), `IPO2` (IPO OAP), `IPO3` (IPO Pre-Launch), `MCB1`, `MCB2`

---

## TOPS 1.66 trading messages

| Type | ID | Bytes | What it carries |
|---|---|---:|---|
| `Q` Quote Update | 0x51 | 42 | best bid/ask aggregate (Bid Size 4B, Bid Price 8B, Ask Price 8B, Ask Size 4B) |
| `T` Trade Report | 0x54 | 38 | every individual fill (Size, Price, Trade ID for break correlation) |
| `X` Official Price | 0x58 | 26 | IEX-listed opening/closing prices (`Q` = open, `M` = close) |
| `B` Trade Break | 0x42 | TBD | cancelled trade (references Trade ID from `T`) |
| Auction messages | ... | ... | covered in pages 21–23 of the spec (Auction Information Message — IEX-listed auction collars, indicative prices, etc.) |

### Q — Quote Update wire layout

| Offset | Field | Size | Type |
|---:|---|---:|---|
| 0 | Message Type (0x51) | 1 | Byte |
| 1 | Flags | 1 | Byte |
| 2 | Timestamp | 8 | Timestamp |
| 10 | Symbol | 8 | String |
| 18 | Bid Size | 4 | Integer |
| 22 | Bid Price | 8 | Price |
| 30 | Ask Price | 8 | Price |
| 38 | Ask Size | 4 | Integer |

**Zero quote** convention: prior to start of trading, IEX publishes a Quote Update with all four fields set to zero for every symbol.

### T — Trade Report wire layout

| Offset | Field | Size | Type |
|---:|---|---:|---|
| 0 | Message Type (0x54) | 1 | Byte |
| 1 | Sale Condition Flags | 1 | Byte |
| 2 | Timestamp | 8 | Timestamp |
| 10 | Symbol | 8 | String |
| 18 | Size | 4 | Integer |
| 22 | Price | 8 | Price |
| 30 | Trade ID | 8 | Long |

Trade ID is the join key for `B` Trade Break messages.

---

## DEEP 1.08 trading messages

| Type | ID | Bytes | What it carries |
|---|---|---:|---|
| `8` Price Level Update — Buy | 0x38 | 30 | aggregate displayed size at a buy-side price level |
| `5` Price Level Update — Sell | 0x35 | 30 | aggregate displayed size at a sell-side price level |
| `T` Trade Report | 0x54 | 38 | identical to TOPS (shared decoder) |
| `B` Trade Break | 0x42 | TBD | identical to TOPS |
| `X` Official Price | 0x58 | 26 | identical to TOPS |
| Auction messages | ... | ... | covered on pages 25+ of the spec |

### 8 / 5 — Price Level Update wire layout

| Offset | Field | Size | Type |
|---:|---|---:|---|
| 0 | Message Type | 1 | Byte (`8` for Buy, `5` for Sell) |
| 1 | Event Flags | 1 | Byte (**0x0 = mid-transaction, 0x1 = transaction complete**) |
| 2 | Timestamp | 8 | Timestamp |
| 10 | Symbol | 8 | String |
| 18 | Size | 4 | Integer (aggregate at this level; **0 = remove level**) |
| 22 | Price | 8 | Price |

### DEEP atomic-transaction rule (critical)

A single matching-engine event can change multiple price levels atomically. IEX serializes that as:

```
PLU(Event Flags=0x0) PLU(0x0) ... PLU(0x0) PLU(0x1)   ← final closes the transaction
```

**Rule**: while processing a sequence of `0x0` PLUs, **hold the previous IEX BBO**. Only recompute BBO when an `0x1` PLU arrives. The intermediate book states never "really existed" and any narrative computed against them would be wrong.

If a single price level changes atomically (just one PLU), it carries `0x1` directly — no preceding `0x0` PLUs. The PLU with `0x1` always ends a transaction (whether or not there were preceding `0x0` PLUs).

A `Trade Report` can be emitted between an opening `0x0` PLU and the closing `0x1` PLU for the same atomic event — the trade is part of the transaction.

---

## DEEP+ 1.02 trading messages (out of scope for v1; reference for phase 2)

| Type | ID | Bytes | What it carries |
|---|---|---:|---|
| `a` Add Order | 0x61 | 38 | new displayed order on book — assigns Order ID |
| `M` Order Modify | 0x4d | 38 | order price/size/priority change (references Order ID) |
| `R` Order Delete | 0x52 | 26 | order removed (references Order ID) |
| `L` Order Executed | 0x4c | 46 | displayed order execution (references Order ID + Trade ID) |
| `T` Trade Message | 0x54 | 38 | non-displayed × non-displayed execution (does not affect book state) |
| `B` Trade Break | 0x42 | TBD | cancelled trade |
| `C` Clear Book | 0x43 | TBD | reset book (typically on halt or session boundary) |

### DEEP+ book reconstruction

Maintain `Map<OrderID → {symbol, side, price, size}>` per session. Updates:

- `a` Add → insert with Order ID
- `M` Modify → update fields (Modify Flags bit 0 = `0` resets priority, `1` maintains priority)
- `R` Delete → remove
- `L` Order Executed → subtract `Size` from order; if remaining is 0, remove. Emit derived `Trade` event using the `Trade ID`. (Executed price may differ from the order's posted price due to IEX's price-slide / improvement logic.)
- `T` Trade Message → does **not** modify any displayed order; only impacts cumulative volume on IEX.
- `C` Clear Book → drop all orders for the symbol.

**Derivation**: DEEP-equivalent price levels = aggregate sizes by `(symbol, side, price)` across active orders. TOPS-equivalent BBO = best price level on each side.

The Order ID space is per-session; `R` (delete) frees an ID but a subsequent `a` (add) with the same ID begins a new tracked order.

---

## Price encoding (all feeds)

`$103.25` stored as the integer `1032500`. Decode `price / 10_000.0` only at presentation; keep storage as `BIGINT` to preserve nanosecond precision and avoid float-rounding bugs in aggregates.

`Adjusted POC Price` (in Security Directory) follows the same encoding and represents the corporate-action-adjusted previous official closing price; for IPOs this field carries the issue price.

---

## Parser gotchas captured during implementation

Every gotcha encountered while building the parser lands here. Real findings get a date; speculative items stay marked TBD until confirmed.

- **2026-05-10 — HIST files are PCAP-NG, not classic libpcap.** Magic bytes at the start of `20260508_IEXTP1_TOPS1.6.pcap.gz` (after gunzip) are `0a 0d 0d 0a` — the PCAP-NG Section Header Block signature. The README and the spec PDFs' "pcap format" wording implied classic libpcap. `PcapReader` auto-detects and handles both; the format-discriminator dispatch is in `PcapReader.open()`. The SHB even self-identifies as a merged file ("File created by merging:" option string), suggesting IEX uses `mergecap` or equivalent in their HIST publishing pipeline.

- **2026-05-10 — IEX-TP heartbeats dominate the early packets.** First several packets in a trading day are heartbeats: IEX-TP header with `Payload Length = 0`, `Message Count = 0`, `First Sequence = 1`, arriving at ~1s intervals (verified: 2026-05-08 file, packets 1–10 at 11:05:18 → 11:05:28 UTC = 7:05 AM ET, well before pre-market). Real market messages begin later, framed by the System Event `O` (Start of Messages). Any parser stage that sums per-packet stats must skip-or-tolerate `Payload Length = 0` packets — they're framing keep-alives, not malformed data.

- **2026-05-10 — Spec example timestamp annotations are inconsistent UTC vs ET.** TOPS 1.66 page 4 ("Timestamp: 8 bytes, signed integer containing a counter of nanoseconds since POSIX (Epoch) time UTC") is unambiguous: timestamps on the wire are UTC. But the worked examples mix conventions: SystemEvent (p7), SecurityDirectory (p9), and SecurityEvent (DEEP p16) all annotate their example bytes with UTC times that decode correctly. But TradingStatus (p11), RetailLiquidityIndicator (p12), OperationalHaltStatus (p13), and ShortSalePriceTestStatus (p14–15) all share the same example timestamp bytes `ac 63 c0 20 96 86 6d 14` annotated as "2016-08-23 15:30:32.572715948" — which is actually **2016-08-23 19:30:32.572715948 UTC** (= 15:30:32 EDT, UTC−4 in August). On-wire values are always UTC; the spec comments on those pages are ET. Don't trust spec example comments blindly when writing decoder tests — verify against the LE long the bytes actually produce.

- **(TBD)** Truncated packets at end-of-file
- **(TBD)** Sequence-number gaps and how (if) IEX signals them in HIST files (gap fills via TCP unicast don't apply to HIST — every file is supposed to be complete, but defensive coding still matters)
- **(TBD)** DST transition behavior in Send Time — nanosecond epoch is monotonic, but session boundary alignment may surprise
- **(TBD)** Sale Condition Flags semantics on `T` (most narrative scoring will care about ISO / regular-session / round-lot bits)
- **(TBD)** Whether real captures ever exceed the spec'd maximum message size — IEX reserves the right to grow messages

---

## Validation harness

`DailyTotalsValidator` cross-checks the day's parsed data against IEX's published per-symbol daily summaries (available via the same HIST endpoint or IEX's free `/stats` endpoints):

- Total trade count per symbol
- Total share volume per symbol
- High / low / open / close prices

Discrepancy > 0.1% on a sampled symbol = parser bug. Failed validation flags the day's events as `unverified` rather than publishing them.

**Important caveat**: this validation works for TOPS and DEEP because both carry `T` Trade Reports for every fill. For DEEP+ phase-2 work, the validator additionally needs to reconstruct book state at sampled timestamps and compare against any periodically-published reference — book-state validation is structurally harder and is part of why DEEP+ is deferred to phase 2.
