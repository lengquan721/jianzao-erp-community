package org.erp.trade.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.tenant.helper.TenantHelper;
import org.erp.trade.service.IErpOrderProgressService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ErpOrderProgressServiceImpl 单元测试（DOC/31 §13.1 单元测试清单）。
 *
 * 覆盖：recalc 幂等（连跑三次结果一致）、退货净额、超收后 remain 不下探、关闭+退货 closed_qty 不膨胀、
 * 改单后按新数量/新交期重算、加锁顺序（订单头 → 订单行 id ASC → 聚合 → 回写）；
 * lockAndValidate 的超收容差上限（含 3 位 HALF_UP）、同订单行多行汇总、跨单行/空行/未确认拦截。
 *
 * <p>JdbcTemplate 用 Mockito 模拟，结果集按服务内 RowMapper 真实映射，不连接数据库。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Tag("dev")
class ErpOrderProgressServiceImplTest {

    private static final String TENANT = "000000";
    private static final long ORDER_ID = 100L;
    private static final java.sql.Date D7 = java.sql.Date.valueOf("2026-11-12");
    private static final java.sql.Date D10 = java.sql.Date.valueOf("2026-11-15");
    private static final java.sql.Date D30 = java.sql.Date.valueOf("2026-12-05");

    @Mock
    private JdbcTemplate jdbcTemplate;

    private MockedStatic<TenantHelper> tenantMock;

    private ErpOrderProgressServiceImpl service;

    private final List<ItemSpec> items = new ArrayList<>();
    private final Map<Long, BigDecimal> executed = new HashMap<>();
    private final Map<Long, BigDecimal> returned = new HashMap<>();
    private final List<UpdateCall> updates = new ArrayList<>();
    private final Map<Long, BigDecimal> storedExecuted = new HashMap<>();
    private final Map<Long, BigDecimal> storedClosed = new HashMap<>();
    private final List<String> groupedSqls = new ArrayList<>();
    private String headStatus;
    private BigDecimal overTol;
    private java.sql.Date storedDueDate;
    private String storedExecStatus;

    @BeforeEach
    void setUp() {
        tenantMock = org.mockito.Mockito.mockStatic(TenantHelper.class);
        tenantMock.when(TenantHelper::getTenantId).thenReturn(TENANT);
        service = new ErpOrderProgressServiceImpl(jdbcTemplate);
        items.clear();
        executed.clear();
        returned.clear();
        updates.clear();
        storedExecuted.clear();
        storedClosed.clear();
        groupedSqls.clear();
        headStatus = "CONFIRMED";
        overTol = BigDecimal.ZERO;
        storedDueDate = null;
        storedExecStatus = null;
        stubJdbc();
    }

    @AfterEach
    void tearDown() {
        tenantMock.close();
    }

    // ---------- recalc ----------

    @Test
    void recalc_连跑三次结果完全一致() {
        givenOrder("CONFIRMED", "0", item(1L, "100", "0", "0", 0, D7));
        executed.put(1L, new BigDecimal("30"));

        service.recalc(IErpOrderProgressService.TYPE_PURCHASE, ORDER_ID, null);
        service.recalc(IErpOrderProgressService.TYPE_PURCHASE, ORDER_ID, null);
        service.recalc(IErpOrderProgressService.TYPE_PURCHASE, ORDER_ID, null);

        // 首次修正时更新一次；随后两次结果完全相同，不重复 UPDATE 行或头。
        List<UpdateCall> itemWrites = itemUpdates();
        List<UpdateCall> headWrites = headUpdates();
        assertEquals(1, itemWrites.size());
        assertEquals(1, headWrites.size());
        assertItemWrite(itemWrites.get(0).values(), "30", "0", 1L);
        assertHeadWrite(headWrites.get(0).values(), D7, "PARTIAL");
    }

    @Test
    void recalc_退货按订单行净额扣减() {
        givenOrder("CONFIRMED", "0", item(1L, "100", "0", "0", 0, D7));
        executed.put(1L, new BigDecimal("60"));
        returned.put(1L, new BigDecimal("10"));

        service.recalc(IErpOrderProgressService.TYPE_PURCHASE, ORDER_ID, null);

        // 60 - 10 = 50，订单回到部分执行
        assertItemWrite(itemUpdates().get(0).values(), "50", "0", 1L);
        assertHeadWrite(headUpdates().get(0).values(), D7, "PARTIAL");
    }

    @Test
    void recalc_关闭后部分退货_closedQty保持40不膨胀() {
        // qty=100、已执行 60、关闭 40；退货 10 → executed 50，closed 仍冻结在 40，不得变成 50
        givenOrder("CONFIRMED", "0", item(1L, "100", "0", "40", 1, D7));
        executed.put(1L, new BigDecimal("60"));
        returned.put(1L, new BigDecimal("10"));

        service.recalc(IErpOrderProgressService.TYPE_PURCHASE, ORDER_ID, null);

        assertItemWrite(itemUpdates().get(0).values(), "50", "40", 1L);
        // 仍有未交量 10 → 关闭行被退货「打开」回 PARTIAL
        assertHeadWrite(headUpdates().get(0).values(), D7, "PARTIAL");
    }

    @Test
    void recalc_超收时executed可大于qty但remain为零且状态FULL() {
        // D6：允许超收，executed_qty 记录真实净额 110；remain=max(0,100-110)=0，进度按 min 截断到 100%
        givenOrder("CONFIRMED", "10", item(1L, "100", "0", "0", 0, D7));
        executed.put(1L, new BigDecimal("110"));

        service.recalc(IErpOrderProgressService.TYPE_PURCHASE, ORDER_ID, null);

        assertItemWrite(itemUpdates().get(0).values(), "110", "0", 1L);
        assertHeadWrite(headUpdates().get(0).values(), D7, "FULL");
    }

    @Test
    void recalc_改单后按新数量与新交期重算() {
        givenOrder("CONFIRMED", "0", item(1L, "100", "0", "0", 0, D30));
        executed.put(1L, new BigDecimal("30"));
        service.recalc(IErpOrderProgressService.TYPE_PURCHASE, ORDER_ID, null);

        // 改单：qty 100→120，交期 D30→D10；重算后 executed 不变、剩余量按新 qty 重算、头交期取新最早交期
        givenOrder("CONFIRMED", "0", item(1L, "120", "0", "0", 0, D10));
        service.recalc(IErpOrderProgressService.TYPE_PURCHASE, ORDER_ID, null);

        List<UpdateCall> headWrites = headUpdates();
        assertEquals(2, headWrites.size());
        assertEquals(D30.toLocalDate(), ((java.sql.Date) headWrites.get(0).values()[0]).toLocalDate());
        assertEquals(D10.toLocalDate(), ((java.sql.Date) headWrites.get(1).values()[0]).toLocalDate());
        assertEquals("PARTIAL", headWrites.get(1).values()[1]);
        assertItemWrite(itemUpdates().get(1).values(), "30", "0", 1L);
    }

    @Test
    void recalc_全部关闭且无未交量时状态CLOSED() {
        givenOrder("CONFIRMED", "0", item(1L, "100", "0", "40", 1, D7));
        executed.put(1L, new BigDecimal("60"));

        service.recalc(IErpOrderProgressService.TYPE_PURCHASE, ORDER_ID, null);

        assertItemWrite(itemUpdates().get(0).values(), "60", "40", 1L);
        // 没有未交量但有放弃量 → CLOSED；全部交完时头交期保留原最早交期，不置空
        assertHeadWrite(headUpdates().get(0).values(), D7, "CLOSED");
    }

    @Test
    void recalc_无订单行时状态NOT_STARTED且交期为空() {
        givenOrder("CONFIRMED", "0");

        service.recalc(IErpOrderProgressService.TYPE_PURCHASE, ORDER_ID, null);

        assertTrue(itemUpdates().isEmpty());
        UpdateCall head = headUpdates().get(0);
        assertNull(head.values()[0]);
        assertEquals("NOT_STARTED", head.values()[1]);
    }

    @Test
    void recalc_先锁订单头再锁订单行最后聚合回写() {
        givenOrder("CONFIRMED", "0", item(1L, "100", "0", "0", 0, D7));
        executed.put(1L, new BigDecimal("30"));

        service.recalc(IErpOrderProgressService.TYPE_PURCHASE, ORDER_ID, null);

        // DOC/31 §6.8.1：订单头 → 订单行（id ASC）→ 聚合读 → 行/头回写，顺序不能反
        InOrder order = inOrder(jdbcTemplate);
        order.verify(jdbcTemplate).query(org.mockito.ArgumentMatchers.argThat(sql -> sql.contains("over_tol_pct")),
            any(RowMapper.class), any(), any());
        order.verify(jdbcTemplate).query(org.mockito.ArgumentMatchers.argThat(sql -> sql.contains("select id, qty")),
            any(RowMapper.class), any(), any());
        order.verify(jdbcTemplate).queryForList(org.mockito.ArgumentMatchers.argThat(sql ->
            sql.contains("biz_purchase_item") && !sql.contains("return_item")), any(Object[].class));
        order.verify(jdbcTemplate).queryForList(org.mockito.ArgumentMatchers.argThat(sql ->
            sql.contains("return_item")), any(Object[].class));
        order.verify(jdbcTemplate).update(org.mockito.ArgumentMatchers.argThat(sql -> sql.contains("executed_qty")),
            any(), any(), any(), any());
        order.verify(jdbcTemplate).update(org.mockito.ArgumentMatchers.argThat(sql -> sql.contains("exec_status")),
            any(), any(), any(), any());
    }

    @Test
    void recalc_订单不存在抛40905() {
        doReturn(List.of()).when(jdbcTemplate).query(anyString(), any(RowMapper.class), any(), any());

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.recalc(IErpOrderProgressService.TYPE_PURCHASE, ORDER_ID, null));

        assertEquals(40905, ex.getCode());
    }

    @Test
    void recalc_缺少租户上下文拒绝且不回落默认租户() {
        tenantMock.when(TenantHelper::getTenantId).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.recalc(IErpOrderProgressService.TYPE_PURCHASE, ORDER_ID, null));

        assertEquals(40901, ex.getCode());
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void recalc_已变更行SQL带租户和软删条件() {
        givenOrder("CONFIRMED", "0", item(1L, "100", "0", "0", 0, D7));
        executed.put(1L, new BigDecimal("30"));

        service.recalc(IErpOrderProgressService.TYPE_PURCHASE, ORDER_ID, null);

        assertTrue(itemUpdates().get(0).sql().contains("tenant_id = ?"));
        assertTrue(itemUpdates().get(0).sql().contains("del_flag = '0'"));
        assertTrue(headUpdates().get(0).sql().contains("tenant_id = ?"));
        assertTrue(headUpdates().get(0).sql().contains("del_flag = '0'"));
    }

    @Test
    void recalc_结果未变化时不更新头行() {
        givenOrder("CONFIRMED", "0", item(1L, "100", "0", "0", 0, D7));
        storedDueDate = D7;
        storedExecStatus = "NOT_STARTED";

        service.recalc(IErpOrderProgressService.TYPE_PURCHASE, ORDER_ID, null);

        verify(jdbcTemplate, never()).update(anyString(), any(), any(), any(), any());
    }

    @Test
    void recalc_采购与销售使用各自表名() {
        givenOrder("CONFIRMED", "0", item(1L, "10", "0", "0", 0, D7));
        executed.put(1L, BigDecimal.ONE);

        service.recalc(IErpOrderProgressService.TYPE_SALE, ORDER_ID, null);

        List<String> sqls = updates.stream().map(UpdateCall::sql).toList();
        assertTrue(sqls.get(0).contains("biz_sale_order_item"));
        assertTrue(sqls.get(1).contains("biz_sale_order"));
    }

    // ---------- lockAndValidate ----------

    @Test
    void lockAndValidate_容差为零超收被拒40902() {
        givenOrder("CONFIRMED", "0", item(1L, "100", "60", "0", 0, D7));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.lockAndValidate(
            IErpOrderProgressService.TYPE_PURCHASE, ORDER_ID, List.of(new IErpOrderProgressService.ExecLine(1L, new BigDecimal("50")))));

        assertEquals(40902, ex.getCode());
        assertEquals("超出订单量容差：订单 100，可收上限 100，已收 60，本次 50", ex.getMessage());
    }

    @Test
    void lockAndValidate_容差10pct允许累计到110() {
        givenOrder("CONFIRMED", "10", item(1L, "100", "60", "0", 0, D7));

        service.lockAndValidate(IErpOrderProgressService.TYPE_PURCHASE, ORDER_ID,
            List.of(new IErpOrderProgressService.ExecLine(1L, new BigDecimal("50"))));

        // 未抛异常即通过；上限 = 100 × 1.10 = 110，60 + 50 = 110 恰好达上限
        verify(jdbcTemplate, never()).update(anyString(), any(), any(), any(), any());
    }

    @Test
    void lockAndValidate_达到上限后再多收1被拒() {
        givenOrder("CONFIRMED", "10", item(1L, "100", "110", "0", 0, D7));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.lockAndValidate(
            IErpOrderProgressService.TYPE_PURCHASE, ORDER_ID, List.of(new IErpOrderProgressService.ExecLine(1L, BigDecimal.ONE))));

        assertEquals(40902, ex.getCode());
        assertTrue(ex.getMessage().contains("可收上限 110"));
    }

    @Test
    void lockAndValidate_同一订单行多行先汇总再校验() {
        givenOrder("CONFIRMED", "0", item(1L, "50", "0", "0", 0, D7));

        // 30 + 30 = 60 > 50：必须按订单行汇总后校验，不能逐行放过
        ServiceException ex = assertThrows(ServiceException.class, () -> service.lockAndValidate(
            IErpOrderProgressService.TYPE_PURCHASE, ORDER_ID, List.of(
                new IErpOrderProgressService.ExecLine(1L, new BigDecimal("30")),
                new IErpOrderProgressService.ExecLine(1L, new BigDecimal("30")))));

        assertTrue(ex.getMessage().contains("本次 60"));
    }

    @Test
    void lockAndValidate_订单行不属于本单被拒() {
        givenOrder("CONFIRMED", "0", item(1L, "50", "0", "0", 0, D7));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.lockAndValidate(
            IErpOrderProgressService.TYPE_PURCHASE, ORDER_ID,
            List.of(new IErpOrderProgressService.ExecLine(99L, BigDecimal.ONE))));

        assertEquals(40902, ex.getCode());
        assertTrue(ex.getMessage().contains("执行行不属于该订单"));
    }

    @Test
    void lockAndValidate_挂订单空行被拒() {
        givenOrder("CONFIRMED", "0", item(1L, "50", "0", "0", 0, D7));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.lockAndValidate(
            IErpOrderProgressService.TYPE_PURCHASE, ORDER_ID,
            List.of(new IErpOrderProgressService.ExecLine(1L, BigDecimal.ONE),
                new IErpOrderProgressService.ExecLine(null, BigDecimal.ONE))));

        assertEquals(40902, ex.getCode());
        assertTrue(ex.getMessage().contains("不能手工加行"));
    }

    @Test
    void lockAndValidate_订单未确认被拒() {
        givenOrder("DRAFT", "0", item(1L, "50", "0", "0", 0, D7));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.lockAndValidate(
            IErpOrderProgressService.TYPE_PURCHASE, ORDER_ID,
            List.of(new IErpOrderProgressService.ExecLine(1L, BigDecimal.ONE))));

        assertEquals(40902, ex.getCode());
        assertTrue(ex.getMessage().contains("订单未确认"));
    }

    @Test
    void lockAndValidate_容差上限按3位小数HALF_UP() {
        // 1.111 × (1 + 50%) = 1.6665 → 3 位 HALF_UP = 1.667
        givenOrder("CONFIRMED", "50", item(1L, "1.111", "0", "0", 0, D7));

        service.lockAndValidate(IErpOrderProgressService.TYPE_PURCHASE, ORDER_ID,
            List.of(new IErpOrderProgressService.ExecLine(1L, new BigDecimal("1.667"))));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.lockAndValidate(
            IErpOrderProgressService.TYPE_PURCHASE, ORDER_ID,
            List.of(new IErpOrderProgressService.ExecLine(1L, new BigDecimal("1.668")))));
        assertTrue(ex.getMessage().contains("可收上限 1.667"));
    }

    @Test
    void lockAndValidate_直开单直接返回不加锁() {
        service.lockAndValidate(IErpOrderProgressService.TYPE_PURCHASE, null,
            List.of(new IErpOrderProgressService.ExecLine(null, BigDecimal.ONE)));

        verify(jdbcTemplate, never()).query(anyString(), any(RowMapper.class), any(), any());
        verify(jdbcTemplate, never()).queryForList(anyString(), any(Object[].class));
    }

    @Test
    void lockOrder_订单不存在抛40905() {
        doReturn(List.of()).when(jdbcTemplate).query(anyString(), any(RowMapper.class), any(), any());

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.lockOrder(IErpOrderProgressService.TYPE_PURCHASE, ORDER_ID));

        assertEquals(40905, ex.getCode());
    }

    // ---------- 辅助 ----------

    /**
     * 订单行规格（测试内构造，服务内部 record 不对外暴露）。
     */
    private record ItemSpec(long id, String qty, String executedQty, String closedQty, int closedFlag,
                            java.sql.Date dueDate) {
    }

    /**
     * 一次 update 调用：SQL + 参数。
     */
    private record UpdateCall(String sql, Object[] values) {
    }

    private ItemSpec item(long id, String qty, String executedQty, String closedQty, int closedFlag,
                          java.sql.Date dueDate) {
        return new ItemSpec(id, qty, executedQty, closedQty, closedFlag, dueDate);
    }

    private void givenOrder(String status, String tolerance, ItemSpec... specs) {
        headStatus = status;
        overTol = new BigDecimal(tolerance);
        items.clear();
        items.addAll(List.of(specs));
        storedExecuted.clear();
        storedClosed.clear();
        for (ItemSpec spec : specs) {
            storedExecuted.put(spec.id(), new BigDecimal(spec.executedQty()));
            storedClosed.put(spec.id(), new BigDecimal(spec.closedQty()));
        }
    }

    /**
     * JdbcTemplate 桩：query 按 SQL 区分订单头/订单行，用服务自己的 RowMapper 映射模拟结果集；
     * queryForList 按 SQL 区分执行量/退货量聚合；update 记录回写参数。
     */
    private void stubJdbc() {
        doAnswer(inv -> {
            String sql = inv.getArgument(0);
            RowMapper<?> mapper = inv.getArgument(1);
            if (sql.contains("over_tol_pct")) {
                return List.of(mapper.mapRow(headResultSet(), 0));
            }
            List<Object> rows = new ArrayList<>();
            int rowNum = 0;
            for (ItemSpec spec : items) {
                rows.add(mapper.mapRow(itemResultSet(spec), rowNum++));
            }
            return rows;
        }).when(jdbcTemplate).query(anyString(), any(RowMapper.class), any(), any());

        org.mockito.stubbing.Answer<List<Map<String, Object>>> groupedAnswer = inv -> {
            String sql = inv.getArgument(0);
            Map<Long, BigDecimal> source = sql.contains("return_item") ? returned : executed;
            List<Map<String, Object>> rows = new ArrayList<>();
            source.forEach((id, total) -> {
                Map<String, Object> row = new HashMap<>();
                row.put("item_id", id);
                row.put("total", total);
                rows.add(row);
            });
            return rows;
        };
        doAnswer(groupedAnswer).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));

        doAnswer(inv -> {
            Object[] raw = inv.getArguments();
            Object[] values;
            if (raw.length == 2 && raw[1] instanceof Object[]) {
                values = (Object[]) raw[1];
            } else {
                values = Arrays.copyOfRange(raw, 1, raw.length);
            }
            String sql = (String) raw[0];
            updates.add(new UpdateCall(sql, values));
            if (sql.contains("executed_qty")) {
                storedExecuted.put(((Number) values[2]).longValue(), (BigDecimal) values[0]);
                storedClosed.put(((Number) values[2]).longValue(), (BigDecimal) values[1]);
            } else if (sql.contains("exec_status")) {
                storedDueDate = (java.sql.Date) values[0];
                storedExecStatus = (String) values[1];
            }
            return 1;
        }).when(jdbcTemplate).update(anyString(), any(), any(), any(), any());
    }

    private ResultSet headResultSet() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("status")).thenReturn(headStatus);
        when(rs.getBigDecimal("over_tol_pct")).thenReturn(overTol);
        when(rs.getDate("due_date")).thenReturn(storedDueDate);
        when(rs.getString("exec_status")).thenReturn(storedExecStatus);
        return rs;
    }

    private ResultSet itemResultSet(ItemSpec spec) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("id")).thenReturn(spec.id());
        when(rs.getBigDecimal("qty")).thenReturn(new BigDecimal(spec.qty()));
        when(rs.getBigDecimal("executed_qty")).thenReturn(storedExecuted.getOrDefault(spec.id(), new BigDecimal(spec.executedQty())));
        when(rs.getBigDecimal("closed_qty")).thenReturn(storedClosed.getOrDefault(spec.id(), new BigDecimal(spec.closedQty())));
        when(rs.getInt("closed_flag")).thenReturn(spec.closedFlag());
        when(rs.getDate("due_date")).thenReturn(spec.dueDate());
        return rs;
    }

    private List<UpdateCall> itemUpdates() {
        return updates.stream().filter(u -> u.sql().contains("executed_qty")).toList();
    }

    private List<UpdateCall> headUpdates() {
        return updates.stream().filter(u -> u.sql().contains("exec_status")).toList();
    }

    private void assertItemWrite(Object[] values, String executed, String closed, long itemId) {
        assertEquals(0, new BigDecimal(executed).compareTo((BigDecimal) values[0]));
        assertEquals(0, new BigDecimal(closed).compareTo((BigDecimal) values[1]));
        assertEquals(itemId, values[2]);
        assertEquals(TENANT, values[3]);
    }

    private void assertHeadWrite(Object[] values, java.sql.Date dueDate, String execStatus) {
        assertEquals(dueDate.toLocalDate(), ((java.sql.Date) values[0]).toLocalDate());
        assertEquals(execStatus, values[1]);
        assertEquals(ORDER_ID, values[2]);
        assertEquals(TENANT, values[3]);
    }

    /**
     * 参数签名（忽略 BigDecimal 尾零差异），用于幂等断言。
     *
     * @param values 参数
     * @return 可比较的签名字符串
     */
    private String signature(Object[] values) {
        List<String> parts = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof BigDecimal) {
                parts.add(((BigDecimal) value).stripTrailingZeros().toPlainString());
            } else {
                parts.add(String.valueOf(value));
            }
        }
        return String.join("|", parts);
    }
}
