package org.erp.trade.service.impl;

import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.tenant.helper.TenantHelper;
import org.erp.trade.service.IErpOrderProgressService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 订单执行进度服务实现。
 *
 * <p>加锁顺序（全链路统一，DOC/31 §6.8.1）：单据头 → 订单头 → 订单行（id ASC）→ fin_ar_ap 既有行
 * → st_stock（product_id ASC）→ fin_ar_ap 新行。本类只做「订单头 → 订单行」，订单行一律按 id 升序；
 * 执行单确认、撤回、作废、退货四条链路都必须先锁自身单据头，再调这里，禁止反过来。
 *
 * <p>直接用 JdbcTemplate：重算要跨采购/销售两套表，租户条件显式带入（原生 SQL 不经过多租户插件）。
 */
@RequiredArgsConstructor
@Service
public class ErpOrderProgressServiceImpl implements IErpOrderProgressService {

    /**
     * 数量精度 DECIMAL(18,3)（DOC/31 §5.8）
     */
    private static final int QTY_SCALE = 3;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private static final OrderTables PURCHASE_TABLES = new OrderTables(
        "biz_purchase_order", "biz_purchase_order_item", "biz_purchase", "biz_purchase_item",
        "biz_purchase_return", "biz_purchase_return_item", "purchase_item_id");

    private static final OrderTables SALE_TABLES = new OrderTables(
        "biz_sale_order", "biz_sale_order_item", "biz_sale", "biz_sale_item",
        "biz_sale_return", "biz_sale_return_item", "sale_item_id");

    private static final RowMapper<OrderItemRow> ITEM_ROW_MAPPER = (rs, rowNum) -> new OrderItemRow(
        rs.getLong("id"), nvl(rs.getBigDecimal("qty")), nvl(rs.getBigDecimal("executed_qty")),
        nvl(rs.getBigDecimal("closed_qty")), rs.getInt("closed_flag"), rs.getDate("due_date"));

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void lockOrder(String orderType, Long orderId) {
        if (orderId == null) {
            return;
        }
        OrderTables t = tables(orderType);
        String tenantId = currentTenantId();
        if (lockHead(t, orderId, tenantId) == null) {
            throw new ServiceException("订单不存在或已删除", 40905);
        }
        // 一次锁齐本单全部订单行（id ASC），与 recalc 的行集一致，避免 IN 列表与全量锁顺序打架
        selectItems(t, orderId, tenantId, true);
    }

    @Override
    public void lockAndValidate(String orderType, Long orderId, List<ExecLine> lines) {
        if (orderId == null) {
            return;
        }
        OrderTables t = tables(orderType);
        String tenantId = currentTenantId();
        OrderHead head = lockHead(t, orderId, tenantId);
        if (head == null) {
            throw new ServiceException("订单不存在或已删除", 40905);
        }
        if (!"CONFIRMED".equals(head.status())) {
            throw new ServiceException("订单未确认或已作废，不能执行", 40902);
        }
        String billName = TYPE_SALE.equals(orderType) ? "出库单" : "入库单";
        if (lines == null || lines.isEmpty()) {
            throw new ServiceException("挂订单的" + billName + "不能手工加行，请删除该行或改成直开单", 40902);
        }
        // 同一订单行可能被拆成多行执行，先按订单行汇总本批量
        Map<Long, BigDecimal> batchQty = new LinkedHashMap<>();
        for (ExecLine line : lines) {
            if (line.orderItemId() == null) {
                throw new ServiceException("挂订单的" + billName + "不能手工加行，请删除该行或改成直开单", 40902);
            }
            if (line.qty() == null || line.qty().compareTo(BigDecimal.ZERO) <= 0) {
                throw new ServiceException("执行数量必须大于0", 40902);
            }
            batchQty.merge(line.orderItemId(), line.qty(), BigDecimal::add);
        }
        Map<Long, OrderItemRow> orderItems = new HashMap<>();
        for (OrderItemRow row : selectItems(t, orderId, tenantId, true)) {
            orderItems.put(row.id(), row);
        }
        // 上限 = 订单量 × (1 + 超收容差%/100)，HALF_UP 3 位；行必须属于本单，防止跨单混选
        BigDecimal tolerance = BigDecimal.ONE.add(nvl(head.overTolPct()).divide(HUNDRED, 6, RoundingMode.HALF_UP));
        for (Map.Entry<Long, BigDecimal> entry : batchQty.entrySet()) {
            OrderItemRow item = orderItems.get(entry.getKey());
            if (item == null) {
                throw new ServiceException("执行行不属于该订单：" + entry.getKey(), 40902);
            }
            BigDecimal limit = item.qty().multiply(tolerance).setScale(QTY_SCALE, RoundingMode.HALF_UP);
            BigDecimal total = item.executedQty().add(entry.getValue());
            if (total.compareTo(limit) > 0) {
                // 文案按 §6.6/§13.1 第 6 条：可收上限 / 已收 / 本次
                throw new ServiceException("超出订单量容差：订单 " + fmt(item.qty()) + "，可收上限 " + fmt(limit)
                    + "，已收 " + fmt(item.executedQty()) + "，本次 " + fmt(entry.getValue()), 40902);
            }
        }
    }

    @Override
    public void recalc(String orderType, Long orderId, Long excludeBillId) {
        if (orderId == null) {
            return;
        }
        OrderTables t = tables(orderType);
        String tenantId = currentTenantId();
        // 先锁订单头再锁订单行：结尾要 UPDATE 订单头，顺序反了会与订单改单成环（§6.8.1 规则 1）。
        OrderHead head = lockHead(t, orderId, tenantId);
        if (head == null) {
            throw new ServiceException("订单不存在或已删除", 40905);
        }
        selectItems(t, orderId, tenantId, true);
        Map<Long, BigDecimal> executed = sumExecuted(t, tenantId, orderId, excludeBillId);
        Map<Long, BigDecimal> returned = sumReturned(t, tenantId, orderId);
        List<OrderItemRow> items = selectItems(t, orderId, tenantId, false);
        BigDecimal sumExecuted = BigDecimal.ZERO;
        BigDecimal sumClosed = BigDecimal.ZERO;
        boolean hasRemain = false;
        Date earliestAll = null;
        Date earliestRemain = null;
        for (OrderItemRow item : items) {
            BigDecimal net = executed.getOrDefault(item.id(), BigDecimal.ZERO)
                .subtract(returned.getOrDefault(item.id(), BigDecimal.ZERO)).max(BigDecimal.ZERO);
            // 关闭量在关闭那一刻冻结，重算只做下界保护，不再按 qty − executed 推导（D7）。
            BigDecimal closed = item.closedFlag() == 1
                ? item.closedQty().min(item.qty().subtract(net).max(BigDecimal.ZERO)) : BigDecimal.ZERO;
            // recalc 可能因确认/撤回/订单改单重复调用；不变行不发 UPDATE，减少锁持有和 binlog 噪声。
            if (item.executedQty().compareTo(net) != 0 || item.closedQty().compareTo(closed) != 0) {
                jdbcTemplate.update("update " + t.item()
                        + " set executed_qty = ?, closed_qty = ? where id = ? and tenant_id = ? and del_flag = '0'",
                    net, closed, item.id(), tenantId);
            }
            sumExecuted = sumExecuted.add(net);
            sumClosed = sumClosed.add(closed);
            earliestAll = earlier(earliestAll, item.dueDate());
            if (item.qty().subtract(net).subtract(closed).signum() > 0) {
                hasRemain = true;
                earliestRemain = earlier(earliestRemain, item.dueDate());
            }
        }
        String execStatus = items.isEmpty() ? "NOT_STARTED" : execStatusOf(hasRemain, sumExecuted, sumClosed);
        Date targetDue = earliestRemain != null ? earliestRemain : earliestAll;
        // 全部交完/关闭时保留全部行的最早交期，不置空（§5.5.1 的「置空」表述已被 §6.1.3 与 DDL 覆盖）。
        // 头表同样只在派生值变化时覆盖，避免无意义写锁；不改变业务状态语义。
        if (!sameDate(head.dueDate(), targetDue) || !Objects.equals(head.execStatus(), execStatus)) {
            jdbcTemplate.update("update " + t.head()
                    + " set due_date = ?, exec_status = ? where id = ? and tenant_id = ? and del_flag = '0'",
                targetDue, execStatus, orderId, tenantId);
        }
    }

    /**
     * 已确认执行单行按订单行汇总；excludeBillId 用于撤回/作废在状态落库前调用时排除本单。
     *
     * @param t            表名组合
     * @param tenantId     租户编号
     * @param orderId      订单头ID
     * @param excludeBillId 排除的执行单ID，可空
     * @return 订单行ID → 已执行数量
     */
    private Map<Long, BigDecimal> sumExecuted(OrderTables t, String tenantId, Long orderId, Long excludeBillId) {
        String sql = "select oi.id as item_id, sum(i.qty) as total"
            + " from " + t.execItem() + " i join " + t.execHead()
            + " h on h.id = i.order_id and h.tenant_id = i.tenant_id"
            + " join " + t.head() + " oh on oh.id = h.source_order_id and oh.tenant_id = h.tenant_id"
            + " join " + t.item() + " oi on oi.id = i.source_order_item_id and oi.order_id = oh.id"
            + " and oi.tenant_id = oh.tenant_id"
            + " where oh.id = ? and oh.tenant_id = ? and h.tenant_id = ? and i.tenant_id = ? and oi.tenant_id = ?"
            + " and h.status = 'CONFIRMED' and oh.del_flag = '0' and h.del_flag = '0'"
            + " and i.del_flag = '0' and oi.del_flag = '0' and i.source_order_item_id is not null"
            + (excludeBillId == null ? "" : " and h.id <> ?")
            + " group by oi.id";
        return excludeBillId == null
            ? queryGrouped(sql, orderId, tenantId, tenantId, tenantId, tenantId)
            : queryGrouped(sql, orderId, tenantId, tenantId, tenantId, tenantId, excludeBillId);
    }

    /**
     * 已确认退货量按订单行汇总（退货行 → 执行单行 → 订单行两跳）。
     *
     * @param t        表名组合
     * @param tenantId 租户编号
     * @param orderId  订单头ID
     * @return 订单行ID → 已退货数量
     */
    private Map<Long, BigDecimal> sumReturned(OrderTables t, String tenantId, Long orderId) {
        String sql = "select oi.id as item_id, sum(r.qty) as total"
            + " from " + t.returnItem() + " r"
            + " join " + t.returnHead() + " rh on rh.id = r.order_id and rh.tenant_id = r.tenant_id"
            + " join " + t.execItem() + " i on i.id = r." + t.returnExecItemFk() + " and i.tenant_id = r.tenant_id"
            + " join " + t.execHead() + " h on h.id = i.order_id and h.tenant_id = i.tenant_id"
            + " join " + t.head() + " oh on oh.id = h.source_order_id and oh.tenant_id = h.tenant_id"
            + " join " + t.item() + " oi on oi.id = i.source_order_item_id and oi.order_id = oh.id"
            + " and oi.tenant_id = oh.tenant_id"
            + " where oh.id = ? and oh.tenant_id = ? and h.tenant_id = ? and i.tenant_id = ?"
            + " and rh.tenant_id = ? and r.tenant_id = ? and oi.tenant_id = ?"
            + " and h.status = 'CONFIRMED' and rh.status = 'CONFIRMED'"
            + " and oh.del_flag = '0' and h.del_flag = '0' and i.del_flag = '0'"
            + " and rh.del_flag = '0' and r.del_flag = '0' and oi.del_flag = '0'"
            + " and i.source_order_item_id is not null"
            + " group by oi.id";
        return queryGrouped(sql, orderId, tenantId, tenantId, tenantId, tenantId, tenantId, tenantId);
    }

    /**
     * 执行聚合查询并转成「订单行ID → 数量」映射。
     *
     * @param sql  聚合 SQL，结果列固定为 item_id/total
     * @param args SQL 参数
     * @return 订单行ID → 数量
     */
    private Map<Long, BigDecimal> queryGrouped(String sql, Object... args) {
        Map<Long, BigDecimal> result = new HashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql, args)) {
            result.put(((Number) row.get("item_id")).longValue(), nvl((BigDecimal) row.get("total")));
        }
        return result;
    }

    /**
     * 锁订单头并取状态与超收容差。
     *
     * @param t        表名组合
     * @param orderId  订单头ID
     * @param tenantId 租户编号
     * @return 订单头锁定行；不存在返回 null
     */
    private OrderHead lockHead(OrderTables t, Long orderId, String tenantId) {
        List<OrderHead> rows = jdbcTemplate.query(
            "select status, over_tol_pct, due_date, exec_status from " + t.head()
                + " where id = ? and tenant_id = ? and del_flag = '0' for update",
            (rs, rowNum) -> new OrderHead(rs.getString("status"), rs.getBigDecimal("over_tol_pct"),
                rs.getDate("due_date"), rs.getString("exec_status")),
            orderId, tenantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 查订单全部行。
     *
     * @param t         表名组合
     * @param orderId   订单头ID
     * @param tenantId  租户编号
     * @param forUpdate true=按 id 升序 FOR UPDATE
     * @return 订单行列表
     */
    private List<OrderItemRow> selectItems(OrderTables t, Long orderId, String tenantId, boolean forUpdate) {
        String sql = "select id, qty, executed_qty, closed_qty, closed_flag, due_date from " + t.item()
            + " where order_id = ? and tenant_id = ? and del_flag = '0' order by id"
            + (forUpdate ? " for update" : "");
        return jdbcTemplate.query(sql, ITEM_ROW_MAPPER, orderId, tenantId);
    }

    /**
     * 按订单类型取表名组合。
     *
     * @param orderType 订单类型 PURCHASE/SALE
     * @return 表名组合
     */
    private OrderTables tables(String orderType) {
        if (TYPE_PURCHASE.equals(orderType)) {
            return PURCHASE_TABLES;
        }
        if (TYPE_SALE.equals(orderType)) {
            return SALE_TABLES;
        }
        throw new IllegalArgumentException("未知的订单类型：" + orderType);
    }

    /**
     * 执行进度状态：有未交量时按是否已执行区分 PARTIAL/NOT_STARTED，没有未交量时按是否有放弃量区分 CLOSED/FULL。
     *
     * @param hasRemain   是否存在 remain > 0 的行
     * @param sumExecuted 已执行数量合计
     * @param sumClosed   已关闭数量合计
     * @return NOT_STARTED/PARTIAL/FULL/CLOSED
     */
    private String execStatusOf(boolean hasRemain, BigDecimal sumExecuted, BigDecimal sumClosed) {
        if (hasRemain) {
            return sumExecuted.signum() > 0 ? "PARTIAL" : "NOT_STARTED";
        }
        return sumClosed.signum() > 0 ? "CLOSED" : "FULL";
    }

    /**
     * 原生 SQL 不经过多租户插件，租户显式带入；缺少租户上下文直接拒绝，绝不回落默认租户。
     *
     * @return 当前租户编号
     */
    private String currentTenantId() {
        String tenantId = TenantHelper.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new ServiceException("缺少租户上下文，拒绝执行订单进度重算", 40901);
        }
        return tenantId;
    }

    /**
     * 取较早的非空日期。
     *
     * @param current   当前最早日期，可空
     * @param candidate 候选日期，可空
     * @return 较早者；两者都空返回 null
     */
    private Date earlier(Date current, Date candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null || candidate.before(current) ? candidate : current;
    }

    private boolean sameDate(Date left, Date right) {
        return left == null ? right == null : left.equals(right);
    }

    /**
     * 数量字符串化（去掉无意义的尾零，便于拼错误提示）。
     *
     * @param value 数量
     * @return 字符串
     */
    private String fmt(BigDecimal value) {
        return nvl(value).stripTrailingZeros().toPlainString();
    }

    /**
     * 空值兜底为 0，避免旧数据/空列导致 NPE。
     *
     * @param value 原值，可空
     * @return 非空数量
     */
    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * 订单表名组合：头/行/执行单头/执行单行/退货头/退货行/退货行上的执行单行外键。
     */
    private record OrderTables(String head, String item, String execHead, String execItem,
                               String returnHead, String returnItem, String returnExecItemFk) {
    }

    /**
     * 订单头锁定行。
     */
    private record OrderHead(String status, BigDecimal overTolPct, Date dueDate, String execStatus) {
    }

    /**
     * 订单行锁定行。
     */
    private record OrderItemRow(Long id, BigDecimal qty, BigDecimal executedQty, BigDecimal closedQty,
                                int closedFlag, Date dueDate) {
    }
}
