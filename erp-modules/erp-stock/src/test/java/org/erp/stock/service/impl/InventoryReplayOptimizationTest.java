package org.erp.stock.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.erp.master.mapper.ErpCustomerMapper;
import org.erp.master.mapper.ErpProductMapper;
import org.erp.master.mapper.ErpSupplierMapper;
import org.erp.stock.domain.ErpStock;
import org.erp.stock.domain.ErpStockLog;
import org.erp.stock.mapper.ErpStockLogMapper;
import org.erp.stock.mapper.ErpStockMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 这是无数据库的回归测试，不作为检查点逐笔/并发生产等价证据。 */
@Tag("dev")
class InventoryReplayOptimizationTest {
    ErpStockMapper stocks = mock(ErpStockMapper.class);
    ErpStockLogMapper logs = mock(ErpStockLogMapper.class);
    InventoryServiceImpl service = new InventoryServiceImpl(stocks, logs, mock(ErpProductMapper.class),
        mock(ErpSupplierMapper.class), mock(ErpCustomerMapper.class));

    @Test
    void manyRowsSameProductAcrossWarehouses_lockAndReplayExactlyOncePerPair_andBalancesUseLockedSeed() {
        List<ErpStockLog> origins = new ArrayList<>();
        for (int i = 1; i <= 250; i++) {
            origins.add(row(i, i % 2 == 0 ? 2L : 1L, "PURCHASE", "1", "10"));
        }
        when(logs.selectBillStockKeys("P1")).thenReturn(origins);
        when(stocks.selectForUpdate(1L, 1L)).thenReturn(stock("125"));
        when(stocks.selectForUpdate(1L, 2L)).thenReturn(stock("125"));
        when(logs.selectBillRowsForUpdate("P1", null, null)).thenReturn(origins);
        when(stocks.deductQty(any(), any(), any())).thenReturn(1);
        when(logs.selectReplayRowsForUpdate(any(), any())).thenReturn(List.of());

        service.reverseByBill("P1", 9L, new Date(0));

        var order = inOrder(stocks, logs);
        order.verify(logs).selectBillStockKeys("P1");
        order.verify(stocks).selectForUpdate(1L, 1L);
        order.verify(stocks).selectForUpdate(1L, 2L);
        order.verify(logs).selectBillRowsForUpdate("P1", null, null);
        verify(stocks, times(1)).selectForUpdate(1L, 1L);
        verify(stocks, times(1)).selectForUpdate(1L, 2L);
        verify(logs, times(1)).selectReplayRowsForUpdate(1L, 1L);
        verify(logs, times(1)).selectReplayRowsForUpdate(1L, 2L);
        verify(stocks, never()).selectOne(any());
        verify(stocks, never()).selectCount(any());
        verify(logs, never()).selectList(any());
        ArgumentCaptor<ErpStockLog> captured = ArgumentCaptor.forClass(ErpStockLog.class);
        verify(logs, times(250)).insert(captured.capture());
        assertEquals(new BigDecimal("124"), captured.getAllValues().get(0).getBalanceAfter());
        assertEquals(new BigDecimal("0"), captured.getAllValues().get(248).getBalanceAfter());
        assertEquals(new BigDecimal("0"), captured.getAllValues().get(249).getBalanceAfter());
        assertEquals(249, captured.getAllValues().get(249).getSeq());
    }

    @Test
    void pairedVoids_areReadOnlyAfterStockLocks_andBlockSecondReversal() {
        var origin = row(1, 1L, "PURCHASE", "10", "10");
        var paired = row(2, 1L, "VOID", "-10", "10"); paired.setPartnerId(1L);
        when(logs.selectBillStockKeys("P1")).thenReturn(List.of(origin));
        when(stocks.selectForUpdate(1L, 1L)).thenReturn(stock("0"));
        when(logs.selectBillRowsForUpdate("P1", null, null)).thenReturn(List.of(origin, paired));
        var exception = assertThrows(ServiceException.class, () -> service.reverseByBill("P1", 9L, new Date(0)));
        assertTrue(exception.getMessage().contains("禁止重复回退"));
        var order = inOrder(stocks, logs);
        order.verify(logs).selectBillStockKeys("P1");
        order.verify(stocks).selectForUpdate(1L, 1L);
        order.verify(logs).selectBillRowsForUpdate("P1", null, null);
        verify(logs, never()).insert(any(ErpStockLog.class));
        verify(logs, never()).selectReplayRowsForUpdate(any(), any());
    }

    @Test
    void changedLockKeys_refuseInsteadOfLateUnorderedLockOrUnlockedReplay() {
        var old = row(1, 1L, "PURCHASE", "10", "10");
        var added = row(2, 2L, "PURCHASE", "10", "10");
        when(logs.selectBillStockKeys("P1")).thenReturn(List.of(old));
        when(stocks.selectForUpdate(1L, 1L)).thenReturn(stock("10"));
        when(logs.selectBillRowsForUpdate("P1", null, null)).thenReturn(List.of(old, added));
        var exception = assertThrows(ServiceException.class, () -> service.reverseByBill("P1", 9L, new Date(0)));
        assertEquals(40902, exception.getCode());
        verify(stocks, never()).selectForUpdate(1L, 2L);
        verify(logs, never()).insert(any(ErpStockLog.class));
    }

    @Test
    void deductionFailure_doesNotWriteVoidOrRecompute_andTransactionBoundaryIsPreserved() throws Exception {
        var origin = row(1, 1L, "PURCHASE", "10", "10");
        when(stocks.selectForUpdate(1L, 1L)).thenReturn(stock("5"));
        when(logs.selectBillRowsForUpdate("P1", 1L, 1L)).thenReturn(List.of(origin));
        var exception = assertThrows(ServiceException.class, () -> service.reverse("P1", 9L, 1L, 1L, new Date(0), 0));
        assertEquals(40901, exception.getCode());
        verify(logs, never()).insert(any(ErpStockLog.class));
        verify(logs, never()).selectReplayRowsForUpdate(any(), any());
        for (var method : InventoryServiceImpl.class.getDeclaredMethods()) {
            if (List.of("reverse", "reverseByBill", "replayAvgCost").contains(method.getName())) {
                Transactional transactional = method.getAnnotation(Transactional.class);
                assertNotNull(transactional);
                assertTrue(List.of(transactional.rollbackFor()).contains(Exception.class));
                assertEquals(org.springframework.transaction.annotation.Propagation.REQUIRED, transactional.propagation());
            }
        }
    }

    @Test
    void narrowReadReplay_matchesLegacyRoundingForDeterministicHistories_withOpeningPriorityAndVoids() {
        Random random = new Random(20261120L);
        for (int run = 0; run < 75; run++) {
            List<ErpStockLog> rows = new ArrayList<>();
            // 后补 OPENING id 大，仍必须排在其它流水前；业务日期与 ID 不同序。
            rows.add(row(10000, 1L, "OPENING", "100", "10.1234"));
            int balance = 100;
            for (int i = 1; i <= 200; i++) {
                boolean positive = balance < 10 || random.nextBoolean();
                int delta = positive ? 1 + random.nextInt(15) : -(1 + random.nextInt(Math.min(15, balance)));
                balance += delta;
                String type = i % 7 == 0 ? "VOID" : positive ? "PURCHASE" : "SALE";
                String cost = BigDecimal.valueOf(random.nextInt(300000), 4).toPlainString();
                rows.add(row(i, 1L, type, Integer.toString(delta), cost));
            }
            BigDecimal expected = legacyAverage(rows);
            Collections.shuffle(rows, random);
            reset(stocks, logs);
            when(stocks.selectForUpdate(1L, 1L)).thenReturn(stock(Integer.toString(balance)));
            when(logs.selectReplayRowsForUpdate(1L, 1L)).thenReturn(List.copyOf(rows));
            service.replayAvgCost(1L, 1L);
            verify(stocks).updateAvgCost(1L, 1L, expected);
            var order = inOrder(stocks, logs);
            order.verify(stocks).selectForUpdate(1L, 1L);
            order.verify(logs).selectReplayRowsForUpdate(1L, 1L);
            verify(logs, never()).selectList(any());
        }
    }

    /** 原基线算式（包含逐次 HALF_UP 6 -> 4 舍入），不借新服务计算期望值。 */
    private BigDecimal legacyAverage(List<ErpStockLog> input) {
        List<ErpStockLog> rows = new ArrayList<>(input);
        rows.sort(Comparator.comparing((ErpStockLog r) -> "OPENING".equals(r.getBizType()) ? 0 : 1)
            .thenComparing(ErpStockLog::getId));
        BigDecimal qty = BigDecimal.ZERO;
        BigDecimal avg = BigDecimal.ZERO;
        for (var r : rows) {
            BigDecimal delta = r.getQty();
            BigDecimal cost = r.getUnitCost() == null ? BigDecimal.ZERO : r.getUnitCost();
            boolean isVoid = "VOID".equals(r.getBizType());
            if (delta.signum() > 0) {
                if (!isVoid && qty.add(delta).signum() > 0) {
                    avg = qty.multiply(avg).add(delta.multiply(cost)).divide(qty.add(delta), 6, RoundingMode.HALF_UP)
                        .setScale(4, RoundingMode.HALF_UP);
                }
                qty = qty.add(delta);
            } else {
                BigDecimal remaining = qty.add(delta);
                if (isVoid && remaining.signum() > 0 && cost.signum() > 0) {
                    avg = qty.multiply(avg).subtract(delta.negate().multiply(cost)).divide(remaining, 6, RoundingMode.HALF_UP)
                        .setScale(4, RoundingMode.HALF_UP);
                } else if (isVoid && remaining.signum() == 0) {
                    avg = BigDecimal.ZERO;
                }
                qty = remaining;
            }
        }
        return avg.setScale(4, RoundingMode.HALF_UP);
    }

    private ErpStockLog row(long id, Long warehouseId, String type, String qty, String cost) {
        var row = new ErpStockLog();
        row.setId(id); row.setProductId(1L); row.setWarehouseId(warehouseId); row.setBizType(type);
        row.setQty(new BigDecimal(qty)); row.setUnitCost(new BigDecimal(cost));
        row.setAmount(row.getQty().multiply(row.getUnitCost())); row.setBizDate(new Date(-id * 86400000L));
        return row;
    }

    private ErpStock stock(String qty) {
        var stock = new ErpStock(); stock.setQty(new BigDecimal(qty)); stock.setAvgCost(BigDecimal.TEN);
        return stock;
    }
}
