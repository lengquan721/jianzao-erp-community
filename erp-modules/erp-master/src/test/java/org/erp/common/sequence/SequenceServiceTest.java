package org.erp.common.sequence;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.tenant.helper.TenantHelper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("dev")
class SequenceServiceTest {

    @Test
    void missingTenantIsRejectedBeforeAnyJdbcCall() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        SequenceService service = new SequenceService(jdbcTemplate);

        try (MockedStatic<TenantHelper> tenantHelper = mockStatic(TenantHelper.class)) {
            tenantHelper.when(TenantHelper::getTenantId).thenReturn(null, "", " \t ");

            assertThrows(ServiceException.class, () -> service.nextNo("SO"));
            assertThrows(ServiceException.class, () -> service.nextNo("SO"));
            assertThrows(ServiceException.class, () -> service.nextNo("SO"));

            verifyNoInteractions(jdbcTemplate);
        }
    }

    @Test
    void explicitTenantIsUsedForUpdateInsertRetryAndQuery() {
        String tenantId = "tenant-42";
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        SequenceService service = new SequenceService(jdbcTemplate);

        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            Object[] args = (Object[]) invocation.getRawArguments()[1];
            if (sql.startsWith("insert into sys_sequence")) {
                assertEquals(tenantId, args[0]);
                throw new IllegalStateException("simulate concurrent insert");
            }
            assertEquals(tenantId, args[2]);
            return 0;
        });
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(7L);

        try (MockedStatic<TenantHelper> tenantHelper = mockStatic(TenantHelper.class)) {
            tenantHelper.when(TenantHelper::getTenantId).thenReturn(tenantId);

            String sequenceNo = service.nextNo("SO");

            String seqDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            assertEquals("SO" + seqDate + "0007", sequenceNo);
        }

        ArgumentCaptor<Object[]> updateArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, times(2)).update(startsWith("update sys_sequence"), updateArgs.capture());
        updateArgs.getAllValues().forEach(args -> assertEquals(tenantId, args[2]));

        ArgumentCaptor<Object[]> insertArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(startsWith("insert into sys_sequence"), insertArgs.capture());
        assertEquals(tenantId, insertArgs.getValue()[0]);

        ArgumentCaptor<Object[]> queryArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForObject(startsWith("select current_value"), eq(Long.class), queryArgs.capture());
        assertEquals(tenantId, queryArgs.getValue()[2]);
    }
}
