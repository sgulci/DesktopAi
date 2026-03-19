package com.desktopai.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import atlantafx.base.theme.Styles;
import com.desktopai.model.DAIDocument;
import com.desktopai.model.DAIDocument.DocumentStatus;
import com.desktopai.service.RAGService;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import com.desktopai.util.Icons;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class DocumentController {
    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);
    @FXML private VBox dropZone;
    @FXML private VBox documentList;
    @FXML private HBox processingBar;
    @FXML private ProgressIndicator progressIndicator;
    @FXML private Label processingStatus;

    private RAGService ragService;
    private boolean isProcessing = false;

    @FXML
    public void initialize() {
        ragService = new RAGService();
        loadDocuments();
    }

    @FXML
    public void uploadDocument() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Document");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("All Supported", "*.pdf", "*.docx", "*.txt", "*.md", "*.html", "*.rtf"),
                new FileChooser.ExtensionFilter("PDF", "*.pdf"),
                new FileChooser.ExtensionFilter("Word", "*.docx"),
                new FileChooser.ExtensionFilter("Text", "*.txt", "*.md"),
                new FileChooser.ExtensionFilter("HTML", "*.html"),
                new FileChooser.ExtensionFilter("All Files", "*.*"));

        File file = fileChooser.showOpenDialog(dropZone.getScene().getWindow());
        if (file != null) processFile(file);
    }

    @FXML
    public void handleDragOver(DragEvent event) {
        if (event.getDragboard().hasFiles()) {
            event.acceptTransferModes(TransferMode.COPY);
            dropZone.getStyleClass().add("drop-zone-active");
        }
        event.consume();
    }

    @FXML
    public void handleDragDropped(DragEvent event) {
        var db = event.getDragboard();
        boolean success = false;
        if (db.hasFiles()) {
            success = true;
            for (File file : db.getFiles()) processFile(file);
        }
        event.setDropCompleted(success);
        event.consume();
    }

    @FXML
    public void handleDragExited(DragEvent event) {
        dropZone.getStyleClass().remove("drop-zone-active");
        event.consume();
    }

    @FXML
    public void cancelProcessing() {
        isProcessing = false;
        hideProcessingBar();
    }

    private void processFile(File file) {
        if (isProcessing) {
            showAlert("Please wait for the current document to finish processing.");
            return;
        }
        isProcessing = true;
        showProcessingBar("Processing: " + file.getName());

        CompletableFuture.runAsync(() -> {
            try {
                ragService.processDocument(file, status -> Platform.runLater(() -> processingStatus.setText(status)));
                Platform.runLater(() -> { loadDocuments(); hideProcessingBar(); isProcessing = false; });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    hideProcessingBar();
                    isProcessing = false;
                    showAlert("Failed to process document: " + e.getMessage());
                    loadDocuments();
                });
            }
        });
    }

    private void loadDocuments() {
        try {
            List<DAIDocument> documents = ragService.getAllDocuments();
            documentList.getChildren().clear();

            if (documents.isEmpty()) {
                Label emptyLabel = new Label("No documents uploaded yet");
                emptyLabel.getStyleClass().addAll(Styles.TEXT_MUTED, Styles.TEXT_ITALIC);
                documentList.getChildren().add(emptyLabel);
                return;
            }
            for (DAIDocument doc : documents) documentList.getChildren().add(createDocumentItem(doc));
        } catch (Exception e) {
            log.error("Failed to load documents", e);
        }
    }

    private HBox createDocumentItem(DAIDocument doc) {
        HBox item = new HBox(10);
        item.getStyleClass().add("document-item");
        item.setAlignment(Pos.CENTER_LEFT);

        FontIcon icon = Icons.of(getDocumentIconLiteral(doc.getContentType()), 22);
        icon.getStyleClass().add("doc-type-icon");

        VBox info = new VBox(2);
        Label name = new Label(doc.getFilename());
        name.getStyleClass().add("document-name");
        String sizeStr = formatFileSize(doc.getSizeBytes());
        String chunksStr = doc.getChunkCount() > 0 ? ", " + doc.getChunkCount() + " chunks" : "";
        Label details = new Label(sizeStr + chunksStr);
        details.getStyleClass().addAll(Styles.TEXT_SUBTLE, Styles.TEXT_SMALL);
        info.getChildren().addAll(name, details);
        HBox.setHgrow(info, Priority.ALWAYS);

        Label status = new Label(getStatusText(doc.getStatus()));
        status.setGraphic(getStatusIcon(doc.getStatus()));
        status.setGraphicTextGap(4);
        status.getStyleClass().add("document-status-" + doc.getStatus().name().toLowerCase());

        Button deleteBtn = new Button();
        deleteBtn.setGraphic(Icons.of("mdal-delete", 15));
        deleteBtn.getStyleClass().addAll(Styles.SMALL, Styles.FLAT);
        deleteBtn.setOnAction(e -> deleteDocument(doc));

        item.getChildren().addAll(icon, info, status, deleteBtn);
        return item;
    }

    private String getDocumentIconLiteral(String contentType) {
        if (contentType == null) return "mdal-description";
        if (contentType.contains("pdf")) return "mdal-description";
        if (contentType.contains("word") || contentType.contains("document")) return "mdal-article";
        if (contentType.contains("html")) return "mdal-article";
        if (contentType.contains("text")) return "mdal-description";
        return "mdal-description";
    }

    private FontIcon getStatusIcon(DocumentStatus status) {
        return switch (status) {
            case PENDING    -> Icons.of("mdal-hourglass_empty", 13);
            case PROCESSING -> Icons.of("mdmz-sync", 13);
            case READY      -> Icons.of("mdal-check_circle", 13, Color.web("#A3BE8C"));
            case ERROR      -> Icons.of("mdal-cancel", 13, Color.web("#BF616A"));
        };
    }

    private String getStatusText(DocumentStatus status) {
        return switch (status) {
            case PENDING    -> "Pending";
            case PROCESSING -> "Processing";
            case READY      -> "Ready";
            case ERROR      -> "Error";
        };
    }

    private void deleteDocument(DAIDocument doc) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete Document");
        confirm.setHeaderText("Delete " + doc.getFilename() + "?");
        confirm.setContentText("This will remove the document and all its indexed data.");

        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                try {
                    ragService.deleteDocument(doc.getId());
                    loadDocuments();
                } catch (Exception e) {
                    showAlert("Failed to delete document: " + e.getMessage());
                }
            }
        });
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void showProcessingBar(String status) {
        processingStatus.setText(status);
        processingBar.setVisible(true);
        processingBar.setManaged(true);
    }

    private void hideProcessingBar() {
        processingBar.setVisible(false);
        processingBar.setManaged(false);
    }

    private void showAlert(String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Warning");
        alert.setContentText(message);
        alert.showAndWait();
    }
}
