package net.shchoo.scafe.scafe.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.List;

/** One SQLite file: connection, schema migrations (via {@code PRAGMA user_version}) and small JDBC helpers. */
abstract class SqliteStore implements AutoCloseable {
    /** How long to wait for another process holding the file (e.g. two worlds sharing accounts.db). */
    private static final int BUSY_TIMEOUT_MILLIS = 5000;

    protected final Connection connection;

    protected SqliteStore(Path file, List<String> migrations) throws Exception {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("pragma foreign_keys = on");
            statement.execute("pragma busy_timeout = " + BUSY_TIMEOUT_MILLIS);
        }
        migrate(migrations);
    }

    private void migrate(List<String> migrations) throws SQLException {
        for (int i = schemaVersion(); i < migrations.size(); i++) {
            int target = i + 1;
            transaction(() -> {
                try (Statement statement = connection.createStatement()) {
                    for (String sql : migrations.get(target - 1).split(";")) {
                        if (!sql.isBlank()) {
                            statement.execute(sql);
                        }
                    }
                    statement.execute("pragma user_version = " + target);
                }
            });
        }
    }

    public int schemaVersion() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("pragma user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    protected interface SqlWork {
        void run() throws SQLException;
    }

    protected interface RowHandler {
        void handle(ResultSet rs) throws SQLException;
    }

    protected void transaction(SqlWork work) throws SQLException {
        connection.setAutoCommit(false);
        try {
            work.run();
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    protected void query(String sql, int param, RowHandler handler) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    handler.handle(rs);
                }
            }
        }
    }

    protected int lastInsertId() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("select last_insert_rowid()")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    protected static void set(PreparedStatement ps, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) {
            ps.setObject(i + 1, values[i]);
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
