package org.dromara.common.core.utils;

import jakarta.servlet.http.HttpServletRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Request body limits shared by the earliest crypto and repeatable-request filters. */
public final class RequestBodyLimits {

    public static final int JSON_LIMIT_BYTES = 2 * 1024 * 1024;
    public static final int PRINT_DESIGN_LIMIT_BYTES = 4 * 1024 * 1024;
    private static final int BUFFER_SIZE = 8192;

    private RequestBodyLimits() {
    }

    public static int plainLimit(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri != null && (uri.endsWith("/erp/print/preview/design")
            || uri.endsWith("/erp/print/templates") || uri.contains("/erp/print/templates/"))) {
            return PRINT_DESIGN_LIMIT_BYTES;
        }
        return JSON_LIMIT_BYTES;
    }

    public static boolean isJsonContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        String mediaType = contentType.split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
        return mediaType.equals("application/json") || mediaType.equals("text/json") || mediaType.endsWith("+json");
    }

    /** Allow Base64/AES expansion on encrypted requests while still imposing a firm streaming cap. */
    public static int encryptedWireLimit(HttpServletRequest request) {
        return Math.addExact(Math.multiplyExact(plainLimit(request), 2), 64 * 1024);
    }

    /** Reads at most limit+1 bytes and rejects the body even when Content-Length is absent or false. */
    public static byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, BUFFER_SIZE));
        byte[] buffer = new byte[BUFFER_SIZE];
        int total = 0;
        int count;
        while ((count = input.read(buffer, 0, Math.min(buffer.length, limit - total + 1))) != -1) {
            total += count;
            if (total > limit) {
                throw new BodyTooLargeException();
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    public static final class BodyTooLargeException extends IOException {
        public BodyTooLargeException() {
            super("Request body exceeds the configured limit");
        }
    }
}
