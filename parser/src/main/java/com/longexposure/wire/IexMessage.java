package com.longexposure.wire;

/**
 * Common marker for any decoded IEX message — admin or feed-specific
 * (TOPS today, DEEP+ in phase 2). Lets per-feed routers return a single
 * type that covers both shared admin messages and feed-specific bodies.
 *
 * <p>Intentionally not sealed: the set of feed-specific subhierarchies
 * grows as new feeds land. The two existing sealed subhierarchies
 * ({@code AdminMessage}, {@code TopsMessage}) restrict their own subtypes.
 */
public interface IexMessage {
    /** Spec-defined 1-byte type identifier. */
    byte messageType();

    /** Nanoseconds since POSIX epoch UTC, from the message's Timestamp field. */
    long timestampNanos();
}
