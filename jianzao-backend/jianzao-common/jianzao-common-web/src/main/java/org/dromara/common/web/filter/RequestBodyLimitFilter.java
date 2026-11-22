package org.dromara.common.web.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.dromara.common.core.utils.RequestBodyLimits;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Enforces a streaming request-body cap before any filter can cache or decrypt the payload. */
public class RequestBodyLimitFilter implements Filter {

    private final String encryptHeader;

    public RequestBodyLimitFilter(String encryptHeader) {
        this.encryptHeader = encryptHeader;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        String encryptionHeader = httpRequest.getHeader(encryptHeader);
        boolean encrypted = encryptionHeader != null && !encryptionHeader.isBlank();
        boolean json = RequestBodyLimits.isJsonContentType(httpRequest.getContentType());
        if (!encrypted && !json) {
            chain.doFilter(request, response);
            return;
        }

        int plainLimit = RequestBodyLimits.plainLimit(httpRequest);
        int wireLimit = encrypted ? RequestBodyLimits.encryptedWireLimit(httpRequest) : plainLimit;
        long declaredLength = httpRequest.getContentLengthLong();
        if (declaredLength > wireLimit) {
            httpResponse.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }
        final byte[] body;
        try {
            body = RequestBodyLimits.readLimited(httpRequest.getInputStream(), wireLimit);
        } catch (RequestBodyLimits.BodyTooLargeException e) {
            httpResponse.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }
        chain.doFilter(new BufferedBodyRequest(httpRequest, body), response);
    }

    private static final class BufferedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private BufferedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public jakarta.servlet.ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new jakarta.servlet.ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public int available() { return input.available(); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(jakarta.servlet.ReadListener listener) { }
            };
        }
    }
}
