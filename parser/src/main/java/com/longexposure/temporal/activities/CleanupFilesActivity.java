package com.longexposure.temporal.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.List;

/**
 * Deletes the day's raw .pcap.gz files from disk after the rest of the
 * pipeline has succeeded. Best-effort: a failure to delete one file
 * logs a warning but doesn't fail the activity.
 *
 * <p>The workflow only calls this activity when BOTH parse and validate
 * succeeded. On either failure, the workflow skips the cleanup and the
 * files remain on disk for forensic debugging (manual cleanup if
 * desired).
 *
 * <p>Returns total bytes freed across all successfully-deleted files.
 */
@ActivityInterface
public interface CleanupFilesActivity {

    @ActivityMethod
    long cleanup(List<String> filePaths);
}
