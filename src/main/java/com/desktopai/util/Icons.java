package com.desktopai.util;

import javafx.scene.paint.Color;
import org.kordamp.ikonli.javafx.FontIcon;

/** Thin factory so controllers can create FontIcons from string literals. */
public final class Icons {
    private Icons() {}

    public static FontIcon of(String literal, int size) {
        FontIcon fi = new FontIcon(literal);
        fi.setIconSize(size);
        return fi;
    }

    public static FontIcon of(String literal, int size, Color color) {
        FontIcon fi = new FontIcon(literal);
        fi.setIconSize(size);
        fi.setIconColor(color);
        return fi;
    }
}
