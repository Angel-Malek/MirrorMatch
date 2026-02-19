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
    private final JToggleButton insertModeToggle = new JToggleButton("Insert mode", true);
    private final AppActions appActions;

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

    private final CollapsedModeController collapsedModeController;

    private final CenterDiffGutter centerGutter;
    private final DiffController diffController;
    private final MergeController mergeController;

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

        centerGutter = new CenterDiffGutter(left.area(), right.area());
        diffController = new DiffController(
                left,
                right,
                session,
                centerGutter,
                ignoreWsToggle::isSelected,
                insertModeToggle::isSelected,
                this::setStatus,
                this::refreshDiffOnlyView,
                this::updateNavButtons,
                this::autoSaveIfNeeded,
                focusPainter,
                inlinePainter,
                deletePainter,
                insertPainter,
                changeLeftPainter,
                changeRightPainter
        );
        mergeController = new MergeController(
                left,
                right,
                session,
                diffController,
                this::recompute,
                this::updateNavButtons,
                this::setStatus,
                insertModeToggle::isSelected
        );
        appActions = new AppActions(this, diffController, mergeController, session, left, right);
        collapsedModeController = new CollapsedModeController(left, right, session, centerGutter, diffController);

        setJMenuBar(createMenuBar());

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
        insertModeToggle.setToolTipText("Insert mode: missing lines become insert/delete; off = rewrite changes");
        tools.add(insertModeToggle);
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

        appActions.wireToolbar(
                prevBtn, nextBtn,
                copyL2R, copyR2L,
                delLeft, delRight,
                undoLeft, undoRight, undoAny,
                diffOnlyToggle, ignoreWsToggle, insertModeToggle,
                this::toggleDiffOnlyView,
                this::recompute
        );

        centerGutter.setHandler(new CenterDiffGutter.ArrowHandler() {
            @Override public void onCopyLeftToRight(int lineIndex, boolean bulk) {
                mergeController.handleArrowCopy(true, lineIndex, bulk);
            }
            @Override public void onCopyRightToLeft(int lineIndex, boolean bulk) {
                mergeController.handleArrowCopy(false, lineIndex, bulk);
            }
            @Override public boolean isBulkMode() {
                return chunkModeToggle.isSelected();
            }
        });

        // drag & drop – one handler, side decided by where you drop
        FileDropHandler fileDropHandler = new FileDropHandler(this);
        TransferHandler defaultLeft = left.area().getTransferHandler();
        TransferHandler defaultRight = right.area().getTransferHandler();
        DualTransferHandler leftHandler = new DualTransferHandler(fileDropHandler, defaultLeft);
        DualTransferHandler rightHandler = new DualTransferHandler(fileDropHandler, defaultRight);
        leftScroll.setTransferHandler(fileDropHandler);
        leftScroll.getViewport().setTransferHandler(fileDropHandler);
        left.area().setTransferHandler(leftHandler);

        rightScroll.setTransferHandler(fileDropHandler);
        rightScroll.getViewport().setTransferHandler(fileDropHandler);
        right.area().setTransferHandler(rightHandler);

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

    JMenuBar createMenuBar() {
        JMenuBar mb = new JMenuBar();
        JMenu file = new JMenu("File");

        JMenuItem openLeft = new JMenuItem("Open Left…");
        JMenuItem openRight = new JMenuItem("Open Right…");
        JMenuItem saveLeft = new JMenuItem("Save Left");
        JMenuItem saveRight = new JMenuItem("Save Right");
        JMenuItem saveLeftAs = new JMenuItem("Save Left As…");
        JMenuItem saveRightAs = new JMenuItem("Save Right As…");
        JMenuItem exit = new JMenuItem("Exit");

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
        view.add(recomputeItem);
        JMenuItem prefsItem = new JMenuItem("Preferences…");
        view.addSeparator();
        view.add(prefsItem);

        appActions.wireMenu(openLeft, openRight,
                saveLeft, saveRight, saveLeftAs, saveRightAs,
                exit,
                recomputeItem,
                prefsItem,
                () -> {
                    PreferencesDialog.Result res = PreferencesDialog.show(this, themeManager.fontSize(), themeManager.themeName());
                    if (res != null) {
                        themeManager.setFontSize(res.fontSize());
                        themeManager.setThemeName(res.themeName());
                        themeManager.apply(left, right, leftDiffView, rightDiffView, getContentPane());
                    }
                });

        mb.add(file);
        mb.add(view);
        return mb;
    }

    void openInto(JTextArea area, boolean left) {
        JFileChooser fc = new JFileChooser();
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            Path p = fc.getSelectedFile().toPath();
            try {
                FileContentLoader.LoadedContent payload = FileContentLoader.load(p);
                area.setText(payload.text());
                mergeController.resetUndoHistory(java.util.EnumSet.of(left ? EditorSide.LEFT : EditorSide.RIGHT));
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

    void saveFrom(JTextArea area, boolean left, boolean forceAs) {
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

    void recompute() {
        diffController.recompute();
    }

    private void toggleDiffOnlyView() {
        boolean on = diffOnlyToggle.isSelected();
        if (on) {
            boolean entered = collapsedModeController.enter();
            if (!entered) {
                setStatus("No differences to collapse.");
                diffOnlyToggle.setSelected(false);
                return;
            }
            setStatus("Diff only: editing differences.");
        } else {
            collapsedModeController.exit();
            setStatus("Back to full view.");
        }
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

    void updateNavButtons() {
        java.util.List<Hunk> changes = session.changes();
        boolean has = !changes.isEmpty();
        prevBtn.setEnabled(has && session.currentIndex() > 0);
        nextBtn.setEnabled(has && session.currentIndex() < changes.size() - 1);
        copyL2R.setEnabled(has);
        copyR2L.setEnabled(has);
        delLeft.setEnabled(has);
        delRight.setEnabled(has);
    }

    private void addEditorListeners() {
        DocumentListener dl = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) {
                if (!diffController.isSuppressDocEvents()) diffController.delayedRecompute();
            }
            @Override public void removeUpdate(DocumentEvent e) {
                if (!diffController.isSuppressDocEvents()) diffController.delayedRecompute();
            }
            @Override public void changedUpdate(DocumentEvent e) {
                if (!diffController.isSuppressDocEvents()) diffController.delayedRecompute();
            }
        };
        left.area().getDocument().addDocumentListener(dl);
        right.area().getDocument().addDocumentListener(dl);

        left.area().getDocument().addUndoableEditListener(left.undoManager());
        right.area().getDocument().addUndoableEditListener(right.undoManager());

        appActions.installUndoShortcut(left.area());
        appActions.installUndoShortcut(right.area());
        appActions.installUndoShortcut(leftDiffView);
        appActions.installUndoShortcut(rightDiffView);
        appActions.installSaveShortcut(left.area(), true);
        appActions.installSaveShortcut(right.area(), false);
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
                mergeController.resetUndoHistory(java.util.EnumSet.of(EditorSide.LEFT, EditorSide.RIGHT));
                refreshHeaders();
                recompute();
                setStatus("Loaded sample-left.txt and sample-right.txt");
            } catch (IOException ignored) {}
        }
    }

    // === APIs used by FileDropHandler ===

    JTextArea getLeftArea() { return left.area(); }
    JTextArea getRightArea() { return right.area(); }

    void handleSingleDrop(boolean leftSide, Path p, FileContentLoader.LoadedContent payload) {
        String content = payload.text();
        if (leftSide) {
            left.area().setText(content);
            left.setPath(p);
            mergeController.resetUndoHistory(java.util.EnumSet.of(EditorSide.LEFT));
            left.setLastSaved(content);
            refreshHeaders();
        } else {
            right.area().setText(content);
            right.setPath(p);
            mergeController.resetUndoHistory(java.util.EnumSet.of(EditorSide.RIGHT));
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
        mergeController.resetUndoHistory(java.util.EnumSet.of(EditorSide.LEFT, EditorSide.RIGHT));
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

}
