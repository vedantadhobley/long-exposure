package com.longexposure.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Apply the bundled {@code schema.sql} (events hypertables, narratives,
 * pipeline_runs, continuous aggregates) to a Postgres+TimescaleDB instance.
 *
 * <p>Idempotent: every {@code CREATE TABLE}, {@code CREATE EXTENSION},
 * {@code SELECT create_hypertable(..., if_not_exists => TRUE)} etc. in the
 * script can run on an already-initialized DB without error.
 *
 * <p>Called from {@code Main.java} on startup when DB writes are enabled.
 */
public final class SchemaManager {

    private static final String SCHEMA_RESOURCE = "/schema.sql";

    private SchemaManager() {}

    public static void apply(final Connection conn) throws SQLException, IOException {
        String script = loadResource(SCHEMA_RESOURCE);
        List<String> statements = splitStatements(script);

        conn.setAutoCommit(true);
        try (Statement st = conn.createStatement()) {
            int applied = 0;
            for (String stmt : statements) {
                String trimmed = stmt.trim();
                if (trimmed.isEmpty()) continue;
                st.execute(trimmed);
                applied++;
            }
            System.out.println("schema: applied " + applied + " statements from " + SCHEMA_RESOURCE);
        }
    }

    private static String loadResource(final String path) throws IOException {
        try (InputStream in = SchemaManager.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("Resource not found on classpath: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Split a SQL script into individual statements on {@code ;}
     * terminators, with two pieces of awareness the naive
     * "strip-comments-then-split-on-semicolon" approach lacked:
     *
     * <ol>
     *   <li><b>Dollar-quoted blocks.</b> {@code ;} inside a {@code $$ ... $$}
     *       block is part of the block body (e.g. a {@code DO $$ ... $$}
     *       PL/pgSQL sanity check, or a function body), NOT a statement
     *       terminator. Splitting there hands JDBC a fragment ending mid-block
     *       and it throws "Unterminated dollar quote." We toggle an
     *       in-dollar-quote flag on each {@code $$} and only split on
     *       {@code ;} when outside one.
     *   <li><b>Line comments.</b> {@code --} comments are skipped to
     *       end-of-line — but only when OUTSIDE a dollar-quoted block, since
     *       a {@code --} inside a PL/pgSQL body is part of that body and
     *       stripping it would corrupt the block.
     * </ol>
     *
     * <p>Only the bare {@code $$} delimiter is recognized (not named tags like
     * {@code $func$}); our schema uses only {@code $$}. String-literal
     * semicolons are still not handled, but our schema has none.
     *
     * <p>Single-pass scanner — kept deliberately small + covered by
     * {@code SchemaManagerTest} so future schema constructs (functions,
     * triggers, DO blocks) don't silently break the split.
     */
    static List<String> splitStatements(final String script) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inDollarQuote = false;
        int i = 0;
        int n = script.length();

        while (i < n) {
            // $$ delimiter — toggle in/out of a dollar-quoted block.
            if (script.charAt(i) == '$' && i + 1 < n && script.charAt(i + 1) == '$') {
                inDollarQuote = !inDollarQuote;
                cur.append("$$");
                i += 2;
                continue;
            }

            // -- line comment, only outside dollar-quotes: skip to EOL.
            if (!inDollarQuote
                    && script.charAt(i) == '-' && i + 1 < n && script.charAt(i + 1) == '-') {
                int eol = script.indexOf('\n', i);
                if (eol < 0) break;          // comment runs to EOF — nothing more to scan
                i = eol;                     // leave the '\n' for the next iteration to append
                continue;
            }

            // ; terminator, only outside dollar-quotes.
            if (!inDollarQuote && script.charAt(i) == ';') {
                String stmt = cur.toString().trim();
                if (!stmt.isEmpty()) out.add(stmt);
                cur.setLength(0);
                i++;
                continue;
            }

            cur.append(script.charAt(i));
            i++;
        }

        String tail = cur.toString().trim();
        if (!tail.isEmpty()) out.add(tail);
        return out;
    }
}
