package com.longexposure.temporal.activities;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

/**
 * Implementation of {@link DownloadFileActivity}.
 *
 * <p>Streams a large .pcap.gz from a GCS-signed URL to a local path,
 * heartbeating every few seconds so Temporal knows the activity is alive.
 *
 * <p>Resume safety: if {@code destPath} already exists and its size
 * matches the {@code Content-Length} header from a HEAD request, the
 * activity returns immediately — useful when Temporal retries a long
 * download after a transient failure mid-stream. We don't try to do
 * a true byte-range resume; full re-download is simpler and fast enough
 * for the 10 GB file size at our download speed.
 */
public final class DownloadFileActivityImpl implements DownloadFileActivity {

    private static final Logger LOG = LoggerFactory.getLogger(DownloadFileActivityImpl.class);

    /** Heartbeat every N bytes streamed. ~50 MB → roughly every second at typical speeds. */
    private static final long HEARTBEAT_BYTES = 50L * 1024 * 1024;

    /** Buffer size for the stream copy. */
    private static final int BUFFER_BYTES = 64 * 1024;

    private final OkHttpClient http;

    public DownloadFileActivityImpl() {
        this(new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(15))
                .readTimeout(Duration.ofMinutes(5))
                .callTimeout(Duration.ofMinutes(30))
                .build());
    }

    public DownloadFileActivityImpl(final OkHttpClient http) {
        this.http = http;
    }

    @Override
    public String downloadFile(final String url, final String destPath) {
        ActivityExecutionContext ctx = Activity.getExecutionContext();
        Path target = Path.of(destPath);

        // Resume short-circuit: if file already exists at the expected size, skip the download.
        try {
            long expected = headContentLength(url);
            if (expected > 0 && Files.exists(target) && Files.size(target) == expected) {
                LOG.info("download skipped — file already exists at expected size  path={} bytes={}",
                        destPath, expected);
                return destPath;
            }
        } catch (IOException headFailed) {
            // Best effort — if HEAD failed, just download fresh
            LOG.warn("HEAD failed, will download fresh  url={} err={}", url, headFailed.getMessage());
        }

        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            throw new RuntimeException("could not create parent directory for " + destPath, e);
        }

        long startNanos = System.nanoTime();
        Request req = new Request.Builder().url(url).get().build();
        long totalBytes = 0;
        long nextHeartbeatAt = HEARTBEAT_BYTES;

        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("download returned HTTP " + resp.code() + " for url=" + url);
            }
            ResponseBody body = resp.body();
            if (body == null) {
                throw new IOException("response body was null for url=" + url);
            }

            try (InputStream in = body.byteStream();
                 OutputStream out = Files.newOutputStream(target,
                         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buf = new byte[BUFFER_BYTES];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    totalBytes += n;
                    if (totalBytes >= nextHeartbeatAt) {
                        ctx.heartbeat(totalBytes);
                        nextHeartbeatAt += HEARTBEAT_BYTES;
                    }
                }
            }
        } catch (IOException ioe) {
            // Best effort cleanup of partial file before letting Temporal retry
            try { Files.deleteIfExists(target); } catch (IOException ignored) {}
            throw new RuntimeException("download failed for url=" + url, ioe);
        }

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        LOG.info("download complete  url={} path={} bytes={} elapsed_ms={} mb_per_sec={}",
                url, destPath, totalBytes, elapsedMs,
                String.format("%.1f", (totalBytes / 1_048_576.0) / Math.max(elapsedMs / 1000.0, 1)));
        return destPath;
    }

    /** HEAD the URL to learn the file size; -1 if unavailable. */
    private long headContentLength(final String url) throws IOException {
        Request req = new Request.Builder().url(url).head().build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) return -1L;
            String len = resp.header("Content-Length");
            if (len == null) return -1L;
            return Long.parseLong(len);
        } catch (NumberFormatException nfe) {
            return -1L;
        }
    }
}
