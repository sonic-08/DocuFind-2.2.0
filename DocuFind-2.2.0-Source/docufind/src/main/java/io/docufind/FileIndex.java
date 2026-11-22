package io.docufind;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class FileIndex {
    private static final String UPSERT = """
        INSERT INTO files (path_key, path, name_key, search_path, root, folder, size, modified, seen)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(path_key) DO UPDATE SET path = excluded.path, name_key = excluded.name_key,
          search_path = excluded.search_path, root = excluded.root, folder = excluded.folder,
          size = excluded.size, modified = excluded.modified, seen = excluded.seen
        WHERE files.seen <> excluded.seen OR files.path <> excluded.path
          OR files.folder <> excluded.folder OR files.size <> excluded.size
          OR files.modified <> excluded.modified
        """;

    record Results(List<FileEntry> files, boolean more) {}
    private static final int LIMIT = 250;
    private final String url;

    FileIndex(Path database) throws IOException, SQLException {
        Path file = database.toAbsolutePath().normalize();
        Files.createDirectories(file.getParent());
        url = "jdbc:sqlite:" + file;
        try (var conn = connect(); var sql = conn.createStatement()) {
            sql.execute("PRAGMA journal_mode = WAL");
            sql.execute("""
                CREATE TABLE IF NOT EXISTS files (
                    id INTEGER PRIMARY KEY,
                    path_key TEXT NOT NULL UNIQUE,
                    path TEXT NOT NULL,
                    name_key TEXT NOT NULL,
                    search_path TEXT NOT NULL,
                    root TEXT NOT NULL,
                    folder INTEGER NOT NULL,
                    size INTEGER NOT NULL,
                    modified INTEGER NOT NULL,
                    seen INTEGER NOT NULL
                )
                """);
            sql.execute("CREATE INDEX IF NOT EXISTS files_by_name ON files(name_key, path_key)");
            sql.execute("CREATE TABLE IF NOT EXISTS scans (id INTEGER PRIMARY KEY, generation INTEGER NOT NULL)");
            sql.execute("INSERT OR IGNORE INTO scans VALUES (1, 0)");
            sql.execute("""
                CREATE VIRTUAL TABLE IF NOT EXISTS file_paths USING fts5(
                    name_key, search_path, content='files', content_rowid='id', tokenize='trigram case_sensitive 1')
                """);
            sql.execute("""
                CREATE TRIGGER IF NOT EXISTS file_insert AFTER INSERT ON files BEGIN
                    INSERT INTO file_paths(rowid, name_key, search_path) VALUES (new.id, new.name_key, new.search_path);
                END
                """);
            sql.execute("""
                CREATE TRIGGER IF NOT EXISTS file_delete AFTER DELETE ON files BEGIN
                    INSERT INTO file_paths(file_paths, rowid, name_key, search_path)
                    VALUES ('delete', old.id, old.name_key, old.search_path);
                END
                """);
            sql.execute("""
                CREATE TRIGGER IF NOT EXISTS file_rename AFTER UPDATE OF search_path ON files
                WHEN new.search_path <> old.search_path BEGIN
                    INSERT INTO file_paths(file_paths, rowid, name_key, search_path)
                    VALUES ('delete', old.id, old.name_key, old.search_path);
                    INSERT INTO file_paths(rowid, name_key, search_path) VALUES (new.id, new.name_key, new.search_path);
                END
                """);
        }
    }

    private Connection connect() throws SQLException {
        var conn = DriverManager.getConnection(url);
        try (var sql = conn.createStatement()) {
            sql.execute("PRAGMA busy_timeout = 30000");
            sql.execute("PRAGMA synchronous = NORMAL");
            return conn;
        } catch (SQLException e) {
            try {
                conn.close();
            } catch (SQLException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }

    synchronized long nextScanId() throws SQLException {
        try (var conn = connect(); var sql = conn.createStatement();
             var rows = sql.executeQuery("UPDATE scans SET generation = generation + 1 WHERE id = 1 RETURNING generation")) {
            if (!rows.next()) throw new SQLException("Missing scan generation.");
            return rows.getLong(1);
        }
    }

    synchronized void save(List<FileEntry> files, Path root, long scanId) throws SQLException {
        if (files.isEmpty()) return;
        try (var conn = connect()) {
            conn.setAutoCommit(false);
            try (var sql = conn.prepareStatement(UPSERT)) {
                String drive = FileEntry.pathKey(root);
                for (FileEntry entry : files) {
                    String path = entry.path().toString();
                    sql.setString(1, FileEntry.pathKey(entry.path()));
                    sql.setString(2, path);
                    sql.setString(3, FileEntry.fold(entry.name()));
                    sql.setString(4, FileEntry.searchPath(path));
                    sql.setString(5, drive);
                    sql.setBoolean(6, entry.folder());
                    sql.setLong(7, entry.size());
                    sql.setLong(8, entry.modified());
                    sql.setLong(9, scanId);
                    sql.addBatch();
                }
                sql.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    synchronized void removeStale(Path location, long scanId) throws SQLException {
        String prefix = FileEntry.childPrefix(location);
        try (var conn = connect(); var sql = conn.prepareStatement("""
            DELETE FROM files
            WHERE (path_key = ? OR (path_key >= ? AND path_key < ?)) AND seen <> ?
            """)) {
            sql.setString(1, FileEntry.pathKey(location));
            sql.setString(2, prefix);
            sql.setString(3, FileEntry.afterChildren(prefix));
            sql.setLong(4, scanId);
            sql.executeUpdate();
        }
    }

    Results search(String text, Path folder, List<Path> roots) throws SQLException {
        String query = FileEntry.searchPath(text.strip());
        if (query.isEmpty() || roots.isEmpty()) return new Results(List.of(), false);
        List<FileEntry> result = new ArrayList<>();
        String upper = nextPrefix(query);
        String prefixFilter = upper == null ? "f.name_key >= ?" : "f.name_key >= ? AND f.name_key < ?";
        List<String> prefixBounds = upper == null ? List.of(query) : List.of(query, upper);
        String quoted = "\"" + query.replace("\"", "\"\"") + "\"";
        try (var conn = connect()) {
            conn.setAutoCommit(false);
            collect(conn, result, roots, folder, "files AS f", prefixFilter, prefixBounds,
                    " ORDER BY f.name_key, f.path_key");
            if (query.codePointCount(0, query.length()) >= 3 && result.size() <= LIMIT) {
                String join = "file_paths JOIN files AS f ON f.id = file_paths.rowid";
                List<String> nameArgs = new ArrayList<>();
                nameArgs.add("name_key : " + quoted);
                nameArgs.addAll(prefixBounds);
                collect(conn, result, roots, folder, join, "file_paths MATCH ? AND NOT (" + prefixFilter + ")",
                        nameArgs, "");
                if (result.size() <= LIMIT) {
                    collect(conn, result, roots, folder, join, "file_paths MATCH ? AND instr(f.name_key, ?) = 0",
                            List.of("search_path : " + quoted, query), "");
                }
            }
            conn.commit();
        }
        return new Results(List.copyOf(result.subList(0, Math.min(LIMIT, result.size()))), result.size() > LIMIT);
    }

    private static void collect(Connection conn, List<FileEntry> result, List<Path> roots, Path folder,
                                String source, String filter, List<String> values, String order)
            throws SQLException {
        String drives = String.join(",", Collections.nCopies(roots.size(), "?"));
        String scope = folder == null ? "" : " AND f.path_key > ? AND f.path_key < ?";
        String sql = "SELECT f.path, f.folder, f.size, f.modified FROM " + source
                + " WHERE " + filter + " AND f.root IN (" + drives + ")" + scope + order + " LIMIT ?";
        try (var statement = conn.prepareStatement(sql)) {
            int parameter = 1;
            for (String value : values) statement.setString(parameter++, value);
            for (Path root : roots) statement.setString(parameter++, FileEntry.pathKey(root));
            if (folder != null) {
                String prefix = FileEntry.childPrefix(folder);
                statement.setString(parameter++, prefix);
                statement.setString(parameter++, FileEntry.afterChildren(prefix));
            }
            statement.setInt(parameter, LIMIT + 1 - result.size());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new FileEntry(Path.of(rows.getString(1)), rows.getBoolean(2),
                            rows.getLong(3), rows.getLong(4)));
                }
            }
        }
    }

    private static String nextPrefix(String text) {
        int end = text.length();
        while (end > 0) {
            int codePoint = text.codePointBefore(end);
            end -= Character.charCount(codePoint);
            if (codePoint < Character.MAX_CODE_POINT) {
                int next = codePoint == 0xD7FF ? 0xE000 : codePoint + 1;
                return text.substring(0, end) + Character.toString(next);
            }
        }
        return null;
    }
}
