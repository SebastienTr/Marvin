// SPDX-License-Identifier: MIT
package marvin.host.adapter.persistence;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Conversions shared by memory's stores: times, arrays, and embeddings, which are {@code public.vector} with
 * pgvector and {@code real[]} without it (the embedded PostgreSQL).
 */
final class MemoryRows {

    private MemoryRows() {
    }

    static OffsetDateTime at(Instant t) {
        return t == null ? null : t.atOffset(ZoneOffset.UTC);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    static List<Long> longs(ResultSet rs, String column) throws SQLException {
        Array a = rs.getArray(column);
        List<Long> out = new ArrayList<>();
        if (a != null) {
            for (Object o : (Object[]) a.getArray()) {
                out.add(((Number) o).longValue());
            }
        }
        return out;
    }

    static List<String> strings(ResultSet rs, String column) throws SQLException {
        Array a = rs.getArray(column);
        List<String> out = new ArrayList<>();
        if (a != null) {
            for (Object o : (Object[]) a.getArray()) {
                out.add((String) o);
            }
        }
        return out;
    }

    /** How a table's {@code embedding} column is stored. */
    record Vectors(boolean pgvector, int dimensions) {

        /** The SQL type to cast an embedding literal to. */
        String type() {
            return pgvector ? "public.vector" : "real[]";
        }

        /** An embedding as a literal of that type ({@code null} stays {@code null}). */
        String literal(float[] v) {
            if (v == null) {
                return null;
            }
            StringBuilder b = new StringBuilder(v.length * 10).append(pgvector ? '[' : '{');
            for (int i = 0; i < v.length; i++) {
                if (i > 0) {
                    b.append(',');
                }
                b.append(Float.isFinite(v[i]) ? Float.toString(v[i]) : "0");
            }
            return b.append(pgvector ? ']' : '}').toString();
        }

        /** Parses the text form of either type. */
        static float[] parse(String text) {
            if (text == null) {
                return null;
            }
            String t = text.strip();
            t = t.substring(1, t.length() - 1);
            if (t.isEmpty()) {
                return new float[0];
            }
            String[] parts = t.split(",");
            float[] v = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                v[i] = Float.parseFloat(parts[i].strip());
            }
            return v;
        }

        /** Finds out from the catalogue; {@code configured} is the size when the column does not say (real[]). */
        static Vectors of(JdbcClient jdbc, String table, int configured) {
            String udt = jdbc.sql("SELECT udt_name FROM information_schema.columns WHERE table_schema = 'memory' "
                            + "AND table_name = ? AND column_name = 'embedding'")
                    .param(table).query(String.class).optional().orElse("_float4");
            if (!"vector".equals(udt.toLowerCase(Locale.ROOT))) {
                return new Vectors(false, configured);
            }
            Integer mod = jdbc.sql("SELECT atttypmod FROM pg_attribute WHERE attrelid = to_regclass('memory.' || ?) "
                            + "AND attname = 'embedding'")
                    .param(table).query(Integer.class).optional().orElse(configured);
            return new Vectors(true, mod != null && mod > 0 ? mod : configured);
        }
    }
}
