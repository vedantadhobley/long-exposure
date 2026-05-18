package com.longexposure.temporal.activities;

import com.longexposure.storage.SchemaManager;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RefreshSymbolMetadataActivityImpl implements RefreshSymbolMetadataActivity {

    private static final Logger LOG = LoggerFactory.getLogger(RefreshSymbolMetadataActivityImpl.class);

    private static final String NASDAQ_LISTED_URL =
            "https://www.nasdaqtrader.com/dynamic/symdir/nasdaqlisted.txt";
    private static final String OTHER_LISTED_URL =
            "https://www.nasdaqtrader.com/dynamic/symdir/otherlisted.txt";

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(15))
            .readTimeout(Duration.ofMinutes(2))
            .build();

    @Override
    public long refreshSymbolMetadata() {
        long t0 = System.nanoTime();

        Map<String, Row> merged = new LinkedHashMap<>(20_000);

        // Step 1: NASDAQ-listed
        try {
            int n = parseNasdaqListed(fetch(NASDAQ_LISTED_URL), merged);
            LOG.info("nasdaqlisted parsed  rows={}", n);
        } catch (Exception e) {
            LOG.error("nasdaqlisted fetch failed", e);
            throw new RuntimeException("nasdaqlisted fetch failed", e);
        }

        // Step 2: Other-listed (NYSE / NYSE Arca / Cboe / etc.)
        try {
            int n = parseOtherListed(fetch(OTHER_LISTED_URL), merged);
            LOG.info("otherlisted parsed  rows={}", n);
        } catch (Exception e) {
            LOG.error("otherlisted fetch failed", e);
            throw new RuntimeException("otherlisted fetch failed", e);
        }

        // Step 3: merge in IEX SecurityDirectory data from our existing securities table
        long iexAugmented = 0;
        try (Connection conn = openConnection()) {
            SchemaManager.apply(conn);
            iexAugmented = augmentFromIexSecurities(conn, merged);
            LOG.info("iex augmentation  rows_updated={}", iexAugmented);

            // Step 4: upsert into symbols
            long upserted = upsert(conn, merged);
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
            LOG.info("symbols refresh done  rows_upserted={} merged_total={} iex_augmented={} elapsed_ms={}",
                    upserted, merged.size(), iexAugmented, elapsedMs);
            return upserted;
        } catch (Exception e) {
            throw new RuntimeException("symbols upsert failed", e);
        }
    }

    // ─── HTTP ────────────────────────────────────────────────────────────────

    private String fetch(final String url) throws IOException {
        Request req = new Request.Builder().url(url).get().build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("HTTP " + resp.code() + " from " + url);
            }
            return resp.body() == null ? "" : resp.body().string();
        }
    }

    // ─── NASDAQ file parsers ─────────────────────────────────────────────────

    /**
     * nasdaqlisted.txt format (pipe-delimited):
     *   Symbol|Security Name|Market Category|Test Issue|Financial Status|Round Lot Size|ETF|NextShares
     * Last line is a "File Creation Time" footer; we skip lines starting with "File Creation Time".
     */
    private int parseNasdaqListed(final String body, final Map<String, Row> out) throws IOException {
        int count = 0;
        try (BufferedReader r = new BufferedReader(new StringReader(body))) {
            String header = r.readLine();
            if (header == null || !header.startsWith("Symbol|")) {
                throw new IOException("unexpected nasdaqlisted header: " + header);
            }
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("File Creation Time")) break;
                String[] f = line.split("\\|", -1);
                if (f.length < 7) continue;
                String symbol = f[0].trim();
                if (symbol.isEmpty()) continue;
                String securityName = f[1].trim();
                String testIssue    = f[3].trim();
                Integer roundLot    = parseIntOrNull(f[5]);
                String etfFlag      = f[6].trim();

                if ("Y".equals(testIssue)) continue;  // skip test issues

                Row row = out.computeIfAbsent(symbol, Row::new);
                row.companyName     = securityName;
                row.listingExchange = "NASDAQ";
                row.isEtf           = "Y".equals(etfFlag);
                if (row.roundLot == null) row.roundLot = roundLot;
                row.source = "nasdaq_listed";
                count++;
            }
        }
        return count;
    }

    /**
     * otherlisted.txt format (pipe-delimited):
     *   ACT Symbol|Security Name|Exchange|CQS Symbol|ETF|Round Lot Size|Test Issue|NASDAQ Symbol
     * Exchange codes: A=NYSE Arca, N=NYSE, P=NYSE Arca, Z=Cboe BZX, V=IEX, ...
     */
    private int parseOtherListed(final String body, final Map<String, Row> out) throws IOException {
        int count = 0;
        try (BufferedReader r = new BufferedReader(new StringReader(body))) {
            String header = r.readLine();
            if (header == null || !header.startsWith("ACT Symbol|")) {
                throw new IOException("unexpected otherlisted header: " + header);
            }
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("File Creation Time")) break;
                String[] f = line.split("\\|", -1);
                if (f.length < 7) continue;
                String symbol = f[0].trim();
                if (symbol.isEmpty()) continue;
                String securityName = f[1].trim();
                String exchangeCode = f[2].trim();
                String etfFlag      = f[4].trim();
                Integer roundLot    = parseIntOrNull(f[5]);
                String testIssue    = f[6].trim();

                if ("Y".equals(testIssue)) continue;

                Row row = out.computeIfAbsent(symbol, Row::new);
                row.companyName     = securityName;
                row.listingExchange = listingExchangeName(exchangeCode);
                row.isEtf           = "Y".equals(etfFlag);
                if (row.roundLot == null) row.roundLot = roundLot;
                if (row.source == null) row.source = "nasdaq_other";
                count++;
            }
        }
        return count;
    }

    /** NASDAQ's single-letter exchange codes. */
    private static String listingExchangeName(final String code) {
        return switch (code) {
            case "A" -> "NYSE MKT";
            case "N" -> "NYSE";
            case "P" -> "NYSE Arca";
            case "Z" -> "Cboe BZX";
            case "V" -> "IEX";
            default  -> code;
        };
    }

    // ─── IEX SecurityDirectory augmentation ──────────────────────────────────

    /**
     * For each symbol in {@code merged}, look up the most-recent
     * SecurityDirectory row from our local {@code securities} table and
     * fill in round_lot / prev_close / luld_tier / is_etf. These are
     * authoritative for IEX-side metadata, more current than the
     * NASDAQ-listed defaults.
     */
    private long augmentFromIexSecurities(final Connection conn, final Map<String, Row> merged) throws Exception {
        // Most-recent SecurityDirectory row per symbol, looking back 60 days
        // so we have a chunk-bound to avoid scanning the whole hypertable.
        // SecurityDirectory messages are shared across all three IEX feeds
        // (admin messages, byte-identical), so we don't filter by feed_source —
        // most-recent wins.
        String sql = """
                SELECT DISTINCT ON (symbol)
                       symbol, round_lot_size, adjusted_poc_price_raw, luld_tier
                FROM securities
                WHERE ts >= NOW() - INTERVAL '60 days'
                ORDER BY symbol, ts DESC
                """;
        long updated = 0;
        try (PreparedStatement st = conn.prepareStatement(sql);
             java.sql.ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                String symbol = rs.getString("symbol");
                Row row = merged.get(symbol);
                if (row == null) continue;  // IEX has data we don't have a name for — skip

                int roundLot = rs.getInt("round_lot_size");
                long pocRaw  = rs.getLong("adjusted_poc_price_raw");
                short luld   = rs.getShort("luld_tier");

                if (roundLot > 0) row.roundLot = roundLot;
                if (pocRaw > 0)   row.prevCloseDollars = pocRaw / 10_000.0;
                row.luldTier = luldTierLabel(luld);
                updated++;
            }
        }
        return updated;
    }

    /** Map LULD tier byte to spec label (0 = N/A, 1 = Tier 1, 2 = Tier 2). */
    private static String luldTierLabel(final short tier) {
        return switch (tier) {
            case 1  -> "Tier 1";
            case 2  -> "Tier 2";
            default -> null;
        };
    }

    // ─── Upsert ──────────────────────────────────────────────────────────────

    private long upsert(final Connection conn, final Map<String, Row> rows) throws Exception {
        String sql = """
                INSERT INTO symbols (
                    symbol, company_name, listing_exchange, is_etf,
                    round_lot, prev_close_dollars, luld_tier, updated_at, source
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), ?)
                ON CONFLICT (symbol) DO UPDATE SET
                    company_name       = EXCLUDED.company_name,
                    listing_exchange   = EXCLUDED.listing_exchange,
                    is_etf             = EXCLUDED.is_etf,
                    round_lot          = COALESCE(EXCLUDED.round_lot, symbols.round_lot),
                    prev_close_dollars = COALESCE(EXCLUDED.prev_close_dollars, symbols.prev_close_dollars),
                    luld_tier          = COALESCE(EXCLUDED.luld_tier, symbols.luld_tier),
                    updated_at         = NOW(),
                    source             = EXCLUDED.source
                """;
        long n = 0;
        conn.setAutoCommit(false);
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            int batchSize = 0;
            for (Row r : rows.values()) {
                st.setString(1, r.symbol);
                if (r.companyName != null)     st.setString(2, r.companyName);     else st.setNull(2, Types.VARCHAR);
                if (r.listingExchange != null) st.setString(3, r.listingExchange); else st.setNull(3, Types.VARCHAR);
                if (r.isEtf != null)           st.setBoolean(4, r.isEtf);          else st.setNull(4, Types.BOOLEAN);
                if (r.roundLot != null)        st.setInt(5, r.roundLot);           else st.setNull(5, Types.INTEGER);
                if (r.prevCloseDollars != null) st.setDouble(6, r.prevCloseDollars); else st.setNull(6, Types.DOUBLE);
                if (r.luldTier != null)        st.setString(7, r.luldTier);        else st.setNull(7, Types.VARCHAR);
                st.setString(8, r.source != null ? r.source : "nasdaq_listed");
                st.addBatch();
                if (++batchSize % 500 == 0) {
                    int[] result = st.executeBatch();
                    n += result.length;
                }
            }
            int[] result = st.executeBatch();
            n += result.length;
            conn.commit();
        } catch (Exception e) {
            conn.rollback();
            throw e;
        }
        return n;
    }

    private static Integer parseIntOrNull(final String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        try { return Integer.parseInt(t); } catch (NumberFormatException e) { return null; }
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

    /** Intermediate merge target, one per symbol. */
    private static final class Row {
        final String symbol;
        String  companyName;
        String  listingExchange;
        Boolean isEtf;
        Integer roundLot;
        Double  prevCloseDollars;
        String  luldTier;
        String  source;

        Row(final String symbol) {
            this.symbol = symbol;
        }
    }

}
