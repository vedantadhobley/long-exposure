package com.longexposure.temporal.activities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Implementation of {@link RetainRawFilesActivity}. Walks
 * {@code IEX_RAW_DIR} (default {@code /storage/raw}), extracts the date stem
 * from each {@code *.pcap.gz} filename, and deletes any file older than
 * {@code retainDays}. Per-file failures logged, not fatal.
 */
public final class RetainRawFilesActivityImpl implements RetainRawFilesActivity {

    private static final Logger LOG = LoggerFactory.getLogger(RetainRawFilesActivityImpl.class);
    private static final DateTimeFormatter STEM_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** Match {@code 20260513_IEXTP1_DPLS1.0.pcap.gz} etc. — group 1 is the date stem. */
    private static final Pattern PCAP_NAME = Pattern.compile("^(\\d{8})_IEXTP1_[A-Z]+\\d\\.\\d+\\.pcap\\.gz$");

    @Override
    public RetainResult retain(final int retainDays) {
        String rawDir = System.getenv().getOrDefault("IEX_RAW_DIR", "/storage/raw");
        Path dir = Path.of(rawDir);
        if (!Files.isDirectory(dir)) {
            LOG.info("retain skip — raw dir absent  path={}", rawDir);
            return new RetainResult(0, 0);
        }
        LocalDate cutoff = todayUtc().minusDays(retainDays);
        int filesDeleted = 0;
        long bytesFreed = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.pcap.gz")) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                Matcher m = PCAP_NAME.matcher(name);
                if (!m.matches()) continue;
                LocalDate fileDate;
                try {
                    fileDate = LocalDate.parse(m.group(1), STEM_FMT);
                } catch (Exception e) {
                    LOG.warn("retain skip — unparseable date stem  name={}", name);
                    continue;
                }
                if (!fileDate.isBefore(cutoff)) continue;  // within retention window
                try {
                    long size = Files.size(p);
                    Files.delete(p);
                    filesDeleted++;
                    bytesFreed += size;
                    LOG.info("retain delete  name={} date={} bytes={}", name, fileDate, size);
                } catch (Exception e) {
                    LOG.warn("retain delete failed  name={} err={}", name, e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.warn("retain dir scan failed  path={} err={}", rawDir, e.getMessage());
        }
        LOG.info("retain complete  cutoff={} files_deleted={} bytes_freed={}",
                cutoff, filesDeleted, bytesFreed);
        return new RetainResult(filesDeleted, bytesFreed);
    }

    /** UTC today — sufficient for day-granularity retention math. */
    private static LocalDate todayUtc() {
        return LocalDate.now(java.time.ZoneOffset.UTC);
    }
}
