package org.dromara.web.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/**
 * Prevents the global Undertow request limit from silently exceeding the multipart envelope.
 * JSON limits are independently enforced by the earliest streaming body filter.
 */
@Component
public class RequestLimitConfigurationGuard {

    public static final long MAX_HTTP_POST_SIZE_BYTES = 20L * 1024 * 1024;

    public RequestLimitConfigurationGuard(Environment environment) {
        String configured = environment.getProperty("server.undertow.max-http-post-size");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("server.undertow.max-http-post-size 必须显式设置，最大20MB");
        }
        final long bytes;
        try {
            bytes = DataSize.parse(configured.trim()).toBytes();
        } catch (RuntimeException e) {
            throw new IllegalStateException("server.undertow.max-http-post-size 无效", e);
        }
        if (bytes <= 0 || bytes > MAX_HTTP_POST_SIZE_BYTES) {
            throw new IllegalStateException("server.undertow.max-http-post-size 必须在1字节到20MB之间");
        }
    }
}
