package org.erp.stock.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.common.core.domain.R;
import org.dromara.common.json.config.JacksonConfig;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.erp.stock.domain.vo.ErpStockLogPageVo;
import org.erp.stock.domain.vo.ErpStockLogVo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class StockQueryContractTest {
    @Test
    void legacyFullArrayCalls_cannotAccidentallyReceiveFirstPage() throws Exception {
        var balances = ErpStockController.class.getMethod("balances", String.class, String.class, int.class, int.class);
        assertEquals(TableDataInfo.class, balances.getReturnType());
        RequestParam pageNum = balances.getParameters()[2].getAnnotation(RequestParam.class);
        assertTrue(pageNum.required());
        assertEquals(ValueConstants.DEFAULT_NONE, pageNum.defaultValue());
        assertEquals("50", balances.getParameters()[3].getAnnotation(RequestParam.class).defaultValue());
        var opening = StOpeningController.class.getMethod("list", Long.class, int.class, int.class);
        assertEquals(TableDataInfo.class, opening.getReturnType());
        assertTrue(opening.getParameters()[1].getAnnotation(RequestParam.class).required());
        assertEquals(ValueConstants.DEFAULT_NONE, opening.getParameters()[1].getAnnotation(RequestParam.class).defaultValue());
        var logs = ErpStockController.class.getMethod("logs", Long.class, LocalDate.class, LocalDate.class,
            int.class, LocalDate.class, Long.class);
        assertTrue(logs.getParameters()[1].getAnnotation(RequestParam.class).required());
        assertTrue(logs.getParameters()[2].getAnnotation(RequestParam.class).required());
    }

    @Test
    void serializedPagingAndCursor_matchDocumentedEnvelopeAndDateContract() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JacksonConfig().registerJavaTimeModule());
        var row = new ErpStockLogVo(); row.setId(9007199254740993L); row.setBizDate(LocalDate.of(2026, 11, 20));
        var page = new ErpStockLogPageVo(List.of(row), true, row.getBizDate(), row.getId());
        var json = mapper.readTree(mapper.writeValueAsString(R.ok(page)));
        assertTrue(json.has("data"));
        assertTrue(json.at("/data/hasMore").asBoolean());
        assertEquals("2026-11-20", json.at("/data/nextBeforeDate").asText());
        assertEquals("2026-11-20", json.at("/data/rows/0/bizDate").asText());
        assertEquals("9007199254740993", json.at("/data/nextBeforeId").asText());
        assertFalse(json.at("/data").has("total"));
        var table = mapper.readTree(mapper.writeValueAsString(new TableDataInfo<>(List.of(row), 201)));
        assertTrue(table.has("rows"));
        assertEquals(201, table.get("total").asLong());
        assertFalse(table.has("data"));
    }
}
