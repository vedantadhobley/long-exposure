package com.longexposure.temporal.activities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Implementation of {@link CleanupFilesActivity}. Best-effort delete;
 * per-file failures are logged at WARN and don't propagate.
 */
public final class CleanupFilesActivityImpl implements CleanupFilesActivity {

    private static final Logger LOG = LoggerFactory.getLogger(CleanupFilesActivityImpl.class);

    @Override
    public long cleanup(final List<String> filePaths) {
        long bytesFreed = 0;
        for (String p : filePaths) {
            try {
                Path path = Path.of(p);
                if (!Files.exists(path)) {
                    LOG.info("cleanup skip — file missing  path={}", p);
                    continue;
                }
                long size = Files.size(path);
                Files.delete(path);
                bytesFreed += size;
                LOG.info("cleanup ok  path={} bytes={}", p, size);
            } catch (Exception e) {
                LOG.warn("cleanup failed  path={} err={}", p, e.getMessage());
            }
        }
        LOG.info("cleanup complete  files={} bytes_freed={}", filePaths.size(), bytesFreed);
        return bytesFreed;
    }
}
