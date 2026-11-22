package org.dromara.system.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.system.domain.SysTenant;
import org.dromara.system.domain.bo.SysTenantBo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class SysTenantExpireTimeUpdateTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void initializeTableMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),
            "tenant-expiry-unit"), SysTenant.class);
    }

    @Test
    void omittedExpiryDoesNotClearExistingValue() throws Exception {
        SysTenantBo bo = mapper.readValue("{\"id\":12,\"companyName\":\"changed\"}", SysTenantBo.class);

        assertFalse(bo.isExpireTimeSpecified());
        assertNull(bo.getExpireTime());
        assertFalse(explicitSet(bo).contains("expire_time"));
    }

    @Test
    void explicitlyNullExpiryProducesScopedNullAssignment() throws Exception {
        SysTenantBo bo = mapper.readValue("{\"id\":12,\"expireTime\":null}", SysTenantBo.class);
        LambdaUpdateWrapper<SysTenant> update = SysTenantServiceImpl.tenantUpdateScope(bo);

        assertTrue(bo.isExpireTimeSpecified());
        assertNull(bo.getExpireTime());
        assertTrue(update.getSqlSet().contains("expire_time="));
        assertTrue(update.getSqlSegment().contains("id ="));
        assertTrue(update.getParamNameValuePairs().containsValue(12L));
        assertTrue(update.getParamNameValuePairs().containsValue(null));
    }

    @Test
    void explicitTimestampKeepsEntityValueAndDoesNotAddNullAssignment() throws Exception {
        SysTenantBo bo = mapper.readValue("{\"id\":12,\"expireTime\":1800000000000}", SysTenantBo.class);

        assertTrue(bo.isExpireTimeSpecified());
        assertEquals(new Date(1800000000000L), bo.getExpireTime());
        assertFalse(explicitSet(bo).contains("expire_time"));
    }

    @Test
    void clientCannotForgePresenceFlagToClearOmittedExpiry() throws Exception {
        SysTenantBo bo = mapper.readValue("{\"id\":12,\"expireTimeSpecified\":true}", SysTenantBo.class);

        assertFalse(bo.isExpireTimeSpecified());
        assertFalse(explicitSet(bo).contains("expire_time"));
    }

    @Test
    void clientCannotSuppressPresenceOfExplicitNull() throws Exception {
        SysTenantBo bo = mapper.readValue(
            "{\"id\":12,\"expireTime\":null,\"expireTimeSpecified\":false}", SysTenantBo.class);

        assertTrue(bo.isExpireTimeSpecified());
        assertTrue(explicitSet(bo).contains("expire_time="));
    }

    @Test
    void programmaticNullIsExplicitButPresenceFlagIsNotSerialized() throws Exception {
        SysTenantBo bo = new SysTenantBo();
        bo.setId(12L);
        bo.setExpireTime(null);

        assertTrue(bo.isExpireTimeSpecified());
        assertTrue(explicitSet(bo).contains("expire_time="));
        assertFalse(mapper.writeValueAsString(bo).contains("expireTimeSpecified"));
    }

    private static String explicitSet(SysTenantBo bo) {
        return String.valueOf(SysTenantServiceImpl.tenantUpdateScope(bo).getSqlSet());
    }
}
