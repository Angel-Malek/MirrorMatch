package app;

import javax.swing.*;
import javax.swing.event.UndoableEditListener;
import javax.swing.text.BadLocationException;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoManager;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Consumer;

/**
 * Handles copy/delete/arrow copy and shared undo stack across sides.
 */
public class MergeController {

    private final EditorPane left;
    private final EditorPane right;
    private final DiffSession session;
    private final DocEventSuppressor diffController;
    private final Runnable recompute;
    private final Runnable updateNavButtons;
    private final Consumer<String> statusSetter;
    private final java.util.function.Supplier<Boolean> insertModeSupplier;

    private final ArrayDeque<Boolean> undoStack = new ArrayDeque<>();
    private boolean suppressUndoCapture = false;

    private final UndoableEditListener leftRecorder = e -> { if (!suppressUndoCapture) recordEdit(true); };
    private final UndoableEditListener rightRecorder = e -> { if (!suppressUndoCapture) recordEdit(false); };

    public MergeController(EditorPane left,
                           EditorPane right,
                           DiffSession session,
                           DocEventSuppressor diffController,
                           Runnable recompute,
                           Runnable updateNavButtons,
                           Consumer<String> statusSetter,
                           java.util.function.Supplier<Boolean> insertModeSupplier) {
        this.left = left;
        this.right = right;
        this.session = session;
        this.diffController = diffController;
        this.recompute = recompute;
        this.updateNavButtons = updateNavButtons;
        this.statusSetter = statusSetter;
        this.insertModeSupplier = insertModeSupplier;
    }

    public UndoableEditListener leftRecorder() { return leftRecorder; }
    public UndoableEditListener rightRecorder() { return rightRecorder; }

    public void applyCopy(boolean leftToRight) {
        List<DiffEngine.Hunk> changes = session.changes();
        if (session.currentIndex() < 0 || session.currentIndex() >= changes.size()) return;
        DiffEngine.Hunk h = changes.get(session.currentIndex());
        if (h.type() == DiffEngine.HunkType.EQUAL) return; // no-op on equal blocks
        if (insertModeSupplier.get() && h.type() == DiffEngine.HunkType.CHANGE) return; // avoid rewrites in insert mode
        if (leftToRight) {
            // only copy if there is content on the left for this hunk
            if (h.leftStart() < h.leftEnd()) {
                List<String> src = session.leftLines().subList(h.leftStart(), h.leftEnd());
                if (h.rightStart() == h.rightEnd()) {
                    insertLines(right.area(), h.rightStart(), src);
                } else {
                    replaceLines(right.area(), h.rightStart(), h.rightEnd(), src);
                }
            }
        } else {
            if (h.rightStart() < h.rightEnd()) {
                List<String> src = session.rightLines().subList(h.rightStart(), h.rightEnd());
                if (h.leftStart() == h.leftEnd()) {
                    insertLines(left.area(), h.leftStart(), src);
                } else {
                    replaceLines(left.area(), h.leftStart(), h.leftEnd(), src);
                }
            }
        }
        recompute.run();
    }

    public void applyDelete(boolean onLeft) {
        List<DiffEngine.Hunk> changes = session.changes();
        if (session.currentIndex() < 0 || session.currentIndex() >= changes.size()) return;
        DiffEngine.Hunk h = changes.get(session.currentIndex());
        if (h.type() == DiffEngine.HunkType.EQUAL) return;
        if (insertModeSupplier.get() && h.type() == DiffEngine.HunkType.CHANGE) return; // avoid rewrites in insert mode
        if (onLeft) {
            if (h.leftStart() < h.leftEnd()) {
                replaceLines(left.area(), h.leftStart(), h.leftEnd(), List.of());
            }
        } else {
            if (h.rightStart() < h.rightEnd()) {
                replaceLines(right.area(), h.rightStart(), h.rightEnd(), List.of());
            }
        }
        recompute.run();
    }

    public void handleArrowCopy(boolean leftToRight, int lineIndex, boolean bulk) {
        List<DiffEngine.Hunk> changes = session.changes();
        DiffEngine.Hunk h = session.findHunkForLine(leftToRight, lineIndex);
        if (h == null) return;
        int idx = changes.indexOf(h);
        if (idx >= 0) {
            session.setCurrentIndex(idx);
            updateNavButtons.run();
        }
        if (bulk) {
            applyCopy(leftToRight);
            return;
        }
        copySingleLine(leftToRight, h, lineIndex);
        recompute.run();
    }

    public void undoSide(boolean leftSide) {
        UndoManager target = getUndoManager(leftSide);
        if (tryUndo(target)) {
            removeLatestForSide(leftSide);
            statusSetter.accept("Undo " + (leftSide ? "LEFT" : "RIGHT"));
        }
    }

    public void undoLastAnySide() {
        Boolean latest = peekLatestEdit();
        if (latest == null) {
            if (left.undoManager().canUndo()) {
                tryUndo(left.undoManager());
                statusSetter.accept("Undo LEFT");
            } else if (right.undoManager().canUndo()) {
                tryUndo(right.undoManager());
                statusSetter.accept("Undo RIGHT");
            }
            return;
        }
        boolean useLeft = latest;
        UndoManager target = useLeft ? left.undoManager() : right.undoManager();
        if (tryUndo(target)) {
            undoStack.pollLast();
            statusSetter.accept("Undo " + (useLeft ? "LEFT" : "RIGHT"));
        } else {
            undoStack.pollLast();
            undoLastAnySide();
        }
    }

    public void resetUndoHistory(EnumSet<EditorSide> sides) {
        if (sides.contains(EditorSide.LEFT)) {
            left.undoManager().discardAllEdits();
            removeAllForSide(true);
        }
        if (sides.contains(EditorSide.RIGHT)) {
            right.undoManager().discardAllEdits();
            removeAllForSide(false);
        }
    }

    public boolean isSuppressUndoCapture() {
        return suppressUndoCapture;
    }

    private void copySingleLine(boolean leftToRight, DiffEngine.Hunk h, int lineIndex) {
        // only copy from the side that has content for this hunk
        if (leftToRight && h.leftStart() >= h.leftEnd()) return;
        if (!leftToRight && h.rightStart() >= h.rightEnd()) return;

        List<String> srcLines = leftToRight ? session.leftLines() : session.rightLines();
        JTextArea target = leftToRight ? right.area() : left.area();

        int srcStart = leftToRight ? h.leftStart() : h.rightStart();
        int dstStart = leftToRight ? h.rightStart() : h.leftStart();

        int offset = Math.max(0, lineIndex - srcStart);
        int destLine = dstStart + offset;
        List<String> payload = List.of(srcLines.get(Math.max(0, Math.min(lineIndex, srcLines.size() - 1))));

        boolean insertOnly = (leftToRight && h.rightStart() == h.rightEnd())
                || (!leftToRight && h.leftStart() == h.leftEnd());
        if (insertOnly) {
            insertLines(target, destLine, payload);
        } else {
            replaceLines(target, destLine, destLine < target.getLineCount() ? destLine + 1 : destLine, payload);
        }
    }

    private void replaceLines(JTextArea area, int startLine, int endLine, List<String> with) {
        try {
            diffController.setSuppressDocEvents(true);
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
            statusSetter.accept("Edit failed: " + ex.getMessage());
            tryDirectReplace(area, startLine, endLine, with);
        } finally {
            diffController.setSuppressDocEvents(false);
        }
    }

    private void insertLines(JTextArea area, int atLine, List<String> with) {
        tryDirectInsert(area, atLine, with);
    }

    private void tryDirectInsert(JTextArea area, int atLine, List<String> with) {
        try {
            String text = area.getText();
            String insertion = String.join("\n", with);
            String[] lines = text.split("\n", -1);
            java.util.List<String> newLines = new java.util.ArrayList<>(java.util.Arrays.asList(lines));
            newLines.add(Math.max(0, Math.min(atLine, newLines.size())), insertion);
            area.setText(String.join("\n", newLines));
        } catch (Exception ignored) {}
    }

    private void tryDirectReplace(JTextArea area, int startLine, int endLine, List<String> with) {
        try {
            String text = area.getText();
            java.util.List<String> lines = new java.util.ArrayList<>(java.util.Arrays.asList(text.split("\n", -1)));
            int s = Math.max(0, Math.min(startLine, lines.size()));
            int e = Math.max(s, Math.min(endLine, lines.size()));
            java.util.List<String> replacement = with == null ? java.util.List.of() : with;
            java.util.List<String> newLines = new java.util.ArrayList<>();
            newLines.addAll(lines.subList(0, s));
            newLines.addAll(replacement);
            newLines.addAll(lines.subList(e, lines.size()));
            area.setText(String.join("\n", newLines));
        } catch (Exception ignored) {}
    }

    private void recordEdit(boolean onLeft) {
        undoStack.addLast(onLeft);
    }

    private Boolean peekLatestEdit() {
        return undoStack.peekLast();
    }

    private boolean removeLatestForSide(boolean leftSide) {
        java.util.Iterator<Boolean> it = undoStack.descendingIterator();
        while (it.hasNext()) {
            Boolean flag = it.next();
            if (flag != null && flag.booleanValue() == leftSide) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    private void removeAllForSide(boolean leftSide) {
        java.util.Iterator<Boolean> it = undoStack.iterator();
        while (it.hasNext()) {
            Boolean flag = it.next();
            if (flag != null && flag.booleanValue() == leftSide) {
                it.remove();
            }
        }
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

    private boolean tryUndo(UndoManager um) {
        try {
            if (um.canUndo()) {
                um.undo();
                return true;
            }
        } catch (CannotUndoException ignored) {}
        return false;
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
        private final MergeController merge;
        private final boolean onLeft;

        ReplaceEdit(javax.swing.text.Document doc, int offset, String before, String after,
                    UndoManager um, javax.swing.event.UndoableEditListener recorder,
                    MergeController merge, boolean onLeft) {
            this.doc = doc;
            this.offset = offset;
            this.before = before;
            this.after = after;
            this.beforeLen = before.length();
            this.afterLen = after.length();
            this.um = um;
            this.recorder = recorder;
            this.merge = merge;
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
                merge.suppressUndoCapture = true;
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
                merge.suppressUndoCapture = false;
            }
        }
    }
}
