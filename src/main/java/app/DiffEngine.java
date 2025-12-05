package app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

/**
 * DiffEngine
 * ----------
 * Fast line-based diff using Myers O(ND) algorithm with line hashing.
 * Produces "hunks" (EQUAL / DELETE / INSERT / CHANGE) over line ranges.
 * No use of ++ or -- operators (per user request).
 */
public class DiffEngine {

    public enum HunkType { EQUAL, INSERT, DELETE, CHANGE }

    public static record Hunk(HunkType type, int leftStart, int leftEnd, int rightStart, int rightEnd) {
        @Override public String toString() {
            return type + " L[" + leftStart + "," + leftEnd + ") R[" + rightStart + "," + rightEnd + ")";
        }
        public boolean isChangeLike() { return type != HunkType.EQUAL; }
    }

    public static class Result {
        public final List<Hunk> hunks;
        public Result(List<Hunk> hunks) { this.hunks = hunks; }
        public List<Hunk> changeHunks() {
            List<Hunk> out = new ArrayList<>();
            int i = 0;
            int n = hunks.size();
            while (i < n) {
                Hunk h = hunks.get(i);
                if (h.isChangeLike()) out.add(h);
                i = i + 1;
            }
            return out;
        }
    }

    /** Keep simple entry point; delegates to the fast path. */
    public static Result diffLines(String left, String right) {
        return diffLinesFast(left, right);
    }

    /** Faster line-based diff using Myers O(ND) with line hashing. */
    public static Result diffLinesFast(String left, String right) {
        return diffLinesNormalized(left, right, s -> s);
    }

    public static Result diffLinesNormalized(String left, String right, java.util.function.Function<String, String> normalizer) {
        List<String> L = splitFast(left);
        List<String> R = splitFast(right);

        if (normalizer != null) {
            L = normalizeList(L, normalizer);
            R = normalizeList(R, normalizer);
        }

        // Map lines to ints to speed equality checks
        IntMapper mapper = new IntMapper();
        int[] a = mapper.map(L);
        int[] b = mapper.map(R);

        List<Op> ops = myers(a, b); // EQUAL/DELETE/INSERT runs
        List<Hunk> hunks = coalesceToHunks(ops);
        return new Result(hunks);
    }

    private static List<String> normalizeList(List<String> src, java.util.function.Function<String, String> norm) {
        List<String> out = new ArrayList<>(src.size());
        int i = 0;
        int n = src.size();
        while (i < n) {
            out.add(norm.apply(src.get(i)));
            i = i + 1;
        }
        return out;
    }

    /* ===================== Helpers ===================== */

    private static List<String> splitFast(String s) {
        List<String> out = new ArrayList<>();
        int start = 0;
        int n = s.length();
        int i = 0;
        while (i < n) {
            if (s.charAt(i) == '\n') {
                out.add(s.substring(start, i));
                start = i + 1;
            }
            i = i + 1;
        }
        out.add(s.substring(start)); // keep trailing "line" (possibly empty)
        return out;
    }

    private static class IntMapper {
        private final HashMap<String, Integer> map = new HashMap<>();
        private int next = 1;

        int id(String key) {
            Integer cur = map.get(key);
            if (cur != null) return cur;
            int ret = next;
            map.put(key, ret);
            next = next + 1;
            return ret;
        }

        int[] map(List<String> lines) {
            int[] arr = new int[lines.size()];
            int i = 0;
            int n = lines.size();
            while (i < n) {
                arr[i] = id(lines.get(i));
                i = i + 1;
            }
            return arr;
        }
    }

    /* -------------------- Myers core (with backtrack) -------------------- */

    /** op.type: 0=EQUAL, 1=DELETE, 2=INSERT */
    private static class Op {
        final int type;
        final int aStart, aEnd, bStart, bEnd;
        Op(int type, int aStart, int aEnd, int bStart, int bEnd) {
            this.type = type; this.aStart = aStart; this.aEnd = aEnd; this.bStart = bStart; this.bEnd = bEnd;
        }
    }

    private static List<Op> myers(int[] a, int[] b) {
        int N = a.length;
        int M = b.length;
        int MAX = N + M;
        int size = 2 * MAX + 1;

        int[] V = new int[size];
        List<int[]> trace = new ArrayList<>();

        int D = 0;
        while (D <= MAX) {
            int[] cur = Arrays.copyOf(V, V.length);

            int k = -D;
            while (k <= D) {
                int idx = k + MAX;
                int x;
                boolean chooseDown;
                if (k == -D) {
                    chooseDown = true;
                } else {
                    if (k != D) {
                        int leftVal = V[idx - 1];
                        int rightVal = V[idx + 1];
                        chooseDown = leftVal < rightVal;
                    } else {
                        chooseDown = false;
                    }
                }

                if (chooseDown) {
                    x = V[idx + 1];
                } else {
                    x = V[idx - 1] + 1;
                }
                int y = x - k;

                // snake
                while (x < N && y < M && a[x] == b[y]) {
                    x = x + 1;
                    y = y + 1;
                }

                cur[idx] = x;

                if (x >= N && y >= M) {
                    trace.add(cur);
                    return backtrack(a, b, trace, D, MAX);
                }

                k = k + 2;
            }

            trace.add(cur);
            V = cur;
            D = D + 1;
        }
        return List.of();
    }

    private static List<Op> backtrack(int[] a, int[] b, List<int[]> trace, int D, int MAX) {
        LinkedList<Op> ops = new LinkedList<>();
        int x = a.length;
        int y = b.length;

        int d = D;
        while (d > 0) {
            int[] V = trace.get(d - 1);
            int k = x - y;
            int idxK = k + MAX;

            int prevK;
            boolean goDown;
            if (k == -d) {
                goDown = true;
            } else {
                if (k != d) {
                    int leftVal = V[idxK - 1];
                    int rightVal = V[idxK + 1];
                    goDown = leftVal < rightVal;
                } else {
                    goDown = false;
                }
            }
            if (goDown) {
                prevK = k + 1; // insertion
            } else {
                prevK = k - 1; // deletion
            }

            int xPrev = V[prevK + MAX];
            int yPrev = xPrev - prevK;

            // snake (equals)
            while (x > xPrev && y > yPrev) {
                ops.addFirst(new Op(0, x - 1, x, y - 1, y));
                x = x - 1;
                y = y - 1;
            }

            // the edit step
            if (xPrev < x) {
                ops.addFirst(new Op(1, xPrev, x, yPrev, yPrev)); // delete
                x = xPrev;
                y = yPrev;
            } else if (yPrev < y) {
                ops.addFirst(new Op(2, xPrev, xPrev, yPrev, y)); // insert
                x = xPrev;
                y = yPrev;
            }

            d = d - 1;
        }

        // leading equals (if any)
        while (x > 0 && y > 0) {
            ops.addFirst(new Op(0, x - 1, x, y - 1, y));
            x = x - 1;
            y = y - 1;
        }
        while (x > 0) {
            ops.addFirst(new Op(1, x - 1, x, 0, 0));
            x = x - 1;
        }
        while (y > 0) {
            ops.addFirst(new Op(2, 0, 0, y - 1, y));
            y = y - 1;
        }

        // Merge consecutive same-type ops into runs
        List<Op> runs = new ArrayList<>();
        int i = 0;
        int n = ops.size();
        while (i < n) {
            Op op = ops.get(i);
            if (!runs.isEmpty()) {
                Op last = runs.get(runs.size() - 1);
                if (last.type == op.type) {
                    int newAStart = Math.min(last.aStart, op.aStart);
                    int newAEnd   = Math.max(last.aEnd,   op.aEnd);
                    int newBStart = Math.min(last.bStart, op.bStart);
                    int newBEnd   = Math.max(last.bEnd,   op.bEnd);
                    runs.set(runs.size() - 1, new Op(last.type, newAStart, newAEnd, newBStart, newBEnd));
                } else {
                    runs.add(op);
                }
            } else {
                runs.add(op);
            }
            i = i + 1;
        }
        return runs;
    }

    /* -------------------- Convert ops → hunks -------------------- */

    private static List<Hunk> coalesceToHunks(List<Op> ops) {
        List<Hunk> hunks = new ArrayList<>();
        int i = 0;
        int n = ops.size();
        while (i < n) {
            Op op = ops.get(i);
            HunkType t;
            if (op.type == 0) t = HunkType.EQUAL;
            else if (op.type == 1) t = HunkType.DELETE;
            else if (op.type == 2) t = HunkType.INSERT;
            else t = HunkType.CHANGE;

            Hunk h;
            if (t == HunkType.EQUAL) {
                h = new Hunk(HunkType.EQUAL, op.aStart, op.aEnd, op.bStart, op.bEnd);
            } else if (t == HunkType.DELETE) {
                h = new Hunk(HunkType.DELETE, op.aStart, op.aEnd, op.bStart, op.bStart);
            } else if (t == HunkType.INSERT) {
                h = new Hunk(HunkType.INSERT, op.aStart, op.aStart, op.bStart, op.bEnd);
            } else {
                h = new Hunk(HunkType.CHANGE, op.aStart, op.aEnd, op.bStart, op.bEnd);
            }

            if (!hunks.isEmpty()) {
                Hunk last = hunks.get(hunks.size() - 1);
                boolean bothChangeLike = (last.type != HunkType.EQUAL) && (h.type != HunkType.EQUAL);
                boolean touching = (last.leftEnd == h.leftStart) && (last.rightEnd == h.rightStart);
                if (bothChangeLike && touching) {
                    Hunk merged = new Hunk(
                            HunkType.CHANGE,
                            last.leftStart, h.leftEnd,
                            last.rightStart, h.rightEnd
                    );
                    hunks.set(hunks.size() - 1, merged);
                    i = i + 1;
                    continue;
                }
            }

            hunks.add(h);
            i = i + 1;
        }
        return hunks;
    }
}
