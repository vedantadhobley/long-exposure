# IEX TOPS Protocol Notes

Working notes from reading IEX's TOPS specification, with errata and gotchas surfaced during implementation. Authoritative spec lives at https://iextrading.com/trading/market-data/.

The README has the canonical structural overview ([Protocol stack](../README.md#protocol-stack)); this doc is the implementation companion — every gotcha worth remembering goes here.

## Stack

```
.pcap.gz (compressed historical file)
  └─ pcap file (standard network capture format)
       └─ UDP packets
            └─ IEX Transport Protocol header (40 bytes)
                 └─ TOPS message block
                      └─ Individual TOPS messages
```

## IEX Transport Protocol header (40 bytes)

| Offset | Field | Size | Notes |
|---:|---|---:|---|
| 0 | Version | 1 | |
| 1 | Reserved | 1 | |
| 2 | Message Protocol ID | 2 | identifies TOPS, DEEP, etc. |
| 4 | Channel ID | 4 | data channel identifier |
| 8 | Session ID | 4 | session identifier |
| 12 | Payload Length | 2 | length of message block in bytes |
| 14 | Message Count | 2 | number of messages in block |
| 16 | Stream Offset | 8 | byte offset in stream |
| 24 | First Sequence Number | 8 | sequence number of first message in block |
| 32 | Send Time | 8 | nanoseconds since Unix epoch |

Everything is little-endian. In Java: `ByteBuffer.order(ByteOrder.LITTLE_ENDIAN)`.

## TOPS messages

After the IEX-TP header, each message has a **2-byte length prefix** followed by a **1-byte message type identifier**:

| Type | ID | Notes |
|---|---|---|
| System Event | `S` | market open, close, halt |
| Security Directory | `D` | symbol metadata |
| Trading Status | `H` | halted, trading, paused |
| Quote Update | `Q` | best bid/ask with sizes |
| Trade Report | `T` | executed trade price + size |
| Trade Break | `B` | cancelled trade |
| Official Price | `X` | opening / closing price |

## Price encoding

All prices are stored as **8-byte signed integers with 4 implied decimal places**. `$103.25` is stored as `1032500`. Decode with `price / 10_000.0`. Apply only at presentation; keep storage in the integer form to preserve precision.

## Parser gotchas to capture

These get filled in as the parser is built. Examples of the kind of thing that belongs here:

- **(TBD)** Truncated packets at end-of-file
- **(TBD)** Sequence number gaps; how IEX signals gap-fills
- **(TBD)** Sessions that span multiple files
- **(TBD)** DST transition behavior in `Send Time`
- **(TBD)** Specific message types where the spec field-order differs from what shows up in real captures

Every gotcha gets a paired check in `DailyTotalsValidator` so the validator catches it on future days.

## Validation harness

`DailyTotalsValidator` (TBI) cross-checks every parsed day against IEX's published per-symbol summaries:

- Total trade count per symbol
- Total share volume per symbol
- High / low / open / close prices

Discrepancy threshold > 0.1% on a sampled symbol = parser bug. Validation runs as the final activity in the Temporal workflow; failed validation flags the day's events as `unverified` rather than publishing them.
