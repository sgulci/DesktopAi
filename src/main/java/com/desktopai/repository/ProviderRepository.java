package com.desktopai.repository;

import com.desktopai.model.ProviderConfig;
import com.desktopai.model.ProviderType;
import com.desktopai.util.DatabaseManager;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for LLM provider configurations.
 * API keys are obfuscated with XOR+Base64 (prefixed "enc:") for at-rest protection.
 */
public class ProviderRepository {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final byte[] OBFUSCATION_KEY = {0x44, 0x65, 0x73, 0x6B, 0x74, 0x6F, 0x70, 0x41, 0x49};
    private final DatabaseManager dbManager;

    public ProviderRepository() {
        this.dbManager = DatabaseManager.getInstance();
    }

    public ProviderConfig save(ProviderConfig config) throws SQLException {
        if (config.getId() == null) {
            config.setId(UUID.randomUUID().toString());
        }

        String sql = """
                    INSERT INTO providers (id, name, type, api_url, api_key, is_default, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, config.getId());
            stmt.setString(2, config.getName());
            stmt.setString(3, config.getType().name());
            stmt.setString(4, config.getApiUrl());
            stmt.setString(5, obfuscate(config.getApiKey()));
            stmt.setInt(6, config.isDefault() ? 1 : 0);
            stmt.setString(7, config.getCreatedAt().format(FORMATTER));
            stmt.executeUpdate();
        }

        return config;
    }

    public void update(ProviderConfig config) throws SQLException {
        String sql = """
                    UPDATE providers SET name = ?, type = ?, api_url = ?, api_key = ?, is_default = ?
                    WHERE id = ?
                """;

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, config.getName());
            stmt.setString(2, config.getType().name());
            stmt.setString(3, config.getApiUrl());
            stmt.setString(4, obfuscate(config.getApiKey()));
            stmt.setInt(5, config.isDefault() ? 1 : 0);
            stmt.setString(6, config.getId());
            stmt.executeUpdate();
        }
    }

    public Optional<ProviderConfig> findById(String id) throws SQLException {
        String sql = "SELECT * FROM providers WHERE id = ?";

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    public List<ProviderConfig> findAll() throws SQLException {
        String sql = "SELECT * FROM providers ORDER BY created_at";
        List<ProviderConfig> providers = new ArrayList<>();

        try (Connection conn = dbManager.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                providers.add(mapRow(rs));
            }
        }
        return providers;
    }

    public Optional<ProviderConfig> findDefault() throws SQLException {
        String sql = "SELECT * FROM providers WHERE is_default = 1 LIMIT 1";

        try (Connection conn = dbManager.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return Optional.of(mapRow(rs));
            }
        }
        return Optional.empty();
    }

    public void setDefault(String id) throws SQLException {
        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("UPDATE providers SET is_default = 0");
            }
            try (PreparedStatement stmt = conn.prepareStatement("UPDATE providers SET is_default = 1 WHERE id = ?")) {
                stmt.setString(1, id);
                stmt.executeUpdate();
            }
            conn.commit();
            conn.setAutoCommit(true);
        }
    }

    public void deleteById(String id) throws SQLException {
        String sql = "DELETE FROM providers WHERE id = ?";

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, id);
            stmt.executeUpdate();
        }
    }

    private ProviderConfig mapRow(ResultSet rs) throws SQLException {
        ProviderConfig config = new ProviderConfig();
        config.setId(rs.getString("id"));
        config.setName(rs.getString("name"));
        config.setType(ProviderType.valueOf(rs.getString("type")));
        config.setApiUrl(rs.getString("api_url"));
        config.setApiKey(deobfuscate(rs.getString("api_key")));
        config.setDefault(rs.getInt("is_default") == 1);

        String createdAt = rs.getString("created_at");
        if (createdAt != null) {
            config.setCreatedAt(LocalDateTime.parse(createdAt, FORMATTER));
        }

        return config;
    }

    /** Obfuscates a value with XOR+Base64, prefixed with "enc:". */
    private String obfuscate(String value) {
        if (value == null || value.isEmpty()) return value;
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] ^= OBFUSCATION_KEY[i % OBFUSCATION_KEY.length];
        }
        return "enc:" + Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Deobfuscates a value. Handles legacy plaintext values gracefully.
     */
    private String deobfuscate(String value) {
        if (value == null || value.isEmpty()) return value;
        if (!value.startsWith("enc:")) return value; // Legacy plaintext
        try {
            byte[] bytes = Base64.getDecoder().decode(value.substring(4));
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] ^= OBFUSCATION_KEY[i % OBFUSCATION_KEY.length];
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value.substring(4); // Fallback
        }
    }
}
