package com.desktopai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import atlantafx.base.theme.NordDark;
import atlantafx.base.theme.NordLight;
import com.desktopai.service.ConfigService;
import com.desktopai.service.embedding.EmbeddingProviderFactory;
import com.desktopai.service.llm.LLMProviderFactory;
import com.desktopai.service.llm.LocalNativeProvider;
import com.desktopai.util.StderrSuppressor;
import com.desktopai.util.DatabaseManager;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.SnapshotParameters;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.geometry.VPos;
import javafx.stage.Stage;

import java.io.IOException;

public class DesktopAiApplication extends Application {
    private static final Logger log = LoggerFactory.getLogger(DesktopAiApplication.class);
    private static final String APP_TITLE = "DesktopAI";
    private static final int MIN_WIDTH = 900;
    private static final int MIN_HEIGHT = 600;

    @Override
    public void start(Stage stage) throws IOException {
        try {
            DatabaseManager.getInstance().getConnection();
        } catch (Exception e) {
            log.error("Failed to initialize database", e);
        }

        boolean isDark = true;
        try {
            ConfigService cfg = new ConfigService();
            isDark = cfg.isDarkMode();
            // Apply level first (suppress fd 2 before any native lib loads)
            applyLlamaLogLevel(cfg.getLlamaLogLevel());
            // Then install the Java log callback hook
            LocalNativeProvider.installLoggerHook();
        } catch (Exception ignored) {}
        applyTheme(isDark);

        FXMLLoader fxmlLoader = new FXMLLoader(DesktopAiApplication.class.getResource("main-view.fxml"));
        Scene scene = new Scene(fxmlLoader.load(), 1200, 800);
        scene.getStylesheets().add(
                DesktopAiApplication.class.getResource("styles.css").toExternalForm());

        stage.getIcons().add(buildAppIcon(64));
        stage.getIcons().add(buildAppIcon(32));

        stage.setTitle(APP_TITLE);
        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);
        stage.setScene(scene);
        stage.show();
    }

    public static void applyTheme(boolean dark) {
        Application.setUserAgentStylesheet(
                dark ? new NordDark().getUserAgentStylesheet()
                     : new NordLight().getUserAgentStylesheet());
    }

    /**
     * Applies the llama.cpp log level setting:
     * - Redirects/restores native fd 2 (real fix for output that bypasses the Java callback)
     * - Filters inside the Java callback (for messages routed through llama_log_callback)
     * - Sets the Logback level for the "llama.cpp" SLF4J logger
     *
     * DEBUG → restore native stderr (full raw output); all other levels → suppress fd 2.
     */
    public static void applyLlamaLogLevel(String levelStr) {
        // Suppress native fd 2 for everything except DEBUG
        if ("DEBUG".equalsIgnoreCase(levelStr)) {
            StderrSuppressor.restore();
        } else {
            StderrSuppressor.suppress();
        }

        // Filter inside the native Java callback
        LocalNativeProvider.setMinLogLevel(levelStr);

        // Set Logback level for the SLF4J "llama.cpp" logger
        ch.qos.logback.classic.Logger llamaLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("llama.cpp");
        ch.qos.logback.classic.Level level = "OFF".equalsIgnoreCase(levelStr)
                ? ch.qos.logback.classic.Level.OFF
                : ch.qos.logback.classic.Level.toLevel(levelStr, ch.qos.logback.classic.Level.WARN);
        llamaLogger.setLevel(level);
    }

    @Override
    public void stop() {
        LLMProviderFactory.closeAll();
        EmbeddingProviderFactory.closeAll();
        DatabaseManager.getInstance().close();
    }

    /**
     * Builds the application icon programmatically — a rounded square with
     * Nord accent blue background and bold "AI" text in white.
     */
    private static WritableImage buildAppIcon(int size) {
        Canvas c = new Canvas(size, size);
        GraphicsContext g = c.getGraphicsContext2D();

        // Rounded square — Nord blue (#5E81AC)
        double arc = size * 0.22;
        g.setFill(Color.web("#5E81AC"));
        g.fillRoundRect(0, 0, size, size, arc, arc);

        // Inner highlight ring
        g.setStroke(Color.web("#81A1C1"));
        g.setLineWidth(size * 0.03);
        g.strokeRoundRect(size * 0.07, size * 0.07, size * 0.86, size * 0.86, arc * 0.7, arc * 0.7);

        // "AI" bold text
        g.setFill(Color.WHITE);
        g.setFont(Font.font("System", FontWeight.BOLD, size * 0.42));
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.CENTER);
        g.fillText("AI", size / 2.0, size / 2.0 + size * 0.02);

        SnapshotParameters sp = new SnapshotParameters();
        sp.setFill(Color.TRANSPARENT);
        return c.snapshot(sp, null);
    }
}
