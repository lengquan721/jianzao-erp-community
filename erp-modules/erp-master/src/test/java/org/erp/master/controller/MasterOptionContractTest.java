package org.erp.master.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.common.core.domain.R;
import org.erp.master.domain.vo.ErpProductVo;
import org.erp.master.domain.vo.MasterOptionPageVo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class MasterOptionContractTest {
    @Test
    void allFiveOptions_requireExplicitPagingAndHaveBatchEchoRoutes() {
        for (Class<?> controller : List.of(ErpProductController.class, ErpCustomerController.class,
            ErpSupplierController.class, ErpWarehouseController.class, ErpProductCategoryController.class)) {
            var options = java.util.Arrays.stream(controller.getDeclaredMethods()).filter(m -> m.getName().equals("options"))
                .findFirst().orElseThrow();
            var params = options.getParameters();
            RequestParam pageNum = params[params.length - 2].getAnnotation(RequestParam.class);
            assertTrue(pageNum.required(), controller.getName());
            assertEquals(ValueConstants.DEFAULT_NONE, pageNum.defaultValue());
            assertEquals("30", params[params.length - 1].getAnnotation(RequestParam.class).defaultValue());
            var echo = java.util.Arrays.stream(controller.getDeclaredMethods()).filter(m -> m.getName().equals("optionsByIds"))
                .findFirst().orElseThrow();
            assertEquals("/options/by-ids", echo.getAnnotation(PostMapping.class).value()[0]);
        }
    }

    @Test
    void newPage_isObjectInsideRData_notArrayMasqueradingAsCompleteList() throws Exception {
        var page = new MasterOptionPageVo<>(List.of(new ErpProductVo()), true, 2L);
        var mapper = new ObjectMapper();
        var json = mapper.readTree(mapper.writeValueAsString(R.ok(page)));
        assertTrue(json.get("data").isObject());
        assertTrue(json.at("/data/rows").isArray());
        assertTrue(json.at("/data/hasMore").asBoolean());
        assertEquals(2, json.at("/data/nextPageNum").asInt());
        assertFalse(json.get("data").has("total"));
    }
}
