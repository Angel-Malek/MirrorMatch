package app;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.BadLocationException;
import javax.swing.text.Highlighter;
import javax.swing.undo.UndoManager;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class EditorPane {

    private final String fallbackTitle;

    private Path path;
    private String lastSaved = "";

    private final JTextArea area;
    private final JScrollPane scroll;
    private final LineNumberGutter gutter;
    private final JLabel header;
    private final JPanel view;

    private final Highlighter highlighter;
    private final List<Object> focusTags = new ArrayList<>();
    private final UndoManager undoManager = new UndoManager();

    public EditorPane(String fallbackTitle) {
        this.fallbackTitle = fallbackTitle;

        this.area = createEditor();
        this.highlighter = area.getHighlighter();

        this.gutter = new LineNumberGutter(area);

        this.scroll = new JScrollPane(area);
        this.scroll.setBorder(new EmptyBorder(4, 4, 4, 4));
        this.scroll.getViewport().setBackground(area.getBackground());
        this.scroll.setRowHeaderView(gutter);

        this.header = createHeaderLabel(fallbackTitle);

        this.view = new JPanel(new BorderLayout());
        this.view.setBorder(BorderFactory.createCompoundBorder(
                new EmptyBorder(6, 6, 6, 6),
                BorderFactory.createLineBorder(new Color(230, 232, 238), 1, true)
        ));
        this.view.setBackground(new Color(245, 247, 252));
        this.view.add(header, BorderLayout.NORTH);
        this.view.add(scroll, BorderLayout.CENTER);

        refreshHeader();
    }

    // ---- Public API ----

    public JComponent view() {
        return view;
    }

    public JTextArea area() {
        return area;
    }

    public JScrollPane scroll() {
        return scroll;
    }

    public LineNumberGutter gutter() {
        return gutter;
    }

    public Highlighter highlighter() {
        return highlighter;
    }
    public UndoManager undoManager() {
        return undoManager;
    }

    public Path path() {
        return path;
    }

    public void setPath(Path path) {
        this.path = path;
        refreshHeader();
    }

    public String lastSaved() {
        return lastSaved;
    }

    public void setLastSaved(String lastSaved) {
        this.lastSaved = lastSaved == null ? "" : lastSaved;
        refreshHeader();
    }

    public String text() {
        return area.getText();
    }

    public void setText(String text) {
        area.setText(text == null ? "" : text);
    }

    public boolean isEmpty() {
        return text().isEmpty();
    }

    public void clearHighlights() {
        highlighter.removeAllHighlights();
        focusTags.clear();
        gutter.setFocusLines(List.of());
    }

    /**
     * Highlight whole lines (inclusive start, exclusive end).
     */
    public void highlightLines(Highlighter.HighlightPainter painter, int lineStart, int lineEndExclusive) {
        try {
            javax.swing.text.Element root = area.getDocument().getDefaultRootElement();
            int lc = root.getElementCount();
            if (lc <= 0) return;

            int startLine = clamp(lineStart, 0, lc - 1);
            int endLine = clamp(Math.max(lineStart, lineEndExclusive) - 1, 0, lc - 1);

            int start = root.getElement(startLine).getStartOffset();
            int end = root.getElement(endLine).getEndOffset();
            highlighter.addHighlight(start, end, painter);
        } catch (BadLocationException ignored) {}
    }

    /**
     * Add a focus highlight line-by-line (used for "current hunk" emphasis).
     * Also updates gutter focus indicator.
     */
    public void focusLines(Highlighter.HighlightPainter painter, int startLine, int endLineExclusive) {
        try {
            javax.swing.text.Element root = area.getDocument().getDefaultRootElement();
            int lc = root.getElementCount();
            if (lc <= 0) return;

            int s = clamp(startLine, 0, lc - 1);
            int e = clamp(Math.max(startLine, endLineExclusive) - 1, 0, lc - 1);

            // clear previous focus tags
            int i = 0;
            int n = focusTags.size();
            while (i < n) {
                try { highlighter.removeHighlight(focusTags.get(i)); } catch (Exception ignored) {}
                i = i + 1;
            }
            focusTags.clear();

            List<Integer> focus = new ArrayList<>();
            int line = s;
            while (line <= e) {
                int start = root.getElement(line).getStartOffset();
                int end;
                if (line + 1 < lc) {
                    end = root.getElement(line + 1).getStartOffset();
                } else {
                    end = area.getDocument().getLength();
                }
                if (end <= start) end = Math.min(area.getDocument().getLength(), start + 1);

                Object tag = highlighter.addHighlight(start, end, painter);
                focusTags.add(tag);
                focus.add(line);
                line = line + 1;
            }
            gutter.setFocusLines(focus);
        } catch (Exception ignored) {}
    }

    public void applyFont(Font font) {
        if (font == null) return;
        area.setFont(font);
        gutter.setFont(font);
    }

    public void applyColors(Color editorBg, Color editorFg, Color gutterBg, Color gutterFg, Color panelBg) {
        if (editorBg != null) {
            area.setBackground(editorBg);
            scroll.getViewport().setBackground(editorBg);
        }
        if (editorFg != null) {
            area.setForeground(editorFg);
        }
        if (gutterBg != null) gutter.setBackground(gutterBg);
        if (gutterFg != null) gutter.setForeground(gutterFg);
        if (panelBg != null) view.setBackground(panelBg);
    }

    // ---- Header ----

    public void refreshHeader() {
        String name = path != null ? path.getFileName().toString() : fallbackTitle;
        header.setText("  " + name);
        header.setToolTipText(path != null ? path.toString() : null);
        header.setIcon(iconForPath(path));
    }

    private static JLabel createHeaderLabel(String fallbackTitle) {
        JLabel header = new JLabel("  " + fallbackTitle);
        header.setFont(header.getFont().deriveFont(Font.BOLD, 12f));
        header.setForeground(new Color(90, 95, 115));
        header.setBorder(new EmptyBorder(6, 4, 4, 4));
        header.setHorizontalAlignment(SwingConstants.LEFT);
        header.setIcon(UIManager.getIcon("FileView.fileIcon"));
        return header;
    }

    private static Icon iconForPath(Path path) {
        String name = path != null ? path.getFileName().toString().toLowerCase() : "";
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            return letterIcon('X', new Color(56, 142, 60), Color.WHITE);
        }
        if (name.endsWith(".doc") || name.endsWith(".docx")) {
            return letterIcon('W', new Color(33, 150, 243), Color.WHITE);
        }
        Icon sys = UIManager.getIcon("FileView.fileIcon");
        return sys != null ? sys : letterIcon('F', new Color(90, 95, 115), Color.WHITE);
    }

    private static Icon letterIcon(char letter, Color bg, Color fg) {
        int size = 16;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(bg);
            g.fillRoundRect(0, 0, size - 1, size - 1, 4, 4);
            g.setColor(new Color(0, 0, 0, 40));
            g.drawRoundRect(0, 0, size - 1, size - 1, 4, 4);

            g.setColor(fg);
            Font f = new Font("SansSerif", Font.BOLD, 11);
            g.setFont(f);
            FontMetrics fm = g.getFontMetrics();

            int x = (size - fm.charWidth(Character.toUpperCase(letter))) / 2;
            int y = (size + fm.getAscent() - fm.getDescent()) / 2;
            g.drawString(String.valueOf(Character.toUpperCase(letter)), x, y);
        } finally {
            g.dispose();
        }
        return new ImageIcon(img);
    }

    private static JTextArea createEditor() {
        JTextArea area = new JTextArea();
        area.setTabSize(4);
        area.setLineWrap(false);
        area.setMargin(new Insets(8, 10, 8, 10));
        area.setBackground(new Color(250, 251, 254));
        area.setBorder(BorderFactory.createEmptyBorder());
        return area;
    }

    private static int clamp(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }
}
