package net.shchoo.scafe.scafe.persistence;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * The global account database, shared by every world (each world keeps its own characters and points at an
 * account id). Thread-safe, since several worlds' servers may share one AccountStore.
 */
public class AccountStore extends SqliteStore {
    private static final List<String> MIGRATIONS = List.of(
            // v1
            """
            create table account (id integer primary key autoincrement, name text not null unique,
                                  created_at text not null default current_timestamp);
            """,
            // v2: logins (null until the account's first login after this)
            """
            alter table account add column password_hash text;
            """
    );

    public AccountStore(Path file) throws Exception {
        super(file, MIGRATIONS);
    }

    /** The account's id, creating the account if it doesn't exist yet. */
    public synchronized int idFor(String name) throws SQLException {
        Integer existing = find(name);
        if (existing != null) {
            return existing;
        }
        try (PreparedStatement ps = connection.prepareStatement("insert or ignore into account (name) values (?)")) {
            ps.setString(1, name);
            ps.executeUpdate();
        }
        // "or ignore" + re-read covers another process creating it at the same moment
        return find(name);
    }

    public synchronized Integer find(String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("select id from account where name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    /** The account's password hash, or null if there's no such account or it has no password yet. */
    public synchronized String passwordHash(String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("select password_hash from account where name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public synchronized void setPasswordHash(String name, String hash) throws SQLException {
        idFor(name);
        try (PreparedStatement ps = connection.prepareStatement("update account set password_hash = ? where name = ?")) {
            ps.setString(1, hash);
            ps.setString(2, name);
            ps.executeUpdate();
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        super.close();
    }
}
