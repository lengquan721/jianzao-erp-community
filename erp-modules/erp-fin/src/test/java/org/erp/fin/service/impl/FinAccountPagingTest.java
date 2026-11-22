package org.erp.fin.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.erp.fin.domain.FinAccount;
import org.erp.fin.domain.FinAccountMovement;
import org.erp.fin.domain.FinOtherFlow;
import org.erp.fin.domain.FinPayment;
import org.erp.fin.domain.FinReceipt;
import org.erp.fin.domain.vo.FinAccountFlowVo;
import org.erp.fin.mapper.FinAccountMapper;
import org.erp.fin.mapper.FinOtherFlowMapper;
import org.erp.fin.mapper.FinPaymentMapper;
import org.erp.fin.mapper.FinReceiptMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** 实体页和 SQL 投影边界，不连接数据库；SQL 条件/方向由 FinAccountSqlContractTest 覆盖。 */
@ExtendWith(MockitoExtension.class)
@Tag("dev")
class FinAccountPagingTest {

    @Mock private FinAccountMapper accounts;
    @Mock private FinReceiptMapper receipts;
    @Mock private FinPaymentMapper payments;
    @Mock private FinOtherFlowMapper others;
    private FinAccountServiceImpl service;

    @BeforeEach
    void setUp() {
        var configuration = new MybatisConfiguration();
        for (Class<?> entity : List.of(FinAccount.class, FinReceipt.class, FinPayment.class, FinOtherFlow.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "account-page-test"), entity);
        }
        service = new FinAccountServiceImpl(accounts, receipts, payments, others);
    }

    @Test
    void accountListAggregatesOnlyCurrentPageIdsAndKeepsDecimalPrecisionAndShape() {
        FinAccount first = account(7L, "100.01");
        first.setCode("A007");
        first.setName("结算账户");
        first.setType("BANK");
        first.setStatus(1);
        first.setRemark("原备注");
        first.setCreateTime(new Date(1795219200123L));
        Page<FinAccount> entityPage = new Page<>(3, 3, 42);
        entityPage.setRecords(List.of(first, account(8L, "9007199254740993.01"), account(9L, null)));
        when(accounts.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(entityPage);
        when(accounts.selectMovements("TENANT_A", List.of(7L, 8L, 9L)))
            .thenReturn(List.of(movement(7L, "459.995"), movement(8L, "0.01")));

        var result = service.selectPage(null, null, null, new PageQuery(3, 3));

        assertEquals(42, result.getTotal());
        assertEquals(List.of(7L, 8L, 9L), result.getRows().stream().map(row -> row.getId()).toList());
        var row = result.getRows().get(0);
        assertEquals(new BigDecimal("560.01"), row.getBalance(), "先加期初后统一 HALF_UP，不能分项舍入");
        assertEquals(new BigDecimal("9007199254740993.02"), result.getRows().get(1).getBalance());
        assertEquals(new BigDecimal("0.00"), result.getRows().get(2).getBalance(), "无动账且期初 null 视作零");
        assertEquals(first.getOpeningBalance(), row.getOpeningBalance());
        assertEquals(first.getCode(), row.getCode());
        assertEquals(first.getName(), row.getName());
        assertEquals(first.getType(), row.getType());
        assertEquals(first.getStatus(), row.getStatus());
        assertEquals(first.getRemark(), row.getRemark());
        assertEquals(first.getCreateTime(), row.getCreateTime());
        verify(accounts).selectMovements("TENANT_A", List.of(7L, 8L, 9L));
        verify(accounts, never()).selectById(any());
        verifyNoInteractions(receipts, payments, others);
    }

    @Test
    void batchBalanceMatchesRetainedSingleAccountEntitySemanticsIncludingRefundNullAndSignedAmounts() {
        FinAccount head = account(7L, "100.00");
        when(accounts.selectById(7L)).thenReturn(head);
        FinReceipt ordinary = new FinReceipt();
        ordinary.setAmount(new BigDecimal("500.00"));
        FinReceipt refund = new FinReceipt();
        refund.setNegative(1);
        refund.setAmount(new BigDecimal("40.00"));
        FinReceipt signed = new FinReceipt();
        signed.setNegative(2);
        signed.setAmount(new BigDecimal("-1.005"));
        when(receipts.selectList(any(Wrapper.class))).thenReturn(List.of(ordinary, refund, signed, new FinReceipt()));
        FinPayment payment = new FinPayment();
        payment.setAmount(new BigDecimal("25.00"));
        FinPayment signedPayment = new FinPayment();
        signedPayment.setAmount(new BigDecimal("-2.00"));
        when(payments.selectList(any(Wrapper.class))).thenReturn(List.of(payment, signedPayment, new FinPayment()));
        when(others.selectList(any(Wrapper.class))).thenReturn(List.of(other("INCOME", "12.345"),
            other("EXPENSE", "4.005"), other("income", "1.00"), other(null, "2.00"), new FinOtherFlow()));
        Page<FinAccount> page = new Page<>(1, 2, 1);
        page.setRecords(List.of(head));
        when(accounts.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);
        // SQL 净额：500-40-1.005-(25-2)+12.345-4.005-1-2 = 441.335；SQL 符号另由合同测试验证。
        when(accounts.selectMovements("TENANT_A", List.of(7L))).thenReturn(List.of(movement(7L, "441.335")));

        BigDecimal single = service.selectById(7L).getBalance();
        BigDecimal paged = service.selectPage(null, null, null, new PageQuery(2, 1)).getRows().get(0).getBalance();

        assertEquals(new BigDecimal("541.34"), single);
        assertEquals(single, paged);
    }

    @Test
    void accountListWithNoCurrentRowsKeepsTotalAndDoesNotAggregateHistory() {
        Page<FinAccount> entityPage = new Page<>(20, 2, 7);
        entityPage.setRecords(List.of());
        when(accounts.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(entityPage);

        var result = service.selectPage(null, null, null, new PageQuery(2, 20));

        assertEquals(7, result.getTotal());
        assertTrue(result.getRows().isEmpty());
        verify(accounts, never()).selectMovements(any(), any());
        verifyNoInteractions(receipts, payments, others);
    }

    @Test
    void flowLastPageIsExactlySqlPageWithOriginalDateAmountNameAndDirection() {
        when(accounts.selectById(7L)).thenReturn(account(7L, "0.00"));
        when(accounts.countFlows("TENANT_A", 7L)).thenReturn(5L);
        FinAccountFlowVo refund = new FinAccountFlowVo();
        refund.setBizType("RECEIPT");
        refund.setBizNo("SK-REFUND");
        refund.setBizDate(new Date(1795219200123L));
        refund.setAmount(new BigDecimal("9007199254740993.01"));
        refund.setDirection("OUT");
        refund.setRowId(99L);
        List<FinAccountFlowVo> sqlPage = List.of(refund);
        when(accounts.selectFlowPage("TENANT_A", 7L, 4L, 2L)).thenReturn(sqlPage);

        var result = service.flows(7L, new PageQuery(2, 3));

        assertEquals(5, result.getTotal());
        assertSame(sqlPage, result.getRows(), "不能再内存排序或 subList 假分页");
        assertEquals(new Date(1795219200123L), result.getRows().get(0).getBizDate());
        assertEquals(new BigDecimal("9007199254740993.01"), result.getRows().get(0).getAmount());
        assertEquals("OUT", result.getRows().get(0).getDirection());
        assertNull(result.getRows().get(0).getCounterparty(), "缺失或已删客户名称仍可为 null");
        verify(accounts).selectById(7L);
        verify(accounts).countFlows("TENANT_A", 7L);
        verify(accounts).selectFlowPage("TENANT_A", 7L, 4L, 2L);
        verifyNoMoreInteractions(accounts);
        verifyNoInteractions(receipts, payments, others);
    }

    @Test
    void emptyFlowHistoryDoesNotRunPageOrNameQuery() {
        when(accounts.selectById(7L)).thenReturn(account(7L, "0"));
        when(accounts.countFlows("TENANT_A", 7L)).thenReturn(0L);

        var result = service.flows(7L, new PageQuery(2, 1));

        assertEquals(0, result.getTotal());
        assertTrue(result.getRows().isEmpty());
        verify(accounts, never()).selectFlowPage(any(), any(), anyLong(), anyLong());
        verifyNoInteractions(receipts, payments, others);
    }

    @Test
    void flowPastLastPageKeepsTotalInsteadOfOverflowingToFirstPage() {
        when(accounts.selectById(7L)).thenReturn(account(7L, "0"));
        when(accounts.countFlows("TENANT_A", 7L)).thenReturn(5L);

        var result = service.flows(7L, new PageQuery(2, 4));

        assertEquals(5, result.getTotal());
        assertTrue(result.getRows().isEmpty());
        verify(accounts, never()).selectFlowPage(any(), any(), anyLong(), anyLong());
        verifyNoInteractions(receipts, payments, others);
    }

    @Test
    void flowCurrentPageCanBeEmptyAfterCountWithoutDiscardingTotal() {
        when(accounts.selectById(7L)).thenReturn(account(7L, "0"));
        when(accounts.countFlows("TENANT_A", 7L)).thenReturn(5L);
        when(accounts.selectFlowPage("TENANT_A", 7L, 4L, 2L)).thenReturn(List.of());

        var result = service.flows(7L, new PageQuery(2, 3));

        assertEquals(5, result.getTotal());
        assertTrue(result.getRows().isEmpty());
    }

    @Test
    void flowOffsetUsesLongAndSharedPageSizeBoundWithoutIntegerOverflow() {
        when(accounts.selectById(7L)).thenReturn(account(7L, "0"));
        when(accounts.countFlows("TENANT_A", 7L)).thenReturn(Long.MAX_VALUE);
        when(accounts.selectFlowPage("TENANT_A", 7L, 429496729200L, 200L)).thenReturn(List.of());

        var result = service.flows(7L, new PageQuery(200, Integer.MAX_VALUE));

        assertEquals(Long.MAX_VALUE, result.getTotal());
        verify(accounts).selectFlowPage("TENANT_A", 7L, 429496729200L, 200L);
    }

    @Test
    void flowDefaultsAndNonPositivePageNumberUseFirstPageWhileOversizeIsRejected() {
        when(accounts.selectById(7L)).thenReturn(account(7L, "0"));
        when(accounts.countFlows("TENANT_A", 7L)).thenReturn(1L);
        when(accounts.selectFlowPage("TENANT_A", 7L, 0L, 20L)).thenReturn(List.of());

        assertEquals(1, service.flows(7L, new PageQuery(null, 0)).getTotal());
        verify(accounts).selectFlowPage("TENANT_A", 7L, 0L, 20L);
        assertThrows(ServiceException.class, () -> service.flows(7L, new PageQuery(201, 1)));
    }

    @Test
    void missingAccountKeepsExistingSingleAccountError() {
        assertThrows(ServiceException.class, () -> service.flows(404L, new PageQuery(2, 1)));
        verify(accounts, never()).countFlows(any(), any());
    }

    private FinAccount account(Long id, String opening) {
        FinAccount account = new FinAccount();
        account.setId(id);
        account.setTenantId("TENANT_A");
        account.setOpeningBalance(opening == null ? null : new BigDecimal(opening));
        return account;
    }

    private FinOtherFlow other(String type, String amount) {
        FinOtherFlow row = new FinOtherFlow();
        row.setFlowType(type);
        row.setAmount(new BigDecimal(amount));
        return row;
    }

    private FinAccountMovement movement(Long id, String amount) {
        FinAccountMovement row = new FinAccountMovement();
        row.setAccountId(id);
        row.setMovement(new BigDecimal(amount));
        return row;
    }
}
