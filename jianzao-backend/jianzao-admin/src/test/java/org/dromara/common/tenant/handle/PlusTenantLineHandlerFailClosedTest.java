package org.dromara.common.tenant.handle;

import org.dromara.common.tenant.properties.TenantProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class PlusTenantLineHandlerFailClosedTest {

    private final PlusTenantLineHandler handler = new PlusTenantLineHandler();

    @Test
    void onlyExactSystemTablesAreExempt() {
        assertTrue(handler.ignoreTable("sys_menu"));
        assertTrue(handler.ignoreTable("GEN_TABLE"));
        assertFalse(handler.ignoreTable("md_customer"));
        assertFalse(handler.ignoreTable("sys_menux"));
    }

    @Test
    void missingTenantContextFailsClosedForBusinessTables() {
        PlusTenantLineHandler noTenant = new PlusTenantLineHandler(() -> null);
        assertThrows(IllegalStateException.class, noTenant::getTenantId);
    }

    @Test
    void configuredExclusionsCannotExpandBeyondApprovedSystemTables() {
        TenantProperties properties = new TenantProperties();
        properties.setExcludes(List.of("md_customer"));
        assertThrows(IllegalArgumentException.class, () -> new PlusTenantLineHandler(properties));
    }
}
