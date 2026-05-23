package com.longexposure.storage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SchemaManager#splitStatements} — the SQL-script splitter.
 *
 * <p>The load-bearing case is the dollar-quoted {@code DO $$ ... $$} block:
 * an earlier naive splitter stripped {@code --} comments then split on every
 * {@code ;}, which broke a DO block's internal semicolons into fragments and
 * made JDBC throw "Unterminated dollar quote." These tests lock in that a
 * dollar-quoted body stays a single statement.
 */
class SchemaManagerTest {

    @Test
    void splitsPlainStatements() {
        List<String> stmts = SchemaManager.splitStatements(
                "CREATE TABLE a (id INT);\nCREATE TABLE b (id INT);");
        assertEquals(2, stmts.size());
        assertEquals("CREATE TABLE a (id INT)", stmts.get(0));
        assertEquals("CREATE TABLE b (id INT)", stmts.get(1));
    }

    @Test
    void stripsLineComments() {
        List<String> stmts = SchemaManager.splitStatements(
                "-- a leading comment\nSELECT 1;  -- trailing comment\nSELECT 2;");
        assertEquals(2, stmts.size());
        assertEquals("SELECT 1", stmts.get(0).trim());
        assertEquals("SELECT 2", stmts.get(1).trim());
    }

    @Test
    void dollarQuotedBlockStaysOneStatement() {
        // The exact failure shape: a DO block whose body has internal
        // semicolons (after DECLARE, after the SELECT, after RAISE).
        String script = """
                CREATE TABLE x (id INT);
                DO $$
                DECLARE
                  missing TEXT;
                BEGIN
                  SELECT string_agg(name, ', ') INTO missing FROM things WHERE flag = false;
                  IF missing IS NOT NULL THEN
                    RAISE WARNING 'missing: %', missing;
                  END IF;
                END$$;
                SELECT 99;
                """;
        List<String> stmts = SchemaManager.splitStatements(script);

        assertEquals(3, stmts.size(), "expected 3 statements: CREATE, DO-block, SELECT");
        assertEquals("CREATE TABLE x (id INT)", stmts.get(0));

        // The whole DO block must be intact in one statement — internal
        // semicolons preserved, not split.
        String doBlock = stmts.get(1);
        assertTrue(doBlock.startsWith("DO $$"), "DO block should start with DO $$");
        assertTrue(doBlock.trim().endsWith("END$$"), "DO block should end with END$$");
        assertTrue(doBlock.contains("DECLARE"), "DECLARE preserved");
        assertTrue(doBlock.contains("RAISE WARNING"), "RAISE preserved");
        assertTrue(doBlock.contains("missing IS NOT NULL"), "IF body preserved");

        assertEquals("SELECT 99", stmts.get(2).trim());
    }

    @Test
    void commentsInsideDollarQuoteArePreserved() {
        // A -- comment inside a dollar-quoted body is part of the body and
        // must NOT be stripped (it would corrupt the PL/pgSQL).
        String script = """
                DO $$
                BEGIN
                  -- this is a pl/pgsql comment, keep it
                  PERFORM 1;
                END$$;
                """;
        List<String> stmts = SchemaManager.splitStatements(script);
        assertEquals(1, stmts.size());
        assertTrue(stmts.get(0).contains("-- this is a pl/pgsql comment, keep it"),
                "comment inside dollar-quote should be preserved");
    }

    @Test
    void emptyAndWhitespaceChunksDropped() {
        List<String> stmts = SchemaManager.splitStatements(
                "SELECT 1;;;\n  \n;SELECT 2;");
        assertEquals(2, stmts.size());
    }
}
