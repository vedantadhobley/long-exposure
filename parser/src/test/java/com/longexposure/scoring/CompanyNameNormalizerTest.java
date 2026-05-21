package com.longexposure.scoring;

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
        assertEquals("Accenture plc",
                CompanyNameNormalizer.normalize("Accenture plc Class A Ordinary Shares (Ireland)"));
    }

    @Test
    void stripsCommonStockADR() {
        assertEquals("British American Tobacco Industries, p.l.c.",
                CompanyNameNormalizer.normalize("British American Tobacco  Industries, p.l.c. Common Stock ADR"));
    }

    @Test
    void stripsSeriesSuffix() {
        assertEquals("Invesco QQQ Trust",
                CompanyNameNormalizer.normalize("Invesco QQQ Trust, Series 1"));
    }

    @Test
    void leavesAlreadyCleanNamesUnchanged() {
        assertEquals("Vanguard Russell 2000 ETF",
                CompanyNameNormalizer.normalize("Vanguard Russell 2000 ETF"));
        assertEquals("AllianzIM U.S. Large Cap Buffer20 May ETF",
                CompanyNameNormalizer.normalize("AllianzIM U.S. Large Cap Buffer20 May ETF"));
        assertEquals("iShares Russell 2000 Index Fund",
                CompanyNameNormalizer.normalize("iShares Russell 2000 Index Fund"));
    }

    @Test
    void handlesNullAndEmpty() {
        assertNull(CompanyNameNormalizer.normalize(null));
        assertEquals("", CompanyNameNormalizer.normalize(""));
        assertEquals("", CompanyNameNormalizer.normalize("   "));
    }

    @Test
    void collapsesDoubleSpaces() {
        // Double-space appears in NASDAQ data (BTI's case)
        assertEquals("British American Tobacco Industries, p.l.c.",
                CompanyNameNormalizer.normalize("British American Tobacco  Industries, p.l.c."));
    }
}
