package com.longexposure.narration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests focused on the dotted-path source_field resolution added so the
 * verifier can validate blueprints that cite enrichment-derived numbers
 * (e.g. {@code co_occurring.during_event.post_cancel_cluster.sum_orders}).
 */
final class GroundingVerifierTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void hasPath_topLevel() throws Exception {
        JsonNode bd = JSON.readTree("{\"deletes\": 4895, \"symbol\": \"IWM\"}");
        assertTrue(GroundingVerifier.hasPath(bd, "deletes"));
        assertTrue(GroundingVerifier.hasPath(bd, "symbol"));
        assertFalse(GroundingVerifier.hasPath(bd, "missing"));
    }

    @Test
    void hasPath_nested() throws Exception {
        JsonNode bd = JSON.readTree("""
                {
                  "symbol": "IWM",
                  "co_occurring": {
                    "during_event": {
                      "post_cancel_cluster": {
                        "count": 26,
                        "sum_orders": 3759.0,
                        "sum_total_shares": 568131.0
                      }
                    },
                    "total_children": 52
                  }
                }""");

        assertTrue(GroundingVerifier.hasPath(bd, "co_occurring"));
        assertTrue(GroundingVerifier.hasPath(bd, "co_occurring.total_children"));
        assertTrue(GroundingVerifier.hasPath(bd,
                "co_occurring.during_event.post_cancel_cluster.sum_orders"));
        assertTrue(GroundingVerifier.hasPath(bd,
                "co_occurring.during_event.post_cancel_cluster.sum_total_shares"));

        // Iceberg sub-key isn't present in this breakdown
        assertFalse(GroundingVerifier.hasPath(bd,
                "co_occurring.during_event.iceberg.count"));
        // Walking off a non-object value should be a clean miss
        assertFalse(GroundingVerifier.hasPath(bd, "symbol.subfield"));
    }

    @Test
    void hasPath_edgeCases() throws Exception {
        JsonNode bd = JSON.readTree("{\"a\": null, \"b\": {}}");
        assertFalse(GroundingVerifier.hasPath(bd, "a"));       // null leaf
        assertFalse(GroundingVerifier.hasPath(bd, "b.x"));     // missing nested
        assertFalse(GroundingVerifier.hasPath(bd, ""));        // empty path
        assertFalse(GroundingVerifier.hasPath(bd, null));      // null path
    }

    @Test
    void verify_passesWhenBlueprintCitesNestedCoOccurringField() throws Exception {
        // Reproduces the IWM liquidity_withdrawal case: prose cites
        // post_cancel_cluster figures from the co_occurring enrichment
        // and blueprint maps them with dotted source paths.
        JsonNode breakdown = JSON.readTree("""
                {
                  "symbol": "IWM",
                  "deletes": 4895,
                  "rate_per_sec": 417.64,
                  "duration": "11.7 sec",
                  "co_occurring": {
                    "during_event": {
                      "post_cancel_cluster": {
                        "count": 26,
                        "sum_orders": 3759.0,
                        "sum_total_shares": 568131.0
                      }
                    },
                    "total_children": 52
                  }
                }""");
        JsonNode blueprint = JSON.readTree("""
                {
                  "key_numbers": [
                    {"value": 4895, "label": "deletes", "source_field": "deletes"},
                    {"value": 11.7, "label": "duration_s", "source_field": "duration"},
                    {"value": 3759, "label": "child_orders",
                     "source_field": "co_occurring.during_event.post_cancel_cluster.sum_orders"},
                    {"value": 568131, "label": "child_shares",
                     "source_field": "co_occurring.during_event.post_cancel_cluster.sum_total_shares"}
                  ]
                }""");
        String prose = "IWM saw 4895 deletes over 11.7 seconds; during which 26 post-cancel "
                + "clusters totaling 3,759 orders and 568,131 shares unfolded.";

        GroundingVerifier.Result r = new GroundingVerifier().verify(prose, blueprint, breakdown);
        assertTrue(r.passed(), "expected pass, got mismatches: " + r.mismatches());
    }

    @Test
    void verify_failsWhenSourceFieldGenuinelyMissing() throws Exception {
        JsonNode breakdown = JSON.readTree("{\"symbol\": \"X\", \"deletes\": 100}");
        JsonNode blueprint = JSON.readTree("""
                {"key_numbers":[
                   {"value": 999, "label": "bogus", "source_field": "co_occurring.invented.path"}
                ]}""");
        GroundingVerifier.Result r = new GroundingVerifier().verify("X had 100 deletes", blueprint, breakdown);
        assertFalse(r.passed());
        assertTrue(r.mismatches().stream().anyMatch(m -> m.contains("co_occurring.invented.path")),
                "expected mismatch to cite the missing dotted path: " + r.mismatches());
    }

    @Test
    void companyName_acceptsStandardAbbreviation() {
        // Intel Corp. (INTC) — breakdown has "Intel Corporation" (post-normalizer)
        assertTrue(GroundingVerifier.companyNamesAgree("Intel Corp.", "Intel Corporation"));
        // Toast, Inc. ↔ Toast, Inc. (identical post-normalization)
        assertTrue(GroundingVerifier.companyNamesAgree("Toast, Inc.", "Toast, Inc."));
        // plc / Plc case differences
        assertTrue(GroundingVerifier.companyNamesAgree("Accenture Plc", "Accenture plc"));
        // The ETF identity stays intact
        assertTrue(GroundingVerifier.companyNamesAgree("Vanguard Russell 2000 ETF",
                "Vanguard Russell 2000 ETF"));
    }

    @Test
    void companyName_rejectsSubstitution() {
        // ODTX case from v6: model wrote "Oculus Dynamics Inc."
        assertFalse(GroundingVerifier.companyNamesAgree("Oculus Dynamics Inc.",
                "Odyssey Therapeutics, Inc."));
        // AEHL: completely different company
        assertFalse(GroundingVerifier.companyNamesAgree("Anterix Inc.",
                "Antelope Enterprise Holdings Limited"));
        // MAYW: invented name for an ETF
        assertFalse(GroundingVerifier.companyNamesAgree("Mayweather Inc.",
                "AllianzIM U.S. Large Cap Buffer20 May ETF"));
        // LODE: added a word the breakdown doesn't have
        assertFalse(GroundingVerifier.companyNamesAgree("Comstock Mining Inc.", "Comstock Inc."));
    }

    @Test
    void extractLeadingBeforeTicker_returnsLeadingText() {
        // Subject-led: returns everything before "(TICKER)"
        assertEquals("Intel Corp.",
                GroundingVerifier.extractLeadingBeforeTicker(
                        "Intel Corp. (INTC) experienced a layering event.", "INTC"));
        // Verb-led: still returns everything before "(TICKER)"
        assertEquals("Liquidity withdrawal occurred on iShares Russell 2000 Index Fund",
                GroundingVerifier.extractLeadingBeforeTicker(
                        "Liquidity withdrawal occurred on iShares Russell 2000 Index Fund (IWM) marked by 4895 deletes.",
                        "IWM"));
        // Ticker not in prose
        assertNull(GroundingVerifier.extractLeadingBeforeTicker(
                "INTC experienced a layering event.", "INTC"));
        // Ticker only at very start (no leading text)
        assertNull(GroundingVerifier.extractLeadingBeforeTicker(
                "(INTC) experienced a layering event.", "INTC"));
    }

    @Test
    void companyName_acceptsVerbLedProse() {
        // The IWM v7 failure case — verb-led prose, company name is in the
        // middle of the sentence but still the contiguous trailing window
        // of the leading text agrees with the breakdown's company_name.
        assertTrue(GroundingVerifier.companyNamesAgree(
                "Liquidity withdrawal occurred on iShares Russell 2000 Index Fund",
                "iShares Russell 2000 Index Fund"));
        // Verb-led with abbreviated entity suffix
        assertTrue(GroundingVerifier.companyNamesAgree(
                "Shares of Apple Inc.",
                "Apple Inc."));
        // Verb-led where prose used Corp. for Corporation
        assertTrue(GroundingVerifier.companyNamesAgree(
                "Trading of Intel Corp.",
                "Intel Corporation"));
    }

    @Test
    void verify_failsWhenProseSubstitutesCompany() throws Exception {
        JsonNode breakdown = JSON.readTree(
                "{\"symbol\": \"ODTX\", \"company_name\": \"Odyssey Therapeutics, Inc.\"}");
        JsonNode blueprint = JSON.readTree("{\"key_numbers\":[]}");
        String prose = "Oculus Dynamics Inc. (ODTX) was halted.";
        GroundingVerifier.Result r = new GroundingVerifier().verify(prose, blueprint, breakdown);
        assertFalse(r.passed());
        // New mismatch format references the ticker context + breakdown company_name
        assertTrue(r.mismatches().stream().anyMatch(
                m -> m.contains("(ODTX)") && m.contains("Odyssey Therapeutics")),
                "expected mismatch to cite the ticker and the canonical company_name: " + r.mismatches());
    }

    @Test
    void verify_passesWhenProseUsesAbbreviation() throws Exception {
        JsonNode breakdown = JSON.readTree(
                "{\"symbol\": \"INTC\", \"company_name\": \"Intel Corporation\"}");
        JsonNode blueprint = JSON.readTree("{\"key_numbers\":[]}");
        String prose = "Intel Corp. (INTC) experienced a layering event.";
        GroundingVerifier.Result r = new GroundingVerifier().verify(prose, blueprint, breakdown);
        assertTrue(r.passed(), "expected pass for Corp ↔ Corporation: " + r.mismatches());
    }
}
