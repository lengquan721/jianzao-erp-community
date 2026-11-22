package org.erp.common.excel;

import org.erp.master.excel.ProductImportRow;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class ExcelRowReaderLimitsTest {

    @Test
    void stopsAtThe2001stDataRow() throws Exception {
        byte[] workbook = workbook(1, 2001);
        List<ExcelRow<ProductImportRow>> rows = ExcelRowReader.read(new ByteArrayInputStream(workbook), ProductImportRow.class);
        assertEquals(2001, rows.size());
        assertEquals(2002, rows.get(2000).rowNumber());
    }

    @Test
    void rejectsMoreThan64ColumnsFromTheHeaderBeforeImport() throws Exception {
        byte[] workbook = workbook(65, 1);
        assertThrows(ExcelRowReader.ImportLimitException.class,
            () -> ExcelRowReader.read(new ByteArrayInputStream(workbook), ProductImportRow.class));
    }

    private byte[] workbook(int columns, int dataRows) throws Exception {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("import");
            Row header = sheet.createRow(0);
            for (int col = 0; col < columns; col++) {
                header.createCell(col).setCellValue(col == 0 && columns == 1 ? "产品名称" : "h" + col);
            }
            for (int rowNum = 1; rowNum <= dataRows; rowNum++) {
                Row row = sheet.createRow(rowNum);
                for (int col = 0; col < columns; col++) row.createCell(col).setCellValue("v" + rowNum);
            }
            book.write(output);
            return output.toByteArray();
        }
    }
}
