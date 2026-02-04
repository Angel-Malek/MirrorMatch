package app;

import java.util.ArrayList;
import java.util.List;

import app.DiffEngine.HunkType;

/**
 * Holds current diff data and helper utilities that are text-only (no Swing).
 */
public class DiffSession {

    public record InlineSpan(int start, int endLeft, int endRight) {}

    private DiffEngine.Result currentDiff = DiffEngine.diffLines("", "");
    private List<DiffEngine.Hunk> changes = currentDiff.changeHunks();
    private int currentIndex = -1;
    private List<String> leftLines = List.of("");
    private List<String> rightLines = List.of("");

    public DiffEngine.Result currentDiff() { return currentDiff; }
    public List<DiffEngine.Hunk> changes() { return changes; }
    public int currentIndex() { return currentIndex; }
    public void setCurrentIndex(int idx) { this.currentIndex = idx; }
    public List<String> leftLines() { return leftLines; }
    public List<String> rightLines() { return rightLines; }

    public void updateTexts(String leftText, String rightText) {
        leftLines = List.of(leftText.split("\n", -1));
        rightLines = List.of(rightText.split("\n", -1));
    }

    public void updateDiff(DiffEngine.Result diff) {
        this.currentDiff = diff;
        this.changes = diff.changeHunks();
    }

    public String safeLine(boolean leftSide, int idx) {
        List<String> lines = leftSide ? leftLines : rightLines;
        if (idx < 0 || idx >= lines.size()) return "";
        return lines.get(idx);
    }

    public DiffEngine.Hunk findHunkForLine(boolean usingLeftSide, int lineIndex) {
        int n = currentDiff.hunks.size();
        int i = 0;
        while (i < n) {
            DiffEngine.Hunk h = currentDiff.hunks.get(i);
            if (h.type() != HunkType.EQUAL) {
                if (usingLeftSide && lineIndex >= h.leftStart() && lineIndex < h.leftEnd()) {
                    return h;
                }
                if (!usingLeftSide && lineIndex >= h.rightStart() && lineIndex < h.rightEnd()) {
                    return h;
                }
            }
            i = i + 1;
        }
        return null;
    }

    public InlineSpan computeInlineSpan(String l, String r) {
        int lenL = l.length();
        int lenR = r.length();
        int min = Math.min(lenL, lenR);
        int prefix = 0;
        while (prefix < min && l.charAt(prefix) == r.charAt(prefix)) {
            prefix = prefix + 1;
        }
        int suffix = 0;
        while (suffix < min - prefix && l.charAt(lenL - 1 - suffix) == r.charAt(lenR - 1 - suffix)) {
            suffix = suffix + 1;
        }
        int start = prefix;
        int endL = lenL - suffix;
        int endR = lenR - suffix;
        if (start >= endL && start >= endR) return null;
        return new InlineSpan(start, endL, endR);
    }

    public String buildDiffOnlyText(boolean leftSide) {
        List<String> sourceLines = leftSide ? leftLines : rightLines;
        StringBuilder sb = new StringBuilder();
        int lastSeen = 0;
        int i = 0;
        int n = currentDiff.hunks.size();
        while (i < n) {
            DiffEngine.Hunk h = currentDiff.hunks.get(i);
            int start = leftSide ? h.leftStart() : h.rightStart();
            int end = leftSide ? h.leftEnd() : h.rightEnd();

            if (h.type() == HunkType.EQUAL) {
                lastSeen = end;
                i = i + 1;
                continue;
            }

            if (start > lastSeen) {
                sb.append("… ").append(start - lastSeen).append(" unchanged …\n");
            }

            int line = start;
            while (line < end) {
                sb.append(String.format("%5d │ %s%n", line + 1, safeLine(leftSide, line)));
                line = line + 1;
            }

            if (start == end) {
                int count = Math.abs((leftSide ? h.rightEnd() - h.rightStart() : h.leftEnd() - h.leftStart()));
                String note = leftSide ? "only on right" : "only on left";
                sb.append(String.format("%5d │ [%d line(s) %s]%n", start + 1, count, note));
            }
            lastSeen = end;
            i = i + 1;
        }
        if (sb.length() == 0) {
            sb.append("No differences.");
        }
        return sb.toString();
    }
}
