package app;

import javax.swing.*;
import java.awt.Component;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Drag & drop handler for MirrorMatch.
 *
 * Side is inferred from where you drop:
 *  - Drop over LEFT editor/scroll -> primary side is left
 *  - Drop over RIGHT editor/scroll -> primary side is right
 *  - Drop over background/root -> auto, left as primary
 *
 * One file:
 *  - goes to primary side.
 *
 * Two files:
 *  - first file -> primary side
 *  - second file -> other side
 */
class FileDropHandler extends TransferHandler {

    private final MirrorMatchApp app;

    FileDropHandler(MirrorMatchApp app) {
        this.app = app;
    }

    @Override
    public int getSourceActions(JComponent c) {
        return COPY;
    }

    @Override
    public boolean canImport(TransferSupport support) {
        if (!support.isDrop()) return false;
        support.setDropAction(COPY);
        return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                || support.isDataFlavorSupported(DataFlavor.stringFlavor);
    }

    @Override
    public boolean importData(TransferSupport support) {
        if (!canImport(support)) return false;
        try {
            List<Path> paths = extractPaths(support.getTransferable());
            if (paths.isEmpty()) return false;

            Component target = support.getComponent();
            boolean overLeft = SwingUtilities.isDescendingFrom(target, app.getLeftArea());
            boolean overRight = SwingUtilities.isDescendingFrom(target, app.getRightArea());

            boolean primaryLeft;
            if (overLeft) {
                primaryLeft = true;
            } else if (overRight) {
                primaryLeft = false;
            } else {
                primaryLeft = true; // background: default to left
            }

            if (paths.size() >= 2) {
                Path p1 = paths.get(0);
                Path p2 = paths.get(1);
                String t1 = Files.readString(p1);
                String t2 = Files.readString(p2);
                if (primaryLeft) {
                    app.handleBothDrop(p1, t1, p2, t2);
                } else {
                    // dropped on right: swap assignment
                    app.handleBothDrop(p2, t2, p1, t1);
                }
                return true;
            }

            // only one file
            Path p = paths.get(0);
            String content = Files.readString(p);

            boolean finalLeft;
            if (overLeft) {
                finalLeft = true;
            } else if (overRight) {
                finalLeft = false;
            } else {
                // root/unknown: if left empty use left, otherwise right
                finalLeft = app.isLeftEmpty();
            }

            app.handleSingleDrop(finalLeft, p, content);
            return true;

        } catch (Exception ex) {
            app.handleDropError("Drop failed: " + ex.getMessage());
            return false;
        }
    }

    private List<Path> extractPaths(Transferable transferable) throws Exception {
        List<Path> paths = new ArrayList<>();
        if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            @SuppressWarnings("unchecked")
            List<java.io.File> files =
                    (List<java.io.File>) transferable.getTransferData(DataFlavor.javaFileListFlavor);
            int i = 0;
            int n = files.size();
            while (i < n) {
                paths.add(files.get(i).toPath());
                i = i + 1;
            }
        } else if (transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
            String data = (String) transferable.getTransferData(DataFlavor.stringFlavor);
            String[] lines = data.split("\\r?\\n");
            int i = 0;
            int n = lines.length;
            while (i < n) {
                String s = lines[i].trim();
                if (!s.isEmpty()) {
                    if (s.startsWith("file://")) {
                        java.net.URI uri = new java.net.URI(s);
                        paths.add(Path.of(uri));
                    } else {
                        paths.add(Path.of(s));
                    }
                }
                i = i + 1;
            }
        }
        return paths;
    }
}
