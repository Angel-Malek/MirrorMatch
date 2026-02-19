package app;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.util.EnumSet;

/**
 * Centralizes action wiring for buttons/menus/shortcuts.
 */
public class AppActions {

    private final MirrorMatchApp app;
    private final DiffController diffController;
    private final MergeController mergeController;
    private final DiffSession session;
    private final EditorPane left;
    private final EditorPane right;

    public AppActions(MirrorMatchApp app,
                      DiffController diffController,
                      MergeController mergeController,
                      DiffSession session,
                      EditorPane left,
                      EditorPane right) {
        this.app = app;
        this.diffController = diffController;
        this.mergeController = mergeController;
        this.session = session;
        this.left = left;
        this.right = right;
    }

    public void wireToolbar(JButton prevBtn, JButton nextBtn,
                            JButton copyL2R, JButton copyR2L,
                            JButton delLeft, JButton delRight,
                            JButton undoLeft, JButton undoRight, JButton undoAny,
                            JToggleButton diffOnlyToggle, JToggleButton ignoreWsToggle, JToggleButton insertModeToggle,
                            Runnable toggleDiffOnly, Runnable recompute) {
        prevBtn.addActionListener(e -> diffController.gotoDiff(session.currentIndex() - 1));
        nextBtn.addActionListener(e -> diffController.gotoDiff(session.currentIndex() + 1));
        copyL2R.addActionListener(e -> mergeController.applyCopy(true));
        copyR2L.addActionListener(e -> mergeController.applyCopy(false));
        delLeft.addActionListener(e -> mergeController.applyDelete(true));
        delRight.addActionListener(e -> mergeController.applyDelete(false));
        undoLeft.addActionListener(e -> mergeController.undoSide(true));
        undoRight.addActionListener(e -> mergeController.undoSide(false));
        undoAny.addActionListener(e -> mergeController.undoLastAnySide());
        diffOnlyToggle.addActionListener(e -> toggleDiffOnly.run());
        ignoreWsToggle.addActionListener(e -> recompute.run());
        insertModeToggle.addActionListener(e -> recompute.run());
    }

    public void wireMenu(JMenuItem openLeft, JMenuItem openRight,
                         JMenuItem saveLeft, JMenuItem saveRight,
                         JMenuItem saveLeftAs, JMenuItem saveRightAs,
                         JMenuItem exit,
                         JMenuItem recomputeItem,
                         JMenuItem prefsItem,
                         Runnable openPrefs) {
        openLeft.addActionListener(e -> app.openInto(left.area(), true));
        openRight.addActionListener(e -> app.openInto(right.area(), false));
        saveLeft.addActionListener(e -> app.saveFrom(left.area(), true, false));
        saveRight.addActionListener(e -> app.saveFrom(right.area(), false, false));
        saveLeftAs.addActionListener(e -> app.saveFrom(left.area(), true, true));
        saveRightAs.addActionListener(e -> app.saveFrom(right.area(), false, true));
        exit.addActionListener(e -> app.dispose());

        recomputeItem.addActionListener(e -> app.recompute());
        prefsItem.addActionListener(e -> openPrefs.run());
    }

    public void installUndoShortcut(JComponent c) {
        int mask = java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        KeyStroke undoStroke = KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Z, mask);
        c.getInputMap(JComponent.WHEN_FOCUSED).put(undoStroke, "undo-global");
        c.getActionMap().put("undo-global", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                mergeController.undoLastAnySide();
            }
        });
    }

    public void installSaveShortcut(JComponent c, boolean forLeft) {
        int mask = java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        KeyStroke saveStroke = KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_S, mask);
        c.getInputMap(JComponent.WHEN_FOCUSED).put(saveStroke, "save-side");
        c.getActionMap().put("save-side", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                app.saveFrom(forLeft ? left.area() : right.area(), forLeft, false);
            }
        });
    }
}
