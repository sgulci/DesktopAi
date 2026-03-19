package com.desktopai.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import atlantafx.base.theme.Styles;
import com.desktopai.DesktopAiApplication;
import com.desktopai.model.EmbeddingProviderType;
import com.desktopai.model.ProviderConfig;
import com.desktopai.model.ProviderType;
import com.desktopai.repository.ProviderRepository;
import com.desktopai.service.ConfigService;
import com.desktopai.service.ModelManagerService;
import com.desktopai.service.embedding.EmbeddingProvider;
import com.desktopai.service.embedding.EmbeddingProviderFactory;
import com.desktopai.util.DatabaseManager;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import com.desktopai.util.Icons;
import javafx.scene.paint.Color;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.List;
import java.util.UUID;

public class SettingsController {
    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);
    @FXML private CheckBox darkModeToggle;
    @FXML private VBox providersList;
    @FXML private Spinner<Integer> chunkSizeSpinner;
    @FXML private Spinner<Integer> contextChunksSpinner;
    @FXML private TextField ollamaUrlField;
    @FXML private Label ollamaStatus;
    @FXML private Label dbPathLabel;

    @FXML private ComboBox<String> llamaLogLevelCombo;
    @FXML private ComboBox<EmbeddingProviderType> embeddingTypeCombo;
    @FXML private VBox embeddingConfigBox;
    @FXML private TextField embeddingModelField;
    @FXML private HBox embeddingApiUrlRow;
    @FXML private TextField embeddingApiUrlField;
    @FXML private HBox embeddingApiKeyRow;
    @FXML private PasswordField embeddingApiKeyField;
    @FXML private Label embeddingStatus;

    private ConfigService configService;
    private ModelManagerService modelManagerService;
    private ProviderRepository providerRepository;

    @FXML
    public void initialize() {
        configService = new ConfigService();
        modelManagerService = new ModelManagerService();
        providerRepository = new ProviderRepository();

        chunkSizeSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(200, 2000, 800, 100));
        contextChunksSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 20, 5, 1));

        llamaLogLevelCombo.getItems().addAll("OFF", "ERROR", "WARN", "INFO", "DEBUG");
        llamaLogLevelCombo.getSelectionModel().select("WARN");

        embeddingTypeCombo.getItems().addAll(EmbeddingProviderType.values());
        embeddingTypeCombo.getSelectionModel().selectFirst();
        embeddingTypeCombo.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> updateEmbeddingConfigVisibility(newVal));

        loadSettings();
        loadProviders();
    }

    private void loadSettings() {
        try {
            darkModeToggle.setSelected(configService.isDarkMode());
            chunkSizeSpinner.getValueFactory().setValue(configService.getChunkSize());
            contextChunksSpinner.getValueFactory().setValue(configService.getContextChunks());
            dbPathLabel.setText(DatabaseManager.getInstance().getDbPath());

            var providers = providerRepository.findAll();
            for (ProviderConfig p : providers) {
                if (p.getType() == ProviderType.OLLAMA) {
                    ollamaUrlField.setText(p.getApiUrl());
                    break;
                }
            }

            String llamaLevel = configService.getLlamaLogLevel();
            llamaLogLevelCombo.getSelectionModel().select(llamaLevel);

            loadEmbeddingSettings();
        } catch (Exception e) {
            log.error("Failed to load settings", e);
        }
    }

    private void loadEmbeddingSettings() {
        try {
            EmbeddingProviderType type = configService.getEmbeddingType();
            embeddingTypeCombo.getSelectionModel().select(type);

            String model = configService.getEmbeddingModel();
            if (model != null) embeddingModelField.setText(model);

            String apiUrl = configService.getEmbeddingApiUrl();
            if (apiUrl != null) embeddingApiUrlField.setText(apiUrl);

            String apiKey = configService.getEmbeddingApiKey();
            if (apiKey != null) embeddingApiKeyField.setText(apiKey);

            updateEmbeddingConfigVisibility(type);
        } catch (Exception e) {
            log.warn("Failed to load embedding settings", e);
        }
    }

    private void updateEmbeddingConfigVisibility(EmbeddingProviderType type) {
        if (type == null || type == EmbeddingProviderType.LOCAL_ONNX) {
            embeddingConfigBox.setVisible(false);
            embeddingConfigBox.setManaged(false);
        } else {
            embeddingConfigBox.setVisible(true);
            embeddingConfigBox.setManaged(true);
            boolean isOpenAI = type == EmbeddingProviderType.OPENAI;
            embeddingApiUrlRow.setVisible(isOpenAI);
            embeddingApiUrlRow.setManaged(isOpenAI);
            embeddingApiKeyRow.setVisible(isOpenAI);
            embeddingApiKeyRow.setManaged(isOpenAI);
        }
    }

    private void loadProviders() {
        try {
            providersList.getChildren().clear();
            List<ProviderConfig> providers = providerRepository.findAll();
            for (ProviderConfig provider : providers) {
                providersList.getChildren().add(createProviderItem(provider));
            }
        } catch (Exception e) {
            log.error("Failed to load providers", e);
        }
    }

    @FXML
    public void toggleDarkMode() {
        try {
            boolean isDark = darkModeToggle.isSelected();
            configService.setDarkMode(isDark);
            DesktopAiApplication.applyTheme(isDark);
        } catch (Exception e) {
            showAlert("Failed to save dark mode setting");
        }
    }

    @FXML
    public void addProvider() {
        Dialog<ProviderConfig> dialog = new Dialog<>();
        dialog.setTitle("Add Provider");
        dialog.setHeaderText("Add a new LLM provider");

        ComboBox<ProviderType> typeCombo = new ComboBox<>();
        typeCombo.getItems().addAll(ProviderType.values());
        typeCombo.getSelectionModel().selectFirst();

        TextField nameField = new TextField();
        nameField.setPromptText("Provider name");
        TextField urlField = new TextField();
        urlField.setPromptText("API URL");
        PasswordField keyField = new PasswordField();
        keyField.setPromptText("API Key (if required)");

        VBox content = new VBox(10);
        content.getChildren().addAll(
                new Label("Type:"), typeCombo,
                new Label("Name:"), nameField,
                new Label("API URL:"), urlField,
                new Label("API Key:"), keyField);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        dialog.setResultConverter(button -> {
            if (button == ButtonType.OK) {
                ProviderConfig config = new ProviderConfig();
                config.setId(UUID.randomUUID().toString());
                config.setName(nameField.getText().isEmpty()
                        ? typeCombo.getValue().getDisplayName() : nameField.getText());
                config.setType(typeCombo.getValue());
                config.setApiUrl(urlField.getText());
                config.setApiKey(keyField.getText());
                return config;
            }
            return null;
        });

        dialog.showAndWait().ifPresent(config -> {
            try {
                providerRepository.save(config);
                loadProviders();
            } catch (Exception e) {
                showAlert("Failed to add provider: " + e.getMessage());
            }
        });
    }

    @FXML
    public void testEmbeddingConnection() {
        try {
            saveEmbeddingSettings();
            EmbeddingProvider provider = EmbeddingProviderFactory.create(configService);
            boolean ok = provider.testConnection();
            if (ok) {
                embeddingStatus.setGraphic(Icons.of("mdal-check_circle", 14, Color.web("#A3BE8C")));
                embeddingStatus.setText("Connected");
                embeddingStatus.getStyleClass().removeAll(Styles.SUCCESS, Styles.DANGER);
                embeddingStatus.getStyleClass().add(Styles.SUCCESS);
            } else {
                embeddingStatus.setGraphic(Icons.of("mdal-cancel", 14, Color.web("#BF616A")));
                embeddingStatus.setText("Failed");
                embeddingStatus.getStyleClass().removeAll(Styles.SUCCESS, Styles.DANGER);
                embeddingStatus.getStyleClass().add(Styles.DANGER);
            }
        } catch (Exception e) {
            embeddingStatus.setGraphic(Icons.of("mdmz-warning", 14, Color.web("#BF616A")));
            embeddingStatus.setText("Error: " + e.getMessage());
            embeddingStatus.getStyleClass().removeAll(Styles.SUCCESS, Styles.DANGER);
            embeddingStatus.getStyleClass().add(Styles.DANGER);
        }
    }

    @FXML
    public void testOllamaConnection() {
        String url = ollamaUrlField.getText().trim();
        if (url.isEmpty()) {
            ollamaStatus.setGraphic(Icons.of("mdal-cancel", 14, Color.web("#BF616A")));
            ollamaStatus.setText("Enter URL");
            ollamaStatus.getStyleClass().removeAll(Styles.SUCCESS, Styles.DANGER);
            ollamaStatus.getStyleClass().add(Styles.DANGER);
            return;
        }

        try {
            ProviderConfig testConfig = ProviderConfig.createOllama("test", "Test");
            testConfig.setApiUrl(url);
            boolean connected = modelManagerService.testProviderConnection(testConfig);
            if (connected) {
                ollamaStatus.setGraphic(Icons.of("mdal-check_circle", 14, Color.web("#A3BE8C")));
                ollamaStatus.setText("Connected");
                ollamaStatus.getStyleClass().removeAll(Styles.SUCCESS, Styles.DANGER);
                ollamaStatus.getStyleClass().add(Styles.SUCCESS);
            } else {
                ollamaStatus.setGraphic(Icons.of("mdal-cancel", 14, Color.web("#BF616A")));
                ollamaStatus.setText("Failed");
                ollamaStatus.getStyleClass().removeAll(Styles.SUCCESS, Styles.DANGER);
                ollamaStatus.getStyleClass().add(Styles.DANGER);
            }
        } catch (Exception e) {
            ollamaStatus.setGraphic(Icons.of("mdmz-warning", 14, Color.web("#BF616A")));
            ollamaStatus.setText("Error");
            ollamaStatus.getStyleClass().removeAll(Styles.SUCCESS, Styles.DANGER);
            ollamaStatus.getStyleClass().add(Styles.DANGER);
        }
    }

    @FXML
    public void clearAllData() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Clear All Data");
        confirm.setHeaderText("Are you sure?");
        confirm.setContentText("This will delete all documents, chunks, chat history, and settings. This cannot be undone.");

        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                try (java.sql.Connection conn = DatabaseManager.getInstance().getConnection();
                     java.sql.Statement stmt = conn.createStatement()) {
                    stmt.execute("DELETE FROM document_chunks");
                    stmt.execute("DELETE FROM documents");
                    stmt.execute("DELETE FROM chat_history");
                    stmt.execute("DELETE FROM settings");
                    showInfo("All data cleared. Please restart the application.");
                } catch (Exception e) {
                    showAlert("Failed to clear data: " + e.getMessage());
                }
            }
        });
    }

    @FXML
    public void saveSettings() {
        try {
            configService.set(ConfigService.KEY_CHUNK_SIZE, String.valueOf(chunkSizeSpinner.getValue()));
            configService.set(ConfigService.KEY_CONTEXT_CHUNKS, String.valueOf(contextChunksSpinner.getValue()));

            String newUrl = ollamaUrlField.getText().trim();
            if (!newUrl.isEmpty()) {
                var providers = providerRepository.findAll();
                for (ProviderConfig p : providers) {
                    if (p.getType() == ProviderType.OLLAMA) {
                        p.setApiUrl(newUrl);
                        providerRepository.update(p);
                        break;
                    }
                }
            }

            saveEmbeddingSettings();

            String llamaLevel = llamaLogLevelCombo.getValue();
            if (llamaLevel != null) {
                configService.set(ConfigService.KEY_LLAMA_LOG_LEVEL, llamaLevel);
                applyLlamaLogLevel(llamaLevel);
            }

            if (MainController.getInstance() != null) MainController.getInstance().refreshStatus();
            showInfo("Settings saved successfully!");
        } catch (Exception e) {
            showAlert("Failed to save settings: " + e.getMessage());
        }
    }

    private void applyLlamaLogLevel(String levelStr) {
        DesktopAiApplication.applyLlamaLogLevel(levelStr);
    }

    private void saveEmbeddingSettings() throws Exception {
        EmbeddingProviderType type = embeddingTypeCombo.getValue();
        if (type != null) {
            configService.set(ConfigService.KEY_EMBEDDING_TYPE, type.name());
        }
        String model = embeddingModelField.getText().trim();
        if (!model.isEmpty()) configService.set(ConfigService.KEY_EMBEDDING_MODEL, model);
        String apiUrl = embeddingApiUrlField.getText().trim();
        if (!apiUrl.isEmpty()) configService.set(ConfigService.KEY_EMBEDDING_API_URL, apiUrl);
        String apiKey = embeddingApiKeyField.getText().trim();
        if (!apiKey.isEmpty()) configService.set(ConfigService.KEY_EMBEDDING_API_KEY, apiKey);
    }

    private HBox createProviderItem(ProviderConfig provider) {
        HBox item = new HBox(10);
        item.getStyleClass().add("provider-item");
        item.setAlignment(Pos.CENTER_LEFT);

        FontIcon icon = Icons.of(provider.getType().getIconLiteral(), 20);
        icon.getStyleClass().add("provider-type-icon");

        VBox info = new VBox(2);
        Label name = new Label(provider.getName());
        name.getStyleClass().add(Styles.TEXT_BOLD);
        Label type = new Label(provider.getType().getDisplayName());
        type.getStyleClass().addAll(Styles.TEXT_SUBTLE, Styles.TEXT_SMALL);
        info.getChildren().addAll(name, type);
        HBox.setHgrow(info, Priority.ALWAYS);

        Label defaultLabel = new Label();
        if (provider.isDefault()) {
            defaultLabel.setGraphic(Icons.of("mdmz-star", 15, Color.web("#EBCB8B")));
            defaultLabel.setText("Default");
            defaultLabel.setGraphicTextGap(3);
            defaultLabel.getStyleClass().add(Styles.WARNING);
        }

        Button deleteBtn = new Button();
        deleteBtn.setGraphic(Icons.of("mdal-delete", 15));
        deleteBtn.getStyleClass().addAll(Styles.SMALL, Styles.FLAT);
        deleteBtn.setOnAction(e -> deleteProvider(provider));

        item.getChildren().addAll(icon, info, defaultLabel, deleteBtn);
        return item;
    }

    private void deleteProvider(ProviderConfig provider) {
        try {
            providerRepository.deleteById(provider.getId());
            loadProviders();
        } catch (Exception e) {
            showAlert("Failed to delete provider: " + e.getMessage());
        }
    }

    private void showAlert(String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Warning");
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void showInfo(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Success");
        alert.setContentText(message);
        alert.showAndWait();
    }
}
