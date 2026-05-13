package com.longexposure.temporal.activities;

/**
 * Which IEX HIST feed an activity is operating on. Used by
 * {@link ResolveUrlActivity} and {@link DownloadFileActivity} to
 * distinguish the three concurrent feed branches in the workflow.
 *
 * <p>The three values map 1:1 to the {@code feed} field returned by
 * the IEX HIST listing endpoint.
 */
public enum Feed {
    DPLS,
    DEEP,
    TOPS
}
