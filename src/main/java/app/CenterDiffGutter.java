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

    private static class Marker {
        int line;
        boolean leftToRight; // true = ▶   false = ◀
        Marker(int line, boolean l2r) { this.line = line; this.leftToRight = l2r; }
    }

    private static class VisibleMarker {
        Marker marker;
        int y; // center y relative to gutter
        VisibleMarker(Marker m, int y) { this.marker = m; this.y = y; }
    }

    private final List<Marker> markersL2R = new ArrayList<>();
    private final List<Marker> markersR2L = new ArrayList<>();

    // styling
    private final Color bg = new Color(245, 247, 252);
    private final Color border = new Color(220, 225, 236);
    private final Color arrowL2R = new Color(76, 132, 255);   // blue-ish
    private final Color arrowR2L = new Color(168, 85, 247);   // violet-ish
    private final Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 12);
    private final Color stackGlow = new Color(120, 140, 200, 90);

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
                int x = e.getX();
                // find nearest marker within its rendered pill bounds
                Marker hit = findMarkerAtPoint(x, y);
                if (hit == null) return;
                boolean bulk = handler.isBulkMode();
                int targetLine = mapClickToLine(hit, y);
                if (hit.leftToRight) handler.onCopyLeftToRight(targetLine, bulk);
                else handler.onCopyRightToLeft(targetLine, bulk);
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
        markersL2R.clear();
        markersR2L.clear();
        try {
            javax.swing.text.Element rootL = left.getDocument().getDefaultRootElement();
            javax.swing.text.Element rootR = right.getDocument().getDefaultRootElement();

            java.util.Set<Integer> leftLines = new java.util.LinkedHashSet<>();
            java.util.Set<Integer> rightLines = new java.util.LinkedHashSet<>();

            int hl = hunks.size();
            int i = 0;
            while (i < hl) {
                DiffEngine.Hunk h = hunks.get(i);
                if (h.type() != DiffEngine.HunkType.EQUAL) {
                    rangeToList(h.leftStart(), h.leftEnd(), leftLines);
                    rangeToList(h.rightStart(), h.rightEnd(), rightLines);
                }
                i = i + 1;
            }

            for (int line : leftLines) markersL2R.add(new Marker(line, true));  // left -> right
            for (int line : rightLines) markersR2L.add(new Marker(line, false)); // right -> left
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

    private void rangeToList(int start, int end, java.util.Set<Integer> into) {
        int i = start;
        while (i < end) {
            into.add(i);
            i = i + 1;
        }
    }

    private Marker findMarkerAtPoint(int x, int y) {
        int w = getWidth();
        int laneW = Math.max(16, (w - 12) / 2);
        int leftRx = 4;
        int rightRx = leftRx + laneW + 4;
        int pillH = 16;
        Marker best = null;
        int bestDy = Integer.MAX_VALUE;

        List<VisibleMarker> vis = visibleMarkers();
        int i = 0;
        int n = vis.size();
        while (i < n) {
            VisibleMarker vm = vis.get(i);
            int rx = vm.marker.leftToRight ? leftRx : rightRx;
            int yMin = vm.y - (pillH / 2);
            int yMax = vm.y + (pillH / 2);
            int yCenter = vm.y;
            if (x >= rx && x <= rx + laneW && y >= yMin && y <= yMax) {
                int dy = Math.abs(yCenter - y);
                if (dy < bestDy) {
                    best = vm.marker;
                    bestDy = dy;
                }
            }
            i = i + 1;
        }
        return best;
    }

    private int mapClickToLine(Marker mk, int y) {
        return mk.line;
    }

    private int visibleYOffset(boolean leftSide) {
        Rectangle vr = (leftSide ? left : right).getVisibleRect();
        return -vr.y;
    }

    private List<VisibleMarker> visibleMarkers() {
        List<VisibleMarker> out = new ArrayList<>();
        addVisibleForList(markersL2R, left, out);
        addVisibleForList(markersR2L, right, out);
        out.sort(java.util.Comparator.comparingInt(vm -> vm.y));
        return out;
    }

    private void addVisibleForList(List<Marker> src, JTextArea area, List<VisibleMarker> out) {
        int i = 0;
        int n = src.size();
        while (i < n) {
            Marker mk = src.get(i);
            try {
                javax.swing.text.Element root = area.getDocument().getDefaultRootElement();
                int lc = root.getElementCount();
                if (lc <= 0) { i = i + 1; continue; }
                int idx = Math.max(0, Math.min(mk.line, lc - 1));
                Rectangle r = area.modelToView2D(root.getElement(idx).getStartOffset()).getBounds();
                Point p = new Point(0, r.y + (r.height / 2));
                SwingUtilities.convertPointToScreen(p, area);
                SwingUtilities.convertPointFromScreen(p, this);
                if (p.y > -40 && p.y < getHeight() + 40) {
                    out.add(new VisibleMarker(mk, p.y));
                }
            } catch (Exception ignored) {}
            i = i + 1;
        }
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

            List<VisibleMarker> vis = visibleMarkers();

            int laneW = Math.max(16, (w - 12) / 2);
            int leftRx = 4;
            int rightRx = leftRx + laneW + 4;

            drawLane(g2, vis, true, leftRx, laneW, pillH, arc);
            drawLane(g2, vis, false, rightRx, laneW, pillH, arc);
        } finally {
            g2.dispose();
        }
    }

    private void drawLane(Graphics2D g2, List<VisibleMarker> vis, boolean l2r, int rx, int laneW, int pillH, int arc) {
        int i = 0;
        int n = vis.size();
        while (i < n) {
            VisibleMarker vm = vis.get(i);
            if (vm.marker.leftToRight != l2r) { i = i + 1; continue; }

            int runStart = i;
            int runEnd = i;
            while (runEnd + 1 < n) {
                VisibleMarker next = vis.get(runEnd + 1);
                boolean sameDir = next.marker.leftToRight == l2r;
                boolean consecutive = next.marker.line == vis.get(runEnd).marker.line + 1;
                if (sameDir && consecutive) {
                    runEnd = runEnd + 1;
                } else {
                    break;
                }
            }

            if (runEnd > runStart) {
                int spanTop = vis.get(runStart).y - (pillH / 2) - 6;
                int spanBottom = vis.get(runEnd).y + (pillH / 2) + 6;
                g2.setColor(stackGlow);
                g2.fillRoundRect(rx, spanTop, laneW, spanBottom - spanTop, arc, arc);
                g2.setColor(new Color(90, 105, 160, 80));
                int hy = spanTop;
                while (hy < spanBottom) {
                    g2.drawLine(rx + 3, hy, rx + laneW - 3, hy + 6);
                    hy = hy + 6;
                }
            }

            int j = runStart;
            while (j <= runEnd) {
                VisibleMarker cur = vis.get(j);
                int yCenter = cur.y;
                int yTop = yCenter - (pillH / 2);
                int yBottom = yCenter + (pillH / 2);

                if (cur.marker.leftToRight) g2.setColor(new Color(235, 243, 255));
                else g2.setColor(new Color(243, 235, 255));
                g2.fillRoundRect(rx, yTop, laneW, pillH, arc, arc);

                g2.setFont(mono);
                if (cur.marker.leftToRight) g2.setColor(arrowL2R);
                else g2.setColor(arrowR2L);

                String arrow = cur.marker.leftToRight ? "▶" : "◀";
                int sw = g2.getFontMetrics().stringWidth(arrow);
                int sh = g2.getFontMetrics().getAscent();
                int tx = rx + (laneW - sw) / 2;
                int ty = yTop + ((pillH + sh) / 2) - 2;
                g2.drawString(arrow, tx, ty);

                j = j + 1;
            }

            if (runEnd > runStart) {
                int count = (runEnd - runStart) + 1;
                VisibleMarker last = vis.get(runEnd);
                int yBottom = last.y + (pillH / 2);
                String label = String.valueOf(count);
                int lw = g2.getFontMetrics().stringWidth(label);
                int lh = g2.getFontMetrics().getAscent();
                int badgeW = Math.max(14, lw + 8);
                int badgeH = 14;
                int bx = rx + laneW - badgeW - 4;
                int by = yBottom + 2;
                g2.setColor(new Color(50, 60, 90, 210));
                g2.fillRoundRect(bx, by, badgeW, badgeH, 8, 8);
                g2.setColor(Color.WHITE);
                g2.drawString(label, bx + (badgeW - lw) / 2, by + ((badgeH + lh) / 2) - 2);
            }

            i = runEnd + 1;
        }
    }
}
