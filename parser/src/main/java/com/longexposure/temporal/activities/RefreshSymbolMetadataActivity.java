package com.longexposure.temporal.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Refreshes the {@code symbols} reference table from external sources.
 * Designed to run on a weekly Temporal schedule — symbol metadata
 * changes slowly (new listings, ticker changes, ETF flag updates) so
 * once a week is plenty.
 *
 * <p>Sources:
 * <ul>
 *   <li><a href="https://www.nasdaqtrader.com/dynamic/symdir/nasdaqlisted.txt">nasdaqlisted.txt</a>
 *       — pipe-delimited file from NASDAQ FTP listing every NASDAQ-listed
 *       symbol with company name and ETF flag. Updated daily.
 *   <li><a href="https://www.nasdaqtrader.com/dynamic/symdir/otherlisted.txt">otherlisted.txt</a>
 *       — same shape, covers NYSE / NYSE Arca / Cboe / etc. (everything
 *       non-NASDAQ).
 *   <li>Our own {@code securities} table — populated nightly by the
 *       parser from IEX SecurityDirectory messages. Source of round_lot,
 *       previous-day adjusted close, LULD tier, IEX-side flags.
 * </ul>
 *
 * <p>The activity upserts rows into {@code symbols} keyed on the
 * ticker. Idempotent — running it multiple times in a row just
 * refreshes the timestamps.
 *
 * @return number of rows touched (inserts + updates).
 */
@ActivityInterface
public interface RefreshSymbolMetadataActivity {

    @ActivityMethod
    long refreshSymbolMetadata();
}
