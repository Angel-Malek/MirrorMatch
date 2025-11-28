package app;

import javax.swing.*;
import javax.swing.BorderFactory;
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
import java.awt.event.ActionEvent;
import java.awt.event.AdjustmentEvent;
import java.awt.event.AdjustmentListener;
import java.awt.event.KeyEvent;
import java.awt.Toolkit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

import static app.DiffEngine.*;

public class MirrorMatchApp extends JFrame {

    private final JTextArea leftArea = createEditor();
    private final JTextArea rightArea = createEditor();

    private final Highlighter leftHL = leftArea.getHighlighter();
    private final Highlighter rightHL = rightArea.getHighlighter();
    private final java.util.List<Object> focusLeftTags = new java.util.ArrayList<>();
    private final java.util.List<Object> focusRightTags = new java.util.ArrayList<>();
    private final Highlighter.HighlightPainter focusPainter =
            new UnderlineHatchPainter(new Color(120, 160, 255, 120));

    private final JLabel status = new JLabel("Ready.");
    private final JCheckBox syncScroll = new JCheckBox("Sync scroll", true);
    private final JToggleButton diffOnlyToggle = new JToggleButton("Diff only");
    private final JToggleButton chunkModeToggle = new JToggleButton("Chunk copy");
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

    private List<String> leftLines = List.of("");
    private List<String> rightLines = List.of("");
    private Result currentDiff = DiffEngine.diffLines("", "");
    private List<Hunk> changes = currentDiff.changeHunks();
    private int currentIndex = -1;

    Path leftPath = null;
    Path rightPath = null;

    private SwingWorker<DiffEngine.Result, Void> diffWorker;
    private volatile boolean suppressDocEvents = false;
    private Timer debounceTimer;

    private final UndoManager undoManagerLeft = new UndoManager();
    private final UndoManager undoManagerRight = new UndoManager();
    private boolean suppressUndoCapture = false;
    private final javax.swing.event.UndoableEditListener leftRecorder = e -> { if (!suppressUndoCapture) recordEdit(true); };
    private final javax.swing.event.UndoableEditListener rightRecorder = e -> { if (!suppressUndoCapture) recordEdit(false); };
    private final Deque<Boolean> undoStack = new ArrayDeque<>();

    private enum Side { LEFT, RIGHT, BOTH }

    private String lastSavedLeft = "";
    private String lastSavedRight = "";

    private final CenterDiffGutter centerGutter = new CenterDiffGutter(leftArea, rightArea);
    private final LineNumberGutter gutterLeft = new LineNumberGutter(leftArea);
    private final LineNumberGutter gutterRight = new LineNumberGutter(rightArea);
    private final JTextArea leftDiffView = createDiffViewer();
    private final JTextArea rightDiffView = createDiffViewer();
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

        JScrollPane leftScroll = createScroll(leftArea);
        JScrollPane rightScroll = createScroll(rightArea);
        leftScroll.setRowHeaderView(gutterLeft);
        rightScroll.setRowHeaderView(gutterRight);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                wrapEditor(leftScroll, "Left"), wrapEditor(rightScroll, "Right"));
        split.setResizeWeight(0.5);
        split.setDividerSize(48);
        attachCenterGutter(split, centerGutter);

        JScrollPane leftDiffScroll = createScroll(leftDiffView);
        JScrollPane rightDiffScroll = createScroll(rightDiffView);
        leftDiffScroll.setRowHeaderView(new LineNumberGutter(leftDiffView));
        rightDiffScroll.setRowHeaderView(new LineNumberGutter(rightDiffView));
        JSplitPane diffSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                wrapEditor(leftDiffScroll, "Changes (L)"),
                wrapEditor(rightDiffScroll, "Changes (R)"));
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

        prevBtn.addActionListener(e -> gotoDiff(currentIndex - 1));
        nextBtn.addActionListener(e -> gotoDiff(currentIndex + 1));
        copyL2R.addActionListener(e -> applyCopy(true));
        copyR2L.addActionListener(e -> applyCopy(false));
        delLeft.addActionListener(e -> applyDelete(true));
        delRight.addActionListener(e -> applyDelete(false));
        undoLeft.addActionListener(e -> undoSide(true));
        undoRight.addActionListener(e -> undoSide(false));
        undoAny.addActionListener(e -> undoLastAnySide());
        diffOnlyToggle.addActionListener(e -> toggleDiffOnlyView());

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
        leftArea.setTransferHandler(fileDropHandler);

        rightScroll.setTransferHandler(fileDropHandler);
        rightScroll.getViewport().setTransferHandler(fileDropHandler);
        rightArea.setTransferHandler(fileDropHandler);

        getRootPane().setTransferHandler(fileDropHandler);
        getGlassPane().setVisible(false); // keep glass pane hidden to preserve text selection
        ((JComponent) getGlassPane()).setTransferHandler(null);

        // avoid conflict with default text drop
        leftArea.setDropTarget(null);
        rightArea.setDropTarget(null);

        pack();
        setLocationRelativeTo(null);

        loadSampleDefaults();
    }

    private static JTextArea createEditor() {
        JTextArea area = new JTextArea();
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        area.setTabSize(4);
        area.setLineWrap(false);
        area.setMargin(new Insets(8, 10, 8, 10));
        area.setBackground(new Color(250, 251, 254));
        area.setBorder(BorderFactory.createEmptyBorder());
        return area;
    }

    private static JTextArea createDiffViewer() {
        JTextArea area = new JTextArea();
        area.setFont(new Font("IBM Plex Mono", Font.PLAIN, 13));
        area.setEditable(false);
        area.setOpaque(true);
        area.setBackground(new Color(249, 249, 252));
        area.setMargin(new Insets(8, 10, 8, 10));
        area.setLineWrap(false);
        return area;
    }

    private static JScrollPane createScroll(JTextArea area) {
        JScrollPane sp = new JScrollPane(area);
        sp.setBorder(new EmptyBorder(4, 4, 4, 4));
        sp.getViewport().setBackground(area.getBackground());
        return sp;
    }

    private JPanel wrapEditor(JScrollPane scroll, String title) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(
                new EmptyBorder(6, 6, 6, 6),
                BorderFactory.createLineBorder(new Color(230, 232, 238), 1, true)
        ));

        JLabel header = new JLabel("  " + title.toUpperCase());
        header.setFont(header.getFont().deriveFont(Font.BOLD, 12f));
        header.setForeground(new Color(90, 95, 115));
        header.setBorder(new EmptyBorder(6, 4, 4, 4));
        header.setIcon(UIManager.getIcon("FileView.fileIcon"));

        panel.add(header, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        panel.setBackground(new Color(245, 247, 252));
        return panel;
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

        openLeft.addActionListener(e -> openInto(leftArea, true));
        openRight.addActionListener(e -> openInto(rightArea, false));
        saveLeft.addActionListener(e -> saveFrom(leftArea, true, false));
        saveRight.addActionListener(e -> saveFrom(rightArea, false, false));
        saveLeftAs.addActionListener(e -> saveFrom(leftArea, true, true));
        saveRightAs.addActionListener(e -> saveFrom(rightArea, false, true));
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

        mb.add(file);
        mb.add(view);
        return mb;
    }

    private void openInto(JTextArea area, boolean left) {
        JFileChooser fc = new JFileChooser();
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            Path p = fc.getSelectedFile().toPath();
            try {
                String content = Files.readString(p);
                area.setText(content);
                resetUndoHistory(left ? Side.LEFT : Side.RIGHT);
                if (left) {
                    leftPath = p;
                    setStatus("Opened LEFT: " + p);
                    lastSavedLeft = content;
                } else {
                    rightPath = p;
                    setStatus("Opened RIGHT: " + p);
                    lastSavedRight = content;
                }
                recompute();
            } catch (IOException ex) {
                error("Failed to read: " + p + " – " + ex.getMessage());
            }
        }
    }

    private void saveFrom(JTextArea area, boolean left, boolean forceAs) {
        try {
            Path target = left ? leftPath : rightPath;
            if (forceAs || target == null) {
                JFileChooser fc = new JFileChooser();
                if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
                target = fc.getSelectedFile().toPath();
                if (left) {
                    leftPath = target;
                } else {
                    rightPath = target;
                }
            }
            Files.writeString(target, area.getText());
            if (left) {
                lastSavedLeft = area.getText();
            } else {
                lastSavedRight = area.getText();
            }
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
            refreshDiffOnlyView();
            centerCards.show(centerDeck, "diff");
            centerGutter.setVisible(false);
            setStatus("Showing differences only.");
        } else {
            centerCards.show(centerDeck, "main");
            centerGutter.setVisible(true);
            setStatus("Back to full view.");
        }
    }

    private void addHighlight(Highlighter.HighlightPainter painter, JTextArea area,
                              int lineStart, int lineEnd) {
        try {
            javax.swing.text.Element root = area.getDocument().getDefaultRootElement();
            int lc = root.getElementCount();
            if (lc == 0) return;
            int a = Math.min(Math.max(0, lineStart), lc - 1);
            int bLine = Math.max(lineStart, lineEnd) - 1;
            int b = Math.min(Math.max(0, bLine), lc - 1);
            int start = root.getElement(a).getStartOffset();
            int end = root.getElement(b).getEndOffset();
            area.getHighlighter().addHighlight(start, end, painter);
        } catch (BadLocationException ignored) {}
    }

    private void refreshHighlights() {
        leftHL.removeAllHighlights();
        rightHL.removeAllHighlights();
        focusLeftTags.clear();
        focusRightTags.clear();

        Highlighter.HighlightPainter delPainter =
                new DefaultHighlighter.DefaultHighlightPainter(new Color(255, 220, 220));
        Highlighter.HighlightPainter insPainter =
                new DefaultHighlighter.DefaultHighlightPainter(new Color(220, 255, 220));
        Highlighter.HighlightPainter changePainter =
                new DefaultHighlighter.DefaultHighlightPainter(new Color(255, 245, 200));

        int i = 0;
        int n = currentDiff.hunks.size();
        while (i < n) {
            Hunk h = currentDiff.hunks.get(i);
            HunkType t = h.type();
            if (t == HunkType.DELETE) {
                addHighlight(delPainter, leftArea, h.leftStart(), h.leftEnd());
            } else if (t == HunkType.INSERT) {
                addHighlight(insPainter, rightArea, h.rightStart(), h.rightEnd());
            } else if (t == HunkType.CHANGE) {
                addHighlight(changePainter, leftArea, h.leftStart(), h.leftEnd());
                addHighlight(changePainter, rightArea, h.rightStart(), h.rightEnd());
            }
            i = i + 1;
        }
        if (!changes.isEmpty() && currentIndex >= 0 && currentIndex < changes.size()) {
            highlightCurrentHunk(changes.get(currentIndex));
        }
    }

    private void refreshDiffOnlyView() {
        if (!diffOnlyToggle.isSelected() && leftDiffView.getText().isEmpty()) return;
        String leftText = buildDiffOnlyText(leftLines, true);
        String rightText = buildDiffOnlyText(rightLines, false);
        leftDiffView.setText(leftText);
        rightDiffView.setText(rightText);
        leftDiffView.setCaretPosition(0);
        rightDiffView.setCaretPosition(0);
    }

    private String buildDiffOnlyText(List<String> sourceLines, boolean leftSide) {
        StringBuilder sb = new StringBuilder();
        int lastSeen = 0;
        int i = 0;
        int n = currentDiff.hunks.size();
        while (i < n) {
            Hunk h = currentDiff.hunks.get(i);
            int start = leftSide ? h.leftStart() : h.rightStart();
            int end = leftSide ? h.leftEnd() : h.rightEnd();

            if (h.type() == HunkType.EQUAL) {
                lastSeen = end;
                i = i + 1;
                continue;
            }

            if (start > lastSeen) {
                sb.append("… ").append(start - lastSeen).append(" unchanged …\n");
            }

            int line = start;
            while (line < end) {
                sb.append(String.format("%5d │ %s%n", line + 1, safeLine(sourceLines, line)));
                line = line + 1;
            }

            if (start == end) {
                int count = Math.abs((leftSide ? h.rightEnd() - h.rightStart() : h.leftEnd() - h.leftStart()));
                String note = leftSide ? "only on right" : "only on left";
                sb.append(String.format("%5d │ [%d line(s) %s]%n", start + 1, count, note));
            }
            lastSeen = end;
            i = i + 1;
        }
        if (sb.length() == 0) {
            sb.append("No differences.");
        }
        return sb.toString();
    }

    private String safeLine(List<String> lines, int idx) {
        if (idx < 0 || idx >= lines.size()) return "";
        return lines.get(idx);
    }

    private void highlightCurrentHunk(Hunk h) {
        if (h == null) return;
        try {
            javax.swing.text.Element rootL = leftArea.getDocument().getDefaultRootElement();
            javax.swing.text.Element rootR = rightArea.getDocument().getDefaultRootElement();
            clearFocusHighlights();

            if (h.type() == HunkType.DELETE || h.type() == HunkType.CHANGE) {
                addFocusLines(leftHL, focusLeftTags, rootL, leftArea, h.leftStart(), h.leftEnd());
                gutterLeft.setFocusLines(java.util.List.of(clampLine(h.leftStart(), rootL)));
            } else {
                gutterLeft.setFocusLines(java.util.List.of());
            }
            if (h.type() == HunkType.INSERT || h.type() == HunkType.CHANGE) {
                addFocusLines(rightHL, focusRightTags, rootR, rightArea, h.rightStart(), h.rightEnd());
                gutterRight.setFocusLines(java.util.List.of(clampLine(h.rightStart(), rootR)));
            } else {
                gutterRight.setFocusLines(java.util.List.of());
            }
            if (h.type() == HunkType.CHANGE) {
                gutterLeft.setFocusLines(java.util.List.of(clampLine(h.leftStart(), rootL)));
                gutterRight.setFocusLines(java.util.List.of(clampLine(h.rightStart(), rootR)));
            }
        } catch (Exception ignored) {}
        leftArea.repaint();
        rightArea.repaint();
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
        removeTags(leftHL, focusLeftTags);
        removeTags(rightHL, focusRightTags);
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
        if (changes.isEmpty()) return;
        int maxIndex = changes.size() - 1;
        currentIndex = Math.max(0, Math.min(newIndex, maxIndex));
        Hunk h = changes.get(currentIndex);
        try {
            int lStart = leftArea.getLineStartOffset(
                    Math.min(h.leftStart(), Math.max(0, leftArea.getLineCount() - 1)));
            int rStart = rightArea.getLineStartOffset(
                    Math.min(h.rightStart(), Math.max(0, rightArea.getLineCount() - 1)));
            leftArea.setCaretPosition(lStart);
            rightArea.setCaretPosition(rStart);
            leftArea.scrollRectToVisible(leftArea.modelToView2D(lStart).getBounds());
            rightArea.scrollRectToVisible(rightArea.modelToView2D(rStart).getBounds());
        } catch (BadLocationException ignored) {}
        highlightCurrentHunk(h);
        updateNavButtons();
        setStatus("Diff " + (currentIndex + 1) + " / " + changes.size());
    }

    private void updateNavButtons() {
        boolean has = !changes.isEmpty();
        prevBtn.setEnabled(has && currentIndex > 0);
        nextBtn.setEnabled(has && currentIndex < changes.size() - 1);
        copyL2R.setEnabled(has);
        copyR2L.setEnabled(has);
        delLeft.setEnabled(has);
        delRight.setEnabled(has);
    }

    private void applyCopy(boolean leftToRight) {
        if (currentIndex < 0 || currentIndex >= changes.size()) return;
        Hunk h = changes.get(currentIndex);
        if (leftToRight) {
            List<String> src = leftLines.subList(h.leftStart(), h.leftEnd());
            replaceLines(rightArea, h.rightStart(), h.rightEnd(), src);
        } else {
            List<String> src = rightLines.subList(h.rightStart(), h.rightEnd());
            replaceLines(leftArea, h.leftStart(), h.leftEnd(), src);
        }
        recompute();
    }

    private void applyDelete(boolean onLeft) {
        if (currentIndex < 0 || currentIndex >= changes.size()) return;
        Hunk h = changes.get(currentIndex);
        if (onLeft) {
            replaceLines(leftArea, h.leftStart(), h.leftEnd(), List.of());
        } else {
            replaceLines(rightArea, h.rightStart(), h.rightEnd(), List.of());
        }
        recompute();
    }

    private void handleArrowCopy(boolean leftToRight, int lineIndex, boolean bulk) {
        Hunk h = findHunkForLine(leftToRight, lineIndex);
        if (h == null) return;
        int idx = changes.indexOf(h);
        if (idx >= 0) {
            currentIndex = idx;
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
        int n = currentDiff.hunks.size();
        int i = 0;
        while (i < n) {
            Hunk h = currentDiff.hunks.get(i);
            if (h.type() != HunkType.EQUAL) {
                if (usingLeftSide && lineIndex >= h.leftStart() && lineIndex < h.leftEnd()) {
                    return h;
                }
                if (!usingLeftSide && lineIndex >= h.rightStart() && lineIndex < h.rightEnd()) {
                    return h;
                }
            }
            i = i + 1;
        }
        return null;
    }

    private void copySingleLine(boolean leftToRight, Hunk h, int lineIndex) {
        List<String> srcLines = leftToRight ? leftLines : rightLines;
        JTextArea target = leftToRight ? rightArea : leftArea;

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
            boolean onLeft = area == leftArea;
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
        leftArea.getDocument().addDocumentListener(dl);
        rightArea.getDocument().addDocumentListener(dl);

        leftArea.getDocument().addUndoableEditListener(undoManagerLeft);
        rightArea.getDocument().addUndoableEditListener(undoManagerRight);
        leftArea.getDocument().addUndoableEditListener(leftRecorder);
        rightArea.getDocument().addUndoableEditListener(rightRecorder);

        installUndoShortcut(leftArea);
        installUndoShortcut(rightArea);
        installUndoShortcut(leftDiffView);
        installUndoShortcut(rightDiffView);
        installSaveShortcut(leftArea, true);
        installSaveShortcut(rightArea, false);
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
                saveFrom(forLeft ? leftArea : rightArea, forLeft, false);
            }
        });
    }

    private void recordEdit(boolean onLeft) {
        undoStack.addLast(onLeft);
    }

    private void autoSaveIfNeeded() {
        if (!autoSaveToggle.isSelected()) return;
        try {
            if (leftPath != null && !leftArea.getText().equals(lastSavedLeft)) {
                Files.writeString(leftPath, leftArea.getText());
                lastSavedLeft = leftArea.getText();
            }
            if (rightPath != null && !rightArea.getText().equals(lastSavedRight)) {
                Files.writeString(rightPath, rightArea.getText());
                lastSavedRight = rightArea.getText();
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

    private void resetUndoHistory(Side side) {
        if (side == Side.LEFT || side == Side.BOTH) {
            undoManagerLeft.discardAllEdits();
            removeAllForSide(true);
        }
        if (side == Side.RIGHT || side == Side.BOTH) {
            undoManagerRight.discardAllEdits();
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
        final String leftText = leftArea.getText();
        final String rightText = rightArea.getText();

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
                return DiffEngine.diffLinesFast(leftText, rightText);
            }
            @Override protected void done() {
                if (isCancelled()) return;
                try {
                    currentDiff = get();
                    leftLines = List.of(leftText.split("\n", -1));
                    rightLines = List.of(rightText.split("\n", -1));
                    changes = currentDiff.changeHunks();
                    if (changes.isEmpty()) {
                        currentIndex = -1;
                    } else {
                        if (currentIndex < 0 || currentIndex >= changes.size()) {
                            currentIndex = 0;
                        }
                    }
                    refreshHighlights();
                    if (currentIndex >= 0) {
                        gotoDiff(currentIndex);
                    } else {
                        updateNavButtons();
                        setStatus("No differences.");
                    }
                    centerGutter.rebuildMarkers(currentDiff.hunks);
                    refreshDiffOnlyView();
                    autoSaveIfNeeded();
                } catch (Exception ignored) {}
            }
        };
        diffWorker.execute();
    }

    private void undoSide(boolean leftSide) {
        UndoManager target = leftSide ? undoManagerLeft : undoManagerRight;
        if (tryUndo(target)) {
            removeLatestForSide(leftSide);
            setStatus("Undo " + (leftSide ? "LEFT" : "RIGHT"));
        }
    }

    private void undoLastAnySide() {
        Boolean latest = peekLatestEdit();
        if (latest == null) {
            if (undoManagerLeft.canUndo()) {
                tryUndo(undoManagerLeft);
                setStatus("Undo LEFT");
            } else if (undoManagerRight.canUndo()) {
                tryUndo(undoManagerRight);
                setStatus("Undo RIGHT");
            }
            return;
        }
        boolean useLeft = latest;
        UndoManager target = useLeft ? undoManagerLeft : undoManagerRight;
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
                leftArea.setText(lt);
                rightArea.setText(rt);
                leftPath = lp;
                rightPath = rp;
                lastSavedLeft = lt;
                lastSavedRight = rt;
                resetUndoHistory(Side.BOTH);
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

    JTextArea getLeftArea() { return leftArea; }
    JTextArea getRightArea() { return rightArea; }

    void handleSingleDrop(boolean leftSide, Path p, String content) {
        if (leftSide) {
            leftArea.setText(content);
            leftPath = p;
            resetUndoHistory(Side.LEFT);
            lastSavedLeft = content;
        } else {
            rightArea.setText(content);
            rightPath = p;
            resetUndoHistory(Side.RIGHT);
            lastSavedRight = content;
        }
        recompute();
    }

    void handleBothDrop(Path p1, String t1, Path p2, String t2) {
        leftArea.setText(t1);
        rightArea.setText(t2);
        leftPath = p1;
        rightPath = p2;
        resetUndoHistory(Side.BOTH);
        lastSavedLeft = t1;
        lastSavedRight = t2;
        recompute();
    }

    boolean isLeftEmpty() {
        return leftArea.getText().isEmpty();
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
                doc.removeUndoableEditListener(undoManagerLeft);
                doc.removeUndoableEditListener(leftRecorder);
            } else {
                doc.removeUndoableEditListener(undoManagerRight);
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
                doc.addUndoableEditListener(undoManagerLeft);
                doc.addUndoableEditListener(leftRecorder);
            } else {
                doc.addUndoableEditListener(undoManagerRight);
                doc.addUndoableEditListener(rightRecorder);
            }
            suppressUndoCapture = false;
        }
    }

    private UndoManager getUndoManager(boolean leftSide) {
        return leftSide ? undoManagerLeft : undoManagerRight;
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
