package app;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoManager;
import java.awt.*;
import java.awt.event.AdjustmentEvent;
import java.awt.event.AdjustmentListener;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static app.DiffEngine.*;

public class MirrorMatchApp extends JFrame {

    private final JTextArea leftArea = createEditor();
    private final JTextArea rightArea = createEditor();

    private final Highlighter leftHL = leftArea.getHighlighter();
    private final Highlighter rightHL = rightArea.getHighlighter();

    private final JLabel status = new JLabel("Ready.");
    private final JCheckBox syncScroll = new JCheckBox("Sync scroll", true);

    private final JButton prevBtn = new JButton("◀ Prev");
    private final JButton nextBtn = new JButton("Next ▶");
    private final JButton copyL2R = new JButton("Copy ▶");
    private final JButton copyR2L = new JButton("◀ Copy");
    private final JButton delLeft = new JButton("Delete L");
    private final JButton delRight = new JButton("Delete R");
    private final JButton undoLeft = new JButton("Undo L");
    private final JButton undoRight = new JButton("Undo R");

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

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            setSystemLookAndFeel();
            new MirrorMatchApp().setVisible(true);
        });
    }

    public MirrorMatchApp() {
        super("MirrorMatch");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1000, 650));

        setJMenuBar(createMenuBar());

        JScrollPane leftScroll = new JScrollPane(leftArea);
        JScrollPane rightScroll = new JScrollPane(rightArea);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftScroll, rightScroll);
        split.setResizeWeight(0.5);
        split.setDividerSize(6);

        JToolBar tools = new JToolBar();
        tools.setFloatable(false);
        tools.setBorder(new EmptyBorder(6, 8, 6, 8));
        tools.add(prevBtn);
        tools.add(nextBtn);
        tools.addSeparator();
        tools.add(copyL2R);
        tools.add(copyR2L);
        tools.add(delLeft);
        tools.add(delRight);
        tools.addSeparator();
        tools.add(syncScroll);
        tools.addSeparator();
        tools.add(undoLeft);
        tools.add(undoRight);

        JPanel statusBar = new JPanel(new BorderLayout());
        status.setBorder(new EmptyBorder(4, 8, 4, 8));
        statusBar.add(status, BorderLayout.CENTER);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(tools, BorderLayout.NORTH);
        getContentPane().add(split, BorderLayout.CENTER);
        getContentPane().add(statusBar, BorderLayout.SOUTH);

        addEditorListeners();
        addScrollSync(leftScroll, rightScroll);

        prevBtn.addActionListener(e -> gotoDiff(currentIndex - 1));
        nextBtn.addActionListener(e -> gotoDiff(currentIndex + 1));
        copyL2R.addActionListener(e -> applyCopy(true));
        copyR2L.addActionListener(e -> applyCopy(false));
        delLeft.addActionListener(e -> applyDelete(true));
        delRight.addActionListener(e -> applyDelete(false));
        undoLeft.addActionListener(e -> tryUndo(undoManagerLeft));
        undoRight.addActionListener(e -> tryUndo(undoManagerRight));

        // drag & drop – one handler, side decided by where you drop
        FileDropHandler fileDropHandler = new FileDropHandler(this);
        leftScroll.setTransferHandler(fileDropHandler);
        leftScroll.getViewport().setTransferHandler(fileDropHandler);
        leftArea.setTransferHandler(fileDropHandler);

        rightScroll.setTransferHandler(fileDropHandler);
        rightScroll.getViewport().setTransferHandler(fileDropHandler);
        rightArea.setTransferHandler(fileDropHandler);

        getRootPane().setTransferHandler(fileDropHandler);
        getGlassPane().setVisible(true);
        ((JComponent) getGlassPane()).setTransferHandler(fileDropHandler);

        // avoid conflict with default text drop
        leftArea.setDropTarget(null);
        rightArea.setDropTarget(null);

        pack();
        setLocationRelativeTo(null);
    }

    private static JTextArea createEditor() {
        JTextArea area = new JTextArea();
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        area.setTabSize(4);
        area.setLineWrap(false);
        return area;
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
                if (left) {
                    leftPath = p;
                    setStatus("Opened LEFT: " + p);
                } else {
                    rightPath = p;
                    setStatus("Opened RIGHT: " + p);
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
            setStatus("Saved " + (left ? "LEFT" : "RIGHT") + " → " + target);
        } catch (IOException ex) {
            error("Save failed: " + ex.getMessage());
        }
    }

    private void recompute() {
        startDiffInBackground();
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

    private void replaceLines(JTextArea area, int startLine, int endLine, List<String> with) {
        try {
            suppressDocEvents = true;
            int startOffset = area.getLineStartOffset(
                    Math.min(startLine, Math.max(0, area.getLineCount() - 1)));
            int endOffset;
            if (endLine <= 0) {
                endOffset = startOffset;
            } else if (endLine >= area.getLineCount()) {
                endOffset = area.getDocument().getLength();
            } else {
                endOffset = area.getLineStartOffset(endLine);
            }
            String replacement = String.join("\n", with);
            area.getDocument().remove(startOffset, Math.max(0, endOffset - startOffset));
            if (!replacement.isEmpty()) {
                area.getDocument().insertString(startOffset, replacement + "\n", null);
            }
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
                } catch (Exception ignored) {}
            }
        };
        diffWorker.execute();
    }

    private void tryUndo(UndoManager um) {
        try {
            if (um.canUndo()) {
                um.undo();
            }
        } catch (CannotUndoException ignored) {}
    }

    // === APIs used by FileDropHandler ===

    JTextArea getLeftArea() { return leftArea; }
    JTextArea getRightArea() { return rightArea; }

    void handleSingleDrop(boolean leftSide, Path p, String content) {
        if (leftSide) {
            leftArea.setText(content);
            leftPath = p;
        } else {
            rightArea.setText(content);
            rightPath = p;
        }
        recompute();
    }

    void handleBothDrop(Path p1, String t1, Path p2, String t2) {
        leftArea.setText(t1);
        rightArea.setText(t2);
        leftPath = p1;
        rightPath = p2;
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
}
