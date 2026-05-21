package com.longexposure.narration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RenderResultTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void stitched_concatenatesSlotsWithSingleSpaces() {
        RenderResult r = new RenderResult(
                "AMD experienced a layering event on IEX lasting 166.0 ms.",
                List.of("The activity involved 187 orders across 116 distinct price levels."),
                "During this interval, the spread tightened by 2.5 cents.");
        assertEquals(
                "AMD experienced a layering event on IEX lasting 166.0 ms. "
                        + "The activity involved 187 orders across 116 distinct price levels. "
                        + "During this interval, the spread tightened by 2.5 cents.",
                r.stitched());
    }

    @Test
    void stitched_omitsNullCoOccurring() {
        RenderResult r = new RenderResult("ODTX was halted for 4h 43m.", List.of(), null);
        assertEquals("ODTX was halted for 4h 43m.", r.stitched());
    }

    @Test
    void emptyFactsListIsValid() {
        RenderResult r = new RenderResult("just a lead.", List.of(), null);
        assertEquals("just a lead.", r.stitched());
        assertTrue(r.facts().isEmpty());
    }

    @Test
    void emptyLeadRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new RenderResult("", List.of("fact"), null));
        assertThrows(IllegalArgumentException.class,
                () -> new RenderResult(null, List.of(), null));
    }

    @Test
    void roundTripJson() throws Exception {
        RenderResult original = new RenderResult(
                "IWM saw a liquidity withdrawal.",
                List.of("4,895 deletes at 417.64/sec."),
                "Co-occurring layering involved 565,131 shares.");
        JsonNode encoded = original.toJson(JSON);
        RenderResult roundtripped = RenderResult.fromJson(encoded);
        assertEquals(original.lead(), roundtripped.lead());
        assertEquals(original.facts(), roundtripped.facts());
        assertEquals(original.coOccurring(), roundtripped.coOccurring());
    }

    @Test
    void fromJson_treatsNullCoOccurringAsNull() throws Exception {
        JsonNode node = JSON.readTree(
                "{\"lead\":\"foo\",\"facts\":[\"bar\"],\"co_occurring\":null}");
        RenderResult r = RenderResult.fromJson(node);
        assertNull(r.coOccurring());
    }
}
