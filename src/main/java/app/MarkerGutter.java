package app;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;
import javax.swing.text.Element;

/**
 * MarkerGutter renders line numbers plus click-able arrows for line-by-line copy.
 * - Draws ▶ on LEFT gutter for lines that differ (copy left→right)
 * - Draws ◀ on RIGHT gutter for lines that differ (copy right→left)
 * On click it calls the provided LineAction with (lineIndex, bulkFlag).
 */
public class MarkerGutter extends JComponent {
    public interface LineAction {
        void onLineClicked(int lineIndex, boolean bulk);
    }

    private final JTextArea area;
    private final boolean isLeft; // true if this gutter belongs to left editor
    private final Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 12);
    private java.util.Set<Integer> diffLines = new HashSet<>();
    private LineAction lineAction;
    private java.util.function.BooleanSupplier isBulkSupplier = () -> false; // checked from main app

    // layout numbers
    private int numWidth = 40;  // width segment for line numbers
    private int markWidth = 18; // width segment for arrow marker
    private int pad = 6;

    public MarkerGutter(JTextArea area, boolean isLeft) {
        this.area = area;
        this.isLeft = isLeft;
        setOpaque(true);
        setFont(mono);
        setForeground(new Color(120, 120, 120));
        setBackground(new Color(245, 245, 245));
        setPreferredSize(new Dimension(numWidth + markWidth + pad * 2, Integer.MAX_VALUE));

        // mouse click -> map Y to line index
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int y = e.getY();
                try {
                    int pos = area.viewToModel2D(new Point(0, y));
                    int line = area.getLineOfOffset(pos);
                    if (diffLines.contains(line) && lineAction != null) {
                        boolean bulk = isBulkSupplier.getAsBoolean();
                        lineAction.onLineClicked(line, bulk);
                    }
                } catch (Exception ignored) {}
            }
        });
    }

    public void setDiffLines(Collection<Integer> lines) {
        diffLines = new HashSet<>(lines);
        repaint();
    }

    public void setLineAction(LineAction action) { this.lineAction = action; }
    public void setBulkSupplier(java.util.function.BooleanSupplier bulk) { this.isBulkSupplier = bulk; }

    @Override public void paintComponent(Graphics g) {
        super.paintComponent(g);
        Rectangle clip = g.getClipBounds();
        g.setColor(getBackground());
        g.fillRect(clip.x, clip.y, clip.width, clip.height);

        g.setFont(mono);
        g.setColor(getForeground());

        Element root = area.getDocument().getDefaultRootElement();
        int lc = root.getElementCount();
        int startPos = area.viewToModel2D(new Point(0, clip.y));
        int endPos   = area.viewToModel2D(new Point(0, clip.y + clip.height));

        while (startPos <= endPos) {
            try {
                int line = area.getLineOfOffset(startPos);
                Rectangle r = area.modelToView2D(startPos).getBounds();
                String label = String.valueOf(line + 1);

                // line number
                int numX = pad + (numWidth - getFontMetrics(mono).stringWidth(label));
                int baseline = r.y + getFontMetrics(mono).getAscent();
                g.drawString(label, numX, baseline);

                // arrow marker if this line differs
                if (diffLines.contains(line)) {
                    // draw small rounded pill background to hint clickable
                    int ax = pad + numWidth + 2;
                    int ay = r.y + 2;
                    int aw = markWidth - 4;
                    int ah = Math.min(r.height - 4, 14);
                    g.setColor(new Color(230, 230, 255));
                    g.fillRoundRect(ax, ay, aw, ah, 8, 8);
                    g.setColor(new Color(80, 90, 180));
                    String arrow = isLeft ? "▶" : "◀";
                    // center arrow
                    int tx = ax + (aw - getFontMetrics(mono).stringWidth(arrow)) / 2;
                    int ty = ay + ah - 3;
                    g.drawString(arrow, tx, ty);
                    g.setColor(getForeground());
                }

                // advance to next line
                int lineEnd = area.getLineEndOffset(line);
                startPos = Math.min(lineEnd + 1, area.getDocument().getLength());
                if (line >= lc - 1) break;
            } catch (Exception ex) {
                break;
            }
        }
    }
}
