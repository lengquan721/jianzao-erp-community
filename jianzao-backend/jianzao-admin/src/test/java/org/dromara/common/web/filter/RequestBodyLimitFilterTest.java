package org.dromara.common.web.filter;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.dromara.common.core.utils.RequestBodyLimits;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@Tag("dev")
class RequestBodyLimitFilterTest {

    @Test
    void streamingLimitRejectsBodyEvenWhenLengthIsUnavailable() throws Exception {
        byte[] content = new byte[RequestBodyLimits.JSON_LIMIT_BYTES + 1];
        MockHttpServletRequest base = new MockHttpServletRequest("POST", "/erp/master/products");
        base.setContentType("application/json;charset=UTF-8");
        HttpServletRequest noLengthRequest = new HttpServletRequestWrapper(base) {
            @Override public long getContentLengthLong() { return -1; }
            @Override public int getContentLength() { return -1; }
            @Override public ServletInputStream getInputStream() {
                ByteArrayInputStream input = new ByteArrayInputStream(content);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { }
                };
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        jakarta.servlet.FilterChain chain = mock(jakarta.servlet.FilterChain.class);

        new RequestBodyLimitFilter("encrypt-key").doFilter(noLengthRequest, response, chain);

        assertEquals(413, response.getStatus());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void encryptedWireCapAllowsFiniteCiphertextBudget() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/erp/master/products");
        request.setContentType("application/json");
        request.addHeader("encrypt-key", "present");
        request.setContent(new byte[RequestBodyLimits.JSON_LIMIT_BYTES + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        jakarta.servlet.FilterChain chain = mock(jakarta.servlet.FilterChain.class);

        new RequestBodyLimitFilter("encrypt-key").doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        verify(chain).doFilter(any(), any());
    }
}
