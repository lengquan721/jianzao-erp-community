package org.dromara.web.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class RequestLimitConfigurationGuardTest {

    @Test
    void acceptsExplicitUploadEnvelopeLimit() {
        assertDoesNotThrow(() -> new RequestLimitConfigurationGuard(new MockEnvironment()
            .withProperty("server.undertow.max-http-post-size", "20MB")));
    }

    @Test
    void rejectsMissingOrOversizedUndertowLimit() {
        assertThrows(IllegalStateException.class,
            () -> new RequestLimitConfigurationGuard(new MockEnvironment()));
        assertThrows(IllegalStateException.class, () -> new RequestLimitConfigurationGuard(new MockEnvironment()
            .withProperty("server.undertow.max-http-post-size", "1GB")));
    }
}
