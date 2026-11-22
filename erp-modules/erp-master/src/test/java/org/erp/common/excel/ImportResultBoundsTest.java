package org.erp.common.excel;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("dev")
class ImportResultBoundsTest {

    @Test
    void errorDetailsAndMessagesAreBoundedWithoutLosingCounters() {
        ImportResult result = new ImportResult();
        String longMessage = "x".repeat(2000);
        for (int row = 0; row < 150; row++) result.fail(row + 2, longMessage);

        assertEquals(150, result.getTotal());
        assertEquals(150, result.getFailed());
        assertEquals(100, result.getErrors().size());
        assertEquals(513, result.getErrors().get(0).message().length());
    }
}
