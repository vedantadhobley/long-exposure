package com.longexposure.temporal.activities;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * See {@link CompressChunksActivity} for the contract.
 *
 * <p>Implementation: two SQL roundtrips per chunk — one to compute the
 * list (so we can heartbeat between each compress_chunk call), and one
 * compress_chunk per item. Empirically the 75 GB {@code order_lifecycle}
 * chunk takes ~7 min to compress on a single CPU thread, and our largest
 * historical day compressed in ~26 min total across 39 chunks — well
 * within the 60-min start-to-close budget.
 */
public final class CompressChunksActivityImpl implements CompressChunksActivity {

    private static final Logger LOG = LoggerFactory.getLogger(CompressChunksActivityImpl.class);

    /**
     * Find uncompressed chunks whose time range overlaps the trading
     * date. The interval is half-open: chunk overlaps the day if
     * {@code chunk.range_start < dayEnd AND chunk.range_end > dayStart}.
     * Restricted to hypertables with {@code compression_enabled = true}
     * (excludes continuous-aggregate-internal hypertables that use a
     * different compression mechanism).
     */
    private static final String FIND_CHUNKS_SQL = """
            SELECT format('%I.%I', c.chunk_schema, c.chunk_name) AS chunk_ref,
                   c.hypertable_name
            FROM timescaledb_information.chunks c
            JOIN timescaledb_information.hypertables h ON h.hypertable_name = c.hypertable_name
            WHERE NOT c.is_compressed
              AND h.compression_enabled = true
              AND c.range_start < ?
              AND c.range_end > ?
            ORDER BY c.hypertable_name, c.range_start
            """;

    @Override
    public long compress(final LocalDate tradingDate) {
        ActivityExecutionContext actx = Activity.getExecutionContext();

        Timestamp dayStart = Timestamp.from(tradingDate.atStartOfDay().toInstant(ZoneOffset.UTC));
        Timestamp dayEnd   = Timestamp.from(tradingDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));

        try (Connection conn = openConnection()) {
            List<ChunkRef> chunks = findChunks(conn, dayStart, dayEnd);
            LOG.info("compress start  date={} candidates={}", tradingDate, chunks.size());

            if (chunks.isEmpty()) {
                LOG.info("no uncompressed chunks for date  date={}", tradingDate);
                return 0;
            }

            long compressed = 0;
            long t0 = System.nanoTime();
            for (ChunkRef ref : chunks) {
                actx.heartbeat("compressing:" + ref.hypertable + ":" + ref.chunkRef);
                long chunkT0 = System.nanoTime();
                try (PreparedStatement st = conn.prepareStatement("SELECT compress_chunk(?::regclass)")) {
                    st.setString(1, ref.chunkRef);
                    try (ResultSet rs = st.executeQuery()) {
                        rs.next();  // drain the result
                    }
                } catch (Exception e) {
                    // Per-chunk error doesn't abort the whole compression
                    // run — TimescaleDB occasionally fails on tiny chunks
                    // ("poor compression ratio" warnings can become hard
                    // errors in some versions). Log + continue.
                    LOG.warn("compress_chunk failed (continuing)  chunk={} err={}",
                            ref.chunkRef, e.getMessage());
                    continue;
                }
                compressed++;
                long chunkMs = (System.nanoTime() - chunkT0) / 1_000_000L;
                LOG.info("compressed  chunk={} hypertable={} elapsed_ms={} ({}/{} done)",
                        ref.chunkRef, ref.hypertable, chunkMs, compressed, chunks.size());
            }

            long totalSec = (System.nanoTime() - t0) / 1_000_000_000L;
            LOG.info("compress done  date={} compressed={} elapsed_sec={}",
                    tradingDate, compressed, totalSec);
            return compressed;
        } catch (Exception e) {
            throw new RuntimeException("compress chunks failed for date=" + tradingDate, e);
        }
    }

    private List<ChunkRef> findChunks(final Connection conn,
                                       final Timestamp dayStart,
                                       final Timestamp dayEnd) throws Exception {
        List<ChunkRef> out = new ArrayList<>();
        try (PreparedStatement st = conn.prepareStatement(FIND_CHUNKS_SQL)) {
            st.setTimestamp(1, dayEnd);
            st.setTimestamp(2, dayStart);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    out.add(new ChunkRef(rs.getString("chunk_ref"), rs.getString("hypertable_name")));
                }
            }
        }
        return out;
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

    private record ChunkRef(String chunkRef, String hypertable) {}
}
