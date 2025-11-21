package app;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;

/** Lightweight line number gutter bound to a JTextArea. */
public class LineNumberGutter extends JComponent implements DocumentListener, PropertyChangeListener {
    private final JTextArea textArea;
    private final Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 12);
    private int lineCountCache = 1;

    public LineNumberGutter(JTextArea textArea) {
        this.textArea = textArea;
        setFont(mono);
        setForeground(new Color(120, 120, 120));
        setBackground(new Color(245, 245, 245));
        setOpaque(true);
        textArea.getDocument().addDocumentListener(this);
        textArea.addPropertyChangeListener(this);
        setPreferredWidth();
    }

    private void setPreferredWidth() {
        int lines = Math.max(1, textArea.getLineCount());
        int digits = String.valueOf(lines).length();
        int width = 10 + getFontMetrics(mono).charWidth('0') * digits + 10;
        setPreferredSize(new Dimension(width, Integer.MAX_VALUE));
        revalidate();
    }

    @Override public void paintComponent(Graphics g) {
        super.paintComponent(g);
        Rectangle clip = g.getClipBounds();
        g.setColor(getBackground());
        g.fillRect(clip.x, clip.y, clip.width, clip.height);

        g.setColor(getForeground());
        g.setFont(mono);

        int start = textArea.viewToModel2D(new Point(0, clip.y));
        int end   = textArea.viewToModel2D(new Point(0, clip.y + clip.height));

        while (start <= end) {
            try {
                int line = textArea.getLineOfOffset(start);
                int y = textArea.modelToView2D(start).getBounds().y;
                String label = String.valueOf(line + 1);
                int ascent = getFontMetrics(mono).getAscent();
                g.drawString(label, getWidth() - 10 - getFontMetrics(mono).stringWidth(label), y + ascent);
                start = textArea.getLineEndOffset(line) + 1;
            } catch (Exception ex) {
                break;
            }
        }
    }

    // document listener
    @Override public void insertUpdate(DocumentEvent e) { maybeUpdate(); }
    @Override public void removeUpdate(DocumentEvent e) { maybeUpdate(); }
    @Override public void changedUpdate(DocumentEvent e) { maybeUpdate(); }
    private void maybeUpdate() {
        int lc = textArea.getLineCount();
        if (lc != lineCountCache) {
            lineCountCache = lc;
            setPreferredWidth();
            repaint();
        }
    }

    @Override public void propertyChange(PropertyChangeEvent evt) {
        if ("font".equals(evt.getPropertyName())) {
            repaint();
        }
    }
}
