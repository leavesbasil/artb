package artb.ads.campaign.store;

import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/** Литералы массивов и векторов Postgres для параметров вида {@code ?::text[]}, {@code ?::vector}. */
final class PgArrays {

    private PgArrays() {
    }

    static String textArray(Collection<String> values) {
        return values.stream()
                .map(v -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(",", "{", "}"));
    }

    static String uuidArray(Collection<?> values) {
        return values.stream().map(Object::toString).collect(Collectors.joining(",", "{", "}"));
    }

    static String vector(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 10).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    static List<String> toList(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return Arrays.stream((Object[]) array.getArray()).map(Object::toString).toList();
    }
}
