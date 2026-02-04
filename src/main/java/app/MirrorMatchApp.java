package app;

import javax.swing.*;
import javax.swing.BorderFactory;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoManager;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.event.ActionEvent;
import java.awt.event.AdjustmentEvent;
import java.awt.event.AdjustmentListener;
import java.awt.event.KeyEvent;
import java.awt.Toolkit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;

import app.FileContentLoader;

import static app.DiffEngine.*;

public class MirrorMatchApp extends JFrame {

    private final EditorPane left = new EditorPane("Left");
    private final EditorPane right = new EditorPane("Right");

    private final JTextArea leftDiffView = createDiffView();
    private final JTextArea rightDiffView = createDiffView();

    private final java.util.List<Object> focusLeftTags = new java.util.ArrayList<>();
    private final java.util.List<Object> focusRightTags = new java.util.ArrayList<>();
    private final Highlighter.HighlightPainter focusPainter =
            new UnderlineHatchPainter(new Color(120, 160, 255, 120));
    private final Highlighter.HighlightPainter inlinePainter =
            new DefaultHighlighter.DefaultHighlightPainter(new Color(200, 215, 255, 150));
    private final Highlighter.HighlightPainter deletePainter =
            new DefaultHighlighter.DefaultHighlightPainter(new Color(255, 232, 232));
    private final Highlighter.HighlightPainter insertPainter =
            new DefaultHighlighter.DefaultHighlightPainter(new Color(230, 245, 230));
    private final Highlighter.HighlightPainter changeLeftPainter =
            new DefaultHighlighter.DefaultHighlightPainter(new Color(255, 242, 217));
    private final Highlighter.HighlightPainter changeRightPainter =
            new DefaultHighlighter.DefaultHighlightPainter(new Color(220, 236, 255));
    private final ThemeManager themeManager = new ThemeManager();

    private final JLabel status = new JLabel("Ready.");
    private final JCheckBox syncScroll = new JCheckBox("Sync scroll", true);
    private final JToggleButton diffOnlyToggle = new JToggleButton("Diff only");
    private final JToggleButton chunkModeToggle = new JToggleButton("Chunk copy");
    private final JToggleButton ignoreWsToggle = new JToggleButton("Ignore whitespace");
    private final JToggleButton autoSaveToggle = new JToggleButton("Auto-save");

    private final JButton prevBtn = new JButton("◀ Prev");
    private final JButton nextBtn = new JButton("Next ▶");
    private final JButton copyL2R = new JButton("Copy ▶");
    private final JButton copyR2L = new JButton("◀ Copy");
    private final JButton delLeft = new JButton("Delete L");
    private final JButton delRight = new JButton("Delete R");
    private final JButton undoLeft = new JButton("Undo L");
    private final JButton undoRight = new JButton("Undo R");
    private final JButton undoAny = new JButton("Undo (⌘/Ctrl+Z)");

    private final DiffSession session = new DiffSession();

    private SwingWorker<DiffEngine.Result, Void> diffWorker;
    private volatile boolean suppressDocEvents = false;
    private Timer debounceTimer;

    private boolean suppressUndoCapture = false;
    private final javax.swing.event.UndoableEditListener leftRecorder = e -> { if (!suppressUndoCapture) recordEdit(true); };
    private final javax.swing.event.UndoableEditListener rightRecorder = e -> { if (!suppressUndoCapture) recordEdit(false); };
    private final Deque<Boolean> undoStack = new ArrayDeque<>();
    private boolean collapsedMode = false;

    private final CenterDiffGutter centerGutter;

    private final CardLayout centerCards = new CardLayout();
    private final JPanel centerDeck = new JPanel(centerCards);

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            setSystemLookAndFeel();
            new MirrorMatchApp().setVisible(true);
        });
    }

    public MirrorMatchApp() {
        super("MirrorMatch");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1100, 700));
        getContentPane().setBackground(new Color(242, 244, 248));

        setJMenuBar(createMenuBar());

        centerGutter = new CenterDiffGutter(left.area(), right.area());

        JScrollPane leftScroll = left.scroll();
        JScrollPane rightScroll = right.scroll();

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                left.view(), right.view());
        split.setResizeWeight(0.5);
        split.setDividerSize(48);
        attachCenterGutter(split, centerGutter);

        JScrollPane leftDiffScroll = createScroll(leftDiffView);
        JScrollPane rightDiffScroll = createScroll(rightDiffView);
        leftDiffScroll.setRowHeaderView(new LineNumberGutter(leftDiffView));
        rightDiffScroll.setRowHeaderView(new LineNumberGutter(rightDiffView));
        JSplitPane diffSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                wrapEditor(leftDiffScroll, createHeaderLabel("Changes (L)"), "Changes (L)"),
                wrapEditor(rightDiffScroll, createHeaderLabel("Changes (R)"), "Changes (R)"));
        diffSplit.setResizeWeight(0.5);
        diffSplit.setDividerSize(12);
        diffSplit.setEnabled(false);

        centerDeck.add(split, "main");
        centerDeck.add(diffSplit, "diff");
        centerCards.show(centerDeck, "main");

        JToolBar tools = new JToolBar();
        tools.setFloatable(false);
        tools.setBorder(new EmptyBorder(8, 12, 8, 12));
        tools.setBackground(new Color(245, 247, 252));
        tools.add(prevBtn);
        tools.add(nextBtn);
        tools.addSeparator();
        tools.add(copyL2R);
        tools.add(copyR2L);
        tools.add(delLeft);
        tools.add(delRight);
        tools.addSeparator();
        tools.add(syncScroll);
        tools.add(diffOnlyToggle);
        tools.add(chunkModeToggle);
        tools.add(ignoreWsToggle);
        tools.addSeparator();
        tools.add(autoSaveToggle);
        tools.add(undoAny);
        tools.add(undoLeft);
        tools.add(undoRight);

        JPanel statusBar = new JPanel(new BorderLayout());
        status.setBorder(new EmptyBorder(4, 8, 4, 8));
        statusBar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(230, 232, 238)));
        statusBar.setBackground(new Color(248, 248, 251));
        statusBar.add(status, BorderLayout.CENTER);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(tools, BorderLayout.NORTH);
        getContentPane().add(centerDeck, BorderLayout.CENTER);
        getContentPane().add(statusBar, BorderLayout.SOUTH);

        addEditorListeners();
        addScrollSync(leftScroll, rightScroll);
        addGutterRepaintOnScroll(leftScroll, rightScroll);

        prevBtn.addActionListener(e -> gotoDiff(session.currentIndex() - 1));
        nextBtn.addActionListener(e -> gotoDiff(session.currentIndex() + 1));
        copyL2R.addActionListener(e -> applyCopy(true));
        copyR2L.addActionListener(e -> applyCopy(false));
        delLeft.addActionListener(e -> applyDelete(true));
        delRight.addActionListener(e -> applyDelete(false));
        undoLeft.addActionListener(e -> undoSide(true));
        undoRight.addActionListener(e -> undoSide(false));
        undoAny.addActionListener(e -> undoLastAnySide());
        diffOnlyToggle.addActionListener(e -> toggleDiffOnlyView());
        ignoreWsToggle.addActionListener(e -> recompute());

        centerGutter.setHandler(new CenterDiffGutter.ArrowHandler() {
            @Override public void onCopyLeftToRight(int lineIndex, boolean bulk) {
                handleArrowCopy(true, lineIndex, bulk);
            }
            @Override public void onCopyRightToLeft(int lineIndex, boolean bulk) {
                handleArrowCopy(false, lineIndex, bulk);
            }
            @Override public boolean isBulkMode() {
                return chunkModeToggle.isSelected();
            }
        });

        // drag & drop – one handler, side decided by where you drop
        FileDropHandler fileDropHandler = new FileDropHandler(this);
        leftScroll.setTransferHandler(fileDropHandler);
        leftScroll.getViewport().setTransferHandler(fileDropHandler);
        left.area().setTransferHandler(fileDropHandler);

        rightScroll.setTransferHandler(fileDropHandler);
        rightScroll.getViewport().setTransferHandler(fileDropHandler);
        right.area().setTransferHandler(fileDropHandler);

        getRootPane().setTransferHandler(fileDropHandler);
        getGlassPane().setVisible(false); // keep glass pane hidden to preserve text selection
        ((JComponent) getGlassPane()).setTransferHandler(null);

        // avoid conflict with default text drop
        left.area().setDropTarget(null);
        right.area().setDropTarget(null);

        pack();
        setLocationRelativeTo(null);

        loadSampleDefaults();
        themeManager.apply(left, right, leftDiffView, rightDiffView, getContentPane());
        refreshHeaders();

    }

    private static JTextArea createDiffView() {
        JTextArea area = new JTextArea();
        area.setTabSize(4);
        area.setLineWrap(false);
        area.setMargin(new Insets(8, 10, 8, 10));
        area.setBackground(new Color(250, 251, 254));
        area.setBorder(BorderFactory.createEmptyBorder());
        return area;
    }

    private static JScrollPane createScroll(JTextArea area) {
        JScrollPane sp = new JScrollPane(area);
        sp.setBorder(new EmptyBorder(4, 4, 4, 4));
        sp.getViewport().setBackground(area.getBackground());
        return sp;
    }

    private JPanel wrapEditor(JScrollPane scroll, JLabel header, String fallbackTitle) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(
                new EmptyBorder(6, 6, 6, 6),
                BorderFactory.createLineBorder(new Color(230, 232, 238), 1, true)
        ));

        header.setText("  " + fallbackTitle.toUpperCase());

        panel.add(header, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        panel.setBackground(new Color(245, 247, 252));
        return panel;
    }

    private JLabel createHeaderLabel(String fallbackTitle) {
        JLabel header = new JLabel("  " + fallbackTitle.toUpperCase());
        header.setFont(header.getFont().deriveFont(Font.BOLD, 12f));
        header.setForeground(new Color(90, 95, 115));
        header.setBorder(new EmptyBorder(6, 4, 4, 4));
        header.setHorizontalAlignment(SwingConstants.LEFT);
        header.setIcon(iconForPath(null));
        return header;
    }

    private void refreshHeaders() {
        left.refreshHeader();
        right.refreshHeader();
    }

    private Icon iconForPath(Path path) {
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

    private Icon letterIcon(char letter, Color bg, Color fg) {
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

    private void attachCenterGutter(JSplitPane split, JComponent gutter) {
        gutter.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        SwingUtilities.invokeLater(() -> {
            if (split.getUI() instanceof BasicSplitPaneUI ui) {
                BasicSplitPaneDivider div = ui.getDivider();
                div.setLayout(new BorderLayout());
                div.removeAll();
                div.add(gutter, BorderLayout.CENTER);
                div.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 1, new Color(225, 227, 234)));
                div.setBackground(new Color(245, 247, 252));
            }
        });
    }

    private static void setSystemLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}
    }

    private JMenuBar createMenuBar() {
        JMenuBar mb = new JMenuBar();
        JMenu file = new JMenu("File");

        JMenuItem openLeft = new JMenuItem("Open Left…");
        JMenuItem openRight = new JMenuItem("Open Right…");
        JMenuItem saveLeft = new JMenuItem("Save Left");
        JMenuItem saveRight = new JMenuItem("Save Right");
        JMenuItem saveLeftAs = new JMenuItem("Save Left As…");
        JMenuItem saveRightAs = new JMenuItem("Save Right As…");
        JMenuItem exit = new JMenuItem("Exit");

        openLeft.addActionListener(e -> openInto(left.area(), true));
        openRight.addActionListener(e -> openInto(right.area(), false));
        saveLeft.addActionListener(e -> saveFrom(left.area(), true, false));
        saveRight.addActionListener(e -> saveFrom(right.area(), false, false));
        saveLeftAs.addActionListener(e -> saveFrom(left.area(), true, true));
        saveRightAs.addActionListener(e -> saveFrom(right.area(), false, true));
        exit.addActionListener(e -> dispose());

        file.add(openLeft);
        file.add(openRight);
        file.addSeparator();
        file.add(saveLeft);
        file.add(saveRight);
        file.add(saveLeftAs);
        file.add(saveRightAs);
        file.addSeparator();
        file.add(exit);

        JMenu view = new JMenu("View");
        JMenuItem recomputeItem = new JMenuItem("Recompute Diff");
        recomputeItem.addActionListener(e -> recompute());
        view.add(recomputeItem);
        JMenuItem prefsItem = new JMenuItem("Preferences…");
        prefsItem.addActionListener(e -> {
            PreferencesDialog.Result res = PreferencesDialog.show(this, themeManager.fontSize(), themeManager.themeName());
            if (res != null) {
                themeManager.setFontSize(res.fontSize());
                themeManager.setThemeName(res.themeName());
                themeManager.apply(left, right, leftDiffView, rightDiffView, getContentPane());
            }
        });
        view.addSeparator();
        view.add(prefsItem);

        mb.add(file);
        mb.add(view);
        return mb;
    }

    private void openInto(JTextArea area, boolean left) {
        JFileChooser fc = new JFileChooser();
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            Path p = fc.getSelectedFile().toPath();
            try {
                FileContentLoader.LoadedContent payload = FileContentLoader.load(p);
                area.setText(payload.text());
                resetUndoHistory(java.util.EnumSet.of(left ? EditorSide.LEFT : EditorSide.RIGHT));
                EditorPane pane = left ? this.left : this.right;
                pane.setPath(p);
                pane.setLastSaved(payload.text());
                setStatus("Opened " + (left ? "LEFT" : "RIGHT") + ": " + p);
                refreshHeaders();
                recompute();
            } catch (IOException ex) {
                error("Failed to read: " + p + " – " + ex.getMessage());
            }
        }
    }

    private void saveFrom(JTextArea area, boolean left, boolean forceAs) {
        try {
            EditorPane pane = left ? this.left : this.right;
            Path target = pane.path();
            if (forceAs || target == null) {
                JFileChooser fc = new JFileChooser();
                if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
                target = fc.getSelectedFile().toPath();
                pane.setPath(target);
            }
            Files.writeString(target, area.getText());
            pane.setLastSaved(area.getText());
            refreshHeaders();
            setStatus("Saved " + (left ? "LEFT" : "RIGHT") + " → " + target);
        } catch (IOException ex) {
            error("Save failed: " + ex.getMessage());
        }
    }

    private void recompute() {
        startDiffInBackground();
    }

    private void toggleDiffOnlyView() {
        boolean on = diffOnlyToggle.isSelected();
        if (on) {
            enterCollapsedMode();
            setStatus("Diff only: editing differences.");
        } else {
            exitCollapsedMode();
            setStatus("Back to full view.");
        }
    }

    private List<String> fullLeftBackup = List.of();
    private List<String> fullRightBackup = List.of();
    private List<Hunk> collapsedHunks = List.of();

    private void enterCollapsedMode() {
        if (collapsedMode) return;
        if (session.changes().isEmpty()) {
            setStatus("No differences to collapse.");
            diffOnlyToggle.setSelected(false);
            return;
        }
        fullLeftBackup = List.of(left.area().getText().split("\n", -1));
        fullRightBackup = List.of(right.area().getText().split("\n", -1));
        collapsedHunks = new ArrayList<>(session.changes());

        collapseIntoEditors(true);
        collapseIntoEditors(false);

        collapsedMode = true;
        centerGutter.setVisible(true);
        session.updateTexts(left.area().getText(), right.area().getText());
        session.updateDiff(session.currentDiff()); // refresh highlights on collapsed content
        refreshHighlights();
        centerGutter.rebuildMarkers(session.changes());
    }

    private void exitCollapsedMode() {
        if (!collapsedMode) return;
        collapsedMode = false;
        suppressDocEvents = true;
        try {
            left.area().setText(String.join("\n", fullLeftBackup));
            right.area().setText(String.join("\n", fullRightBackup));
        } finally {
            suppressDocEvents = false;
        }
        DiffEngine.Result diff = DiffEngine.diffLines(left.area().getText(), right.area().getText());
        session.updateDiff(diff);
        session.updateTexts(left.area().getText(), right.area().getText());
        refreshHighlights();
        centerGutter.rebuildMarkers(session.currentDiff().hunks);
    }

    private void collapseIntoEditors(boolean leftSide) {
        JTextArea area = leftSide ? left.area() : right.area();
        List<String> base = leftSide ? fullLeftBackup : fullRightBackup;
        StringBuilder sb = new StringBuilder();
        for (Hunk h : collapsedHunks) {
            if (h.type() == HunkType.EQUAL) continue;
            int start = leftSide ? h.leftStart() : h.rightStart();
            int end = leftSide ? h.leftEnd() : h.rightEnd();
            for (int i = start; i < end; i++) {
                sb.append(i < base.size() ? base.get(i) : "").append("\n");
            }
        }
        suppressDocEvents = true;
        try {
            area.setText(sb.toString());
        } finally {
            suppressDocEvents = false;
        }
    }



    private void refreshHighlights() {
        clearFocusHighlights();
        left.clearHighlights();
        right.clearHighlights();

        int i = 0;
        int n = session.currentDiff().hunks.size();
        while (i < n) {
            Hunk h = session.currentDiff().hunks.get(i);
            if (h.type() == HunkType.DELETE) {
                left.highlightLines(deletePainter, h.leftStart(), h.leftEnd());
            } else if (h.type() == HunkType.INSERT) {
                right.highlightLines(insertPainter, h.rightStart(), h.rightEnd());
            } else if (h.type() == HunkType.CHANGE) {
                left.highlightLines(changeLeftPainter, h.leftStart(), h.leftEnd());
                right.highlightLines(changeRightPainter, h.rightStart(), h.rightEnd());
            }
            i = i + 1;
        }
    }

    private void addInlineHighlights() {
        int i = 0;
        int n = session.currentDiff().hunks.size();
        while (i < n) {
            Hunk h = session.currentDiff().hunks.get(i);
            if (h.type() == HunkType.CHANGE) {
                int pairCount = Math.min(h.leftEnd() - h.leftStart(), h.rightEnd() - h.rightStart());
                int j = 0;
                while (j < pairCount) {
                    String l = session.safeLine(true, h.leftStart() + j);
                    String r = session.safeLine(false, h.rightStart() + j);
                    DiffSession.InlineSpan span = session.computeInlineSpan(l, r);
                    if (span != null) {
                        highlightWord(left.area(), h.leftStart() + j, span.start(), span.endLeft(), inlinePainter);
                        highlightWord(right.area(), h.rightStart() + j, span.start(), span.endRight(), inlinePainter);
                    }
                    j = j + 1;
                }
            }
            i = i + 1;
        }
    }

    private void highlightWord(JTextArea area, int lineIndex, int colStart, int colEnd, Highlighter.HighlightPainter painter) {
        try {
            int lineOffset = area.getLineStartOffset(Math.max(0, Math.min(lineIndex, area.getLineCount() - 1)));
            int lineLen = Math.max(0, session.safeLine(area == left.area(), lineIndex).length());
            int s = lineOffset + Math.max(0, Math.min(colStart, lineLen));
            int e = lineOffset + Math.max(0, Math.min(colEnd, lineLen));
            if (e > s) {
                area.getHighlighter().addHighlight(s, e, painter);
            }
        } catch (Exception ignored) {}
    }

    private void refreshDiffOnlyView() {
        if (!diffOnlyToggle.isSelected() && leftDiffView.getText().isEmpty()) return;
        String leftText = session.buildDiffOnlyText(true);
        String rightText = session.buildDiffOnlyText(false);
        leftDiffView.setText(leftText);
        rightDiffView.setText(rightText);
        leftDiffView.setCaretPosition(0);
        rightDiffView.setCaretPosition(0);
    }
    private void highlightCurrentHunk(Hunk h) {
        if (h == null) return;
        try {
            javax.swing.text.Element rootL = left.area().getDocument().getDefaultRootElement();
            javax.swing.text.Element rootR = right.area().getDocument().getDefaultRootElement();
            clearFocusHighlights();

            if (h.type() == HunkType.DELETE || h.type() == HunkType.CHANGE) {
                addFocusLines(left.highlighter(), focusLeftTags, rootL, left.area(), h.leftStart(), h.leftEnd());
                left.gutter().setFocusLines(java.util.List.of(clampLine(h.leftStart(), rootL)));
            } else {
                left.gutter().setFocusLines(java.util.List.of());
            }
            if (h.type() == HunkType.INSERT || h.type() == HunkType.CHANGE) {
                addFocusLines(right.highlighter(), focusRightTags, rootR, right.area(), h.rightStart(), h.rightEnd());
                right.gutter().setFocusLines(java.util.List.of(clampLine(h.rightStart(), rootR)));
            } else {
                right.gutter().setFocusLines(java.util.List.of());
            }
            if (h.type() == HunkType.CHANGE) {
                left.gutter().setFocusLines(java.util.List.of(clampLine(h.leftStart(), rootL)));
                right.gutter().setFocusLines(java.util.List.of(clampLine(h.rightStart(), rootR)));
            }
        } catch (Exception ignored) {}
        left.area().repaint();
        right.area().repaint();
        centerGutter.repaint();
    }

    private int clampLine(int idx, javax.swing.text.Element root) {
        int lc = root.getElementCount();
        if (lc <= 0) return 0;
        return Math.max(0, Math.min(idx, lc - 1));
    }

    private void addFocusLines(Highlighter hl, java.util.List<Object> bag,
                               javax.swing.text.Element root, JTextArea area,
                               int startLine, int endLineExclusive) throws BadLocationException {
        int lc = root.getElementCount();
        if (lc <= 0) return;
        int s = clampLine(startLine, root);
        int e = clampLine(Math.max(startLine, endLineExclusive - 1), root);
        int line = s;
        while (line <= e) {
            int[] offs = lineOffsets(root, area, line);
            Object tag = hl.addHighlight(offs[0], offs[1], focusPainter);
            bag.add(tag);
            line = line + 1;
        }
    }

    private int[] lineOffsets(javax.swing.text.Element root, JTextArea area, int line) throws BadLocationException {
        int start = root.getElement(line).getStartOffset();
        int endExclusive;
        if (line + 1 < root.getElementCount()) {
            endExclusive = root.getElement(line + 1).getStartOffset();
        } else {
            endExclusive = area.getDocument().getLength();
        }
        if (endExclusive <= start) endExclusive = Math.min(area.getDocument().getLength(), start + 1);
        return new int[]{start, endExclusive};
    }

    private void clearFocusHighlights() {
        removeTags(left.highlighter(), focusLeftTags);
        removeTags(right.highlighter(), focusRightTags);
        focusLeftTags.clear();
        focusRightTags.clear();
    }

    private void removeTags(Highlighter hl, java.util.List<Object> tags) {
        int i = 0;
        int n = tags.size();
        while (i < n) {
            try {
                hl.removeHighlight(tags.get(i));
            } catch (Exception ignored) {}
            i = i + 1;
        }
    }

    /** Painter that draws a semi-transparent hatch to sit atop existing diff colors. */
    private static class UnderlineHatchPainter implements Highlighter.HighlightPainter {
        private final Color fill;
        UnderlineHatchPainter(Color fill) { this.fill = fill; }

        @Override public void paint(Graphics g, int p0, int p1, Shape bounds, JTextComponent c) {
            try {
                int docLen = c.getDocument().getLength();
                int endOffset = Math.max(p0, Math.min(p1 - 1, Math.max(0, docLen - 1)));
                Rectangle r0 = c.modelToView2D(p0).getBounds();
                Rectangle r1 = c.modelToView2D(endOffset).getBounds();
                int y = r0.y;
                int h = r0.height;
                int x = r0.x;
                int w = Math.max(4, (r1.x + r1.width) - r0.x);

                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setClip(x, y, w, h);
                    g2.setColor(fill);
                    g2.fillRect(x, y, w, h);
                    g2.setStroke(new BasicStroke(1f));
                    g2.setColor(new Color(60, 90, 160, 200));
                    float dash = 6f;
                    g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{dash, dash}, 0f));
                    g2.drawLine(x, y + h - 3, x + w, y + h - 3);
                } finally {
                    g2.dispose();
                }
            } catch (BadLocationException ignored) {}
        }
    }

    private void gotoDiff(int newIndex) {
        java.util.List<Hunk> changes = session.changes();
        if (changes.isEmpty()) return;
        int maxIndex = changes.size() - 1;
        session.setCurrentIndex(Math.max(0, Math.min(newIndex, maxIndex)));
        Hunk h = changes.get(session.currentIndex());
        try {
            int lStart = left.area().getLineStartOffset(
                    Math.min(h.leftStart(), Math.max(0, left.area().getLineCount() - 1)));
            int rStart = right.area().getLineStartOffset(
                    Math.min(h.rightStart(), Math.max(0, right.area().getLineCount() - 1)));
            left.area().setCaretPosition(lStart);
            right.area().setCaretPosition(rStart);
            left.area().scrollRectToVisible(left.area().modelToView2D(lStart).getBounds());
            right.area().scrollRectToVisible(right.area().modelToView2D(rStart).getBounds());
        } catch (BadLocationException ignored) {}
        highlightCurrentHunk(h);
        updateNavButtons();
        setStatus("Diff " + (session.currentIndex() + 1) + " / " + changes.size());
    }

    private void updateNavButtons() {
        java.util.List<Hunk> changes = session.changes();
        boolean has = !changes.isEmpty();
        prevBtn.setEnabled(has && session.currentIndex() > 0);
        nextBtn.setEnabled(has && session.currentIndex() < changes.size() - 1);
        copyL2R.setEnabled(has);
        copyR2L.setEnabled(has);
        delLeft.setEnabled(has);
        delRight.setEnabled(has);
    }

    private void applyCopy(boolean leftToRight) {
        java.util.List<Hunk> changes = session.changes();
        if (session.currentIndex() < 0 || session.currentIndex() >= changes.size()) return;
        Hunk h = changes.get(session.currentIndex());
        if (leftToRight) {
            List<String> src = session.leftLines().subList(h.leftStart(), h.leftEnd());
            replaceLines(right.area(), h.rightStart(), h.rightEnd(), src);
        } else {
            List<String> src = session.rightLines().subList(h.rightStart(), h.rightEnd());
            replaceLines(left.area(), h.leftStart(), h.leftEnd(), src);
        }
        recompute();
    }

    private void applyDelete(boolean onLeft) {
        java.util.List<Hunk> changes = session.changes();
        if (session.currentIndex() < 0 || session.currentIndex() >= changes.size()) return;
        Hunk h = changes.get(session.currentIndex());
        if (onLeft) {
            replaceLines(left.area(), h.leftStart(), h.leftEnd(), List.of());
        } else {
            replaceLines(right.area(), h.rightStart(), h.rightEnd(), List.of());
        }
        recompute();
    }

    private void handleArrowCopy(boolean leftToRight, int lineIndex, boolean bulk) {
        java.util.List<Hunk> changes = session.changes();
        Hunk h = findHunkForLine(leftToRight, lineIndex);
        if (h == null) return;
        int idx = changes.indexOf(h);
        if (idx >= 0) {
            session.setCurrentIndex(idx);
            updateNavButtons();
        }
        if (bulk) {
            applyCopy(leftToRight);
            return;
        }
        copySingleLine(leftToRight, h, lineIndex);
        recompute();
    }

    private Hunk findHunkForLine(boolean usingLeftSide, int lineIndex) {
        return session.findHunkForLine(usingLeftSide, lineIndex);
    }

    private void copySingleLine(boolean leftToRight, Hunk h, int lineIndex) {
        List<String> srcLines = leftToRight ? session.leftLines() : session.rightLines();
        JTextArea target = leftToRight ? right.area() : left.area();

        int srcStart = leftToRight ? h.leftStart() : h.rightStart();
        int dstStart = leftToRight ? h.rightStart() : h.leftStart();

        int offset = Math.max(0, lineIndex - srcStart);
        int destLine = dstStart + offset;
        List<String> payload = List.of(srcLines.get(Math.max(0, Math.min(lineIndex, srcLines.size() - 1))));

        replaceLines(target, destLine, destLine < target.getLineCount() ? destLine + 1 : destLine, payload);
    }

    private void replaceLines(JTextArea area, int startLine, int endLine, List<String> with) {
        try {
            suppressDocEvents = true;
            boolean onLeft = area == left.area();
            int lineCount = area.getLineCount();
            int startOffset;
            if (lineCount <= 0) {
                startOffset = 0;
            } else if (startLine >= lineCount) {
                startOffset = area.getDocument().getLength();
            } else {
                startOffset = area.getLineStartOffset(Math.max(0, startLine));
            }
            int endOffset;
            if (endLine <= 0) {
                endOffset = startOffset;
            } else if (endLine >= lineCount) {
                endOffset = area.getDocument().getLength();
            } else {
                endOffset = area.getLineStartOffset(endLine);
            }
            String replacement = String.join("\n", with);
            applyWithCustomUndo(area, startOffset, endOffset, replacement, onLeft);
        } catch (Exception ex) {
            error("Edit failed: " + ex.getMessage());
        } finally {
            suppressDocEvents = false;
        }
    }

    private void addEditorListeners() {
        DocumentListener dl = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) {
                if (!suppressDocEvents) delayedRecompute();
            }
            @Override public void removeUpdate(DocumentEvent e) {
                if (!suppressDocEvents) delayedRecompute();
            }
            @Override public void changedUpdate(DocumentEvent e) {
                if (!suppressDocEvents) delayedRecompute();
            }
        };
        left.area().getDocument().addDocumentListener(dl);
        right.area().getDocument().addDocumentListener(dl);

        left.area().getDocument().addUndoableEditListener(left.undoManager());
        right.area().getDocument().addUndoableEditListener(right.undoManager());
        left.area().getDocument().addUndoableEditListener(leftRecorder);
        right.area().getDocument().addUndoableEditListener(rightRecorder);

        installUndoShortcut(left.area());
        installUndoShortcut(right.area());
        installUndoShortcut(leftDiffView);
        installUndoShortcut(rightDiffView);
        installSaveShortcut(left.area(), true);
        installSaveShortcut(right.area(), false);
    }

    private void delayedRecompute() {
        if (debounceTimer != null && debounceTimer.isRunning()) {
            debounceTimer.stop();
        }
        debounceTimer = new Timer(600, e -> startDiffInBackground());
        debounceTimer.setRepeats(false);
        debounceTimer.start();
    }

    private void addScrollSync(JScrollPane leftScroll, JScrollPane rightScroll) {
        AdjustmentListener sync = new AdjustmentListener() {
            boolean internal = false;
            long last = 0L;
            @Override public void adjustmentValueChanged(AdjustmentEvent e) {
                if (!syncScroll.isSelected()) return;
                if (internal) return;
                if (e.getValueIsAdjusting()) return;
                long now = System.nanoTime();
                if (now - last < 8000000L) return;
                last = now;
                internal = true;
                JScrollBar src = (JScrollBar) e.getAdjustable();
                JScrollBar dst = (src == leftScroll.getVerticalScrollBar())
                        ? rightScroll.getVerticalScrollBar()
                        : leftScroll.getVerticalScrollBar();
                float ratio = (float) src.getValue()
                        / Math.max(1, src.getMaximum() - src.getVisibleAmount());
                int newVal = Math.round(ratio * (dst.getMaximum() - dst.getVisibleAmount()));
                dst.setValue(Math.max(0, Math.min(newVal, dst.getMaximum())));
                internal = false;
            }
        };
        leftScroll.getVerticalScrollBar().addAdjustmentListener(sync);
        rightScroll.getVerticalScrollBar().addAdjustmentListener(sync);
    }

    private void addGutterRepaintOnScroll(JScrollPane leftScroll, JScrollPane rightScroll) {
        AdjustmentListener repaint = e -> centerGutter.repaint();
        leftScroll.getVerticalScrollBar().addAdjustmentListener(repaint);
        rightScroll.getVerticalScrollBar().addAdjustmentListener(repaint);
    }

    private void installUndoShortcut(JComponent c) {
        int mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx(); // Cmd on macOS, Ctrl elsewhere
        KeyStroke undoStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z, mask);
        c.getInputMap(JComponent.WHEN_FOCUSED).put(undoStroke, "undo-global");
        c.getActionMap().put("undo-global", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                undoLastAnySide();
            }
        });
    }

    private void installSaveShortcut(JComponent c, boolean forLeft) {
        int mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        KeyStroke saveStroke = KeyStroke.getKeyStroke(KeyEvent.VK_S, mask);
        c.getInputMap(JComponent.WHEN_FOCUSED).put(saveStroke, "save-side");
        c.getActionMap().put("save-side", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                saveFrom(forLeft ? left.area() : right.area(), forLeft, false);
            }
        });
    }

    private void recordEdit(boolean onLeft) {
        undoStack.addLast(onLeft);
    }

    private void autoSaveIfNeeded() {
        if (!autoSaveToggle.isSelected()) return;
        try {
            if (left.path() != null && !left.area().getText().equals(left.lastSaved())) {
                Files.writeString(left.path(), left.area().getText());
                left.setLastSaved(left.area().getText());
            }
            if (right.path() != null && !right.area().getText().equals(right.lastSaved())) {
                Files.writeString(right.path(), right.area().getText());
                right.setLastSaved(right.area().getText());
            }
        } catch (IOException ex) {
            setStatus("Auto-save failed: " + ex.getMessage());
        }
    }

    private Boolean peekLatestEdit() {
        return undoStack.peekLast();
    }

    private boolean removeLatestForSide(boolean leftSide) {
        Iterator<Boolean> it = undoStack.descendingIterator();
        while (it.hasNext()) {
            Boolean flag = it.next();
            if (flag != null && flag.booleanValue() == leftSide) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    private void resetUndoHistory(java.util.EnumSet<EditorSide> sides) {
        if (sides.contains(EditorSide.LEFT)) {
            left.undoManager().discardAllEdits();
            removeAllForSide(true);
        }
        if (sides.contains(EditorSide.RIGHT)) {
            right.undoManager().discardAllEdits();
            removeAllForSide(false);
        }
    }

    private void removeAllForSide(boolean leftSide) {
        Iterator<Boolean> it = undoStack.iterator();
        while (it.hasNext()) {
            Boolean flag = it.next();
            if (flag != null && flag.booleanValue() == leftSide) {
                it.remove();
            }
        }
    }

    private void startDiffInBackground() {
        final String leftText = left.area().getText();
        final String rightText = right.area().getText();
        final boolean ignoreWS = ignoreWsToggle.isSelected();

        if (leftText.length() > 2000000 || rightText.length() > 2000000) {
            setStatus("Large file mode: View → Recompute Diff");
            return;
        }

        if (diffWorker != null && !diffWorker.isDone()) {
            diffWorker.cancel(true);
        }

        setStatus("Computing diff…");
        diffWorker = new SwingWorker<>() {
            @Override protected DiffEngine.Result doInBackground() {
                java.util.function.Function<String, String> norm = ignoreWS
                        ? s -> s.replaceAll("\\s+", "")
                        : java.util.function.Function.identity();
                return DiffEngine.diffLinesNormalized(leftText, rightText, norm);
            }
            @Override protected void done() {
                if (isCancelled()) return;
                try {
                    DiffEngine.Result diff = get();
                    session.updateTexts(leftText, rightText);
                    session.updateDiff(diff);
                    java.util.List<Hunk> changes = session.changes();
                    if (changes.isEmpty()) {
                        session.setCurrentIndex(-1);
                    } else {
                        int idx = session.currentIndex();
                        if (idx < 0 || idx >= changes.size()) {
                            session.setCurrentIndex(0);
                        }
                    }
                    refreshHighlights();
                    addInlineHighlights();
                    if (session.currentIndex() >= 0) {
                        gotoDiff(session.currentIndex());
                    } else {
                        updateNavButtons();
                        setStatus("No differences.");
                    }
                    centerGutter.rebuildMarkers(session.currentDiff().hunks);
                    refreshDiffOnlyView();
                    autoSaveIfNeeded();
                } catch (Exception ignored) {}
            }
        };
        diffWorker.execute();
    }

    private void undoSide(boolean leftSide) {
        UndoManager target = leftSide ? left.undoManager() : right.undoManager();
        if (tryUndo(target)) {
            removeLatestForSide(leftSide);
            setStatus("Undo " + (leftSide ? "LEFT" : "RIGHT"));
        }
    }

    private void undoLastAnySide() {
        Boolean latest = peekLatestEdit();
        if (latest == null) {
            if (left.undoManager().canUndo()) {
                tryUndo(left.undoManager());
                setStatus("Undo LEFT");
            } else if (right.undoManager().canUndo()) {
                tryUndo(right.undoManager());
                setStatus("Undo RIGHT");
            }
            return;
        }
        boolean useLeft = latest;
        UndoManager target = useLeft ? left.undoManager() : right.undoManager();
        if (tryUndo(target)) {
            undoStack.pollLast();
            setStatus("Undo " + (useLeft ? "LEFT" : "RIGHT"));
        } else {
            // If the recorded side cannot undo, discard it and try the other side
            undoStack.pollLast();
            undoLastAnySide();
        }
    }

    private void loadSampleDefaults() {
        Path lp = Path.of("sample-left.txt");
        Path rp = Path.of("sample-right.txt");
        if (Files.exists(lp) && Files.exists(rp)) {
            try {
                String lt = Files.readString(lp);
                String rt = Files.readString(rp);
                left.area().setText(lt);
                right.area().setText(rt);
                left.setPath(lp);
                right.setPath(rp);
                left.setLastSaved(lt);
                right.setLastSaved(rt);
                resetUndoHistory(java.util.EnumSet.of(EditorSide.LEFT, EditorSide.RIGHT));
                refreshHeaders();
                recompute();
                setStatus("Loaded sample-left.txt and sample-right.txt");
            } catch (IOException ignored) {}
        }
    }

    private boolean tryUndo(UndoManager um) {
        try {
            if (um.canUndo()) {
                um.undo();
                return true;
            }
        } catch (CannotUndoException ignored) {}
        return false;
    }

    // === APIs used by FileDropHandler ===

    JTextArea getLeftArea() { return left.area(); }
    JTextArea getRightArea() { return right.area(); }

    void handleSingleDrop(boolean leftSide, Path p, FileContentLoader.LoadedContent payload) {
        String content = payload.text();
        if (leftSide) {
            left.area().setText(content);
            left.setPath(p);
            resetUndoHistory(java.util.EnumSet.of(EditorSide.LEFT));
            left.setLastSaved(content);
            refreshHeaders();
        } else {
            right.area().setText(content);
            right.setPath(p);
            resetUndoHistory(java.util.EnumSet.of(EditorSide.RIGHT));
            right.setLastSaved(content);
            refreshHeaders();
        }
        recompute();
    }

    void handleBothDrop(Path p1, FileContentLoader.LoadedContent t1, Path p2, FileContentLoader.LoadedContent t2) {
        left.area().setText(t1.text());
        right.area().setText(t2.text());
        left.setPath(p1);
        right.setPath(p2);
        resetUndoHistory(java.util.EnumSet.of(EditorSide.LEFT, EditorSide.RIGHT));
        left.setLastSaved(t1.text());
        right.setLastSaved(t2.text());
        refreshHeaders();
        recompute();
    }

    boolean isLeftEmpty() {
        return left.isEmpty();
    }

    void handleDropError(String msg) {
        error(msg);
    }

    private void setStatus(String s) {
        status.setText(s);
    }

    private void error(String s) {
        JOptionPane.showMessageDialog(this, s, "Error", JOptionPane.ERROR_MESSAGE);
        setStatus("Error: " + s);
    }

    private void applyWithCustomUndo(JTextArea area, int startOffset, int endOffset, String replacement, boolean onLeft) throws Exception {
        javax.swing.text.Document doc = area.getDocument();
        int len = Math.max(0, endOffset - startOffset);
        String prev = len > 0 ? doc.getText(startOffset, len) : "";
        String insertText = replacement.isEmpty() ? "" : replacement + "\n";

        suppressUndoCapture = true;
        try {
            if (onLeft) {
                doc.removeUndoableEditListener(left.undoManager());
                doc.removeUndoableEditListener(leftRecorder);
            } else {
                doc.removeUndoableEditListener(right.undoManager());
                doc.removeUndoableEditListener(rightRecorder);
            }

            doc.remove(startOffset, len);
            if (!insertText.isEmpty()) {
                doc.insertString(startOffset, insertText, null);
            }

            ReplaceEdit edit = new ReplaceEdit(
                    doc,
                    startOffset,
                    prev,
                    insertText,
                    getUndoManager(onLeft),
                    onLeft ? leftRecorder : rightRecorder,
                    this,
                    onLeft
            );
            getUndoManager(onLeft).addEdit(edit);
            undoStack.addLast(onLeft);
        } finally {
            if (onLeft) {
                doc.addUndoableEditListener(left.undoManager());
                doc.addUndoableEditListener(leftRecorder);
            } else {
                doc.addUndoableEditListener(right.undoManager());
                doc.addUndoableEditListener(rightRecorder);
            }
            suppressUndoCapture = false;
        }
    }

    private UndoManager getUndoManager(boolean leftSide) {
        return leftSide ? left.undoManager() : right.undoManager();
    }

    private static class ReplaceEdit extends javax.swing.undo.AbstractUndoableEdit {
        private final javax.swing.text.Document doc;
        private final int offset;
        private final String before;
        private final String after;
        private final int beforeLen;
        private final int afterLen;
        private final UndoManager um;
        private final javax.swing.event.UndoableEditListener recorder;
        private final MirrorMatchApp app;
        private final boolean onLeft;

        ReplaceEdit(javax.swing.text.Document doc, int offset, String before, String after,
                    UndoManager um, javax.swing.event.UndoableEditListener recorder,
                    MirrorMatchApp app, boolean onLeft) {
            this.doc = doc;
            this.offset = offset;
            this.before = before;
            this.after = after;
            this.beforeLen = before.length();
            this.afterLen = after.length();
            this.um = um;
            this.recorder = recorder;
            this.app = app;
            this.onLeft = onLeft;
        }

        @Override public void undo() throws javax.swing.undo.CannotUndoException {
            super.undo();
            apply(before, afterLen);
        }

        @Override public void redo() throws javax.swing.undo.CannotRedoException {
            super.redo();
            apply(after, beforeLen);
        }

        private void apply(String text, int removeLen) {
            try {
                app.suppressUndoCapture = true;
                doc.removeUndoableEditListener(um);
                doc.removeUndoableEditListener(recorder);

                doc.remove(offset, Math.max(0, removeLen));
                if (text != null && !text.isEmpty()) {
                    doc.insertString(offset, text, null);
                }
            } catch (Exception ignored) {
            } finally {
                doc.addUndoableEditListener(um);
                doc.addUndoableEditListener(recorder);
                app.suppressUndoCapture = false;
            }
        }
    }

}
