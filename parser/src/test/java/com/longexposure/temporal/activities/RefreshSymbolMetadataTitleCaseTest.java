package com.longexposure.temporal.activities;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the EDGAR title casing applied to all-uppercase SEC titles.
 * Mixed-case titles are passed through untouched.
 */
final class RefreshSymbolMetadataTitleCaseTest {

    @Test
    void allUppercaseGetsTitleCased() {
        assertEquals("Microsoft Corp",
                RefreshSymbolMetadataActivityImpl.titleCase("MICROSOFT CORP"));
        assertEquals("Apple Inc.",
                RefreshSymbolMetadataActivityImpl.titleCase("APPLE INC."));
        assertEquals("Intel Corporation",
                RefreshSymbolMetadataActivityImpl.titleCase("INTEL CORPORATION"));
    }

    @Test
    void mixedCaseLeftAlone() {
        assertEquals("Apple Inc.", RefreshSymbolMetadataActivityImpl.titleCase("Apple Inc."));
        // iShares brand style preserved
        assertEquals("iShares Russell 2000 ETF",
                RefreshSymbolMetadataActivityImpl.titleCase("iShares Russell 2000 ETF"));
        // plc lowercase preserved
        assertEquals("Accenture plc",
                RefreshSymbolMetadataActivityImpl.titleCase("Accenture plc"));
    }

    @Test
    void entityTokensHaveCanonicalForms() {
        // Inc, Corp, Ltd → title-case
        assertEquals("Banzai International Inc",
                RefreshSymbolMetadataActivityImpl.titleCase("BANZAI INTERNATIONAL INC"));
        // plc stays lowercase
        assertEquals("Accenture plc",
                RefreshSymbolMetadataActivityImpl.titleCase("ACCENTURE PLC"));
        // LLC stays all-caps
        assertEquals("Acme Holdings LLC",
                RefreshSymbolMetadataActivityImpl.titleCase("ACME HOLDINGS LLC"));
        // LP stays all-caps
        assertEquals("Plains All American Pipeline LP",
                RefreshSymbolMetadataActivityImpl.titleCase("PLAINS ALL AMERICAN PIPELINE LP"));
    }

    @Test
    void handlesNullAndEmpty() {
        assertEquals(null, RefreshSymbolMetadataActivityImpl.titleCase(null));
        assertEquals("", RefreshSymbolMetadataActivityImpl.titleCase(""));
        assertEquals("   ", RefreshSymbolMetadataActivityImpl.titleCase("   "));
    }

    @Test
    void preferEdgar_edgarWinsWhenMixedCase() {
        // EDGAR has clean mixed-case version; NASDAQ has filing noise
        assertTrue(RefreshSymbolMetadataActivityImpl.preferEdgar(
                "Odyssey Therapeutics, Inc. - Common Stock",
                "Odyssey Therapeutics, Inc."));
    }

    @Test
    void preferEdgar_nasdaqWinsWhenEdgarAllCaps() {
        // EDGAR returns ALL CAPS; NASDAQ has brand-correct CamelCase
        assertFalse(RefreshSymbolMetadataActivityImpl.preferEdgar(
                "NVIDIA Corporation - Common Stock",
                "NVIDIA CORP"));
        assertFalse(RefreshSymbolMetadataActivityImpl.preferEdgar(
                "FuelCell Energy, Inc. - Common Stock",
                "FUELCELL ENERGY INC"));
    }

    @Test
    void preferEdgar_edgarWinsWhenNasdaqAbsent() {
        assertTrue(RefreshSymbolMetadataActivityImpl.preferEdgar(null, "Whatever"));
        assertTrue(RefreshSymbolMetadataActivityImpl.preferEdgar("", "ANYTHING"));
    }

    @Test
    void preferEdgar_edgarWinsWhenBothAllCaps() {
        // NASDAQ is also all-caps so its case doesn't carry brand info;
        // EDGAR's title-cased version is at least canonical
        assertTrue(RefreshSymbolMetadataActivityImpl.preferEdgar(
                "ALL CAPS NASDAQ THING",
                "ALL CAPS EDGAR THING"));
    }
}
