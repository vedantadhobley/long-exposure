package com.longexposure.scoring;

/**
 * Helpers for translating IEX wire-format side codes ({@code '8'} = buy,
 * {@code '5'} = sell) into human-readable labels for narration. Wire
 * codes are correct for storage but illegible to an LLM narrator — see
 * the post-cancel-cluster hallucination documented in
 * {@code docs/scoring-and-narration.md} where the model guessed "sell"
 * for {@code side: "8"}.
 */
public final class Side {

    private Side() {}

    /**
     * @param wireSide one-char wire-format side string from {@code orders_add.side}
     * @return {@code "buy"}, {@code "sell"}, or {@code "unknown"}
     */
    public static String label(final String wireSide) {
        if (wireSide == null || wireSide.isEmpty()) return "unknown";
        return switch (wireSide.charAt(0)) {
            case '8' -> "buy";
            case '5' -> "sell";
            default  -> "unknown";
        };
    }
}
