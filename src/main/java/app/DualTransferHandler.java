package app;

import javax.swing.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.Transferable;

/**
 * Delegates drops to a file drop handler, and clipboard operations to the original handler.
 * This restores copy/paste while keeping custom file drops.
 */
public class DualTransferHandler extends TransferHandler {
    private final TransferHandler dropHandler;
    private final TransferHandler baseHandler;

    public DualTransferHandler(TransferHandler dropHandler, TransferHandler baseHandler) {
        this.dropHandler = dropHandler;
        this.baseHandler = baseHandler;
    }

    @Override
    public boolean canImport(TransferSupport support) {
        if (support.isDrop() && dropHandler != null) {
            return dropHandler.canImport(support);
        }
        return baseHandler != null && baseHandler.canImport(support);
    }

    @Override
    public boolean importData(TransferSupport support) {
        if (support.isDrop() && dropHandler != null) {
            return dropHandler.importData(support);
        }
        return baseHandler != null && baseHandler.importData(support);
    }

    @Override
    public void exportToClipboard(JComponent comp, Clipboard clip, int action) {
        if (baseHandler != null) {
            baseHandler.exportToClipboard(comp, clip, action);
        }
    }

    @Override
    public int getSourceActions(JComponent c) {
        return baseHandler != null ? baseHandler.getSourceActions(c) : NONE;
    }

}
