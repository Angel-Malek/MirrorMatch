package app;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.text.Element;
import java.util.ArrayList;
import java.util.List;

/**
 * A slim center gutter drawn between editors that shows clickable arrows
 * per changed line (▶ copies LEFT→RIGHT, ◀ copies RIGHT→LEFT).
 *
 * It asks the hosting app (callbacks) to perform the copy when clicked.
 * No ++/-- used in this class.
 */
public class CenterDiffGutter extends JComponent {

    public interface ArrowHandler {
        void onCopyLeftToRight(int lineIndex, boolean bulk);
        void onCopyRightToLeft(int lineIndex, boolean bulk);
        boolean isBulkMode();
    }

    private final JTextArea left;
    private final JTextArea right;
    private ArrowHandler handler;

    // A compact structure of arrow markers aligned to lines
    private static class Marker {
        int line;        // zero-based line index (absolute in that side)
        boolean leftToRight; // true = ▶   false = ◀
        int y;           // cached y pixel for quick hit-testing
        Marker(int line, boolean l2r, int y) { this.line = line; this.leftToRight = l2r; this.y = y; }
    }

    private final List<Marker> markers = new ArrayList<>();

    // styling
    private final Color bg = new Color(245, 247, 252);
    private final Color border = new Color(220, 225, 236);
    private final Color arrowL2R = new Color(76, 132, 255);   // blue-ish
    private final Color arrowR2L = new Color(168, 85, 247);   // violet-ish
    private final Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 12);

    public CenterDiffGutter(JTextArea left, JTextArea right) {
        this.left = left;
        this.right = right;
        setOpaque(true);
        setPreferredSize(new Dimension(36, Integer.MAX_VALUE));
        setMinimumSize(new Dimension(28, 0));
        setMaximumSize(new Dimension(60, Integer.MAX_VALUE));

        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (handler == null) return;
                int y = e.getY();
                // find nearest marker (within same line band)
                Marker hit = findMarkerAtY(y);
                if (hit == null) return;
                boolean bulk = handler.isBulkMode();
                if (hit.leftToRight) handler.onCopyLeftToRight(hit.line, bulk);
                else handler.onCopyRightToLeft(hit.line, bulk);
            }
        });
    }

    public void setHandler(ArrowHandler handler) { this.handler = handler; }

    /**
     * Rebuild markers from current diff (compute y positions from editors).
     * Provide all hunks (EQUAL/DELETE/INSERT/CHANGE) and we’ll populate arrows
     * for the “change-like” parts.
     */
    public void rebuildMarkers(List<DiffEngine.Hunk> hunks) {
        markers.clear();
        try {
            javax.swing.text.Element rootL = left.getDocument().getDefaultRootElement();
            javax.swing.text.Element rootR = right.getDocument().getDefaultRootElement();
            int hl = hunks.size();
            int i = 0;
            while (i < hl) {
                DiffEngine.Hunk h = hunks.get(i);
                if (h.type() != DiffEngine.HunkType.EQUAL) {
                    // LEFT side changed part
                    int ls = h.leftStart();
                    int le = h.leftEnd();
                    int l = ls;
                    while (l < le) {
                        int y = lineToY(left, rootL, l);
                        if (y >= 0) markers.add(new Marker(l, true, y)); // ▶
                        l = l + 1;
                    }
                    // RIGHT side changed part
                    int rs = h.rightStart();
                    int re = h.rightEnd();
                    int r = rs;
                    while (r < re) {
                        int y = lineToY(right, rootR, r);
                        if (y >= 0) markers.add(new Marker(r, false, y)); // ◀
                        r = r + 1;
                    }
                }
                i = i + 1;
            }
        } catch (Exception ignored) {}
        repaint();
    }

    private int lineToY(JTextArea area, Element root, int line) {
        try {
            int lc = root.getElementCount();
            if (lc <= 0) return -1;
            int idx = Math.max(0, Math.min(line, lc - 1));
            Rectangle r = area.modelToView2D(root.getElement(idx).getStartOffset()).getBounds();
            return r.y + (r.height / 2);
        } catch (Exception e) {
            return -1;
        }
    }

    private Marker findMarkerAtY(int y) {
        int tol = 8;
        int m = markers.size();
        int i = 0;
        Marker best = null;
        int bestDy = Integer.MAX_VALUE;
        while (i < m) {
            Marker mk = markers.get(i);
            int dy = Math.abs(mk.y - y);
            if (dy < bestDy && dy <= tol) {
                best = mk;
                bestDy = dy;
            }
            i = i + 1;
        }
        return best;
    }

    @Override public void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // background and borders
            g2.setColor(bg);
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setColor(border);
            g2.drawLine(0, 0, 0, getHeight());
            g2.drawLine(getWidth() - 1, 0, getWidth() - 1, getHeight());

            // draw arrow pills per marker
            int w = getWidth();
            int pillW = Math.max(18, w - 10);
            int pillH = 16;
            int rx = (w - pillW) / 2;
            int arc = 10;

            int i = 0;
            int n = markers.size();
            while (i < n) {
                Marker mk = markers.get(i);
                int yCenter = mk.y;
                int yTop = yCenter - (pillH / 2);
                if (mk.leftToRight) g2.setColor(new Color(235, 243, 255));
                else g2.setColor(new Color(243, 235, 255));
                g2.fillRoundRect(rx, yTop, pillW, pillH, arc, arc);

                g2.setFont(mono);
                if (mk.leftToRight) g2.setColor(arrowL2R);
                else g2.setColor(arrowR2L);

                String arrow = mk.leftToRight ? "▶" : "◀";
                int sw = g2.getFontMetrics().stringWidth(arrow);
                int sh = g2.getFontMetrics().getAscent();
                int tx = rx + (pillW - sw) / 2;
                int ty = yTop + ((pillH + sh) / 2) - 2;
                g2.drawString(arrow, tx, ty);

                i = i + 1;
            }
        } finally {
            g2.dispose();
        }
    }
}
