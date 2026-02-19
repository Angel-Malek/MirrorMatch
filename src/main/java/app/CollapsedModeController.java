package app;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;

import static app.DiffEngine.HunkType;

/**
 * Handles diff-only/collapsed mode transformations.
 */
public class CollapsedModeController {

    private final EditorPane left;
    private final EditorPane right;
    private final DiffSession session;
    private final CenterDiffGutter gutter;
    private final DiffController diffController;

    private boolean collapsedMode = false;
    private List<String> fullLeftBackup = List.of();
    private List<String> fullRightBackup = List.of();
    private List<DiffEngine.Hunk> collapsedHunks = List.of();

    public CollapsedModeController(EditorPane left,
                                   EditorPane right,
                                   DiffSession session,
                                   CenterDiffGutter gutter,
                                   DiffController diffController) {
        this.left = left;
        this.right = right;
        this.session = session;
        this.gutter = gutter;
        this.diffController = diffController;
    }

    public boolean isCollapsedMode() {
        return collapsedMode;
    }

    public boolean enter() {
        if (collapsedMode) return true;
        if (session.changes().isEmpty()) {
            return false;
        }
        fullLeftBackup = List.of(left.area().getText().split("\n", -1));
        fullRightBackup = List.of(right.area().getText().split("\n", -1));
        collapsedHunks = new ArrayList<>(session.changes());

        collapseIntoEditors(true);
        collapseIntoEditors(false);

        collapsedMode = true;
        gutter.setVisible(true);
        session.updateTexts(left.area().getText(), right.area().getText());
        session.updateDiff(session.currentDiff()); // keep same diff object, refresh highlights on collapsed content
        diffController.refreshUIAfterSessionChange();
        return true;
    }

    public void exit() {
        if (!collapsedMode) return;
        collapsedMode = false;
        diffController.setSuppressDocEvents(true);
        try {
            left.area().setText(String.join("\n", fullLeftBackup));
            right.area().setText(String.join("\n", fullRightBackup));
        } finally {
            diffController.setSuppressDocEvents(false);
        }
        DiffEngine.Result diff = DiffEngine.diffLines(left.area().getText(), right.area().getText());
        session.updateDiff(diff);
        session.updateTexts(left.area().getText(), right.area().getText());
        diffController.refreshUIAfterSessionChange();
    }

    private void collapseIntoEditors(boolean leftSide) {
        JTextArea area = leftSide ? left.area() : right.area();
        List<String> base = leftSide ? fullLeftBackup : fullRightBackup;
        StringBuilder sb = new StringBuilder();
        for (DiffEngine.Hunk h : collapsedHunks) {
            if (h.type() == HunkType.EQUAL) continue;
            int start = leftSide ? h.leftStart() : h.rightStart();
            int end = leftSide ? h.leftEnd() : h.rightEnd();
            for (int i = start; i < end; i++) {
                sb.append(i < base.size() ? base.get(i) : "").append("\n");
            }
        }
        diffController.setSuppressDocEvents(true);
        try {
            area.setText(sb.toString());
        } finally {
            diffController.setSuppressDocEvents(false);
        }
    }
}
