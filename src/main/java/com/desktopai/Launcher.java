package com.desktopai;

import javafx.application.Application;
import org.slf4j.bridge.SLF4JBridgeHandler;

public class Launcher {
    public static void main(String[] args) {
        // Redirect java.util.logging (used by JavaFX) into SLF4J/Logback
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
        Application.launch(DesktopAiApplication.class, args);
    }
}
