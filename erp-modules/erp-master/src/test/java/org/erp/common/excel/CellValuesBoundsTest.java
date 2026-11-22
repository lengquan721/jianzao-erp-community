package org.erp.common.excel;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class CellValuesBoundsTest {

    @Test
    void decimalRejectsExponentsAndValuesOutsideDatabasePrecision() {
        var errors = new ArrayList<String>();
        assertEquals(new BigDecimal("99999999999999.9999"),
            CellValues.decimal("99999999999999.9999", "成本", 18, 4, true, errors));
        assertNull(CellValues.decimal("100000000000000", "成本", 18, 4, true, errors));
        assertNull(CellValues.decimal("1e1000000", "成本", 18, 4, true, errors));
        assertNull(CellValues.decimal("1".repeat(65), "成本", 18, 4, true, errors));
        assertEquals(3, errors.size());
        assertTrue(errors.get(1).contains("普通十进制数字"));
    }
}
