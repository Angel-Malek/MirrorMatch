package app;

import org.junit.Test;

import javax.swing.*;
import java.util.EnumSet;

import static org.junit.Assert.assertEquals;

public class MergeControllerTest {

    private static class StubSuppressor implements DocEventSuppressor {
        private boolean suppress;
        @Override public void setSuppressDocEvents(boolean suppress) { this.suppress = suppress; }
        @Override public boolean isSuppressDocEvents() { return suppress; }
    }

    @Test
    public void insertModeCopiesMissingLineWithoutOverwrite() {
        EditorPane left = new EditorPane("Left");
        EditorPane right = new EditorPane("Right");
        DiffSession session = new DiffSession();
        session.updateTexts("Lima\nMike\nNovember\n", "Lima\nNovember\n");
        session.updateDiff(new DiffEngine.Result(java.util.List.of(
                new DiffEngine.Hunk(DiffEngine.HunkType.EQUAL, 0, 1, 0, 1),
                new DiffEngine.Hunk(DiffEngine.HunkType.DELETE, 1, 2, 1, 1),
                new DiffEngine.Hunk(DiffEngine.HunkType.EQUAL, 2, 3, 1, 2)
        )));
        session.setCurrentIndex(0);

        left.area().setText("Lima\nMike\nNovember\n");
        right.area().setText("Lima\nNovember\n");

        StubSuppressor suppressor = new StubSuppressor();
        MergeController mc = new MergeController(
                left,
                right,
                session,
                suppressor,
                () -> {},
                () -> {},
                s -> {},
                () -> true
        );

        try {
            SwingUtilities.invokeAndWait(() -> mc.applyCopy(true)); // copy LEFT -> RIGHT for delete hunk (missing line on right)
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        String actual = right.area().getText();
        if (!"Lima\nMike\nNovember\n".equals(actual)) {
            throw new AssertionError("Actual right text was:\n" + actual);
        }
    }
}
