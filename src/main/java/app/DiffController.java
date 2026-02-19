package app;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.Highlighter;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static app.DiffEngine.HunkType;

/**
 * Handles diff computation (with debounce), highlights, and navigation.
 */
public class DiffController implements DocEventSuppressor {

    private final EditorPane left;
    private final EditorPane right;
    private final DiffSession session;
    private final CenterDiffGutter gutter;
    private final Supplier<Boolean> ignoreWhitespaceSupplier;
    private final Supplier<Boolean> insertModeSupplier;
    private final Consumer<String> statusSetter;
    private final Runnable refreshDiffOnlyView;
    private final Runnable updateNavButtons;
    private final Runnable autoSave;

    private final Highlighter.HighlightPainter focusPainter;
    private final Highlighter.HighlightPainter inlinePainter;
    private final Highlighter.HighlightPainter deletePainter;
    private final Highlighter.HighlightPainter insertPainter;
    private final Highlighter.HighlightPainter changeLeftPainter;
    private final Highlighter.HighlightPainter changeRightPainter;

    private final List<Object> focusLeftTags = new ArrayList<>();
    private final List<Object> focusRightTags = new ArrayList<>();

    private SwingWorker<DiffEngine.Result, Void> diffWorker;
    private Timer debounceTimer;
    private volatile boolean suppressDocEvents = false;

    public DiffController(EditorPane left,
                          EditorPane right,
                          DiffSession session,
                          CenterDiffGutter gutter,
                          Supplier<Boolean> ignoreWhitespaceSupplier,
                          Supplier<Boolean> insertModeSupplier,
                          Consumer<String> statusSetter,
                          Runnable refreshDiffOnlyView,
                          Runnable updateNavButtons,
                          Runnable autoSave,
                          Highlighter.HighlightPainter focusPainter,
                          Highlighter.HighlightPainter inlinePainter,
                          Highlighter.HighlightPainter deletePainter,
                          Highlighter.HighlightPainter insertPainter,
                          Highlighter.HighlightPainter changeLeftPainter,
                          Highlighter.HighlightPainter changeRightPainter) {
        this.left = left;
        this.right = right;
        this.session = session;
        this.gutter = gutter;
        this.ignoreWhitespaceSupplier = ignoreWhitespaceSupplier;
        this.insertModeSupplier = insertModeSupplier;
        this.statusSetter = statusSetter;
        this.refreshDiffOnlyView = refreshDiffOnlyView;
        this.updateNavButtons = updateNavButtons;
        this.autoSave = autoSave;
        this.focusPainter = focusPainter;
        this.inlinePainter = inlinePainter;
        this.deletePainter = deletePainter;
        this.insertPainter = insertPainter;
        this.changeLeftPainter = changeLeftPainter;
        this.changeRightPainter = changeRightPainter;
    }

    @Override
    public boolean isSuppressDocEvents() {
        return suppressDocEvents;
    }

    @Override
    public void setSuppressDocEvents(boolean suppressDocEvents) {
        this.suppressDocEvents = suppressDocEvents;
    }

    public void delayedRecompute() {
        if (debounceTimer != null && debounceTimer.isRunning()) {
            debounceTimer.stop();
        }
        debounceTimer = new Timer(600, e -> startDiffInBackground());
        debounceTimer.setRepeats(false);
        debounceTimer.start();
    }

    public void recompute() {
        startDiffInBackground();
    }

    public void refreshUIAfterSessionChange() {
        refreshHighlights();
        addInlineHighlights();
        updateNavButtons.run();
        gutter.rebuildMarkers(session.currentDiff().hunks);
        refreshDiffOnlyView.run();
    }

    public void gotoDiff(int newIndex) {
        List<DiffEngine.Hunk> changes = session.changes();
        if (changes.isEmpty()) return;
        int maxIndex = changes.size() - 1;
        session.setCurrentIndex(Math.max(0, Math.min(newIndex, maxIndex)));
        DiffEngine.Hunk h = changes.get(session.currentIndex());
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
        updateNavButtons.run();
        statusSetter.accept("Diff " + (session.currentIndex() + 1) + " / " + changes.size());
    }

    private void startDiffInBackground() {
        final String leftText = left.area().getText();
        final String rightText = right.area().getText();
        final boolean ignoreWS = ignoreWhitespaceSupplier.get();

        if (leftText.length() > 2000000 || rightText.length() > 2000000) {
            statusSetter.accept("Large file mode: View → Recompute Diff");
            return;
        }

        if (diffWorker != null && !diffWorker.isDone()) {
            diffWorker.cancel(true);
        }

        statusSetter.accept("Computing diff…");
        diffWorker = new SwingWorker<>() {
            @Override protected DiffEngine.Result doInBackground() {
                java.util.function.Function<String, String> norm = ignoreWS
                        ? s -> s.replaceAll("\\s+", "")
                        : java.util.function.Function.identity();
                DiffEngine.Result base = DiffEngine.diffLinesNormalized(leftText, rightText, norm);
                if (insertModeSupplier.get()) {
                    return DiffEngine.refineChanges(base, leftText, rightText);
                }
                return base;
            }
            @Override protected void done() {
                if (isCancelled()) return;
                try {
                    DiffEngine.Result diff = get();
                    session.updateTexts(leftText, rightText);
                    session.updateDiff(diff);
                    if (DebugLog.isEnabled()) {
                        logHunksWithText(diff, session.leftLines(), session.rightLines());
                    }
                    List<DiffEngine.Hunk> changes = session.changes();
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
                        updateNavButtons.run();
                        statusSetter.accept("No differences.");
                    }
                    gutter.rebuildMarkers(session.currentDiff().hunks);
                    refreshDiffOnlyView.run();
                    autoSave.run();
                } catch (Exception ignored) {}
            }
        };
        diffWorker.execute();
    }

    private void logHunksWithText(DiffEngine.Result diff, List<String> leftLines, List<String> rightLines) {
        DebugLog.log("Hunks (mode: %s, ignoreWS=%s)", insertModeSupplier.get() ? "INSERT" : "REWRITE", ignoreWhitespaceSupplier.get());
        int max = Math.min(diff.hunks.size(), 50);
        for (int i = 0; i < max; i++) {
            DiffEngine.Hunk h = diff.hunks.get(i);
            DebugLog.log("  %s", h);
            if (h.type() != DiffEngine.HunkType.EQUAL) {
                if (h.leftStart() < h.leftEnd()) {
                    DebugLog.log("    L: %s", slicePreview(leftLines, h.leftStart(), h.leftEnd()));
                }
                if (h.rightStart() < h.rightEnd()) {
                    DebugLog.log("    R: %s", slicePreview(rightLines, h.rightStart(), h.rightEnd()));
                }
            }
        }
    }

    private String slicePreview(List<String> lines, int start, int end) {
        StringBuilder sb = new StringBuilder();
        int i = start;
        while (i < end) {
            sb.append("[").append(i).append("] ").append(lines.get(i)).append(" | ");
            i = i + 1;
        }
        return sb.toString();
    }

    private void refreshHighlights() {
        clearFocusHighlights();
        left.clearHighlights();
        right.clearHighlights();

        int i = 0;
        int n = session.currentDiff().hunks.size();
        while (i < n) {
            DiffEngine.Hunk h = session.currentDiff().hunks.get(i);
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
            DiffEngine.Hunk h = session.currentDiff().hunks.get(i);
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

    private void highlightCurrentHunk(DiffEngine.Hunk h) {
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
        gutter.repaint();
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
}
