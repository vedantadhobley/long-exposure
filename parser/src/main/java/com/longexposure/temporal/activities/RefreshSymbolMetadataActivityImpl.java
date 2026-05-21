package com.longexposure.temporal.activities;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RefreshSymbolMetadataActivityImpl implements RefreshSymbolMetadataActivity {

    private static final Logger LOG = LoggerFactory.getLogger(RefreshSymbolMetadataActivityImpl.class);

    private static final String NASDAQ_LISTED_URL =
            "https://www.nasdaqtrader.com/dynamic/symdir/nasdaqlisted.txt";
    private static final String OTHER_LISTED_URL =
            "https://www.nasdaqtrader.com/dynamic/symdir/otherlisted.txt";
    /**
     * SEC EDGAR's canonical ticker → CIK + entity name mapping. Updated daily,
     * one row per current ticker, ~13K entries. The {@code title} field is
     * the SEC-registered entity name without filing-class decoration —
     * "Apple Inc.", not "Apple Inc. - Common Stock". This is the cleanest
     * public source for ticker → company-name lookups; we prefer it over
     * the NASDAQ Security Name field whenever EDGAR has the ticker.
     */
    private static final String SEC_EDGAR_TICKERS_URL =
            "https://www.sec.gov/files/company_tickers.json";

    /**
     * SEC's fair-access policy (https://www.sec.gov/os/accessing-edgar-data)
     * requires a User-Agent containing a real contact email. Requests
     * without one return 403. Override at deploy time via
     * {@code SEC_USER_AGENT} env var if needed.
     */
    private static final String SEC_USER_AGENT = System.getenv().getOrDefault(
            "SEC_USER_AGENT",
            "LongExposure/1.0 admin@vedanta.systems");

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(15))
            .readTimeout(Duration.ofMinutes(2))
            .build();

    private final ObjectMapper json = new ObjectMapper();

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

        // Step 2.5: Overlay SEC EDGAR's canonical company names. Failure here
        // is non-fatal — NASDAQ Security Names + the normalizer are an
        // adequate fallback if SEC is unreachable.
        try {
            int n = overlayEdgarTitles(fetch(SEC_EDGAR_TICKERS_URL, SEC_USER_AGENT), merged);
            LOG.info("edgar titles applied  rows_overlaid={}", n);
        } catch (Exception e) {
            LOG.warn("edgar fetch failed — falling back to NASDAQ Security Name only", e);
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
        return fetch(url, null);
    }

    private String fetch(final String url, final String userAgent) throws IOException {
        Request.Builder b = new Request.Builder().url(url).get();
        if (userAgent != null) b.header("User-Agent", userAgent);
        try (Response resp = http.newCall(b.build()).execute()) {
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

    // ─── SEC EDGAR overlay ───────────────────────────────────────────────────

    /**
     * SEC publishes {@code company_tickers.json} as a JSON object keyed by
     * numeric index, each value containing {@code cik_str}, {@code ticker},
     * and {@code title}. The {@code title} is the registrant's name with
     * no filing-class decoration — what we want.
     *
     * <p>For symbols we already have in {@code merged}, overlay EDGAR's
     * {@code title} on top of NASDAQ's {@code Security Name}. Symbols
     * absent from EDGAR keep the NASDAQ value (which {@link
     * com.longexposure.narration.CompanyNameNormalizer} cleans at score
     * time as a fallback). Symbols present in EDGAR but absent from
     * NASDAQ are ignored — without exchange / round-lot metadata they
     * aren't useful for narration.
     */
    private int overlayEdgarTitles(final String body, final Map<String, Row> out) throws IOException {
        JsonNode root = json.readTree(body);
        if (!root.isObject()) {
            throw new IOException("EDGAR ticker file was not a JSON object");
        }
        int overlaid = 0;
        Iterator<Map.Entry<String, JsonNode>> it = root.fields();
        while (it.hasNext()) {
            JsonNode entry = it.next().getValue();
            String ticker = entry.path("ticker").asText("");
            String title  = entry.path("title").asText("");
            if (ticker.isEmpty() || title.isEmpty()) continue;
            Row row = out.get(ticker);
            if (row == null) continue;   // not in our NASDAQ-listed universe
            if (preferEdgar(row.companyName, title)) {
                row.companyName = titleCase(title);
                overlaid++;
            }
        }
        return overlaid;
    }

    /**
     * Whether EDGAR's title should overlay the NASDAQ Security Name.
     * Logic: EDGAR wins unless NASDAQ has good mixed-case content that
     * EDGAR's all-caps version would degrade. Mixed-case carries brand
     * styling ("NVIDIA Corporation", "FuelCell Energy, Inc.") that an
     * all-caps EDGAR entry would lose through naive title-casing.
     *
     * <ul>
     *   <li>NASDAQ absent → EDGAR wins
     *   <li>EDGAR mixed-case → EDGAR wins (canonical, no decoration)
     *   <li>EDGAR all-caps + NASDAQ mixed-case → NASDAQ wins
     *   <li>Both all-caps → EDGAR wins (we'll title-case it; NASDAQ has
     *       filing decoration that the normalizer needs to strip anyway)
     * </ul>
     */
    static boolean preferEdgar(final String nasdaqName, final String edgarTitle) {
        if (nasdaqName == null || nasdaqName.isBlank()) return true;
        boolean edgarMixed  = isMixedCase(edgarTitle);
        boolean nasdaqMixed = isMixedCase(nasdaqName);
        if (edgarMixed) return true;
        return !nasdaqMixed;
    }

    private static boolean isMixedCase(final String s) {
        if (s == null) return false;
        boolean hasLower = false, hasUpper = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLowerCase(c)) hasLower = true;
            else if (Character.isUpperCase(c)) hasUpper = true;
            if (hasLower && hasUpper) return true;
        }
        return false;
    }

    /**
     * SEC titles are inconsistent in case ("Apple Inc." vs "MICROSOFT
     * CORP" vs "Vanguard Index Funds"). Title-case the all-uppercase ones
     * conservatively — preserve already-mixed-case titles untouched (they
     * encode meaningful case like "plc" or "iShares" the title-case rule
     * would damage).
     */
    static String titleCase(final String s) {
        if (s == null || s.isBlank()) return s;
        // Already mixed case? Leave it alone.
        boolean hasLower = false, hasUpper = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLowerCase(c)) hasLower = true;
            else if (Character.isUpperCase(c)) hasUpper = true;
        }
        if (hasLower && hasUpper) return s;
        if (!hasUpper) return s;     // all lowercase, also unusual but leave

        // All-caps input → title-case word by word, preserving entity-type
        // tokens in their canonical form.
        String[] words = s.toLowerCase().split("\\s+");
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < words.length; i++) {
            if (i > 0) out.append(' ');
            out.append(capitalizeEntityAware(words[i]));
        }
        return out.toString();
    }

    private static String capitalizeEntityAware(final String word) {
        if (word == null || word.isEmpty()) return word;
        // Common entity tokens have canonical forms not produced by naive title-case.
        return switch (word.replaceAll("[^a-z]", "")) {
            case "inc"   -> applyCaseTemplate(word, "Inc");
            case "corp"  -> applyCaseTemplate(word, "Corp");
            case "ltd"   -> applyCaseTemplate(word, "Ltd");
            case "plc"   -> applyCaseTemplate(word, "plc");
            case "llc"   -> applyCaseTemplate(word, "LLC");
            case "lp"    -> applyCaseTemplate(word, "LP");
            case "nv"    -> applyCaseTemplate(word, "N.V.");
            case "ag"    -> applyCaseTemplate(word, "AG");
            case "sa"    -> applyCaseTemplate(word, "S.A.");
            default      -> Character.toUpperCase(word.charAt(0)) + word.substring(1);
        };
    }

    /** Apply replacement while preserving trailing punctuation. */
    private static String applyCaseTemplate(final String original, final String canonical) {
        int idx = original.length() - 1;
        while (idx >= 0 && !Character.isLetterOrDigit(original.charAt(idx))) idx--;
        return canonical + original.substring(idx + 1);
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
