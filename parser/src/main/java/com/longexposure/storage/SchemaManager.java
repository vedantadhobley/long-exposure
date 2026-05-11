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
     * Split a SQL script on {@code ;} statement terminators, stripping
     * {@code --}-prefixed line comments first. Naive but enough for our
     * own schema script — no string literals contain semicolons and no
     * dollar-quoted function bodies are used.
     */
    static List<String> splitStatements(final String script) {
        StringBuilder sansComments = new StringBuilder(script.length());
        for (String line : script.split("\\R")) {
            int commentStart = line.indexOf("--");
            String code = (commentStart >= 0) ? line.substring(0, commentStart) : line;
            sansComments.append(code).append('\n');
        }

        List<String> out = new ArrayList<>();
        for (String chunk : sansComments.toString().split(";")) {
            String trimmed = chunk.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }
}
