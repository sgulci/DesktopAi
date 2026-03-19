package com.desktopai.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Manages SQLite database connections and schema initialization.
 * Returns a fresh connection per call (WAL mode) for thread safety.
 */
public class DatabaseManager {
    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);
    private static final String DB_NAME = "desktopai.db";
    private static DatabaseManager instance;
    private final String dbPath;
    private volatile boolean schemaInitialized = false;

    private DatabaseManager() {
        String userHome = System.getProperty("user.home");
        File dataDir = new File(userHome, ".desktopai");
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }
        this.dbPath = new File(dataDir, DB_NAME).getAbsolutePath();
        log.info("Database path: {}", dbPath);
    }

    public static synchronized DatabaseManager getInstance() {
        if (instance == null) {
            instance = new DatabaseManager();
        }
        return instance;
    }

    /**
     * Returns a new connection for the caller to own and close.
     * Schema is initialized on first call.
     */
    public Connection getConnection() throws SQLException {
        ensureSchemaInitialized();
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA foreign_keys=ON");
        }
        return conn;
    }

    private synchronized void ensureSchemaInitialized() throws SQLException {
        if (schemaInitialized) return;
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            initializeSchema(conn);
            schemaInitialized = true;
        }
    }

    private void initializeSchema(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                        CREATE TABLE IF NOT EXISTS documents (
                            id TEXT PRIMARY KEY,
                            filename TEXT NOT NULL,
                            filepath TEXT,
                            content_type TEXT,
                            size_bytes INTEGER,
                            created_at TEXT DEFAULT (datetime('now')),
                            chunk_count INTEGER DEFAULT 0,
                            status TEXT DEFAULT 'PENDING'
                        )
                    """);

            stmt.execute("""
                        CREATE TABLE IF NOT EXISTS document_chunks (
                            id TEXT PRIMARY KEY,
                            document_id TEXT NOT NULL,
                            content TEXT NOT NULL,
                            embedding BLOB NOT NULL,
                            position INTEGER,
                            metadata TEXT,
                            FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE
                        )
                    """);

            stmt.execute("""
                        CREATE INDEX IF NOT EXISTS idx_chunks_document
                        ON document_chunks(document_id)
                    """);

            stmt.execute("""
                        CREATE TABLE IF NOT EXISTS providers (
                            id TEXT PRIMARY KEY,
                            name TEXT NOT NULL,
                            type TEXT NOT NULL,
                            api_url TEXT,
                            api_key TEXT,
                            is_default INTEGER DEFAULT 0,
                            created_at TEXT DEFAULT (datetime('now'))
                        )
                    """);

            stmt.execute("""
                        CREATE TABLE IF NOT EXISTS chat_history (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            session_id TEXT NOT NULL,
                            role TEXT NOT NULL,
                            content TEXT NOT NULL,
                            model_used TEXT,
                            provider_id TEXT,
                            timestamp TEXT DEFAULT (datetime('now'))
                        )
                    """);

            stmt.execute("""
                        CREATE TABLE IF NOT EXISTS settings (
                            key TEXT PRIMARY KEY,
                            value TEXT
                        )
                    """);

            stmt.execute("""
                        INSERT OR IGNORE INTO providers (id, name, type, api_url, is_default)
                        VALUES ('ollama-default', 'Local Ollama', 'OLLAMA', 'http://localhost:11434', 1)
                    """);

            stmt.execute("""
                        INSERT OR IGNORE INTO providers (id, name, type, is_default)
                        VALUES ('native-default', 'Native (GGUF)', 'LOCAL_NATIVE', 0)
                    """);
        }
    }

    /** No-op: connections are per-request. Kept for API compatibility. */
    public void close() {
        // No persistent connection to close
    }

    public String getDbPath() {
        return dbPath;
    }
}
