package com.longexposure.temporal.activities;

import com.longexposure.dpls.DplsMessageRouter;
import com.longexposure.pcap.PcapReader;
import com.longexposure.storage.SchemaManager;
import com.longexposure.storage.TimescaleWriter;
import com.longexposure.transport.IexTpDecoder;
import com.longexposure.wire.Bytes;
import com.longexposure.wire.IexMessage;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Implementation of {@link ParseAndWriteDplsActivity}.
 *
 * <p>Pre-clean step: deletes DPLS rows for {@code targetDate} across
 * every event table. Then opens a {@link PcapReader}, decodes via
 * {@link DplsMessageRouter}, writes via {@link TimescaleWriter}. Heartbeats
 * every 100K messages processed.
 */
public final class ParseAndWriteDplsActivityImpl implements ParseAndWriteDplsActivity {

    private static final Logger LOG = LoggerFactory.getLogger(ParseAndWriteDplsActivityImpl.class);

    /** Heartbeat cadence in number of messages processed. */
    private static final long HEARTBEAT_EVERY = 100_000L;

    private static final String[] DPLS_TABLES = {
            "trades", "trade_breaks", "quotes", "status_events", "auction_info",
            "official_prices", "securities", "retail_liquidity",
            "orders_add", "orders_modify", "orders_delete", "orders_executed", "clear_books"
    };

    @Override
    public long parseAndWrite(final String dplsFilePath, final LocalDate targetDate, final boolean force) {
        ActivityExecutionContext ctx = Activity.getExecutionContext();
        LOG.info("parse + write DPLS start  file={} date={} force={}", dplsFilePath, targetDate, force);

        long t0 = System.nanoTime();

        try (Connection conn = openConnection()) {
            SchemaManager.apply(conn);

            // Pre-clean: idempotent — running twice is safe.
            long deleted = preClean(conn, targetDate);
            LOG.info("pre-clean done  date={} rows_deleted={}", targetDate, deleted);
            ctx.heartbeat("preclean_done:" + deleted);

            try (TimescaleWriter writer = new TimescaleWriter(conn, "DPLS");
                 PcapReader reader = PcapReader.open(Path.of(dplsFilePath))) {

                long messageCount = 0;
                long packetCount = 0;
                long heartbeatPackets = 0;
                long nextHeartbeatAt = HEARTBEAT_EVERY;

                byte[] payload;
                while ((payload = reader.nextUdpPayload()) != null) {
                    packetCount++;
                    if (payload.length < IexTpDecoder.HEADER_BYTES) continue;
                    IexTpDecoder.Header h = IexTpDecoder.decode(payload);
                    if (h.messageCount() == 0 || h.payloadLength() == 0) {
                        heartbeatPackets++;
                        continue;
                    }

                    int pos = IexTpDecoder.HEADER_BYTES;
                    int endOfBlock = IexTpDecoder.HEADER_BYTES + h.payloadLength();
                    for (int i = 0; i < h.messageCount() && pos + 2 <= endOfBlock; i++) {
                        int msgLen = Bytes.readShortLE(payload, pos);
                        pos += 2;
                        if (msgLen == 0 || pos + msgLen > endOfBlock) break;
                        byte typeByte = payload[pos];
                        messageCount++;

                        try {
                            IexMessage m = DplsMessageRouter.decode(typeByte, payload, pos);
                            writer.writeMessage(m);
                        } catch (IllegalArgumentException decodeFailed) {
                            // Unknown / malformed message type — log and continue
                            LOG.warn("decode failed type=0x{} pos={}",
                                    String.format("%02x", typeByte & 0xff), pos);
                        }
                        pos += msgLen;

                        if (messageCount >= nextHeartbeatAt) {
                            ctx.heartbeat("messages:" + messageCount);
                            nextHeartbeatAt += HEARTBEAT_EVERY;
                        }
                    }
                }

                writer.flush();
                long totalRows = writer.totalRows().values().stream().mapToLong(Long::longValue).sum();
                long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
                LOG.info("parse + write DPLS done  packets={} heartbeats={} messages={} rows={} elapsed_ms={}",
                        packetCount, heartbeatPackets, messageCount, totalRows, elapsedMs);
                return totalRows;
            }
        } catch (Exception e) {
            throw new RuntimeException("parseAndWrite failed for date=" + targetDate
                    + " file=" + dplsFilePath, e);
        }
    }

    /**
     * Delete all DPLS rows for the given trading date across every event
     * table. Uses {@code [00:00:00, 00:00:00 next day)} half-open range in UTC.
     *
     * <p>The trading date is a calendar date in IEX's "trading day" sense,
     * which maps to a session running ~04:00–20:00 ET. UTC-aligned is good
     * enough as a containing range — real trading-day rows fall well inside
     * the UTC-midnight bounds.
     */
    private long preClean(final Connection conn, final LocalDate targetDate) throws Exception {
        Timestamp from = Timestamp.from(targetDate.atStartOfDay().toInstant(ZoneOffset.UTC));
        Timestamp to = Timestamp.from(targetDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));

        long total = 0;
        for (String table : DPLS_TABLES) {
            String sql = "DELETE FROM " + table
                    + " WHERE feed_source = 'DPLS' AND ts >= ? AND ts < ?";
            try (PreparedStatement st = conn.prepareStatement(sql)) {
                st.setTimestamp(1, from);
                st.setTimestamp(2, to);
                long deleted = st.executeLargeUpdate();
                if (deleted > 0) {
                    LOG.info("pre-clean  table={} deleted={}", table, deleted);
                }
                total += deleted;
            }
        }
        return total;
    }

    private static Connection openConnection() throws Exception {
        String host = System.getenv().getOrDefault("POSTGRES_HOST", "localhost");
        String port = System.getenv().getOrDefault("POSTGRES_PORT", "5432");
        String db   = System.getenv().getOrDefault("POSTGRES_DB", "longexposure");
        String user = System.getenv().getOrDefault("POSTGRES_USER", "leuser");
        String pwd  = System.getenv().getOrDefault("POSTGRES_PASSWORD", "lepass");
        String url = "jdbc:postgresql://" + host + ":" + port + "/" + db;
        return DriverManager.getConnection(url, user, pwd);
    }
}
