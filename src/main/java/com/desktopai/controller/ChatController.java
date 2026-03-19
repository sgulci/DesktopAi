package com.desktopai.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import atlantafx.base.theme.Styles;
import com.desktopai.model.*;
import com.desktopai.model.DAIChatMessage.Role;
import com.desktopai.repository.ChatHistoryRepository;
import com.desktopai.repository.ChatHistoryRepository.SessionSummary;
import com.desktopai.service.ModelManagerService;
import com.desktopai.service.RAGService;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.*;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Callback;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Controller for the chat interface.
 */
public class ChatController {
    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    @FXML
    private ComboBox<LLMModel> modelSelector;
    @FXML
    private Label selectedDocsLabel;
    @FXML
    private VBox messagesContainer;
    @FXML
    private ScrollPane chatScrollPane;
    @FXML
    private TextArea inputField;
    @FXML
    private Button sendBtn;
    @FXML
    private Button stopBtn;
    @FXML
    private VBox chatHistoryList;

    private RAGService ragService;
    private ModelManagerService modelManagerService;
    private ChatHistoryRepository chatHistoryRepository;

    private String currentSessionId;
    // null = All documents (RAG across all indexed docs)
    // empty list = No context (pure LLM, no RAG)  ← default
    // non-empty list = specific documents only
    private List<String> selectedDocumentIds = new ArrayList<>();
    private AtomicBoolean isGenerating = new AtomicBoolean(false);
    private TextArea currentAssistantLabel;

    @FXML
    public void initialize() {
        ragService = new RAGService();
        modelManagerService = new ModelManagerService();
        chatHistoryRepository = new ChatHistoryRepository();

        setupModelSelector();
        currentSessionId = chatHistoryRepository.createNewSession();
        loadModels();
        loadChatHistorySidebar();

        inputField.setOnKeyPressed(event -> {
            if (event.getCode().toString().equals("ENTER") && !event.isShiftDown()) {
                event.consume();
                sendMessage();
            }
        });

        addSystemMessage("Welcome to DesktopAI! Upload documents and ask questions about them.");
    }

    private void setupModelSelector() {
        Callback<ListView<LLMModel>, ListCell<LLMModel>> cellFactory = new Callback<>() {
            @Override
            public ListCell<LLMModel> call(ListView<LLMModel> l) {
                return new ListCell<>() {
                    @Override
                    protected void updateItem(LLMModel item, boolean empty) {
                        super.updateItem(item, empty);
                        if (item == null || empty) {
                            setText(null);
                            setGraphic(null);
                        } else {
                            setText(item.getDisplayName());
                            org.kordamp.ikonli.javafx.FontIcon ic =
                                    new org.kordamp.ikonli.javafx.FontIcon(
                                            item.getProviderType().getIconLiteral());
                            ic.setIconSize(13);
                            setGraphic(ic);
                        }
                    }
                };
            }
        };

        modelSelector.setButtonCell(cellFactory.call(null));
        modelSelector.setCellFactory(cellFactory);
    }

    private void loadModels() {
        try {
            modelSelector.getItems().clear();
            List<LLMModel> allModels = modelManagerService.getAllModels();
            modelSelector.getItems().addAll(allModels);

            if (!modelSelector.getItems().isEmpty()) {
                modelSelector.getSelectionModel().selectFirst();
            }
        } catch (Exception e) {
            log.error("Failed to load models", e);
        }
    }

    @FXML
    public void startNewChat() {
        currentSessionId = chatHistoryRepository.createNewSession();
        messagesContainer.getChildren().clear();
        selectedDocumentIds = new ArrayList<>(); // reset to "No context"
        selectedDocsLabel.setText("No context");
        addSystemMessage("New chat started. Ask a question about your documents.");
        loadChatHistorySidebar();
    }

    private void loadChatHistorySidebar() {
        try {
            List<SessionSummary> summaries = chatHistoryRepository.findSessionSummaries();
            Platform.runLater(() -> {
                chatHistoryList.getChildren().clear();
                for (SessionSummary summary : summaries) {
                    chatHistoryList.getChildren().add(createHistoryItem(summary));
                }
            });
        } catch (Exception e) {
            log.warn("Failed to load chat history sidebar", e);
        }
    }

    private VBox createHistoryItem(SessionSummary summary) {
        VBox item = new VBox(2);
        item.getStyleClass().add("chat-history-item");
        item.setCursor(Cursor.HAND);
        item.setPadding(new Insets(8, 10, 8, 10));

        if (summary.sessionId().equals(currentSessionId)) {
            item.getStyleClass().add("chat-history-item-active");
        }

        Label titleLabel = new Label(summary.title());
        titleLabel.getStyleClass().add(Styles.TEXT_BOLD);
        titleLabel.setWrapText(true);
        titleLabel.setMaxWidth(Double.MAX_VALUE);

        Label dateLabel = new Label(summary.startedAt()
                .format(DateTimeFormatter.ofPattern("MMM d, HH:mm")));
        dateLabel.getStyleClass().addAll(Styles.TEXT_SUBTLE, Styles.TEXT_SMALL);

        item.getChildren().addAll(titleLabel, dateLabel);
        item.setOnMouseClicked(e -> loadSession(summary.sessionId()));
        return item;
    }

    private void loadSession(String sessionId) {
        try {
            currentSessionId = sessionId;
            List<DAIChatMessage> messages = chatHistoryRepository.findBySessionId(sessionId);
            messagesContainer.getChildren().clear();
            for (DAIChatMessage msg : messages) {
                if (msg.getRole() == Role.USER) {
                    addUserMessage(msg.getContent());
                } else if (msg.getRole() == Role.ASSISTANT) {
                    addAssistantMessage(msg.getContent());
                }
            }
            scrollToBottom();
            loadChatHistorySidebar();
        } catch (Exception e) {
            log.error("Failed to load session {}", sessionId, e);
        }
    }

    @FXML
    public void selectDocuments() {
        try {
            List<DAIDocument> docs = ragService.getAllDocuments().stream()
                    .filter(d -> d.getStatus() == DAIDocument.DocumentStatus.READY)
                    .toList();

            if (docs.isEmpty()) {
                addSystemMessage("No documents available. Upload and process documents first.");
                return;
            }

            Dialog<List<String>> dialog = new Dialog<>();
            dialog.setTitle("Document Context");
            dialog.setHeaderText(
                    "Select documents to filter context, or choose a mode below.\n" +
                    "\"All Documents\" searches every indexed document.");

            ListView<DAIDocument> listView = new ListView<>();
            listView.getItems().addAll(docs);
            listView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
            listView.setCellFactory(lv -> new ListCell<>() {
                @Override
                protected void updateItem(DAIDocument item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(item == null || empty ? null : item.getFilename());
                }
            });
            listView.setPrefHeight(220);

            // Restore previous selection (only when specific docs were chosen)
            if (selectedDocumentIds != null) {
                for (int i = 0; i < docs.size(); i++) {
                    if (selectedDocumentIds.contains(docs.get(i).getId())) {
                        listView.getSelectionModel().select(i);
                    }
                }
            }

            ButtonType allDocsType  = new ButtonType("All Documents", ButtonData.LEFT);
            ButtonType noContextType = new ButtonType("No Context",    ButtonData.LEFT);
            dialog.getDialogPane().setContent(listView);
            dialog.getDialogPane().getButtonTypes()
                    .addAll(ButtonType.OK, allDocsType, noContextType, ButtonType.CANCEL);

            // Track which button was used (OK can mean "apply selection" regardless of count)
            AtomicReference<String> mode = new AtomicReference<>("CANCEL");
            dialog.setResultConverter(btn -> {
                if (btn == ButtonType.OK) {
                    mode.set("SELECTED");
                    return listView.getSelectionModel().getSelectedItems()
                            .stream().map(DAIDocument::getId).toList();
                }
                if (btn == allDocsType)   { mode.set("ALL");  return List.of(); }
                if (btn == noContextType) { mode.set("NONE"); return List.of(); }
                return null; // CANCEL
            });

            dialog.showAndWait().ifPresent(ids -> {
                switch (mode.get()) {
                    case "ALL" -> {
                        selectedDocumentIds = null;
                        selectedDocsLabel.setText("All documents");
                    }
                    case "NONE" -> {
                        selectedDocumentIds = new ArrayList<>();
                        selectedDocsLabel.setText("No context");
                    }
                    case "SELECTED" -> {
                        if (ids.isEmpty()) {
                            // OK clicked with nothing selected → treat as "All documents"
                            selectedDocumentIds = null;
                            selectedDocsLabel.setText("All documents");
                        } else {
                            selectedDocumentIds = new ArrayList<>(ids);
                            selectedDocsLabel.setText(ids.size() + " doc(s) selected");
                        }
                    }
                }
            });

        } catch (Exception e) {
            log.error("Failed to open document selector", e);
        }
    }

    @FXML
    public void sendMessage() {
        String message = inputField.getText().trim();
        if (message.isEmpty() || isGenerating.get()) {
            return;
        }

        LLMModel selectedModel = modelSelector.getSelectionModel().getSelectedItem();
        if (selectedModel == null) {
            addUserMessage(message);
            addSystemMessage("Please select a model first.");
            inputField.clear();
            return;
        }

        addUserMessage(message);
        inputField.clear();

        isGenerating.set(true);
        sendBtn.setDisable(true);
        stopBtn.setVisible(true);
        stopBtn.setManaged(true);

        currentAssistantLabel = addAssistantMessage("Thinking...");

        // Capture values for the async block
        // null = all docs, empty = no context, non-empty = specific docs
        final String sessionId = currentSessionId;
        final List<String> docIds = selectedDocumentIds == null ? null
                : new ArrayList<>(selectedDocumentIds);

        CompletableFuture.runAsync(() -> {
            try {
                StringBuilder response = new StringBuilder();

                ProviderConfig providerConfig = modelManagerService.getAllProviders().stream()
                        .filter(p -> p.getId().equals(selectedModel.getProviderId()))
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException("Provider not found for model"));

                CompletableFuture<Void> currentFuture = ragService.answerStream(
                        message,
                        providerConfig,
                        selectedModel.getName(),
                        docIds,
                        sessionId,
                        token -> {
                            if (isGenerating.get()) {
                                response.append(token);
                                Platform.runLater(() -> {
                                    currentAssistantLabel.setText(response.toString());
                                    scrollToBottom();
                                });
                            }
                        });

                stopBtn.setOnAction(e -> {
                    currentFuture.cancel(true);
                    stopGeneration();
                });

                currentFuture.whenCompleteAsync((result, ex) -> {
                    if (ex != null && !(ex instanceof java.util.concurrent.CancellationException)) {
                        Platform.runLater(() ->
                                currentAssistantLabel.setText("Error: " + ex.getMessage()));
                    }

                    Platform.runLater(() -> {
                        isGenerating.set(false);
                        sendBtn.setDisable(false);
                        stopBtn.setVisible(false);
                        stopBtn.setManaged(false);
                    });

                    if (!response.isEmpty()) {
                        try {
                            DAIChatMessage userMsg = new DAIChatMessage(sessionId, Role.USER, message);
                            chatHistoryRepository.save(userMsg);

                            DAIChatMessage assistantMsg = new DAIChatMessage(sessionId, Role.ASSISTANT,
                                    response.toString());
                            assistantMsg.setModelUsed(selectedModel.getName());
                            assistantMsg.setProviderId(selectedModel.getProviderId());
                            chatHistoryRepository.save(assistantMsg);

                            loadChatHistorySidebar();
                        } catch (Exception e) {
                            log.error("Failed to save chat history", e);
                        }
                    }
                });

            } catch (Exception e) {
                Platform.runLater(() ->
                        currentAssistantLabel.setText("Error: " + e.getMessage()));
                Platform.runLater(() -> {
                    isGenerating.set(false);
                    sendBtn.setDisable(false);
                    stopBtn.setVisible(false);
                    stopBtn.setManaged(false);
                });
            }
        });
    }

    @FXML
    public void stopGeneration() {
        isGenerating.set(false);
        sendBtn.setDisable(false);
        stopBtn.setVisible(false);
        stopBtn.setManaged(false);

        if (currentAssistantLabel != null) {
            currentAssistantLabel.setText(currentAssistantLabel.getText() + " [stopped]");
        }
    }

    private void addUserMessage(String text) {
        HBox container = new HBox();
        container.setAlignment(Pos.CENTER_RIGHT);
        container.setPadding(new Insets(5, 0, 5, 50));

        VBox bubble = new VBox();
        bubble.getStyleClass().addAll("message-bubble", "message-user");

        TextArea textArea = createSelectableMessage(text);
        textArea.getStyleClass().add("message-text-area");

        bubble.getChildren().add(textArea);
        container.getChildren().add(bubble);
        messagesContainer.getChildren().add(container);
        scrollToBottom();
    }

    private TextArea addAssistantMessage(String text) {
        HBox container = new HBox();
        container.setAlignment(Pos.CENTER_LEFT);
        container.setPadding(new Insets(5, 50, 5, 0));

        VBox bubble = new VBox();
        bubble.getStyleClass().addAll("message-bubble", "message-assistant");

        TextArea textArea = createSelectableMessage(text);
        textArea.getStyleClass().add("message-text-area");

        bubble.getChildren().add(textArea);
        container.getChildren().add(bubble);
        messagesContainer.getChildren().add(container);
        scrollToBottom();

        return textArea;
    }

    private TextArea createSelectableMessage(String text) {
        TextArea textArea = new TextArea(text);
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setPrefRowCount(1);

        textArea.textProperty().addListener((obs, oldVal, newVal) -> adjustHeight(textArea));
        textArea.widthProperty().addListener((obs, oldVal, newVal) -> adjustHeight(textArea));
        Platform.runLater(() -> adjustHeight(textArea));

        return textArea;
    }

    private void adjustHeight(TextArea textArea) {
        javafx.scene.text.Text helper = new javafx.scene.text.Text();
        helper.setFont(javafx.scene.text.Font.font("System", 14));
        helper.setText(textArea.getText());
        helper.setWrappingWidth(textArea.getWidth() - 20);

        double height = helper.getLayoutBounds().getHeight() + 20;
        textArea.setPrefHeight(height);
        textArea.setMinHeight(height);
        textArea.setMaxHeight(height);
    }

    private void addSystemMessage(String text) {
        Label label = new Label(text);
        label.getStyleClass().addAll(Styles.TEXT_MUTED, Styles.TEXT_ITALIC);
        label.setWrapText(true);
        label.setPadding(new Insets(10, 0, 10, 0));

        HBox container = new HBox(label);
        container.setAlignment(Pos.CENTER);
        messagesContainer.getChildren().add(container);
    }

    private void scrollToBottom() {
        Platform.runLater(() -> chatScrollPane.setVvalue(1.0));
    }
}
