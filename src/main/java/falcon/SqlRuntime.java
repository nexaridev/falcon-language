package falcon;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Per-run SQLite connections exposed through the sql standard module. */
final class SqlRuntime implements AutoCloseable {
    private final Map<Long, Connection> connections = new LinkedHashMap<>();
    private long nextId = 1;

    Object invoke(String operation, List<Object> args) throws Exception {
        return switch (operation) {
            case "sql.open" -> open((String) args.get(0));
            case "sql.close" -> { connection(args).close(); connections.remove((Long) args.get(0)); yield null; }
            case "sql.execute" -> execute(args);
            case "sql.query" -> query(args);
            case "sql.lastInsertId" -> lastInsertId(args);
            case "sql.begin" -> { connection(args).setAutoCommit(false); yield null; }
            case "sql.commit" -> { connection(args).commit(); connection(args).setAutoCommit(true); yield null; }
            case "sql.rollback" -> { connection(args).rollback(); connection(args).setAutoCommit(true); yield null; }
            default -> throw new IllegalArgumentException("unknown SQLite operation.");
        };
    }

    private long open(String filename) throws Exception {
        Class.forName("org.sqlite.JDBC");
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + filename);
        long id = nextId++;
        connections.put(id, connection);
        return id;
    }

    private int execute(List<Object> args) throws Exception {
        Connection connection = connection(args);
        try (PreparedStatement statement = prepare(connection, (String) args.get(1), (List<?>) args.get(2))) {
            return statement.executeUpdate();
        }
    }

    private List<Map<String, Object>> query(List<Object> args) throws Exception {
        Connection connection = connection(args);
        try (PreparedStatement statement = prepare(connection, (String) args.get(1), (List<?>) args.get(2));
             ResultSet result = statement.executeQuery()) {
            var rows = new ArrayList<Map<String, Object>>();
            var metadata = result.getMetaData();
            while (result.next()) {
                var row = new LinkedHashMap<String, Object>();
                for (int column = 1; column <= metadata.getColumnCount(); column++) {
                    Object value = result.getObject(column);
                    if (value instanceof Integer || value instanceof Short || value instanceof Byte)
                        value = ((Number) value).longValue();
                    else if (value instanceof Float) value = ((Number) value).doubleValue();
                    else if (value instanceof byte[] bytes)
                        value = java.util.Base64.getEncoder().encodeToString(bytes);
                    row.put(metadata.getColumnLabel(column), value);
                }
                rows.add(row);
            }
            return rows;
        }
    }

    private long lastInsertId(List<Object> args) throws Exception {
        try (Statement statement = connection(args).createStatement();
             ResultSet result = statement.executeQuery("SELECT last_insert_rowid()")) {
            return result.next() ? result.getLong(1) : 0L;
        }
    }

    private static PreparedStatement prepare(Connection connection, String sql, List<?> parameters) throws Exception {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int i = 0; i < parameters.size(); i++) {
            Object value = parameters.get(i);
            if (value instanceof Boolean bool) statement.setInt(i + 1, bool ? 1 : 0);
            else statement.setObject(i + 1, value);
        }
        return statement;
    }

    private Connection connection(List<Object> args) {
        Object handle = args.get(0);
        if (!(handle instanceof Long id) || !connections.containsKey(id))
            throw new IllegalArgumentException("database handle is closed or invalid.");
        return connections.get(id);
    }

    @Override public void close() {
        for (Connection connection : connections.values()) try { connection.close(); } catch (Exception ignored) {}
        connections.clear();
    }
}
