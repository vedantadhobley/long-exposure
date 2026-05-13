package com.longexposure.temporal.activities;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Implementation of {@link ResolveUrlActivity}.
 *
 * <p>Hits {@code https://iextrading.com/api/1.0/hist?date=YYYYMMDD} and
 * parses the JSON array. Each entry has {@code feed}, {@code version},
 * {@code link}, {@code size}. We filter for the requested feed and
 * return its link.
 *
 * <p>Distinguishes three error modes:
 * <ul>
 *   <li>HTTP returned but body is non-JSON / "Not Found" → no entry for
 *       this date in the HIST listing → {@link NotATradingDay}
 *   <li>HTTP returned valid JSON but the requested feed isn't present
 *       (e.g. DPLS and DEEP uploaded but TOPS isn't ready yet) →
 *       {@link FilesNotReady}
 *   <li>HTTP error (5xx, timeout, DNS) → {@link IOException}; Temporal
 *       retries on transient policy
 * </ul>
 */
public final class ResolveUrlActivityImpl implements ResolveUrlActivity {

    private static final Logger LOG = LoggerFactory.getLogger(ResolveUrlActivityImpl.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String LISTING_URL = "https://iextrading.com/api/1.0/hist?date=";

    private final OkHttpClient http;
    private final ObjectMapper json;

    public ResolveUrlActivityImpl() {
        this(new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(30))
                .build(),
             new ObjectMapper());
    }

    public ResolveUrlActivityImpl(final OkHttpClient http, final ObjectMapper json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public String resolveUrl(final LocalDate tradingDate, final Feed feed) {
        String dateStr = tradingDate.format(DATE_FMT);
        String url = LISTING_URL + dateStr;

        LOG.info("resolving HIST URL  date={} feed={} listing_url={}", dateStr, feed, url);

        Request req = new Request.Builder().url(url).get().build();

        final String body;
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("HIST listing returned HTTP " + resp.code()
                        + " for date=" + dateStr);
            }
            body = resp.body() == null ? "" : resp.body().string();
        } catch (IOException ioe) {
            // Transient — let Temporal retry
            throw new RuntimeException("listing fetch failed transient", ioe);
        }

        // The endpoint returns "Not Found" (non-JSON) when no entries exist.
        // Treat that as the listing-absent case.
        if (body.isBlank() || body.startsWith("Not Found")) {
            throw new NotATradingDay("HIST listing empty for date=" + dateStr
                    + " — not a trading day or files not yet published");
        }

        final JsonNode root;
        try {
            root = json.readTree(body);
        } catch (IOException ioe) {
            // Non-JSON body that isn't "Not Found" is a transient API anomaly
            throw new RuntimeException("HIST listing returned non-JSON body for date=" + dateStr, ioe);
        }

        if (!root.isArray() || root.size() == 0) {
            throw new NotATradingDay("HIST listing empty array for date=" + dateStr);
        }

        String feedName = feed.name();   // DPLS / DEEP / TOPS, matches the API
        for (JsonNode entry : root) {
            String entryFeed = entry.path("feed").asText("");
            if (feedName.equals(entryFeed)) {
                String link = entry.path("link").asText("");
                if (link.isBlank()) {
                    throw new FilesNotReady("HIST entry for date=" + dateStr
                            + " feed=" + feedName + " has empty link");
                }
                LOG.info("resolved  date={} feed={} url={}", dateStr, feedName, link);
                return link;
            }
        }

        // Date has entries but the requested feed isn't among them.
        // Retriable in cron mode, non-retriable in ad-hoc.
        throw new FilesNotReady("HIST listing for date=" + dateStr
                + " has " + root.size() + " entries but no " + feedName + " feed yet");
    }
}
