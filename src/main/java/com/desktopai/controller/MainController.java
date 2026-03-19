package com.desktopai.controller;

import atlantafx.base.theme.Styles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.desktopai.DesktopAiApplication;
import com.desktopai.model.ProviderConfig;
import com.desktopai.service.ModelManagerService;
import com.desktopai.util.Icons;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;

import java.io.IOException;
import java.util.Optional;

public class MainController {
    private static final Logger log = LoggerFactory.getLogger(MainController.class);
    @FXML private StackPane contentArea;
    @FXML private Button chatNavBtn;
    @FXML private Button docsNavBtn;
    @FXML private Button modelsNavBtn;
    @FXML private Button settingsNavBtn;
    @FXML private Label connectionStatus;
    @FXML private Label currentModel;

    private Button activeNavButton;
    private ModelManagerService modelManagerService;

    private static MainController instance;

    public static MainController getInstance() {
        return instance;
    }

    @FXML
    public void initialize() {
        instance = this;
        modelManagerService = new ModelManagerService();
        activeNavButton = chatNavBtn;
        showChat();
        updateStatusBar();
    }

    @FXML public void showChat()      { loadView("chat-view.fxml");          setActiveNav(chatNavBtn);     }
    @FXML public void showDocuments() { loadView("document-view.fxml");      setActiveNav(docsNavBtn);     }
    @FXML public void showModels()    { loadView("model-manager-view.fxml"); setActiveNav(modelsNavBtn);   }
    @FXML public void showSettings()  { loadView("settings-view.fxml");      setActiveNav(settingsNavBtn); }

    private void loadView(String fxmlFile) {
        try {
            FXMLLoader loader = new FXMLLoader(DesktopAiApplication.class.getResource(fxmlFile));
            Node view = loader.load();
            contentArea.getChildren().setAll(view);
        } catch (IOException e) {
            log.error("Failed to load view: {}", fxmlFile, e);
            showError("Failed to load view: " + e.getMessage());
        }
    }

    private void setActiveNav(Button button) {
        if (activeNavButton != null) activeNavButton.getStyleClass().remove("nav-active");
        button.getStyleClass().add("nav-active");
        activeNavButton = button;
    }

    private void updateStatusBar() {
        Thread thread = new Thread(() -> {
            try {
                Optional<ProviderConfig> defaultProvider = modelManagerService.getDefaultProvider();
                if (defaultProvider.isPresent()) {
                    ProviderConfig provider = defaultProvider.get();
                    boolean connected = modelManagerService.testProviderConnection(provider);

                    javafx.application.Platform.runLater(() -> {
                        if (connected) {
                            connectionStatus.setText("Connected");
                            connectionStatus.setGraphic(Icons.of("mdal-fiber_manual_record", 12, javafx.scene.paint.Color.web("#A3BE8C")));
                            connectionStatus.getStyleClass().removeAll(Styles.SUCCESS, Styles.DANGER);
                            connectionStatus.getStyleClass().add(Styles.SUCCESS);


                        } else {
                            connectionStatus.setText("Disconnected");
                            connectionStatus.setGraphic(Icons.of("mdal-fiber_manual_record", 12, javafx.scene.paint.Color.web("#BF616A")));
                            connectionStatus.getStyleClass().removeAll(Styles.SUCCESS, Styles.DANGER);
                            connectionStatus.getStyleClass().add(Styles.DANGER);
                        }

//                        try {
//                            var models = modelManagerService.getModelsForProvider(provider);
//                            if (!models.isEmpty()) currentModel.setText(models.get(0));
//                        } catch (Exception ignored) {}
                    });
                }
            } catch (Exception e) {
                javafx.application.Platform.runLater(() -> {
                    connectionStatus.setText("Error");
                    connectionStatus.setGraphic(Icons.of("mdal-fiber_manual_record", 12, javafx.scene.paint.Color.web("#BF616A")));
                    connectionStatus.getStyleClass().removeAll(Styles.SUCCESS, Styles.DANGER);
                    connectionStatus.getStyleClass().add(Styles.DANGER);
                });
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    private void showError(String message) {
        Label errorLabel = new Label(message);
        errorLabel.setGraphic(Icons.of("mdmz-warning", 20));
        errorLabel.getStyleClass().addAll(Styles.DANGER, Styles.TITLE_4);
        contentArea.getChildren().setAll(errorLabel);
    }

    public void refreshStatus() {
        updateStatusBar();
    }
}
