package app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Centralized file loader that can read plain text or Excel files.
 * Excel files are converted to a tab-separated text representation per sheet.
 * Returns whether the source was Excel so the caller can treat it as read-only.
 */
class FileContentLoader {

    record LoadedContent(String text, boolean fromExcel) {}

    static LoadedContent load(Path path) throws IOException {
        String name = path.getFileName().toString().toLowerCase();
        if (isExcel(name)) {
            return new LoadedContent(readExcel(path), true);
        }
        return new LoadedContent(Files.readString(path), false);
    }

    static boolean isExcel(Path path) {
        if (path == null) return false;
        return isExcel(path.getFileName().toString().toLowerCase());
    }

    private static boolean isExcel(String name) {
        return name.endsWith(".xlsx") || name.endsWith(".xls");
    }

    private static String readExcel(Path path) throws IOException {
        try (var in = Files.newInputStream(path);
             var workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(in)) {
            return ExcelTextCodec.toText(workbook);
        } catch (Exception ex) {
            throw new IOException("Failed to read Excel: " + ex.getMessage(), ex);
        }
    }
}
