package com.longexposure.storage;

import com.longexposure.admin.AdminMessage;
import com.longexposure.admin.OperationalHaltStatus;
import com.longexposure.admin.RetailLiquidityIndicator;
import com.longexposure.admin.SecurityDirectory;
import com.longexposure.admin.SecurityEvent;
import com.longexposure.admin.ShortSalePriceTestStatus;
import com.longexposure.admin.SystemEvent;
import com.longexposure.admin.TradingStatus;
import com.longexposure.tops.AuctionInformation;
import com.longexposure.tops.OfficialPrice;
import com.longexposure.tops.QuoteUpdate;
import com.longexposure.tops.TopsMessage;
import com.longexposure.tops.TradeBreak;
import com.longexposure.tops.TradeReport;
import com.longexposure.wire.IexMessage;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;

import java.io.IOException;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Bulk-loads decoded IEX messages into the per-message-type hypertables
 * defined in {@code schema.sql}. One COPY per buffer flush via
 * {@link CopyManager}; per-table {@link StringBuilder} buffers with
 * auto-flush at {@link #DEFAULT_FLUSH_ROWS} rows.
 *
 * <p>Row-by-row INSERT can't keep up with ~300M messages per trading day;
 * COPY pipelines the writes server-side and runs at ~50–200K rows/sec.
 *
 * <p>One {@code feed_source} value per writer instance — pass {@code "TOPS"}
 * for v1, {@code "DEEP+"} when phase 2 lands.
 *
 * <p>Not thread-safe. Use one writer per parser thread (parser is currently
 * single-threaded; we'll partition by symbol-hash for parallelism later if
 * needed).
 */
public final class TimescaleWriter implements AutoCloseable {

    /** Flush each buffer when it reaches this many rows. */
    public static final int DEFAULT_FLUSH_ROWS = 100_000;

    /** Postgres TIMESTAMPTZ supports microsecond precision (6 decimals). */
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS")
                    .withZone(ZoneOffset.UTC);

    public enum Table {
        TRADES("trades",
                "ts, ts_nanos, feed_source, symbol, size, price_raw, trade_id, sale_condition_flags"),
        TRADE_BREAKS("trade_breaks",
                "ts, ts_nanos, feed_source, symbol, size, price_raw, broken_trade_id, sale_condition_flags"),
        QUOTES("quotes",
                "ts, ts_nanos, feed_source, symbol, bid_size, bid_price_raw, ask_size, ask_price_raw, flags"),
        STATUS_EVENTS("status_events",
                "ts, ts_nanos, feed_source, event_kind, symbol, sub_type, reason, detail"),
        AUCTION_INFO("auction_info",
                "ts, ts_nanos, feed_source, symbol, auction_type, paired_shares, "
                + "reference_price_raw, indicative_clearing_price_raw, imbalance_shares, "
                + "imbalance_side, extension_number, scheduled_auction_time_seconds, "
                + "auction_book_clearing_price_raw, collar_reference_price_raw, "
                + "lower_collar_raw, upper_collar_raw"),
        OFFICIAL_PRICES("official_prices",
                "ts, ts_nanos, feed_source, symbol, price_type, price_raw"),
        SECURITIES("securities",
                "ts, ts_nanos, feed_source, symbol, flags, round_lot_size, "
                + "adjusted_poc_price_raw, luld_tier"),
        RETAIL_LIQUIDITY("retail_liquidity",
                "ts, ts_nanos, feed_source, symbol, indicator");

        public final String tableName;
        public final String columns;

        Table(final String tableName, final String columns) {
            this.tableName = tableName;
            this.columns = columns;
        }
    }

    private final Connection conn;
    private final CopyManager copyManager;
    private final String feedSource;
    private final int flushRows;

    private final Map<Table, StringBuilder> buffers = new EnumMap<>(Table.class);
    private final Map<Table, Long> bufferedRows = new EnumMap<>(Table.class);
    private final Map<Table, Long> totalRows = new EnumMap<>(Table.class);

    public TimescaleWriter(final Connection conn, final String feedSource) throws SQLException {
        this(conn, feedSource, DEFAULT_FLUSH_ROWS);
    }

    public TimescaleWriter(final Connection conn, final String feedSource, final int flushRows) throws SQLException {
        this.conn = conn;
        this.copyManager = conn.unwrap(PGConnection.class).getCopyAPI();
        this.feedSource = feedSource;
        this.flushRows = flushRows;
        conn.setAutoCommit(true);
        for (Table t : Table.values()) {
            buffers.put(t, new StringBuilder(1 << 18));
            bufferedRows.put(t, 0L);
            totalRows.put(t, 0L);
        }
    }

    /**
     * Append one decoded message to the appropriate buffer. Auto-flushes the
     * destination buffer when it reaches {@link #flushRows} pending rows.
     */
    public void writeMessage(final IexMessage m) throws SQLException, IOException {
        if (m instanceof AdminMessage am) {
            writeAdmin(am);
        } else if (m instanceof TopsMessage tm) {
            writeTops(tm);
        }
    }

    private void writeAdmin(final AdminMessage m) throws SQLException, IOException {
        switch (m) {
            case SystemEvent e ->
                appendStatusEvent('S', null, charOf(e.event().value), null, null, e.timestampNanos());
            case TradingStatus s ->
                appendStatusEvent('H', s.symbol(), charOf(s.status().value),
                        s.reason().isEmpty() ? null : s.reason(), null, s.timestampNanos());
            case OperationalHaltStatus o ->
                appendStatusEvent('O', o.symbol(), charOf(o.status().value), null, null, o.timestampNanos());
            case ShortSalePriceTestStatus p ->
                // SSPT.Status is 0x00 / 0x01 (raw bytes, not printable chars). Map to
                // ASCII '0' / '1' so the CHAR(1) column gets a valid UTF-8 codepoint.
                appendStatusEvent('P', p.symbol(),
                        (char) ('0' + (p.status().value & 0x1)),
                        null,
                        charOf(p.detail().value), p.timestampNanos());
            case SecurityEvent se ->
                appendStatusEvent('E', se.symbol(), charOf(se.event().value), null, null, se.timestampNanos());
            case RetailLiquidityIndicator i -> appendRetailLiquidity(i);
            case SecurityDirectory d -> appendSecurity(d);
        }
    }

    private void writeTops(final TopsMessage m) throws SQLException, IOException {
        switch (m) {
            case QuoteUpdate q         -> appendQuote(q);
            case TradeReport t         -> appendTrade(t);
            case TradeBreak b          -> appendTradeBreak(b);
            case OfficialPrice op      -> appendOfficialPrice(op);
            case AuctionInformation a  -> appendAuction(a);
        }
    }

    // ─── per-table appenders ─────────────────────────────────────────────────

    private void appendTrade(final TradeReport t) throws SQLException, IOException {
        StringBuilder sb = buffers.get(Table.TRADES);
        appendTimestamp(sb, t.timestampNanos());
        sb.append('\t').append(t.timestampNanos());
        sb.append('\t').append(feedSource);
        sb.append('\t'); appendEscaped(sb, t.symbol());
        sb.append('\t').append(t.size());
        sb.append('\t').append(t.priceRaw());
        sb.append('\t').append(t.tradeId());
        sb.append('\t').append(t.flags() & 0xff);
        sb.append('\n');
        rowAdded(Table.TRADES);
    }

    private void appendTradeBreak(final TradeBreak b) throws SQLException, IOException {
        StringBuilder sb = buffers.get(Table.TRADE_BREAKS);
        appendTimestamp(sb, b.timestampNanos());
        sb.append('\t').append(b.timestampNanos());
        sb.append('\t').append(feedSource);
        sb.append('\t'); appendEscaped(sb, b.symbol());
        sb.append('\t').append(b.size());
        sb.append('\t').append(b.priceRaw());
        sb.append('\t').append(b.brokenTradeId());
        sb.append('\t').append(b.flags() & 0xff);
        sb.append('\n');
        rowAdded(Table.TRADE_BREAKS);
    }

    private void appendQuote(final QuoteUpdate q) throws SQLException, IOException {
        StringBuilder sb = buffers.get(Table.QUOTES);
        appendTimestamp(sb, q.timestampNanos());
        sb.append('\t').append(q.timestampNanos());
        sb.append('\t').append(feedSource);
        sb.append('\t'); appendEscaped(sb, q.symbol());
        sb.append('\t').append(q.bidSize());
        sb.append('\t').append(q.bidPriceRaw());
        sb.append('\t').append(q.askSize());
        sb.append('\t').append(q.askPriceRaw());
        sb.append('\t').append(q.flags() & 0xff);
        sb.append('\n');
        rowAdded(Table.QUOTES);
    }

    private void appendStatusEvent(
            final char eventKind, final String symbol, final char subType,
            final String reason, final Character detail, final long tsNanos)
            throws SQLException, IOException {
        StringBuilder sb = buffers.get(Table.STATUS_EVENTS);
        appendTimestamp(sb, tsNanos);
        sb.append('\t').append(tsNanos);
        sb.append('\t').append(feedSource);
        sb.append('\t').append(eventKind);
        sb.append('\t');
        if (symbol == null) sb.append("\\N");
        else appendEscaped(sb, symbol);
        sb.append('\t').append(subType);
        sb.append('\t');
        if (reason == null) sb.append("\\N");
        else appendEscaped(sb, reason);
        sb.append('\t');
        if (detail == null) sb.append("\\N");
        else sb.append(detail.charValue());
        sb.append('\n');
        rowAdded(Table.STATUS_EVENTS);
    }

    private void appendAuction(final AuctionInformation a) throws SQLException, IOException {
        StringBuilder sb = buffers.get(Table.AUCTION_INFO);
        appendTimestamp(sb, a.timestampNanos());
        sb.append('\t').append(a.timestampNanos());
        sb.append('\t').append(feedSource);
        sb.append('\t'); appendEscaped(sb, a.symbol());
        sb.append('\t').append(charOf(a.auctionType().value));
        sb.append('\t').append(a.pairedShares());
        sb.append('\t').append(a.referencePriceRaw());
        sb.append('\t').append(a.indicativeClearingPriceRaw());
        sb.append('\t').append(a.imbalanceShares());
        sb.append('\t').append(charOf(a.imbalanceSide().value));
        sb.append('\t').append(a.extensionNumber());
        sb.append('\t').append(a.scheduledAuctionTimeEpochSeconds());
        sb.append('\t').append(a.auctionBookClearingPriceRaw());
        sb.append('\t').append(a.collarReferencePriceRaw());
        sb.append('\t').append(a.lowerAuctionCollarRaw());
        sb.append('\t').append(a.upperAuctionCollarRaw());
        sb.append('\n');
        rowAdded(Table.AUCTION_INFO);
    }

    private void appendOfficialPrice(final OfficialPrice op) throws SQLException, IOException {
        StringBuilder sb = buffers.get(Table.OFFICIAL_PRICES);
        appendTimestamp(sb, op.timestampNanos());
        sb.append('\t').append(op.timestampNanos());
        sb.append('\t').append(feedSource);
        sb.append('\t'); appendEscaped(sb, op.symbol());
        sb.append('\t').append(charOf(op.priceType().value));
        sb.append('\t').append(op.priceRaw());
        sb.append('\n');
        rowAdded(Table.OFFICIAL_PRICES);
    }

    private void appendSecurity(final SecurityDirectory d) throws SQLException, IOException {
        StringBuilder sb = buffers.get(Table.SECURITIES);
        appendTimestamp(sb, d.timestampNanos());
        sb.append('\t').append(d.timestampNanos());
        sb.append('\t').append(feedSource);
        sb.append('\t'); appendEscaped(sb, d.symbol());
        sb.append('\t').append(d.flags() & 0xff);
        sb.append('\t').append(d.roundLotSize());
        sb.append('\t').append(d.adjustedPocPriceRaw());
        sb.append('\t').append(d.luldTier().value & 0xff);
        sb.append('\n');
        rowAdded(Table.SECURITIES);
    }

    private void appendRetailLiquidity(final RetailLiquidityIndicator i) throws SQLException, IOException {
        StringBuilder sb = buffers.get(Table.RETAIL_LIQUIDITY);
        appendTimestamp(sb, i.timestampNanos());
        sb.append('\t').append(i.timestampNanos());
        sb.append('\t').append(feedSource);
        sb.append('\t'); appendEscaped(sb, i.symbol());
        sb.append('\t').append(charOf(i.indicator().value));
        sb.append('\n');
        rowAdded(Table.RETAIL_LIQUIDITY);
    }

    // ─── buffer + flush plumbing ─────────────────────────────────────────────

    private void rowAdded(final Table t) throws SQLException, IOException {
        long n = bufferedRows.merge(t, 1L, Long::sum);
        if (n >= flushRows) {
            flush(t);
        }
    }

    private void appendTimestamp(final StringBuilder sb, final long nanosSinceEpoch) {
        long seconds = nanosSinceEpoch / 1_000_000_000L;
        long nanos = nanosSinceEpoch % 1_000_000_000L;
        if (nanos < 0) {
            nanos += 1_000_000_000L;
            seconds -= 1;
        }
        Instant inst = Instant.ofEpochSecond(seconds, nanos);
        sb.append(TS_FMT.format(inst)).append("+00");
    }

    /** COPY TEXT format escaping: backslash + tab/newline/cr. */
    private static void appendEscaped(final StringBuilder sb, final String s) {
        for (int i = 0, len = s.length(); i < len; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default   -> sb.append(c);
            }
        }
    }

    private static char charOf(final byte b) {
        return (char) (b & 0xff);
    }

    public void flush() throws SQLException, IOException {
        for (Table t : Table.values()) {
            flush(t);
        }
    }

    private void flush(final Table t) throws SQLException, IOException {
        StringBuilder sb = buffers.get(t);
        if (sb.length() == 0) return;
        long pending = bufferedRows.get(t);
        String copyCmd = "COPY " + t.tableName + " (" + t.columns + ") FROM STDIN WITH (FORMAT text)";
        copyManager.copyIn(copyCmd, new StringReader(sb.toString()));
        sb.setLength(0);
        bufferedRows.put(t, 0L);
        totalRows.merge(t, pending, Long::sum);
    }

    /** Totals written so far (per table). Updated on each flush. */
    public Map<Table, Long> totalRows() {
        return new HashMap<>(totalRows);
    }

    @Override
    public void close() throws SQLException {
        try {
            flush();
        } catch (IOException e) {
            throw new SQLException("flush failed during close", e);
        }
    }
}
