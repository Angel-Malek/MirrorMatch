package app;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Converts between Excel workbooks and a tab-separated text representation.
 * Text format:
 *   # Sheet: <name>
 *   col1 \t col2 \t ...
 * Empty lines are preserved. Values are stored as strings on round-trip.
 */
class ExcelTextCodec {

    static String toText(Workbook workbook) throws IOException {
        DataFormatter fmt = new DataFormatter();
        StringBuilder sb = new StringBuilder();
        int sheetIndex = 0;

        for (Sheet sheet : workbook) {
            if (sheetIndex > 0) sb.append("\n");
            sb.append("# Sheet: ").append(sheet.getSheetName()).append("\n");

            int lastRow = sheet.getLastRowNum();
            int rowIdx = 0;
            while (rowIdx <= lastRow) {
                Row row = sheet.getRow(rowIdx);
                short lastCell = row != null ? row.getLastCellNum() : -1;
                if (lastCell < 0) {
                    sb.append("\n");
                    rowIdx = rowIdx + 1;
                    continue;
                }

                int cellIdx = 0;
                while (cellIdx < lastCell) {
                    Cell cell = row.getCell(cellIdx);
                    String val = fmt.formatCellValue(cell);
                    sb.append(val == null ? "" : val);
                    if (cellIdx < lastCell - 1) sb.append("\t");
                    cellIdx = cellIdx + 1;
                }
                sb.append("\n");
                rowIdx = rowIdx + 1;
            }
            sheetIndex = sheetIndex + 1;
        }

        if (sb.length() == 0) sb.append("(empty workbook)");
        return sb.toString();
    }

    static void writeWorkbook(Path target, String text) throws IOException {
        Workbook wb = fromText(text);
        try (OutputStream out = Files.newOutputStream(target)) {
            wb.write(out);
        } catch (Exception ex) {
            throw new IOException("Failed to write Excel: " + ex.getMessage(), ex);
        } finally {
            try { wb.close(); } catch (Exception ignored) {}
        }
    }

    static Workbook fromText(String text) {
        Workbook wb = new XSSFWorkbook();
        // remove default sheet to avoid creating an extra blank tab
        if (wb.getNumberOfSheets() > 0) {
            wb.removeSheetAt(0);
        }
        Sheet current = null;
        int rowIdx = 0;

        String[] lines = text.split("\\r?\\n", -1);
        int i = 0;
        int n = lines.length;
        while (i < n) {
            String line = lines[i];
            if (line.startsWith("# Sheet:")) {
                String name = line.substring("# Sheet:".length()).trim();
                if (name.isEmpty()) {
                    name = "Sheet" + (wb.getNumberOfSheets() + 1);
                }
                current = wb.createSheet(name);
                rowIdx = 0;
                i = i + 1;
                continue;
            }

            if (line.isEmpty()) {
                boolean nextIsHeader = (i + 1 < n) && lines[i + 1].startsWith("# Sheet:");
                boolean trailingOnly = true;
                int j = i + 1;
                while (j < n) {
                    if (!lines[j].isEmpty()) {
                        trailingOnly = false;
                        break;
                    }
                    j = j + 1;
                }
                if (nextIsHeader || trailingOnly) {
                    i = i + 1;
                    continue;
                }
            }

            if (current == null) {
                current = wb.createSheet("Sheet1");
            }
            Row row = current.createRow(rowIdx);
            String[] cells = line.split("\\t", -1);
            int c = 0;
            int m = cells.length;
            while (c < m) {
                Cell cell = row.createCell(c, CellType.STRING);
                cell.setCellValue(cells[c]);
                c = c + 1;
            }
            rowIdx = rowIdx + 1;
            i = i + 1;
        }
        return wb;
    }
}
