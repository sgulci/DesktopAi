package com.desktopai.repository;

import com.desktopai.model.DAIChatMessage;
import com.desktopai.model.DAIChatMessage.Role;
import com.desktopai.util.DatabaseManager;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;



/**
 * Repository for chat history.
 */
public class ChatHistoryRepository {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public record SessionSummary(String sessionId, String title, LocalDateTime startedAt) {}
    private final DatabaseManager dbManager;

    public ChatHistoryRepository() {
        this.dbManager = DatabaseManager.getInstance();
    }

    /**
     * Saves a chat message.
     */
    public DAIChatMessage save(DAIChatMessage message) throws SQLException {
        String sql = """
                    INSERT INTO chat_history (session_id, role, content, model_used, provider_id, timestamp)
                    VALUES (?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, message.getSessionId());
            stmt.setString(2, message.getRole().name());
            stmt.setString(3, message.getContent());
            stmt.setString(4, message.getModelUsed());
            stmt.setString(5, message.getProviderId());
            stmt.setString(6, message.getTimestamp().format(FORMATTER));
            stmt.executeUpdate();

            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    message.setId(rs.getLong(1));
                }
            }
        }

        return message;
    }

    /**
     * Finds messages by session ID.
     */
    public List<DAIChatMessage> findBySessionId(String sessionId) throws SQLException {
        String sql = "SELECT * FROM chat_history WHERE session_id = ? ORDER BY timestamp";
        List<DAIChatMessage> messages = new ArrayList<>();

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, sessionId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    messages.add(mapRow(rs));
                }
            }
        }
        return messages;
    }

    /**
     * Gets distinct session IDs ordered by most recent.
     */
    public List<String> findAllSessionIds() throws SQLException {
        String sql = """
                    SELECT session_id, MAX(timestamp) as last_ts
                    FROM chat_history
                    GROUP BY session_id
                    ORDER BY last_ts DESC
                """;
        List<String> sessions = new ArrayList<>();

        try (Connection conn = dbManager.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                sessions.add(rs.getString("session_id"));
            }
        }
        return sessions;
    }

    /**
     * Deletes all messages in a session.
     */
    public void deleteBySessionId(String sessionId) throws SQLException {
        String sql = "DELETE FROM chat_history WHERE session_id = ?";

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, sessionId);
            stmt.executeUpdate();
        }
    }

    /**
     * Returns session summaries ordered by most recently active.
     * Title is the first user message truncated to 45 chars.
     */
    public List<SessionSummary> findSessionSummaries() throws SQLException {
        String sql = """
                SELECT session_id,
                       MIN(timestamp) as started_at,
                       (SELECT content FROM chat_history c2
                        WHERE c2.session_id = ch.session_id AND c2.role = 'USER'
                        ORDER BY c2.timestamp ASC LIMIT 1) as first_message
                FROM chat_history ch
                GROUP BY session_id
                ORDER BY MAX(timestamp) DESC
                """;
        List<SessionSummary> summaries = new ArrayList<>();

        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String sessionId = rs.getString("session_id");
                String firstMsg = rs.getString("first_message");
                String startedAtStr = rs.getString("started_at");

                String title;
                if (firstMsg != null && !firstMsg.isBlank()) {
                    title = firstMsg.length() > 45 ? firstMsg.substring(0, 45) + "…" : firstMsg;
                } else {
                    title = "Chat";
                }

                LocalDateTime startedAt = startedAtStr != null
                        ? LocalDateTime.parse(startedAtStr, FORMATTER)
                        : LocalDateTime.now();

                summaries.add(new SessionSummary(sessionId, title, startedAt));
            }
        }
        return summaries;
    }

    /**
     * Creates a new session ID.
     */
    public String createNewSession() {
        return UUID.randomUUID().toString();
    }

    private DAIChatMessage mapRow(ResultSet rs) throws SQLException {
        DAIChatMessage msg = new DAIChatMessage();
        msg.setId(rs.getLong("id"));
        msg.setSessionId(rs.getString("session_id"));
        msg.setRole(Role.valueOf(rs.getString("role")));
        msg.setContent(rs.getString("content"));
        msg.setModelUsed(rs.getString("model_used"));
        msg.setProviderId(rs.getString("provider_id"));

        String timestamp = rs.getString("timestamp");
        if (timestamp != null) {
            msg.setTimestamp(LocalDateTime.parse(timestamp, FORMATTER));
        }

        return msg;
    }
}
