package org.erp.fin.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.erp.fin.domain.FinOpening;
import org.erp.fin.mapper.FinOpeningMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 默认 STRICT_STUBS；只是锁序/反例合同，不冒充真实 InnoDB 并发验证。 */
@ExtendWith(MockitoExtension.class)
@Tag("dev")
class FinOpeningGuardServiceImplTest {
    @Mock private FinOpeningMapper mapper;
    private FinOpeningGuardServiceImpl guard;

    @BeforeEach void setup() { guard = new FinOpeningGuardServiceImpl(mapper); }

    @ParameterizedTest
    @CsvSource({"AR,2020-09-29", "AR,2020-09-30", "AP,2020-09-29", "AP,2020-09-30"})
    void confirmedExplicitCutoffRejectsEarlierAndSameDay(String type, String date) {
        stubParty(type);
        when(mapper.selectByPartyForUpdate(type, 9L)).thenReturn(opening("CONFIRMED", LocalDate.of(2020, 9, 30)));
        var denial = assertThrows(ServiceException.class, () -> guard.lockAndCheckBusinessDate(type, 9L, Date.valueOf(date)));
        assertEquals(40902, denial.getCode());
        assertTrue(denial.getMessage().contains("已核销时须先反核销"));
        var order = inOrder(mapper);
        if ("AR".equals(type)) { order.verify(mapper).lockCustomer(9L); }
        else { order.verify(mapper).lockSupplier(9L); }
        order.verify(mapper).selectByPartyForUpdate(type, 9L);
        verifyNoMoreInteractions(mapper);
    }

    @Test void confirmedCutoffDoesNotBlockFollowingDay() {
        stubParty("AR");
        when(mapper.selectByPartyForUpdate("AR", 9L)).thenReturn(opening("CONFIRMED", LocalDate.of(2020, 9, 30)));
        assertDoesNotThrow(() -> guard.lockAndCheckBusinessDate("AR", 9L, Date.valueOf("2020-10-01")));
    }

    @Test void historicalNullCutoffStaysUnknownAndIsNeverBackfilled() {
        stubParty("AR");
        FinOpening historical = opening("CONFIRMED", null);
        when(mapper.selectByPartyForUpdate("AR", 9L)).thenReturn(historical);
        guard.lockAndCheckBusinessDate("AR", 9L, Date.valueOf("1900-01-01"));
        assertNull(historical.getOpeningDate());
        assertEquals("CONFIRMED", historical.getStatus());
        verify(mapper).lockCustomer(9L);
        verify(mapper).selectByPartyForUpdate("AR", 9L);
        verifyNoMoreInteractions(mapper);
    }

    @Test void absentOpeningStillLocksStableMasterRowAndReentrantCallsAreSafe() {
        stubParty("AP");
        guard.lockParty("AP", 9L);
        guard.lockAndCheckBusinessDate("AP", 9L, Date.valueOf("2020-01-01"));
        verify(mapper, times(2)).lockSupplier(9L);
        verify(mapper).selectByPartyForUpdate("AP", 9L);
        verifyNoMoreInteractions(mapper);
    }

    @Test void draftOpeningDoesNotBlockBusinessConfirmation() {
        stubParty("AR");
        when(mapper.selectByPartyForUpdate("AR", 9L)).thenReturn(opening("DRAFT", LocalDate.of(2020, 9, 30)));
        assertDoesNotThrow(() -> guard.lockAndCheckBusinessDate("AR", 9L, Date.valueOf("2020-09-01")));
    }

    @Test void missingOrForeignTenantPartyCannotBecomeAnUnlockedSuccess() {
        when(mapper.lockCustomer(9L)).thenReturn(null);
        var denial = assertThrows(ServiceException.class, () -> guard.lockParty("AR", 9L));
        assertEquals(40902, denial.getCode());
        verify(mapper).lockCustomer(9L);
        verifyNoMoreInteractions(mapper);
    }

    @Test void mapperFailureIsPropagatedNotSwallowed() {
        RuntimeException failure = new IllegalStateException("lock unavailable");
        when(mapper.lockSupplier(9L)).thenThrow(failure);
        assertSame(failure, assertThrows(RuntimeException.class, () -> guard.lockParty("AP", 9L)));
    }

    @Test void invalidDatesAndIdentitiesFailBeforeSql() {
        assertThrows(ServiceException.class, () -> guard.lockAndCheckBusinessDate("AR", 9L, null));
        assertThrows(ServiceException.class, () -> guard.lockParty("XX", 9L));
        assertThrows(ServiceException.class, () -> guard.lockParty("AR", null));
        assertThrows(ServiceException.class, () -> guard.lockParty("AP", 0L));
        verifyNoInteractions(mapper);
    }

    @Test void publicGuardEntriesMustJoinAnExistingTransaction() throws Exception {
        for (var method : FinOpeningGuardServiceImpl.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                Transactional tx = method.getAnnotation(Transactional.class);
                assertNotNull(tx);
                assertEquals(Propagation.MANDATORY, tx.propagation());
                assertArrayEquals(new Class<?>[]{Exception.class}, tx.rollbackFor());
            }
        }
    }

    private void stubParty(String type) {
        if ("AR".equals(type)) { when(mapper.lockCustomer(9L)).thenReturn(9L); }
        else { when(mapper.lockSupplier(9L)).thenReturn(9L); }
    }
    private FinOpening opening(String status, LocalDate cutoff) {
        FinOpening row = new FinOpening();
        row.setStatus(status);
        row.setOpeningDate(cutoff);
        return row;
    }
}
