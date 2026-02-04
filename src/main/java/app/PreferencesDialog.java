package app;

import javax.swing.*;
import java.awt.*;

public final class PreferencesDialog {

    public record Result(int fontSize, String themeName) {}

    private PreferencesDialog() {}

    public static Result show(Component parent, int currentFontSize, String currentTheme) {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(parent), "Preferences", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setLayout(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(6, 8, 6, 8);
        gc.anchor = GridBagConstraints.WEST;

        JLabel fontLbl = new JLabel("Editor font size:");
        JSpinner fontSpinner = new JSpinner(new SpinnerNumberModel(currentFontSize, 10, 32, 1));

        JLabel themeLbl = new JLabel("Theme:");
        JComboBox<String> themeBox = new JComboBox<>(new String[]{"Light", "Soft Dark"});
        themeBox.setSelectedItem(currentTheme);

        gc.gridx = 0; gc.gridy = 0;
        dialog.add(fontLbl, gc);
        gc.gridx = 1;
        dialog.add(fontSpinner, gc);

        gc.gridx = 0; gc.gridy = 1;
        dialog.add(themeLbl, gc);
        gc.gridx = 1;
        dialog.add(themeBox, gc);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton ok = new JButton("OK");
        JButton cancel = new JButton("Cancel");
        buttons.add(cancel);
        buttons.add(ok);
        gc.gridx = 0; gc.gridy = 2; gc.gridwidth = 2;
        gc.anchor = GridBagConstraints.EAST;
        dialog.add(buttons, gc);

        final Result[] result = new Result[1];

        ok.addActionListener(e -> {
            int size = (int) fontSpinner.getValue();
            String theme = (String) themeBox.getSelectedItem();
            result[0] = new Result(size, theme);
            dialog.dispose();
        });
        cancel.addActionListener(e -> {
            result[0] = null;
            dialog.dispose();
        });

        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);

        return result[0];
    }
}
