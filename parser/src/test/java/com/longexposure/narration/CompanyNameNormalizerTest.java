package com.longexposure.narration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class CompanyNameNormalizerTest {

    @Test
    void stripsCommonStockSuffix() {
        assertEquals("Intel Corporation",
                CompanyNameNormalizer.normalize("Intel Corporation - Common Stock"));
        assertEquals("Broadcom Inc.",
                CompanyNameNormalizer.normalize("Broadcom Inc. - Common Stock"));
        assertEquals("Odyssey Therapeutics, Inc.",
                CompanyNameNormalizer.normalize("Odyssey Therapeutics, Inc. - Common Stock"));
    }

    @Test
    void stripsClassACommonStock() {
        assertEquals("Toast, Inc.",
                CompanyNameNormalizer.normalize("Toast, Inc. Class A Common Stock"));
    }

    @Test
    void stripsClassOrdinarySharesPlusCountryParen() {
        assertEquals("Accenture plc",
                CompanyNameNormalizer.normalize("Accenture plc Class A Ordinary Shares (Ireland)"));
    }

    @Test
    void stripsCommonStockADR() {
        assertEquals("British American Tobacco Industries, p.l.c.",
                CompanyNameNormalizer.normalize("British American Tobacco  Industries, p.l.c. Common Stock ADR"));
    }

    @Test
    void stripsSeriesNumberSuffix() {
        assertEquals("Invesco QQQ Trust",
                CompanyNameNormalizer.normalize("Invesco QQQ Trust, Series 1"));
    }

    @Test
    void leavesETFNamesUnchanged() {
        // "ETF" is part of identity, not decoration.
        assertEquals("Vanguard Russell 2000 ETF",
                CompanyNameNormalizer.normalize("Vanguard Russell 2000 ETF"));
        assertEquals("AllianzIM U.S. Large Cap Buffer20 May ETF",
                CompanyNameNormalizer.normalize("AllianzIM U.S. Large Cap Buffer20 May ETF"));
    }

    @Test
    void leavesFundOrTrustNamesUnchanged() {
        // "Fund" and "Trust" are also identity tokens, not decoration.
        assertEquals("iShares Russell 2000 Index Fund",
                CompanyNameNormalizer.normalize("iShares Russell 2000 Index Fund"));
    }

    @Test
    void leavesEntitySuffixIntact() {
        // Inc., Corp., plc, Ltd, Limited — these ARE the company identity.
        assertEquals("Apple Inc.", CompanyNameNormalizer.normalize("Apple Inc."));
        assertEquals("NVIDIA Corporation",
                CompanyNameNormalizer.normalize("NVIDIA Corporation"));
    }

    @Test
    void handlesNullAndEmpty() {
        assertNull(CompanyNameNormalizer.normalize(null));
        assertEquals("", CompanyNameNormalizer.normalize(""));
        assertEquals("", CompanyNameNormalizer.normalize("   "));
    }

    @Test
    void collapsesRunsOfWhitespace() {
        assertEquals("British American Tobacco Industries, p.l.c.",
                CompanyNameNormalizer.normalize("British American Tobacco  Industries, p.l.c."));
    }

    @Test
    void stripsMlpCommonUnitsSuffix() {
        // The MLP filing suffix needs multi-word matching because
        // "Limited" individually is a valid entity-type token.
        assertEquals("Plains All American Pipeline, L.P.",
                CompanyNameNormalizer.normalize(
                        "Plains All American Pipeline, L.P. - Common Units representing Limited Partner Interests"));
    }

    @Test
    void doesNotStripStandaloneLimitedEntity() {
        // "Limited" as a standalone entity suffix must be preserved —
        // the MLP pre-strip is multi-word so it doesn't fire here.
        assertEquals("Antelope Enterprise Holdings Limited",
                CompanyNameNormalizer.normalize("Antelope Enterprise Holdings Limited"));
    }

    @Test
    void stripsEtnDueSuffix() {
        assertEquals("MAX S&P 500 4X Leveraged ETNs",
                CompanyNameNormalizer.normalize("MAX S&P 500 4X Leveraged ETNs due October"));
        assertEquals("MAX S&P 500 4X Leveraged ETNs",
                CompanyNameNormalizer.normalize("MAX S&P 500 4X Leveraged ETNs due October 2030"));
    }
}
