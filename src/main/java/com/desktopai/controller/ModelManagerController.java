package com.desktopai.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import atlantafx.base.theme.Styles;
import com.desktopai.model.ProviderConfig;
import com.desktopai.model.ProviderType;
import com.desktopai.service.ModelManagerService;
import com.desktopai.service.ModelManagerService.HFFile;
import com.desktopai.service.ModelManagerService.HFModel;
import com.desktopai.service.ModelManagerService.OllamaLibraryModel;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import com.desktopai.util.Icons;
import javafx.stage.Popup;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.List;
import java.util.concurrent.*;

public class ModelManagerController {
    private static final Logger log = LoggerFactory.getLogger(ModelManagerController.class);

    // Ollama section
    @FXML private VBox installedModelsList;
    @FXML private TextField modelNameField;
    @FXML private Button downloadBtn;
    @FXML private HBox downloadProgressBar;
    @FXML private ProgressIndicator downloadIndicator;
    @FXML private Label downloadStatus;

    // HuggingFace section
    @FXML private TextField hfSearchField;
    @FXML private ComboBox<HFFile> hfFileCombo;
    @FXML private HBox hfProgressBox;
    @FXML private ProgressIndicator hfProgressIndicator;
    @FXML private Label hfProgressLabel;
    @FXML private Button hfDownloadBtn;

    private ModelManagerService modelManagerService;
    private ProviderConfig ollamaProvider;
    private HFModel selectedHFRepo;

    private Popup autocompletePopup;
    private ListView<HFModel> autocompleteList;
    private ScheduledExecutorService debounceExecutor;
    private ScheduledFuture<?> pendingSearch;

    // Ollama library autocomplete
    private Popup ollamaAutocompletePopup;
    private ListView<OllamaLibraryModel> ollamaAutocompleteList;
    private ScheduledExecutorService ollamaDebounceExecutor;
    private ScheduledFuture<?> pendingOllamaSearch;
    private boolean suppressOllamaSearch = false;

    @FXML
    public void initialize() {
        modelManagerService = new ModelManagerService();
        loadOllamaProvider();
        refreshModels();
        setupHuggingFaceAutocomplete();
        setupOllamaAutocomplete();

        hfFileCombo.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, selected) -> hfDownloadBtn.setDisable(selected == null));
    }

    private void setupHuggingFaceAutocomplete() {
        autocompleteList = new ListView<>();
        autocompleteList.setMaxHeight(280);
        autocompleteList.getStyleClass().add("hf-autocomplete-list");
        autocompleteList.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(HFModel item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.toString());
            }
        });

        autocompletePopup = new Popup();
        autocompletePopup.setAutoHide(true);
        autocompletePopup.getContent().add(autocompleteList);

        debounceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "hf-search");
            t.setDaemon(true);
            return t;
        });

        hfSearchField.textProperty().addListener((obs, old, text) -> {
            if (pendingSearch != null) pendingSearch.cancel(false);
            if (text.trim().length() < 2) {
                autocompletePopup.hide();
                return;
            }
            pendingSearch = debounceExecutor.schedule(() -> {
                try {
                    List<HFModel> results = modelManagerService.searchHuggingFaceModels(text.trim());
                    Platform.runLater(() -> showSuggestions(results));
                } catch (Exception e) {
                    Platform.runLater(autocompletePopup::hide);
                }
            }, 400, TimeUnit.MILLISECONDS);
        });

        // Mouse click selects a suggestion
        autocompleteList.setOnMouseClicked(e -> {
            HFModel sel = autocompleteList.getSelectionModel().getSelectedItem();
            if (sel != null) selectSuggestion(sel);
        });

        // Down-arrow from search field moves focus into list
        hfSearchField.setOnKeyPressed(e -> {
            if (!autocompletePopup.isShowing()) return;
            if (e.getCode() == KeyCode.DOWN) {
                autocompleteList.requestFocus();
                autocompleteList.getSelectionModel().selectFirst();
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                autocompletePopup.hide();
                e.consume();
            }
        });

        // Enter/Escape while list is focused
        autocompleteList.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                selectSuggestion(autocompleteList.getSelectionModel().getSelectedItem());
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                autocompletePopup.hide();
                hfSearchField.requestFocus();
                e.consume();
            }
        });

        // Keep popup width matching the text field
        hfSearchField.widthProperty().addListener(
                (obs, old, w) -> autocompleteList.setPrefWidth(w.doubleValue()));
    }

    private void setupOllamaAutocomplete() {
        ollamaAutocompleteList = new ListView<>();
        ollamaAutocompleteList.setMaxHeight(280);
        ollamaAutocompleteList.getStyleClass().add("hf-autocomplete-list");
        ollamaAutocompleteList.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(OllamaLibraryModel item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.toString());
            }
        });

        ollamaAutocompletePopup = new Popup();
        ollamaAutocompletePopup.setAutoHide(true);
        ollamaAutocompletePopup.getContent().add(ollamaAutocompleteList);

        ollamaDebounceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ollama-search");
            t.setDaemon(true);
            return t;
        });

        modelNameField.textProperty().addListener((obs, old, text) -> {
            if (suppressOllamaSearch) return;
            if (pendingOllamaSearch != null) pendingOllamaSearch.cancel(false);
            if (text.trim().length() < 2) {
                ollamaAutocompletePopup.hide();
                return;
            }
            pendingOllamaSearch = ollamaDebounceExecutor.schedule(() -> {
                try {
                    List<OllamaLibraryModel> results =
                            modelManagerService.searchOllamaLibraryModels(text.trim());
                    Platform.runLater(() -> showOllamaSuggestions(results));
                } catch (Exception e) {
                    log.warn("Ollama library search failed: {}", e.getMessage());
                    Platform.runLater(ollamaAutocompletePopup::hide);
                }
            }, 400, TimeUnit.MILLISECONDS);
        });

        ollamaAutocompleteList.setOnMouseClicked(e -> {
            OllamaLibraryModel sel = ollamaAutocompleteList.getSelectionModel().getSelectedItem();
            if (sel != null) selectOllamaSuggestion(sel);
        });

        modelNameField.setOnKeyPressed(e -> {
            if (!ollamaAutocompletePopup.isShowing()) return;
            if (e.getCode() == KeyCode.DOWN) {
                ollamaAutocompleteList.requestFocus();
                ollamaAutocompleteList.getSelectionModel().selectFirst();
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                ollamaAutocompletePopup.hide();
                e.consume();
            }
        });

        ollamaAutocompleteList.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                selectOllamaSuggestion(ollamaAutocompleteList.getSelectionModel().getSelectedItem());
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                ollamaAutocompletePopup.hide();
                modelNameField.requestFocus();
                e.consume();
            }
        });

        modelNameField.widthProperty().addListener(
                (obs, old, w) -> ollamaAutocompleteList.setPrefWidth(w.doubleValue()));
    }

    private void showOllamaSuggestions(List<OllamaLibraryModel> results) {
        if (results.isEmpty()) {
            ollamaAutocompletePopup.hide();
            return;
        }

        Bounds bounds = modelNameField.localToScreen(modelNameField.getBoundsInLocal());
        if (bounds == null) return;

        javafx.stage.Window window = modelNameField.getScene().getWindow();
        double windowBottom = window.getY() + window.getHeight();
        double availableHeight = windowBottom - bounds.getMaxY() - 8;
        ollamaAutocompleteList.setMaxHeight(Math.max(80, Math.min(280, availableHeight)));
        ollamaAutocompleteList.setPrefWidth(modelNameField.getWidth());

        ollamaAutocompleteList.getItems().setAll(results);

        double popupX = bounds.getMinX();
        double popupY = bounds.getMaxY() + 2;

        if (ollamaAutocompletePopup.isShowing()) {
            ollamaAutocompletePopup.setX(popupX);
            ollamaAutocompletePopup.setY(popupY);
        } else {
            ollamaAutocompletePopup.show(modelNameField, popupX, popupY);
        }
    }

    private void selectOllamaSuggestion(OllamaLibraryModel model) {
        if (model == null) return;
        suppressOllamaSearch = true;
        modelNameField.setText(model.name());
        suppressOllamaSearch = false;
        ollamaAutocompletePopup.hide();
        modelNameField.requestFocus();
        modelNameField.positionCaret(model.name().length());
    }

    private void showSuggestions(List<HFModel> results) {
        if (results.isEmpty()) {
            autocompletePopup.hide();
            return;
        }

        Bounds bounds = hfSearchField.localToScreen(hfSearchField.getBoundsInLocal());
        if (bounds == null) return;

        // Constrain popup height to the space between the bottom of the search field
        // and the bottom of the application window
        javafx.stage.Window window = hfSearchField.getScene().getWindow();
        double windowBottom = window.getY() + window.getHeight();
        double availableHeight = windowBottom - bounds.getMaxY() - 8;
        autocompleteList.setMaxHeight(Math.max(80, Math.min(280, availableHeight)));
        autocompleteList.setPrefWidth(hfSearchField.getWidth());

        autocompleteList.getItems().setAll(results);

        double popupX = bounds.getMinX();
        double popupY = bounds.getMaxY() + 2;

        if (autocompletePopup.isShowing()) {
            autocompletePopup.setX(popupX);
            autocompletePopup.setY(popupY);
        } else {
            autocompletePopup.show(hfSearchField, popupX, popupY);
        }
    }

    private void selectSuggestion(HFModel model) {
        if (model == null) return;
        selectedHFRepo = model;
        hfSearchField.setText(model.id());
        autocompletePopup.hide();

        hfFileCombo.getItems().clear();
        hfFileCombo.setDisable(true);
        hfFileCombo.setPromptText("Loading files...");
        hfDownloadBtn.setDisable(true);

        CompletableFuture.supplyAsync(() -> {
            try {
                return modelManagerService.getHuggingFaceFiles(selectedHFRepo.id());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).whenCompleteAsync((files, ex) -> Platform.runLater(() -> {
            if (ex != null) {
                hfFileCombo.setPromptText("Failed to load files");
                return;
            }
            hfFileCombo.getItems().setAll(files);
            hfFileCombo.setPromptText(files.isEmpty()
                    ? "No GGUF files found" : "— select a GGUF file —");
            hfFileCombo.setDisable(files.isEmpty());
        }));
    }

    private void loadOllamaProvider() {
        try {
            for (ProviderConfig provider : modelManagerService.getAllProviders()) {
                if (provider.getType() == ProviderType.OLLAMA) {
                    ollamaProvider = provider;
                    break;
                }
            }
        } catch (Exception e) {
            log.error("Failed to load Ollama provider", e);
        }
    }

    @FXML
    public void refreshModels() {
        MainController ctrl = MainController.getInstance();
        if (ctrl != null) ctrl.refreshStatus();

        installedModelsList.getChildren().clear();

        if (ollamaProvider != null) {
            Label header = new Label("Ollama Models");
            header.getStyleClass().add(Styles.TEXT_BOLD);
            header.setPadding(new Insets(10, 0, 5, 0));
            installedModelsList.getChildren().add(header);

            try {
                List<String> models = modelManagerService.getModelsForProvider(ollamaProvider);
                if (models.isEmpty()) {
                    installedModelsList.getChildren().add(new Label("No Ollama models found."));
                } else {
                    for (String name : models) {
                        installedModelsList.getChildren().add(createModelItem(name, true));
                    }
                }
            } catch (Exception e) {
                installedModelsList.getChildren().add(new Label("Error loading Ollama models"));
            }
        }

        Label nativeHeader = new Label("Native Models (GGUF)");
        nativeHeader.getStyleClass().add(Styles.TEXT_BOLD);
        nativeHeader.setPadding(new Insets(10, 0, 5, 0));
        installedModelsList.getChildren().add(nativeHeader);

        try {
            List<com.desktopai.model.LLMModel> allModels = modelManagerService.getAllModels();
            boolean foundNative = false;
            for (com.desktopai.model.LLMModel model : allModels) {
                if (model.getProviderType() == ProviderType.LOCAL_NATIVE) {
                    installedModelsList.getChildren().add(createModelItem(model.getName(), false));
                    foundNative = true;
                }
            }
            if (!foundNative) {
                installedModelsList.getChildren().add(
                        new Label("No native models found in ~/.desktopai/models"));
            }
        } catch (Exception e) {
            installedModelsList.getChildren().add(new Label("Error loading native models"));
        }
    }

    @FXML
    public void downloadModel() {
        String input = modelNameField.getText().trim();
        if (input.isEmpty()) {
            showAlert("Please enter a model name or URL");
            return;
        }

        downloadBtn.setDisable(true);
        downloadProgressBar.setVisible(true);
        downloadProgressBar.setManaged(true);
        downloadStatus.setText("Starting...");

        CompletableFuture.runAsync(() -> {
            try {
                if (input.startsWith("http://") || input.startsWith("https://")) {
                    String filename = input.substring(input.lastIndexOf('/') + 1);
                    if (filename.contains("?")) filename = filename.substring(0, filename.indexOf("?"));
                    if (!filename.endsWith(".gguf")) filename += ".gguf";
                    final String fn = filename;
                    Platform.runLater(() -> downloadStatus.setText("Downloading " + fn + "..."));
                    modelManagerService.downloadModel(input, fn, progress -> Platform.runLater(() -> {
                        if (progress < 0) downloadStatus.setText("Download failed");
                        else if (progress >= 1.0) downloadStatus.setText("Download complete!");
                        else downloadStatus.setText(String.format("Downloading: %.0f%%", progress * 100));
                        downloadIndicator.setProgress(progress);
                    }));
                } else {
                    if (ollamaProvider == null) throw new RuntimeException("Ollama provider not found");
                    Platform.runLater(() -> downloadStatus.setText("Pulling from Ollama..."));
                    modelManagerService.pullOllamaModel(ollamaProvider.getId(), input,
                            status -> Platform.runLater(() -> downloadStatus.setText(status)));
                }
                Platform.runLater(() -> {
                    modelNameField.clear();
                    refreshModels();
                    hideOllamaProgress();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    hideOllamaProgress();
                    showAlert("Action failed: " + e.getMessage());
                });
            }
        });
    }

    @FXML
    public void downloadHuggingFaceModel() {
        if (selectedHFRepo == null) return;
        HFFile file = hfFileCombo.getSelectionModel().getSelectedItem();
        if (file == null) return;

        String url = ModelManagerService.huggingFaceDownloadUrl(selectedHFRepo.id(), file.filename());

        hfDownloadBtn.setDisable(true);
        hfProgressBox.setVisible(true);
        hfProgressBox.setManaged(true);
        hfProgressIndicator.setProgress(-1);
        hfProgressLabel.setText("Starting download...");

        CompletableFuture.runAsync(() -> {
            try {
                modelManagerService.downloadModel(url, file.filename(), progress ->
                        Platform.runLater(() -> {
                            hfProgressIndicator.setProgress(progress);
                            if (progress >= 1.0) hfProgressLabel.setText("Download complete!");
                            else hfProgressLabel.setText(String.format("Downloading: %.0f%%", progress * 100));
                        }));
                Platform.runLater(() -> {
                    refreshModels();
                    hfProgressBox.setVisible(false);
                    hfProgressBox.setManaged(false);
                    hfDownloadBtn.setDisable(false);
                    hfSearchField.clear();
                    hfFileCombo.getItems().clear();
                    hfFileCombo.setDisable(true);
                    selectedHFRepo = null;
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    hfProgressBox.setVisible(false);
                    hfProgressBox.setManaged(false);
                    hfDownloadBtn.setDisable(false);
                    showAlert("Download failed: " + e.getMessage());
                });
            }
        });
    }

    private HBox createModelItem(String modelName, boolean isOllama) {
        HBox item = new HBox(10);
        item.getStyleClass().add("model-item");
        item.setAlignment(Pos.CENTER_LEFT);

        FontIcon icon = Icons.of(isOllama ? "mdal-computer" : "mdmz-memory", 20);
        icon.getStyleClass().add("model-type-icon");

        Label name = new Label(modelName);
        HBox.setHgrow(name, Priority.ALWAYS);

        Button deleteBtn = new Button();
        deleteBtn.setGraphic(Icons.of("mdal-delete", 15));
        deleteBtn.getStyleClass().addAll(Styles.SMALL, Styles.FLAT);
        deleteBtn.setOnAction(e -> deleteModel(modelName, isOllama));

        item.getChildren().addAll(icon, name, deleteBtn);
        return item;
    }

    private void deleteModel(String modelName, boolean isOllama) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete Model");
        confirm.setHeaderText("Delete " + modelName + "?");
        confirm.setContentText("This will remove the model from your system.");

        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                try {
                    boolean deleted;
                    if (isOllama) {
                        deleted = modelManagerService.deleteOllamaModel(ollamaProvider.getId(), modelName);
                    } else {
                        java.io.File file = new java.io.File(
                                System.getProperty("user.home"), ".desktopai/models/" + modelName);
                        deleted = file.delete();
                    }
                    if (deleted) refreshModels();
                    else showAlert("Failed to delete model");
                } catch (Exception e) {
                    showAlert("Error: " + e.getMessage());
                }
            }
        });
    }

    private void hideOllamaProgress() {
        downloadBtn.setDisable(false);
        downloadProgressBar.setVisible(false);
        downloadProgressBar.setManaged(false);
    }

    private void showAlert(String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Warning");
        alert.setContentText(message);
        alert.showAndWait();
    }
}
