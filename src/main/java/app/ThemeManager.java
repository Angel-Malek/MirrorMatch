package app;

import javax.swing.*;
import java.awt.*;

/**
 * Centralizes theme + font handling for editors and diff views.
 */
public class ThemeManager {

    public record ThemePalette(Color editorBg, Color editorFg,
                               Color gutterBg, Color gutterFg,
                               Color panelBg) {}

    private int editorFontSize = 14;
    private String themeName = "Light";

    public int fontSize() { return editorFontSize; }
    public String themeName() { return themeName; }

    public void setFontSize(int size) { this.editorFontSize = size; }
    public void setThemeName(String name) { this.themeName = name; }

    public void apply(EditorPane left, EditorPane right,
                      JTextArea leftDiffView, JTextArea rightDiffView,
                      Container root) {
        applyFontSize(left, right, leftDiffView, rightDiffView);
        applyTheme(left, right, leftDiffView, rightDiffView, root);
    }

    public void applyFontSize(EditorPane left, EditorPane right,
                              JTextArea leftDiffView, JTextArea rightDiffView) {
        Font main = new Font(Font.MONOSPACED, Font.PLAIN, editorFontSize);
        left.applyFont(main);
        right.applyFont(main);
        Font diffFont = new Font("IBM Plex Mono", Font.PLAIN, Math.max(10, editorFontSize - 1));
        leftDiffView.setFont(diffFont);
        rightDiffView.setFont(diffFont);
    }

    public void applyTheme(EditorPane left, EditorPane right,
                           JTextArea leftDiffView, JTextArea rightDiffView,
                           Container root) {
        ThemePalette palette = paletteForName(themeName);

        left.applyColors(palette.editorBg(), palette.editorFg(), palette.gutterBg(), palette.gutterFg(), palette.panelBg());
        right.applyColors(palette.editorBg(), palette.editorFg(), palette.gutterBg(), palette.gutterFg(), palette.panelBg());
        applyAreaTheme(leftDiffView, palette.editorBg(), palette.editorFg());
        applyAreaTheme(rightDiffView, palette.editorBg(), palette.editorFg());

        if (root != null) {
            root.setBackground(palette.panelBg());
        }
    }

    public ThemePalette paletteForName(String theme) {
        boolean dark = "Soft Dark".equalsIgnoreCase(theme);
        Color bgEditor = dark ? new Color(28, 32, 38) : new Color(250, 251, 254);
        Color fgEditor = dark ? new Color(230, 232, 236) : Color.BLACK;
        Color gutterBg = dark ? new Color(38, 43, 50) : new Color(245, 245, 245);
        Color gutterFg = dark ? new Color(190, 195, 205) : new Color(120, 120, 120);
        Color panelBg = dark ? new Color(32, 36, 44) : new Color(245, 247, 252);
        return new ThemePalette(bgEditor, fgEditor, gutterBg, gutterFg, panelBg);
    }

    private void applyAreaTheme(JTextArea area, Color bg, Color fg) {
        area.setBackground(bg);
        area.setForeground(fg);
        area.getCaret().setVisible(true);
        area.getCaret().setBlinkRate(500);
    }
}
