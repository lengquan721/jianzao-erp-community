package org.erp.fin.service.impl;

import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.common.core.exception.ServiceException;
import org.erp.fin.domain.FinOpening;
import org.erp.fin.domain.vo.FinOpeningConfirmedAmountVo;
import org.erp.fin.mapper.FinOpeningMapper;
import org.erp.fin.service.IFinArApService;
import org.erp.fin.service.IFinOpeningGuardService;
import org.erp.master.domain.vo.ErpCustomerVo;
import org.erp.master.domain.vo.ErpSupplierVo;
import org.erp.master.mapper.ErpCustomerMapper;
import org.erp.master.mapper.ErpSupplierMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 默认 STRICT_STUBS；有界双查询、权限/未知边界、原始金额与字符串精度合同。未连接数据库。 */
@ExtendWith(MockitoExtension.class)
@Tag("dev")
class FinOpeningConfirmedAmountsTest {
    @Mock private FinOpeningMapper openings;
    @Mock private ErpCustomerMapper customers;
    @Mock private ErpSupplierMapper suppliers;
    @Mock private IFinArApService ledger;
    @Mock private IFinOpeningGuardService guard;
    private FinOpeningServiceImpl service;

    @BeforeEach void setup() {
        service = new FinOpeningServiceImpl(openings, customers, suppliers, ledger, guard);
    }

    @Test void returnsOnlyRequestedReadablePartiesWithRawConfirmedAmountAndExplicitKnownZero() throws Exception {
        long largeId = 9007199254740993L;
        when(customers.selectOptionsByIds(List.of(largeId, 2L, 3L, 4L)))
            .thenReturn(List.of(customer(2), customer(largeId), customer(99)));
        // 历史 openingDate=NULL 的已确认行仍返回原始期初，不看台账未结额。
        FinOpening historical = opening(largeId, "9007199254740993.01"); historical.setOpeningDate(null);
        when(openings.selectConfirmedAmountsByPartyIds("AR", List.of(largeId, 2L)))
            .thenReturn(List.of(historical, opening(99L, "123.00")));
        try (var permissions = mockStatic(StpUtil.class)) {
            permissions.when(() -> StpUtil.hasPermission("erp:master:customer:list")).thenReturn(true);
            var result = service.confirmedByPartyIds("AR", "9007199254740993,2,3,4,9007199254740993");
            assertEquals(List.of(new FinOpeningConfirmedAmountVo("9007199254740993", "9007199254740993.01"),
                new FinOpeningConfirmedAmountVo("2", "0")), result);
            String json = new ObjectMapper().writeValueAsString(result);
            assertEquals("[{\"partyId\":\"9007199254740993\",\"amount\":\"9007199254740993.01\"},{\"partyId\":\"2\",\"amount\":\"0\"}]", json);
            permissions.verify(() -> StpUtil.checkPermission("erp:finance:list"));
            permissions.verify(() -> StpUtil.hasPermission("erp:master:customer:list"));
            permissions.verifyNoMoreInteractions();
        }
        verify(customers).selectOptionsByIds(List.of(largeId, 2L, 3L, 4L));
        verify(openings).selectConfirmedAmountsByPartyIds("AR", List.of(largeId, 2L));
        verifyNoMoreInteractions(customers, openings);
        verifyNoInteractions(suppliers, ledger, guard);
    }

    @Test void arApSameIdAreIndependentAndMaxSignedBigintIsNotRounded() {
        when(customers.selectOptionsByIds(List.of(Long.MAX_VALUE))).thenReturn(List.of(customer(Long.MAX_VALUE)));
        when(suppliers.selectOptionsByIds(List.of(Long.MAX_VALUE))).thenReturn(List.of(supplier(Long.MAX_VALUE)));
        when(openings.selectConfirmedAmountsByPartyIds("AR", List.of(Long.MAX_VALUE)))
            .thenReturn(List.of(opening(Long.MAX_VALUE, "12.3400")));
        when(openings.selectConfirmedAmountsByPartyIds("AP", List.of(Long.MAX_VALUE)))
            .thenReturn(List.of(opening(Long.MAX_VALUE, "56.78")));
        try (var permissions = mockStatic(StpUtil.class)) {
            // 主数据 query 权限亦是既有读取入口；无 list 时必须检查对应 query，而不是别的方向。
            assertEquals(List.of(new FinOpeningConfirmedAmountVo("9223372036854775807", "12.3400")),
                service.confirmedByPartyIds("AR", "9223372036854775807"));
            assertEquals(List.of(new FinOpeningConfirmedAmountVo("9223372036854775807", "56.78")),
                service.confirmedByPartyIds("AP", "9223372036854775807"));
            permissions.verify(() -> StpUtil.checkPermission("erp:master:customer:query"));
            permissions.verify(() -> StpUtil.checkPermission("erp:master:supplier:query"));
        }
        verifyNoInteractions(ledger, guard);
    }

    @Test void deletedMissingForeignTenantOrRowScopeOmittedPartiesNeverBecomeZero() {
        when(customers.selectOptionsByIds(List.of(1L, 2L))).thenReturn(List.of());
        try (var permissions = mockStatic(StpUtil.class)) {
            permissions.when(() -> StpUtil.hasPermission("erp:master:customer:list")).thenReturn(true);
            assertTrue(service.confirmedByPartyIds("AR", "1,2").isEmpty());
        }
        verify(customers).selectOptionsByIds(List.of(1L, 2L));
        verifyNoMoreInteractions(customers);
        verifyNoInteractions(openings, suppliers, ledger, guard);
    }

    @Test void financePermissionDenialPrecedesAllSqlAndMasterPermissionProbes() {
        var denied = new NotPermissionException("erp:finance:list");
        try (var permissions = mockStatic(StpUtil.class)) {
            permissions.when(() -> StpUtil.checkPermission("erp:finance:list")).thenThrow(denied);
            assertSame(denied, assertThrows(NotPermissionException.class,
                () -> service.confirmedByPartyIds("AR", "1")));
            permissions.verify(() -> StpUtil.checkPermission("erp:finance:list"));
            permissions.verifyNoMoreInteractions();
        }
        verifyNoInteractions(openings, customers, suppliers, ledger, guard);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AR", "AP"})
    void financeReadWithoutMatchingMasterReadCannotProbeParties(String type) {
        String prefix = "AR".equals(type) ? "erp:master:customer" : "erp:master:supplier";
        var denied = new NotPermissionException(prefix + ":query");
        try (var permissions = mockStatic(StpUtil.class)) {
            permissions.when(() -> StpUtil.checkPermission(prefix + ":query")).thenThrow(denied);
            assertSame(denied, assertThrows(NotPermissionException.class,
                () -> service.confirmedByPartyIds(type, "1")));
            permissions.verify(() -> StpUtil.checkPermission("erp:finance:list"));
            permissions.verify(() -> StpUtil.hasPermission(prefix + ":list"));
            permissions.verify(() -> StpUtil.checkPermission(prefix + ":query"));
            permissions.verifyNoMoreInteractions();
        }
        verifyNoInteractions(openings, customers, suppliers, ledger, guard);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "0", "-1", "+1", "01", "1e2", "1.0", "9223372036854775808", "1,", ",1", "1,,2", "١", "1);drop table fin_opening"})
    void malformedIdStringsFailBeforePermissionsOrSql(String ids) {
        try (var permissions = mockStatic(StpUtil.class)) {
            assertEquals(400, assertThrows(ServiceException.class, () -> service.confirmedByPartyIds("AR", ids)).getCode());
            permissions.verifyNoInteractions();
        }
        verifyNoInteractions(openings, customers, suppliers, ledger, guard);
    }

    @Test void wrongDirectionOversizeUniqueSetAndOversizeRawTextFailBeforeSql() {
        String tooMany = LongStream.rangeClosed(1, 201).mapToObj(value -> Long.toString(value)).collect(Collectors.joining(","));
        assertThrows(ServiceException.class, () -> service.confirmedByPartyIds("XX", "1"));
        assertThrows(ServiceException.class, () -> service.confirmedByPartyIds("AR", tooMany));
        assertThrows(ServiceException.class, () -> service.confirmedByPartyIds("AP", "1".repeat(8193)));
        verifyNoInteractions(openings, customers, suppliers, ledger, guard);
    }

    @Test void exactly200IdsUseOneMasterBatchAndOneNarrowOpeningQueryAndPreserveOrder() {
        var ids = LongStream.rangeClosed(1, 200).boxed().toList();
        when(suppliers.selectOptionsByIds(ids)).thenReturn(ids.stream().map(this::supplier).toList());
        when(openings.selectConfirmedAmountsByPartyIds("AP", ids)).thenReturn(List.of());
        String query = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        try (var permissions = mockStatic(StpUtil.class)) {
            permissions.when(() -> StpUtil.hasPermission("erp:master:supplier:list")).thenReturn(true);
            var result = service.confirmedByPartyIds("AP", query);
            assertEquals(ids.stream().map(String::valueOf).toList(), result.stream().map(FinOpeningConfirmedAmountVo::partyId).toList());
            assertTrue(result.stream().allMatch(row -> "0".equals(row.amount())));
        }
        verify(suppliers).selectOptionsByIds(ids);
        verify(openings).selectConfirmedAmountsByPartyIds("AP", ids);
        verifyNoMoreInteractions(suppliers, openings);
        verifyNoInteractions(customers, ledger, guard);
    }

    @Test void duplicatesAreDeduplicatedBeforeThe200LimitAndWhitespaceNeverChangesAnIdValue() {
        when(customers.selectOptionsByIds(List.of(1L))).thenReturn(List.of(customer(1)));
        when(openings.selectConfirmedAmountsByPartyIds("AR", List.of(1L))).thenReturn(List.of());
        try (var permissions = mockStatic(StpUtil.class)) {
            permissions.when(() -> StpUtil.hasPermission("erp:master:customer:list")).thenReturn(true);
            assertEquals(List.of(new FinOpeningConfirmedAmountVo("1", "0")), service.confirmedByPartyIds("AR",
                " 1 ," + java.util.Collections.nCopies(300, "1").stream().collect(Collectors.joining(","))));
        }
        verify(customers).selectOptionsByIds(List.of(1L));
        verify(openings).selectConfirmedAmountsByPartyIds("AR", List.of(1L));
        verifyNoMoreInteractions(customers, openings);
    }

    @Test void nullOrDuplicatePersistedAmountsFailRatherThanFabricatingZeroOrSumming() {
        when(customers.selectOptionsByIds(List.of(1L))).thenReturn(List.of(customer(1)));
        FinOpening invalid = new FinOpening(); invalid.setPartyId(1L); invalid.setAmount(null);
        when(openings.selectConfirmedAmountsByPartyIds("AR", List.of(1L)))
            .thenReturn(List.of(invalid), List.of(opening(1L, "1.00"), opening(1L, "2.00")));
        try (var permissions = mockStatic(StpUtil.class)) {
            permissions.when(() -> StpUtil.hasPermission("erp:master:customer:list")).thenReturn(true);
            assertEquals(40902, assertThrows(ServiceException.class, () -> service.confirmedByPartyIds("AR", "1")).getCode());
            assertEquals(40902, assertThrows(ServiceException.class, () -> service.confirmedByPartyIds("AR", "1")).getCode());
        }
    }

    @Test void masterLookupFailureIsNotSwallowedAsKnownZero() {
        var failure = new IllegalStateException("scope query failed");
        when(customers.selectOptionsByIds(List.of(1L))).thenThrow(failure);
        try (var permissions = mockStatic(StpUtil.class)) {
            permissions.when(() -> StpUtil.hasPermission("erp:master:customer:list")).thenReturn(true);
            assertSame(failure, assertThrows(IllegalStateException.class, () -> service.confirmedByPartyIds("AR", "1")));
        }
        verifyNoInteractions(openings, suppliers, ledger, guard);
    }

    @Test void openingLookupFailureIsNotSwallowedAsKnownZero() {
        var failure = new IllegalStateException("opening query failed");
        when(customers.selectOptionsByIds(List.of(1L))).thenReturn(List.of(customer(1)));
        when(openings.selectConfirmedAmountsByPartyIds("AR", List.of(1L))).thenThrow(failure);
        try (var permissions = mockStatic(StpUtil.class)) {
            permissions.when(() -> StpUtil.hasPermission("erp:master:customer:list")).thenReturn(true);
            assertSame(failure, assertThrows(IllegalStateException.class, () -> service.confirmedByPartyIds("AR", "1")));
        }
        verifyNoInteractions(suppliers, ledger, guard);
    }

    private ErpCustomerVo customer(long id) { var row = new ErpCustomerVo(); row.setId(id); return row; }
    private ErpSupplierVo supplier(long id) { var row = new ErpSupplierVo(); row.setId(id); return row; }
    private FinOpening opening(long id, String amount) {
        var row = new FinOpening(); row.setPartyId(id); row.setAmount(new BigDecimal(amount));
        row.setStatus("CONFIRMED"); return row;
    }
}
