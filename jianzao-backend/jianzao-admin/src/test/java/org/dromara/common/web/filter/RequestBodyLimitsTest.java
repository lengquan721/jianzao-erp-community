package org.dromara.common.web.filter;

import org.dromara.common.core.utils.RequestBodyLimits;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class RequestBodyLimitsTest {

    @Test
    void streamLimitDoesNotDependOnContentLength() throws Exception {
        assertArrayEquals(new byte[]{1, 2, 3}, RequestBodyLimits.readLimited(
            new ByteArrayInputStream(new byte[]{1, 2, 3}), 3));
        assertThrows(RequestBodyLimits.BodyTooLargeException.class, () -> RequestBodyLimits.readLimited(
            new ByteArrayInputStream(new byte[]{1, 2, 3, 4}), 3));
    }

    @Test
    void printDesignUsesLargerBoundThanOrdinaryJson() {
        assertEquals(2 * 1024 * 1024, RequestBodyLimits.plainLimit(request("/erp/master/products")));
        assertEquals(4 * 1024 * 1024, RequestBodyLimits.plainLimit(request("/erp/print/preview/design")));
        assertEquals(4 * 1024 * 1024, RequestBodyLimits.plainLimit(request("/erp/print/templates/7")));
    }

    private jakarta.servlet.http.HttpServletRequest request(String uri) {
        var request = org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletRequest.class);
        org.mockito.Mockito.when(request.getRequestURI()).thenReturn(uri);
        return request;
    }
}
