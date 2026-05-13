package com.longexposure.temporal.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Streams a .pcap.gz from a URL (typically a Google Cloud Storage signed
 * URL returned by {@link ResolveUrlActivity}) to a local path.
 *
 * <p>Heartbeats periodically during the multi-GB pull so Temporal knows
 * the activity is alive. Configurable {@code heartbeat_timeout} on the
 * workflow side; the activity heartbeats faster than that.
 *
 * <p>Idempotent on resume: if the file already exists at {@code destPath}
 * with the expected size (queried via HEAD on the URL), the activity
 * returns immediately without re-downloading. Useful for retries after
 * a network blip.
 *
 * <p>Returns the local path that was written.
 */
@ActivityInterface
public interface DownloadFileActivity {

    @ActivityMethod
    String downloadFile(String url, String destPath);
}
