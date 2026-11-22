package org.erp.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.erp.common.enums.StatusText;
import org.erp.common.sequence.SequenceService;
import org.erp.master.domain.ErpCustomer;
import org.erp.master.domain.ErpProduct;
import org.erp.master.mapper.ErpCustomerMapper;
import org.erp.master.mapper.ErpProductMapper;
import org.erp.trade.domain.ErpOrderChangeLog;
import org.erp.trade.domain.ErpSale;
import org.erp.trade.domain.ErpSaleItem;
import org.erp.trade.domain.ErpSaleOrder;
import org.erp.trade.domain.ErpSaleOrderItem;
import org.erp.trade.domain.bo.ErpSaleOrderBo;
import org.erp.trade.domain.vo.ErpSaleOrderVo;
import org.erp.trade.domain.vo.OrderExecutionAmountVo;
import org.erp.trade.mapper.ErpOrderChangeLogMapper;
import org.erp.trade.mapper.ErpSaleItemMapper;
import org.erp.trade.mapper.ErpSaleMapper;
import org.erp.trade.mapper.ErpSaleOrderItemMapper;
import org.erp.trade.mapper.ErpSaleOrderMapper;
import org.erp.trade.service.IErpOrderProgressService;
import org.erp.trade.service.IErpSaleOrderService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 销售订单服务实现（DOC/31 §5.5.3、§6、§9.3）
 *
 * <p>与采购订单逐条同构，差异只有客户字段、送货地址、超发容差（默认 0，M1 前端只读）与销售价口径。
 * 三条硬约束：
 * <ol>
 *   <li>写路径的第一条数据库读必须是订单头锁定读（{@link ErpSaleOrderMapper#lockById}），
 *       随后统一走「订单头 → 订单行（id ASC）→ recalc」，与执行单确认/撤回/退货路径同序，防 ABBA 死锁；</li>
 *   <li>行 amount 与头 goods_amount 同事务重算，任何数量/单价变更后立刻覆盖写；</li>
 *   <li>due_date 与 exec_status 是派生列，只由 {@link IErpOrderProgressService#recalc} 写，不手工推导。</li>
 * </ol>
 */
@RequiredArgsConstructor
@Service
public class ErpSaleOrderServiceImpl implements IErpSaleOrderService {

    /**
     * 订单类型：销售（共享进度服务按此选表）
     */
    private static final String ORDER_TYPE = IErpOrderProgressService.TYPE_SALE;

    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_CONFIRMED = "CONFIRMED";
    private static final String STATUS_VOID = "VOID";
    private static final String EXEC_NOT_STARTED = "NOT_STARTED";
    private static final String EXEC_PARTIAL = "PARTIAL";

    /**
     * 表头默认发票类型与税率，与销售出库单一致
     */
    private static final String DEFAULT_TAX_TYPE = "VAT_INVOICE";
    private static final BigDecimal DEFAULT_TAX_RATE = BigDecimal.valueOf(13);

    /**
     * 销售出库单运杂费分摊方式固定按数量（转单时运费为 0）
     */
    private static final String FREIGHT_ALLOC_BY_QTY = "BY_QTY";

    /**
     * 销售订单号前缀 XSD（采购为 CGD）
     */
    private static final String ORDER_NO_PREFIX = "XSD";

    /**
     * 销售出库单号前缀 XS
     */
    private static final String DELIVERY_NO_PREFIX = "XS";

    /**
     * 数量 3 位 / 单价 4 位 / 金额 2 位（DOC/31 §5.8）
     */
    private static final int QTY_SCALE = 3;
    private static final int PRICE_SCALE = 4;
    private static final int AMOUNT_SCALE = 2;

    private static final int CLOSE_REASON_MAX = 200;
    private static final int REMARK_MAX = 500;
    private static final int ADDRESS_MAX = 255;
    private static final int CONTACT_NAME_MAX = 64;
    private static final int CONTACT_PHONE_MAX = 32;
    private static final int CUSTOMER_ORDER_NO_MAX = 64;

    private final ErpSaleOrderMapper saleOrderMapper;
    private final ErpSaleOrderItemMapper saleOrderItemMapper;
    private final ErpOrderChangeLogMapper changeLogMapper;
    private final ErpSaleMapper saleMapper;
    private final ErpSaleItemMapper saleItemMapper;
    private final ErpCustomerMapper customerMapper;
    private final ErpProductMapper productMapper;
    private final SequenceService sequenceService;
    private final IErpOrderProgressService orderProgressService;

    @Override
    public TableDataInfo<ErpSaleOrderVo> selectPage(String keyword, Long customerId, String status, String execStatus,
                                                    Boolean unfinished, Boolean overdue,
                                                    Date dueDateStart, Date dueDateEnd, PageQuery pageQuery) {
        LambdaQueryWrapper<ErpSaleOrder> lqw = new LambdaQueryWrapper<>();
        // 关键字同时匹配订单号与客户单号（§10.2）
        if (StringUtils.isNotBlank(keyword)) {
            lqw.and(w -> w.like(ErpSaleOrder::getNo, keyword).or().like(ErpSaleOrder::getCustomerOrderNo, keyword));
        }
        lqw.eq(customerId != null, ErpSaleOrder::getCustomerId, customerId);
        lqw.eq(StringUtils.isNotBlank(status), ErpSaleOrder::getStatus, status);
        lqw.eq(StringUtils.isNotBlank(execStatus), ErpSaleOrder::getExecStatus, execStatus);
        // 「只看未完成」= CONFIRMED 且仍有未交量（§6.1.1）
        if (Boolean.TRUE.equals(unfinished)) {
            lqw.eq(ErpSaleOrder::getStatus, STATUS_CONFIRMED);
            lqw.in(ErpSaleOrder::getExecStatus, EXEC_NOT_STARTED, EXEC_PARTIAL);
        }
        // 逾期＝今天 > due_date + allow_delay_days，且订单仍在跟（§5.5.3）
        if (Boolean.TRUE.equals(overdue)) {
            lqw.eq(ErpSaleOrder::getStatus, STATUS_CONFIRMED);
            lqw.in(ErpSaleOrder::getExecStatus, EXEC_NOT_STARTED, EXEC_PARTIAL);
            lqw.apply("date_add(due_date, interval allow_delay_days day) < curdate()");
        }
        lqw.ge(dueDateStart != null, ErpSaleOrder::getDueDate, dueDateStart);
        lqw.le(dueDateEnd != null, ErpSaleOrder::getDueDate, dueDateEnd);
        // 默认排序：快到期的在上（due_date 由 recalc 保证有值），同日期按 id 倒序
        lqw.orderByAsc(ErpSaleOrder::getDueDate).orderByDesc(ErpSaleOrder::getId);
        Page<ErpSaleOrder> page = saleOrderMapper.selectPage(pageQuery.build(), lqw);
        Page<ErpSaleOrderVo> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        Map<Long, String> customerNames = loadCustomerNames(page.getRecords());
        Map<Long, QtySum> qtySums = loadQtySums(page.getRecords());
        Map<Long, BigDecimal> executionAmounts = loadExecutionAmounts(page.getRecords());
        voPage.setRecords(page.getRecords().stream()
            .map(h -> toVo(h, customerNames, qtySums.get(h.getId()), executionAmounts.get(h.getId()))).toList());
        return TableDataInfo.build(voPage);
    }

    @Override
    public ErpSaleOrderVo selectById(Long id) {
        ErpSaleOrder head = saleOrderMapper.selectById(id);
        if (head == null) {
            throw new ServiceException("销售订单不存在", 40905);
        }
        ErpSaleOrderVo vo = toVo(head, loadCustomerNames(List.of(head)), null,
            loadExecutionAmounts(List.of(head)).get(head.getId()));
        List<ErpSaleOrderVo.ItemVo> items = loadItems(id);
        vo.setItems(items);
        // 详情页的执行进度直接由明细汇总，避免再查一次库
        fillQtySummary(vo, items);
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpSaleOrderVo insert(ErpSaleOrderBo bo) {
        requireCustomer(bo.getCustomerId());
        requireBizDate(bo.getBizDate());
        ErpSaleOrder head = new ErpSaleOrder();
        head.setNo(sequenceService.nextNo(ORDER_NO_PREFIX));
        head.setStatus(STATUS_DRAFT);
        head.setExecStatus(EXEC_NOT_STARTED);
        applyHeader(head, bo);
        head.setGoodsAmount(BigDecimal.ZERO);
        saleOrderMapper.insert(head);
        saveLines(head, bo.getItems());
        // 草稿也要重算：否则列表的「交期」「执行进度」两列为空（§6.1.3 调用时机）
        orderProgressService.recalc(ORDER_TYPE, head.getId(), null);
        return selectById(head.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpSaleOrderVo update(Long id, ErpSaleOrderBo bo) {
        // 第一条读必须是锁定读：抢到订单头锁时并发的执行单确认事务已提交，随后的一致性读才看得到它
        ErpSaleOrder head = lockHead(id);
        if (STATUS_VOID.equals(head.getStatus())) {
            throw new ServiceException("已作废订单不可修改", 40902);
        }
        if (STATUS_DRAFT.equals(head.getStatus())) {
            requireCustomer(bo.getCustomerId());
            requireBizDate(bo.getBizDate());
            applyHeader(head, bo);
            saleOrderMapper.updateById(head);
            // 草稿整单重保存：删旧行再插新行，不写变更留痕（草稿无意义，§6.3）
            saleOrderItemMapper.delete(new LambdaQueryWrapper<ErpSaleOrderItem>().eq(ErpSaleOrderItem::getOrderId, id));
            saveLines(head, bo.getItems());
        } else {
            updateConfirmed(head, bo);
        }
        orderProgressService.recalc(ORDER_TYPE, id, null);
        return selectById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDraft(Long id) {
        ErpSaleOrder head = saleOrderMapper.selectById(id);
        if (head == null) {
            // 幂等：删两次第二次静默成功（§9.2 幂等要求）
            return;
        }
        if (!STATUS_DRAFT.equals(head.getStatus())) {
            throw new ServiceException("仅草稿订单可删除", 40902);
        }
        saleOrderItemMapper.delete(new LambdaQueryWrapper<ErpSaleOrderItem>().eq(ErpSaleOrderItem::getOrderId, id));
        saleOrderMapper.deleteById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpSaleOrderVo confirm(Long id) {
        ErpSaleOrder head = lockHead(id);
        if (!STATUS_DRAFT.equals(head.getStatus())) {
            throw new ServiceException("仅草稿订单可确认", 40902);
        }
        BigDecimal goodsAmount = recalcGoodsAmount(id);
        // 状态 CAS：锁失效或跨节点时也只有一个请求能把 DRAFT 改成 CONFIRMED（§6.8.2 防线 3）
        ErpSaleOrder patch = new ErpSaleOrder();
        patch.setStatus(STATUS_CONFIRMED);
        patch.setGoodsAmount(goodsAmount);
        patch.setConfirmedBy(currentUserId());
        patch.setConfirmedAt(new Date());
        int rows = saleOrderMapper.update(patch, new LambdaUpdateWrapper<ErpSaleOrder>()
            .eq(ErpSaleOrder::getId, id).eq(ErpSaleOrder::getStatus, STATUS_DRAFT));
        if (rows == 0) {
            throw new ServiceException("订单状态已变更，请刷新后重试", 40902);
        }
        writeLog(head, new Change(null, "status", STATUS_DRAFT, STATUS_CONFIRMED, false, "状态"), "");
        orderProgressService.recalc(ORDER_TYPE, id, null);
        return selectById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void withdraw(Long id) {
        ErpSaleOrder head = lockHead(id);
        if (!STATUS_CONFIRMED.equals(head.getStatus())) {
            throw new ServiceException("仅已确认订单可撤回", 40902);
        }
        // 入口先锁订单行（id ASC），与确认路径同序，避免「行 → 头」的反向加锁
        List<ErpSaleOrderItem> items = saleOrderMapper.lockItemsByOrderId(id);
        checkWithdrawable(id, items);
        ErpSaleOrder patch = new ErpSaleOrder();
        patch.setStatus(STATUS_DRAFT);
        patch.setWithdrawnBy(currentUserId());
        patch.setWithdrawnAt(new Date());
        int rows = saleOrderMapper.update(patch, new LambdaUpdateWrapper<ErpSaleOrder>()
            .eq(ErpSaleOrder::getId, id).eq(ErpSaleOrder::getStatus, STATUS_CONFIRMED));
        if (rows == 0) {
            throw new ServiceException("订单状态已变更，请刷新后重试", 40902);
        }
        writeLog(head, new Change(null, "status", STATUS_CONFIRMED, STATUS_DRAFT, false, "状态"), "");
        orderProgressService.recalc(ORDER_TYPE, id, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void voidBill(Long id) {
        ErpSaleOrder head = lockHead(id);
        if (STATUS_VOID.equals(head.getStatus())) {
            throw new ServiceException("订单已作废");
        }
        if (STATUS_DRAFT.equals(head.getStatus())) {
            throw new ServiceException("草稿订单请使用删除", 40902);
        }
        List<ErpSaleOrderItem> items = saleOrderMapper.lockItemsByOrderId(id);
        checkWithdrawable(id, items);
        ErpSaleOrder patch = new ErpSaleOrder();
        patch.setStatus(STATUS_VOID);
        patch.setVoidedBy(currentUserId());
        patch.setVoidedAt(new Date());
        int rows = saleOrderMapper.update(patch, new LambdaUpdateWrapper<ErpSaleOrder>()
            .eq(ErpSaleOrder::getId, id).eq(ErpSaleOrder::getStatus, STATUS_CONFIRMED));
        if (rows == 0) {
            throw new ServiceException("订单状态已变更，请刷新后重试", 40902);
        }
        writeLog(head, new Change(null, "status", STATUS_CONFIRMED, STATUS_VOID, false, "状态"), "");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void closeOrder(Long id, String reason) {
        ErpSaleOrder head = lockHead(id);
        checkCloseable(head, reason);
        List<ErpSaleOrderItem> items = saleOrderMapper.lockItemsByOrderId(id);
        List<ErpSaleOrderItem> targets = items.stream()
            .filter(item -> !isClosed(item) && remain(item).signum() > 0).toList();
        if (targets.isEmpty()) {
            throw new ServiceException("没有可关闭的剩余量", 40902);
        }
        closeLines(head, targets, reason);
        orderProgressService.recalc(ORDER_TYPE, id, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void closeItem(Long id, Long itemId, String reason) {
        ErpSaleOrder head = lockHead(id);
        checkCloseable(head, reason);
        ErpSaleOrderItem item = lockItem(id, itemId);
        if (isClosed(item)) {
            // 已关闭的行再点一次=幂等成功，不重复写留痕
            return;
        }
        if (remain(item).signum() <= 0) {
            throw new ServiceException("该行已无剩余量可关闭", 40902);
        }
        closeLines(head, List.of(item), reason);
        orderProgressService.recalc(ORDER_TYPE, id, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reopenItem(Long id, Long itemId, String reason) {
        ErpSaleOrder head = lockHead(id);
        if (STATUS_VOID.equals(head.getStatus())) {
            throw new ServiceException("已作废订单不可重开", 40902);
        }
        if (!STATUS_CONFIRMED.equals(head.getStatus())) {
            throw new ServiceException("仅已确认订单可重开", 40902);
        }
        checkReasonLength(reason);
        ErpSaleOrderItem item = lockItem(id, itemId);
        if (!isClosed(item)) {
            throw new ServiceException("该行未处于关闭态", 40902);
        }
        BigDecimal oldClosedQty = nvl(item.getClosedQty());
        String oldReason = StringUtils.blankToDefault(item.getCloseReason(), "");
        // updateById 会跳过 null 字段，closed_by/closed_at 必须显式 set null 才能真正清空
        saleOrderItemMapper.update(null, new LambdaUpdateWrapper<ErpSaleOrderItem>()
            .eq(ErpSaleOrderItem::getId, item.getId())
            .set(ErpSaleOrderItem::getClosedFlag, 0)
            .set(ErpSaleOrderItem::getClosedQty, BigDecimal.ZERO)
            .set(ErpSaleOrderItem::getCloseReason, "")
            .set(ErpSaleOrderItem::getClosedBy, null)
            .set(ErpSaleOrderItem::getClosedAt, null));
        String logReason = StringUtils.blankToDefault(reason, "");
        writeLog(head, new Change(item.getId(), "closed_qty", fmt(oldClosedQty), "0", true, "关闭量"), logReason);
        writeLog(head, new Change(item.getId(), "closed_flag", "1", "0", true, "关闭标记"), logReason);
        writeLog(head, new Change(item.getId(), "close_reason", oldReason, "", false, "关闭原因"), logReason);
        orderProgressService.recalc(ORDER_TYPE, id, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createDelivery(Long id, ErpSaleOrderBo.DeliveryBo bo) {
        ErpSaleOrder order = lockHead(id);
        if (!STATUS_CONFIRMED.equals(order.getStatus())) {
            throw new ServiceException("订单未确认或已作废，不能出库", 40902);
        }
        // 转单只校验未交量，超发容差留到出库单确认时再校验（§6.7）
        List<ErpSaleOrderItem> orderItems = saleOrderMapper.lockItemsByOrderId(id);
        if (orderItems.isEmpty()) {
            throw new ServiceException("订单没有可出库的明细", 40902);
        }
        List<DeliveryLine> lines = resolveDeliveryLines(orderItems, bo);
        ErpSale sale = new ErpSale();
        sale.setNo(sequenceService.nextNo(DELIVERY_NO_PREFIX));
        sale.setSourceOrderId(order.getId());
        sale.setSourceOrderNo(order.getNo());
        // R1：联系人/电话/送货地址三项随单快照，详情与打印不回查订单
        sale.setContactName(order.getContactName());
        sale.setContactPhone(order.getContactPhone());
        sale.setDeliveryAddress(order.getDeliveryAddress());
        sale.setCustomerId(order.getCustomerId());
        sale.setBizDate(bo != null && bo.getBizDate() != null ? bo.getBizDate() : new Date());
        sale.setStatus(STATUS_DRAFT);
        sale.setTaxType(order.getTaxType());
        sale.setTaxRate(order.getTaxRate());
        BigDecimal goodsAmount = lines.stream()
            .map(line -> amount(line.qty(), line.item().getPrice()))
            .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        sale.setGoodsAmount(goodsAmount);
        sale.setFreightAmount(BigDecimal.ZERO);
        sale.setFreightAllocMethod(FREIGHT_ALLOC_BY_QTY);
        sale.setTotalAmount(goodsAmount);
        sale.setCostAmount(BigDecimal.ZERO);
        sale.setGrossProfit(BigDecimal.ZERO);
        sale.setGrossMargin(BigDecimal.ZERO);
        sale.setRemark(bo == null ? "" : checkLength(bo.getRemark(), REMARK_MAX, "备注"));
        saleMapper.insert(sale);
        int seq = 0;
        for (DeliveryLine line : lines) {
            ErpSaleOrderItem source = line.item();
            ErpSaleItem row = new ErpSaleItem();
            row.setOrderId(sale.getId());
            row.setSourceOrderItemId(source.getId());
            row.setProductId(source.getProductId());
            row.setQty(line.qty());
            row.setPrice(source.getPrice());
            row.setTaxType(sale.getTaxType());
            row.setTaxRate(sale.getTaxRate());
            row.setAmount(amount(line.qty(), source.getPrice()));
            row.setUnitCost(BigDecimal.ZERO);
            row.setCostAmount(BigDecimal.ZERO);
            row.setGrossProfit(BigDecimal.ZERO);
            row.setRemark("");
            // seq 按勾选顺序显式赋值，全 0 会让明细排序失去意义（§6.7）
            row.setSeq(seq++);
            saleItemMapper.insert(row);
        }
        // 不修改订单行 executed_qty：DRAFT 执行单不计入，避免「草稿占量」
        return sale.getId();
    }

    @Override
    public List<ErpSaleOrderVo.ExecRecordVo> selectExecutions(Long id) {
        ErpSaleOrder head = saleOrderMapper.selectById(id);
        if (head == null) {
            throw new ServiceException("销售订单不存在", 40905);
        }
        List<ErpSaleOrderVo.ExecRecordVo> records = new ArrayList<>();
        String tenantId = currentTenantId();
        records.addAll(saleOrderMapper.selectDeliveries(id, tenantId));
        records.addAll(saleOrderMapper.selectReturns(id, tenantId));
        for (ErpSaleOrderVo.ExecRecordVo record : records) {
            record.setTypeText("RETURN".equals(record.getType()) ? "退货" : "出库");
            record.setStatusText(StatusText.bill(record.getStatus()));
        }
        // 按业务日期倒序，同日按单据ID倒序；空日期排在最后
        records.sort(Comparator.comparing(ErpSaleOrderVo.ExecRecordVo::getBizDate,
            Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(ErpSaleOrderVo.ExecRecordVo::getId, Comparator.reverseOrder()));
        return records;
    }

    @Override
    public List<ErpSaleOrderVo.ItemVo> selectOpenItems(Long orderId) {
        if (orderId == null) {
            throw new ServiceException("订单ID不能为空", 40902);
        }
        ErpSaleOrder head = saleOrderMapper.selectById(orderId);
        if (head == null) {
            throw new ServiceException("销售订单不存在", 40905);
        }
        if (!STATUS_CONFIRMED.equals(head.getStatus())) {
            throw new ServiceException("订单未确认或已作废，不能选择订单行", 40902);
        }
        // 只返回这一张订单的未交行，不接受按客户跨单拉行：混选会让另一张订单的行永远不计入重算（§6.7）
        return loadItems(orderId).stream()
            .filter(item -> nvl(item.getRemain()).signum() > 0).toList();
    }

    /**
     * 已确认订单改单：只允许改数量/单价/交期/承诺交期/备注与约定表头字段，全部写变更留痕。
     *
     * @param head 锁定到的订单头
     * @param bo   改单入参
     */
    private void updateConfirmed(ErpSaleOrder head, ErpSaleOrderBo bo) {
        checkLockedHeaderFields(head, bo);
        List<ErpSaleOrderItem> existing = loadOrderItems(head.getId());
        List<ErpSaleOrderBo.ItemBo> reqItems = bo.getItems();
        if (reqItems == null || reqItems.size() != existing.size()) {
            throw new ServiceException("已确认订单不允许增删明细", 40902);
        }
        Map<Long, ErpSaleOrderBo.ItemBo> reqById = indexRequestItems(reqItems, existing);
        if (reqById.isEmpty()) {
            throw new ServiceException("订单明细不能为空", 40902);
        }
        List<ErpSaleOrderItem> locked = saleOrderMapper.lockItemsByIds(reqById.keySet().stream().sorted().toList());
        if (locked.size() != existing.size()) {
            throw new ServiceException("订单行已被删除，请刷新后重试", 40902);
        }
        BigDecimal executedTotal = locked.stream().map(row -> nvl(row.getExecutedQty()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Change> changes = new ArrayList<>();
        BigDecimal goodsAmount = BigDecimal.ZERO;
        int lineNo = 0;
        for (ErpSaleOrderItem row : locked) {
            lineNo++;
            boolean changed = applyLineChange(row, reqById.get(row.getId()), executedTotal, lineNo, changes);
            if (changed) {
                saleOrderItemMapper.updateById(row);
            }
            goodsAmount = goodsAmount.add(nvl(row.getAmount()));
        }
        checkDuplicateProductDue(locked);
        collectHeaderChanges(head, bo, changes);
        requireReason(changes, bo.getReason());
        head.setGoodsAmount(goodsAmount.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP));
        saleOrderMapper.updateById(head);
        for (Change change : changes) {
            writeLog(head, change, bo.getReason());
        }
    }

    /**
     * 校验已确认订单不允许改动的表头字段（客户/发票类型/税率）。
     *
     * @param head 锁定到的订单头
     * @param bo   改单入参
     */
    private void checkLockedHeaderFields(ErpSaleOrder head, ErpSaleOrderBo bo) {
        if (bo.getCustomerId() != null && !bo.getCustomerId().equals(head.getCustomerId())) {
            throw new ServiceException("已确认订单不允许修改客户", 40902);
        }
        if (StringUtils.isNotBlank(bo.getTaxType()) && !bo.getTaxType().equals(head.getTaxType())) {
            throw new ServiceException("已确认订单不允许修改发票类型", 40902);
        }
        if (bo.getTaxRate() != null && bo.getTaxRate().compareTo(nvl(head.getTaxRate())) != 0) {
            throw new ServiceException("已确认订单不允许修改税率", 40902);
        }
    }

    /**
     * 单行改单：数量下限与关闭行的校验在订单行锁内完成，金额随数量/单价重算。
     *
     * @param row           锁定到的订单行
     * @param req           改单行入参
     * @param executedTotal 整单已执行量合计（决定单价是否可改）
     * @param lineNo        行号（错误提示用）
     * @param changes       变更留痕收集器
     * @return 该行是否有字段变化
     */
    private boolean applyLineChange(ErpSaleOrderItem row, ErpSaleOrderBo.ItemBo req, BigDecimal executedTotal,
                                    int lineNo, List<Change> changes) {
        // 商品可能已被逻辑删除：已确认订单的改量不该因此被卡住，商品为空时只做通用精度校验
        ErpProduct product = row.getProductId() == null ? null : productMapper.selectById(row.getProductId());
        BigDecimal oldQty = nvl(row.getQty());
        BigDecimal newQty = requireQty(req.getQty(), product, lineNo);
        BigDecimal oldPrice = nvl(row.getPrice());
        BigDecimal newPrice = requirePrice(req.getPrice(), lineNo);
        if (req.getDueDate() == null) {
            throw new ServiceException("第" + lineNo + "行交期不能为空", 40902);
        }
        boolean changed = false;
        if (newQty.compareTo(oldQty) != 0) {
            // 不变式 closed_flag=1 ⇒ remain=0：关闭的行禁止直接改量，必须先重开（§6.1.2）
            if (isClosed(row)) {
                throw new ServiceException("该行已关闭，请先重开该行再改数量", 40902);
            }
            BigDecimal floor = nvl(row.getExecutedQty()).add(nvl(row.getClosedQty()));
            if (newQty.compareTo(floor) < 0) {
                throw new ServiceException("已执行 " + fmt(row.getExecutedQty()) + "、已关闭 " + fmt(row.getClosedQty())
                    + "，订单量不可小于 " + fmt(floor), 40902);
            }
            changes.add(new Change(row.getId(), "qty", fmt(oldQty), fmt(newQty), true, "数量"));
            row.setQty(newQty);
            changed = true;
        }
        if (newPrice.compareTo(oldPrice) != 0) {
            // 已确认出库单的金额/成本/往来都已冻结，有执行量时单价锁定（§6.3）
            if (executedTotal.signum() > 0) {
                throw new ServiceException("已执行 " + fmt(executedTotal) + "，单价不可修改", 40902);
            }
            changes.add(new Change(row.getId(), "price", fmt(oldPrice), fmt(newPrice), false, "单价"));
            row.setPrice(newPrice);
            changed = true;
        }
        if (req.getDueDate().getTime() != nvlTime(row.getDueDate())) {
            changes.add(new Change(row.getId(), "due_date", fmtDate(row.getDueDate()), fmtDate(req.getDueDate()), true, "交期"));
            row.setDueDate(req.getDueDate());
            changed = true;
        }
        if (req.getPromisedDate() != null && req.getPromisedDate().getTime() != nvlTime(row.getPromisedDate())) {
            changes.add(new Change(row.getId(), "promised_date", fmtDate(row.getPromisedDate()), fmtDate(req.getPromisedDate()), false, "承诺交期"));
            row.setPromisedDate(req.getPromisedDate());
            changed = true;
        }
        if (req.getRemark() != null) {
            row.setRemark(checkLength(req.getRemark(), REMARK_MAX, "行备注"));
            changed = true;
        }
        // 行金额与头金额同事务重算：即使本次没改数量/单价，也把历史脏数据纠回来
        BigDecimal amount = amount(row.getQty(), row.getPrice());
        if (nvl(row.getAmount()).compareTo(amount) != 0) {
            row.setAmount(amount);
            changed = true;
        }
        return changed;
    }

    /**
     * 收集表头变更并写回订单头；联系人/电话/地址只在入参显式给出时比较，避免整单保存把它们冲成空。
     *
     * @param head    锁定到的订单头
     * @param bo      改单入参
     * @param changes 变更留痕收集器
     */
    private void collectHeaderChanges(ErpSaleOrder head, ErpSaleOrderBo bo, List<Change> changes) {
        if (bo.getSalespersonId() != null && !bo.getSalespersonId().equals(head.getSalespersonId())) {
            changes.add(new Change(null, "buyer", str(head.getSalespersonId()), str(bo.getSalespersonId()), false, "跟单人"));
            head.setSalespersonId(bo.getSalespersonId());
            String salespersonName = resolveSalespersonName(bo, bo.getSalespersonId());
            if (StringUtils.isNotBlank(salespersonName)) {
                head.setSalespersonName(salespersonName);
            }
        }
        if (bo.getCustomerOrderNo() != null) {
            String newNo = checkLength(bo.getCustomerOrderNo(), CUSTOMER_ORDER_NO_MAX, "客户单号");
            if (!newNo.equals(StringUtils.blankToDefault(head.getCustomerOrderNo(), ""))) {
                changes.add(new Change(null, "customer_order_no", StringUtils.blankToDefault(head.getCustomerOrderNo(), ""),
                    newNo, false, "客户单号"));
                head.setCustomerOrderNo(newNo);
            }
        }
        if (bo.getAllowDelayDays() != null && !bo.getAllowDelayDays().equals(nvlInt(head.getAllowDelayDays()))) {
            if (bo.getAllowDelayDays() < 0) {
                throw new ServiceException("允许延迟天数不能小于0", 40902);
            }
            changes.add(new Change(null, "allow_delay_days", str(nvlInt(head.getAllowDelayDays())),
                str(bo.getAllowDelayDays()), true, "允许延迟天数"));
            head.setAllowDelayDays(bo.getAllowDelayDays());
        }
        if (bo.getOverTolPct() != null && bo.getOverTolPct().compareTo(nvl(head.getOverTolPct())) != 0) {
            if (bo.getOverTolPct().signum() < 0) {
                throw new ServiceException("超发容差不能小于0", 40902);
            }
            changes.add(new Change(null, "over_tol_pct", fmt(head.getOverTolPct()), fmt(bo.getOverTolPct()), true, "超发容差"));
            head.setOverTolPct(bo.getOverTolPct());
        }
        if (bo.getContactName() != null) {
            String newName = checkLength(bo.getContactName(), CONTACT_NAME_MAX, "联系人");
            if (!newName.equals(StringUtils.blankToDefault(head.getContactName(), ""))) {
                changes.add(new Change(null, "contact_name", StringUtils.blankToDefault(head.getContactName(), ""), newName, false, "联系人"));
                head.setContactName(newName);
            }
        }
        if (bo.getContactPhone() != null) {
            String newPhone = checkLength(bo.getContactPhone(), CONTACT_PHONE_MAX, "联系电话");
            if (!newPhone.equals(StringUtils.blankToDefault(head.getContactPhone(), ""))) {
                changes.add(new Change(null, "contact_phone", StringUtils.blankToDefault(head.getContactPhone(), ""), newPhone, false, "联系电话"));
                head.setContactPhone(newPhone);
            }
        }
        if (bo.getDeliveryAddress() != null) {
            // 地址最长 255，留痕列同为 255：必须完整保存，不能按 64 截断（验收 22）
            String newAddress = checkLength(bo.getDeliveryAddress(), ADDRESS_MAX, "送货地址");
            if (!newAddress.equals(StringUtils.blankToDefault(head.getDeliveryAddress(), ""))) {
                changes.add(new Change(null, "delivery_address", StringUtils.blankToDefault(head.getDeliveryAddress(), ""),
                    newAddress, true, "送货地址"));
                head.setDeliveryAddress(newAddress);
            }
        }
        if (bo.getRemark() != null) {
            head.setRemark(checkLength(bo.getRemark(), REMARK_MAX, "备注"));
        }
    }

    /**
     * 原因必填校验：qty/due_date/allow_delay_days/over_tol_pct/closed_qty/close_reason/delivery_address
     * 变更时必须有原因（§6.3、§5.5.4）。
     *
     * @param changes 变更留痕收集器
     * @param reason  本次改单原因
     */
    private void requireReason(List<Change> changes, String reason) {
        checkReasonLength(reason);
        if (StringUtils.isNotBlank(reason)) {
            return;
        }
        for (Change change : changes) {
            if (change.reasonRequired()) {
                throw new ServiceException("修改" + change.label() + "必须填写原因", 40902);
            }
        }
    }

    /**
     * 校验变更/关闭原因长度（列宽 200）。
     *
     * @param reason 原因
     */
    private void checkReasonLength(String reason) {
        if (reason != null && reason.length() > CLOSE_REASON_MAX) {
            throw new ServiceException("原因不能超过" + CLOSE_REASON_MAX + "个字", 40902);
        }
    }

    /**
     * 关闭前置校验：仅已确认订单、原因必填、无未确认出库单。
     *
     * @param head   锁定到的订单头
     * @param reason 关闭原因
     */
    private void checkCloseable(ErpSaleOrder head, String reason) {
        if (!STATUS_CONFIRMED.equals(head.getStatus())) {
            throw new ServiceException("仅已确认订单可关闭剩余", 40902);
        }
        if (StringUtils.isBlank(reason)) {
            throw new ServiceException("关闭原因不能为空", 40902);
        }
        checkReasonLength(reason);
        int drafts = saleOrderMapper.countDraftDeliveries(head.getId());
        if (drafts > 0) {
            throw new ServiceException("该订单存在 " + drafts + " 张未确认的销售出库单，请先删除后再关闭剩余", 40902);
        }
    }

    /**
     * 把订单行置为已关闭：关闭量在关闭这一刻冻结为 qty − executed，之后 recalc 只做下界保护（D7）。
     *
     * @param head   锁定到的订单头
     * @param lines  待关闭的订单行
     * @param reason 关闭原因
     */
    private void closeLines(ErpSaleOrder head, List<ErpSaleOrderItem> lines, String reason) {
        Date now = new Date();
        Long userId = currentUserId();
        for (ErpSaleOrderItem line : lines) {
            BigDecimal closedQty = remain(line).setScale(QTY_SCALE, RoundingMode.HALF_UP);
            String oldReason = StringUtils.blankToDefault(line.getCloseReason(), "");
            line.setClosedQty(closedQty);
            line.setClosedFlag(1);
            line.setCloseReason(reason);
            line.setClosedBy(userId);
            line.setClosedAt(now);
            saleOrderItemMapper.updateById(line);
            writeLog(head, new Change(line.getId(), "closed_qty", "0", fmt(closedQty), true, "关闭量"), reason);
            writeLog(head, new Change(line.getId(), "closed_flag", "0", "1", true, "关闭标记"), reason);
            writeLog(head, new Change(line.getId(), "close_reason", oldReason, reason, true, "关闭原因"), reason);
        }
    }

    /**
     * 撤回/作废共有前置：无执行量、无未处理的出库单（作废的不管，§6.4）。
     *
     * @param orderId 订单头ID
     * @param items   已加锁的订单行
     */
    private void checkWithdrawable(Long orderId, List<ErpSaleOrderItem> items) {
        BigDecimal executed = items.stream().map(row -> nvl(row.getExecutedQty()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (executed.signum() > 0) {
            throw new ServiceException("订单已执行 " + fmt(executed) + "，不可撤回或作废", 40902);
        }
        int active = saleOrderMapper.countActiveDeliveries(orderId);
        if (active > 0) {
            throw new ServiceException("该订单存在 " + active + " 张未处理的销售出库单，请先删除或作废", 40902);
        }
    }

    /**
     * 确认前重算全部行金额与头 goods_amount（同事务），顺带兜住历史脏数据。
     *
     * @param orderId 订单头ID
     * @return 头金额
     */
    private BigDecimal recalcGoodsAmount(Long orderId) {
        List<ErpSaleOrderItem> items = loadOrderItems(orderId);
        if (items.isEmpty()) {
            throw new ServiceException("订单明细不能为空", 40902);
        }
        BigDecimal goodsAmount = BigDecimal.ZERO;
        int lineNo = 0;
        for (ErpSaleOrderItem item : items) {
            lineNo++;
            if (nvl(item.getQty()).signum() <= 0) {
                throw new ServiceException("第" + lineNo + "行数量必须大于0", 40902);
            }
            if (item.getDueDate() == null) {
                throw new ServiceException("第" + lineNo + "行交期不能为空", 40902);
            }
            BigDecimal amount = amount(item.getQty(), nvl(item.getPrice()));
            if (nvl(item.getAmount()).compareTo(amount) != 0) {
                item.setAmount(amount);
                saleOrderItemMapper.updateById(item);
            }
            goodsAmount = goodsAmount.add(amount);
        }
        return goodsAmount.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 转单行解析：不传 items 默认全部未交行，传了则逐行校验归属、数量与未交量。
     *
     * @param orderItems 已加锁的订单行
     * @param bo         转单入参
     * @return 本次出库行
     */
    private List<DeliveryLine> resolveDeliveryLines(List<ErpSaleOrderItem> orderItems, ErpSaleOrderBo.DeliveryBo bo) {
        Map<Long, ErpSaleOrderItem> byId = new HashMap<>();
        for (ErpSaleOrderItem item : orderItems) {
            byId.put(item.getId(), item);
        }
        List<DeliveryLine> lines = new ArrayList<>();
        if (bo == null || bo.getItems() == null) {
            for (ErpSaleOrderItem item : orderItems) {
                BigDecimal remain = remain(item);
                if (remain.signum() > 0) {
                    lines.add(new DeliveryLine(item, remain));
                }
            }
            if (lines.isEmpty()) {
                throw new ServiceException("订单没有未交量，无法出库", 40902);
            }
            return lines;
        }
        if (bo.getItems().isEmpty()) {
            throw new ServiceException("请选择要出库的订单行", 40902);
        }
        Set<Long> seen = new HashSet<>();
        for (ErpSaleOrderBo.DeliveryItemBo line : bo.getItems()) {
            if (line.getOrderItemId() == null) {
                throw new ServiceException("订单行ID不能为空", 40902);
            }
            if (!seen.add(line.getOrderItemId())) {
                throw new ServiceException("同一订单行不能重复选择", 40902);
            }
            // 行归属：防止跨单混选（§6.7 第 2 条）
            ErpSaleOrderItem item = byId.get(line.getOrderItemId());
            if (item == null) {
                throw new ServiceException("订单行不属于该订单：" + line.getOrderItemId(), 40902);
            }
            BigDecimal qty = line.getQty();
            if (qty == null || qty.signum() <= 0) {
                throw new ServiceException("出库数量必须大于0", 40902);
            }
            // 只做 DECIMAL(18,3) 的通用上限，商品精度由出库单保存/确认时的库存入口再校验（双保险）
            if (Math.max(0, qty.stripTrailingZeros().scale()) > QTY_SCALE) {
                throw new ServiceException("出库数量最多支持 " + QTY_SCALE + " 位小数", 40902);
            }
            BigDecimal remain = remain(item);
            if (qty.compareTo(remain) > 0) {
                throw new ServiceException("订单行未交量 " + fmt(remain) + "，本次出库 " + fmt(qty) + " 超出", 40902);
            }
            lines.add(new DeliveryLine(item, qty));
        }
        return lines;
    }

    /**
     * 校验订单行商品：必须存在且为成品（订单行不支持现录现卖，§1.2）。
     *
     * @param productId 商品ID
     * @param lineNo    行号
     * @return 商品
     */
    private ErpProduct ensureProduct(Long productId, int lineNo) {
        if (productId == null) {
            throw new ServiceException("第" + lineNo + "行商品不能为空", 40902);
        }
        ErpProduct product = productMapper.selectById(productId);
        if (product == null) {
            throw new ServiceException("商品不存在：" + productId, 40905);
        }
        if (!"PRODUCT".equals(product.getType())) {
            throw new ServiceException("第" + lineNo + "行只能选择成品商品：" + product.getName(), 40902);
        }
        return product;
    }

    /**
     * 校验订单量：>0、最多 3 位小数、且不超过商品数量精度（商品已删除时只做通用精度校验）。
     *
     * @param qty     数量
     * @param product 商品（提供 qty_scale），可空
     * @param lineNo  行号
     * @return 原数量
     */
    private BigDecimal requireQty(BigDecimal qty, ErpProduct product, int lineNo) {
        if (qty == null || qty.signum() <= 0) {
            throw new ServiceException("第" + lineNo + "行数量必须大于0", 40902);
        }
        int scale = Math.max(0, qty.stripTrailingZeros().scale());
        if (scale > QTY_SCALE) {
            throw new ServiceException("第" + lineNo + "行数量最多支持 " + QTY_SCALE + " 位小数", 40902);
        }
        if (product == null) {
            return qty;
        }
        int allowed = product.getQtyScale() == null ? 0 : product.getQtyScale();
        if (scale > allowed) {
            throw new ServiceException("商品「" + product.getName() + "」数量最多支持 " + allowed + " 位小数", 40902);
        }
        return qty;
    }

    /**
     * 校验销售单价：不能为空/负数，最多 4 位小数（§5.8）。
     *
     * @param price  单价
     * @param lineNo 行号
     * @return 原单价
     */
    private BigDecimal requirePrice(BigDecimal price, int lineNo) {
        if (price == null || price.signum() < 0) {
            throw new ServiceException("第" + lineNo + "行销售单价不能为空", 40902);
        }
        if (Math.max(0, price.stripTrailingZeros().scale()) > PRICE_SCALE) {
            throw new ServiceException("第" + lineNo + "行单价最多支持 " + PRICE_SCALE + " 位小数", 40902);
        }
        return price;
    }

    /**
     * 按入参构建订单行（不落库）：数量/单价/交期/商品与「同商品同交期不重复」全部在此校验。
     *
     * @param head  订单头（提供 id、税率快照）
     * @param items 行入参
     * @return 待插入的订单行
     */
    private List<ErpSaleOrderItem> buildLines(ErpSaleOrder head, List<ErpSaleOrderBo.ItemBo> items) {
        if (items == null || items.isEmpty()) {
            throw new ServiceException("订单明细不能为空", 40902);
        }
        List<ErpSaleOrderItem> rows = new ArrayList<>(items.size());
        Set<String> productDueKeys = new HashSet<>();
        int seq = 0;
        for (ErpSaleOrderBo.ItemBo item : items) {
            int lineNo = rows.size() + 1;
            ErpProduct product = ensureProduct(item.getProductId(), lineNo);
            BigDecimal qty = requireQty(item.getQty(), product, lineNo);
            BigDecimal price = requirePrice(item.getPrice(), lineNo);
            if (item.getDueDate() == null) {
                throw new ServiceException("第" + lineNo + "行交期不能为空", 40902);
            }
            if (!productDueKeys.add(product.getId() + "#" + fmtDate(item.getDueDate()))) {
                throw new ServiceException("同一商品同一交期已存在，请合并数量或改交期", 40902);
            }
            ErpSaleOrderItem row = new ErpSaleOrderItem();
            row.setOrderId(head.getId());
            row.setSeq(seq++);
            row.setProductId(product.getId());
            row.setQty(qty);
            row.setPrice(price);
            // 发票类型/税率由表头继承写入，明细列仅作历史快照
            row.setTaxType(head.getTaxType());
            row.setTaxRate(head.getTaxRate());
            row.setAmount(amount(qty, price));
            row.setDueDate(item.getDueDate());
            row.setPromisedDate(item.getPromisedDate());
            row.setExecutedQty(BigDecimal.ZERO);
            row.setClosedQty(BigDecimal.ZERO);
            row.setClosedFlag(0);
            row.setCloseReason("");
            row.setRemark(checkLength(item.getRemark(), REMARK_MAX, "行备注"));
            rows.add(row);
        }
        return rows;
    }

    /**
     * 插入订单行并回写头金额（同事务）。
     *
     * @param head  订单头
     * @param items 行入参
     */
    private void saveLines(ErpSaleOrder head, List<ErpSaleOrderBo.ItemBo> items) {
        List<ErpSaleOrderItem> rows = buildLines(head, items);
        BigDecimal goodsAmount = BigDecimal.ZERO;
        for (ErpSaleOrderItem row : rows) {
            saleOrderItemMapper.insert(row);
            goodsAmount = goodsAmount.add(row.getAmount());
        }
        head.setGoodsAmount(goodsAmount.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP));
        saleOrderMapper.updateById(head);
    }

    /**
     * 写回表头可编辑字段（新建与草稿保存共用），默认值与长度在此收口。
     *
     * @param head 订单头
     * @param bo   订单入参
     */
    private void applyHeader(ErpSaleOrder head, ErpSaleOrderBo bo) {
        head.setCustomerId(bo.getCustomerId());
        head.setBizDate(bo.getBizDate());
        head.setCustomerOrderNo(checkLength(bo.getCustomerOrderNo(), CUSTOMER_ORDER_NO_MAX, "客户单号"));
        // 跟单人：不传时保留原值，新建时兜底当前登录人；姓名只在能解析出非空值时覆盖，避免把已有快照冲空
        Long salespersonId = bo.getSalespersonId() == null ? head.getSalespersonId() : bo.getSalespersonId();
        if (salespersonId == null) {
            salespersonId = currentUserId();
        }
        head.setSalespersonId(salespersonId);
        String salespersonName = resolveSalespersonName(bo, salespersonId);
        if (StringUtils.isNotBlank(salespersonName)) {
            head.setSalespersonName(salespersonName);
        }
        head.setContactName(checkLength(bo.getContactName(), CONTACT_NAME_MAX, "联系人"));
        head.setContactPhone(checkLength(bo.getContactPhone(), CONTACT_PHONE_MAX, "联系电话"));
        head.setDeliveryAddress(checkLength(bo.getDeliveryAddress(), ADDRESS_MAX, "送货地址"));
        int allowDelayDays = bo.getAllowDelayDays() == null ? 0 : bo.getAllowDelayDays();
        if (allowDelayDays < 0) {
            throw new ServiceException("允许延迟天数不能小于0", 40902);
        }
        head.setAllowDelayDays(allowDelayDays);
        BigDecimal overTolPct = bo.getOverTolPct() == null ? BigDecimal.ZERO : bo.getOverTolPct();
        if (overTolPct.signum() < 0) {
            throw new ServiceException("超发容差不能小于0", 40902);
        }
        head.setOverTolPct(overTolPct);
        head.setTaxType(StringUtils.isBlank(bo.getTaxType()) ? DEFAULT_TAX_TYPE : bo.getTaxType());
        head.setTaxRate(bo.getTaxRate() == null ? DEFAULT_TAX_RATE : bo.getTaxRate());
        head.setRemark(checkLength(bo.getRemark(), REMARK_MAX, "备注"));
    }

    /**
     * 跟单人姓名快照：优先取入参，未给且跟单人即当前登录人时取登录名。
     *
     * @param bo            订单入参
     * @param salespersonId 已确定的跟单人ID
     * @return 姓名快照；无法解析时返回 ""
     */
    private String resolveSalespersonName(ErpSaleOrderBo bo, Long salespersonId) {
        if (StringUtils.isNotBlank(bo.getSalespersonName())) {
            return checkLength(bo.getSalespersonName(), 50, "跟单人姓名");
        }
        return Objects.equals(salespersonId, currentUserId()) ? currentUserName() : "";
    }

    /**
     * 锁定订单头；不存在抛 40905。
     *
     * @param id 订单头ID
     * @return 锁定到的订单头
     */
    private ErpSaleOrder lockHead(Long id) {
        ErpSaleOrder head = saleOrderMapper.lockById(id);
        if (head == null) {
            throw new ServiceException("销售订单不存在", 40905);
        }
        return head;
    }

    /**
     * 锁定并校验订单行归属。
     *
     * @param orderId 订单头ID
     * @param itemId  订单行ID
     * @return 锁定到的订单行
     */
    private ErpSaleOrderItem lockItem(Long orderId, Long itemId) {
        if (itemId == null) {
            throw new ServiceException("订单行ID不能为空", 40902);
        }
        List<ErpSaleOrderItem> rows = saleOrderMapper.lockItemsByIds(List.of(itemId));
        if (rows.isEmpty()) {
            throw new ServiceException("订单行不存在：" + itemId, 40905);
        }
        ErpSaleOrderItem item = rows.get(0);
        if (!orderId.equals(item.getOrderId())) {
            throw new ServiceException("订单行不属于该订单：" + itemId, 40902);
        }
        return item;
    }

    /**
     * 查询订单行（不加锁），按 seq 排序。
     *
     * @param orderId 订单头ID
     * @return 订单行
     */
    private List<ErpSaleOrderItem> loadOrderItems(Long orderId) {
        return saleOrderItemMapper.selectList(new LambdaQueryWrapper<ErpSaleOrderItem>()
            .eq(ErpSaleOrderItem::getOrderId, orderId)
            .orderByAsc(ErpSaleOrderItem::getSeq)
            .orderByAsc(ErpSaleOrderItem::getId));
    }

    /**
     * 按行 id 给改单入参建索引，并校验一一对应（已确认订单不允许增删行）。
     *
     * @param reqItems 入参行
     * @param existing 库内行
     * @return 行ID → 入参行
     */
    private Map<Long, ErpSaleOrderBo.ItemBo> indexRequestItems(List<ErpSaleOrderBo.ItemBo> reqItems,
                                                               List<ErpSaleOrderItem> existing) {
        Set<Long> existingIds = existing.stream().map(ErpSaleOrderItem::getId).collect(Collectors.toSet());
        Map<Long, ErpSaleOrderBo.ItemBo> result = new HashMap<>();
        for (ErpSaleOrderBo.ItemBo item : reqItems) {
            if (item.getId() == null || !existingIds.contains(item.getId())) {
                throw new ServiceException("已确认订单不允许增删明细", 40902);
            }
            if (result.put(item.getId(), item) != null) {
                throw new ServiceException("订单行重复提交：" + item.getId(), 40902);
            }
        }
        return result;
    }

    /**
     * 校验改单后「同一商品同一交期」仍不重复（§6.3 草稿约束同样适用于已确认改交期）。
     *
     * @param items 订单行
     */
    private void checkDuplicateProductDue(List<ErpSaleOrderItem> items) {
        Set<String> keys = new HashSet<>();
        for (ErpSaleOrderItem item : items) {
            if (!keys.add(item.getProductId() + "#" + fmtDate(item.getDueDate()))) {
                throw new ServiceException("同一商品同一交期已存在，请合并数量或改交期", 40902);
            }
        }
    }

    /**
     * 校验客户存在（40905）。
     *
     * @param customerId 客户ID
     */
    private void requireCustomer(Long customerId) {
        ErpCustomer customer = customerId == null ? null : customerMapper.selectById(customerId);
        if (customer == null) {
            throw new ServiceException("客户不存在：" + customerId, 40905);
        }
    }

    /**
     * 校验下单日期必填。
     *
     * @param bizDate 下单日期
     */
    private void requireBizDate(Date bizDate) {
        if (bizDate == null) {
            throw new ServiceException("下单日期不能为空", 40902);
        }
    }

    /**
     * 字符串长度校验并空值兜底为 ""；超长直接 40902，避免数据库静默截断。
     *
     * @param value 原值，可空
     * @param max   最大长度
     * @param label 字段中文名
     * @return 非空字符串
     */
    private String checkLength(String value, int max, String label) {
        String text = value == null ? "" : value;
        if (text.length() > max) {
            throw new ServiceException(label + "不能超过 " + max + " 个字符", 40902);
        }
        return text;
    }

    /**
     * 写一条订单变更留痕（只增不删）；old/new 为字符串化值，地址 255 位完整保存。
     *
     * @param head   订单头（提供订单号快照）
     * @param change 变更
     * @param reason 变更原因，可空
     */
    private void writeLog(ErpSaleOrder head, Change change, String reason) {
        ErpOrderChangeLog log = new ErpOrderChangeLog();
        log.setOrderType(ORDER_TYPE);
        log.setOrderId(head.getId());
        log.setOrderNo(StringUtils.blankToDefault(head.getNo(), ""));
        log.setItemId(change.itemId());
        log.setFieldKey(change.fieldKey());
        log.setOldValue(StringUtils.blankToDefault(change.oldValue(), ""));
        log.setNewValue(StringUtils.blankToDefault(change.newValue(), ""));
        log.setReason(StringUtils.blankToDefault(reason, ""));
        log.setOperatorId(currentUserId());
        log.setOperatorName(currentUserName());
        log.setOperateTime(new Date());
        changeLogMapper.insert(log);
    }

    /**
     * 批量加载客户名称，避免列表 N+1。
     *
     * @param heads 订单头
     * @return 客户ID → 名称
     */
    private Map<Long, String> loadCustomerNames(Collection<ErpSaleOrder> heads) {
        Map<Long, String> names = new HashMap<>();
        List<Long> ids = heads.stream().map(ErpSaleOrder::getCustomerId).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return names;
        }
        for (ErpCustomer customer : customerMapper.selectByIds(ids)) {
            names.put(customer.getId(), customer.getName());
        }
        return names;
    }

    /**
     * 批量汇总列表页各订单的数量列（订单量/已执行/已关闭）。
     *
     * @param heads 订单头
     * @return 订单ID → 数量合计
     */
    private Map<Long, QtySum> loadQtySums(Collection<ErpSaleOrder> heads) {
        Map<Long, QtySum> sums = new HashMap<>();
        List<Long> ids = heads.stream().map(ErpSaleOrder::getId).toList();
        if (ids.isEmpty()) {
            return sums;
        }
        List<ErpSaleOrderItem> items = saleOrderItemMapper.selectList(new LambdaQueryWrapper<ErpSaleOrderItem>()
            .select(ErpSaleOrderItem::getOrderId, ErpSaleOrderItem::getQty,
                ErpSaleOrderItem::getExecutedQty, ErpSaleOrderItem::getClosedQty)
            .in(ErpSaleOrderItem::getOrderId, ids));
        for (ErpSaleOrderItem item : items) {
            QtySum sum = sums.getOrDefault(item.getOrderId(), new QtySum());
            sum.add(item);
            sums.put(item.getOrderId(), sum);
        }
        return sums;
    }

    /**
     * 批量回传订单已执行净金额，供列表直接显示，避免前端按执行记录逐单请求详情。
     */
    private Map<Long, BigDecimal> loadExecutionAmounts(Collection<ErpSaleOrder> heads) {
        Map<Long, BigDecimal> result = new HashMap<>();
        List<Long> ids = heads.stream().map(ErpSaleOrder::getId).filter(Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return result;
        }
        String tenantId = currentTenantId();
        List<OrderExecutionAmountVo> rows = saleOrderMapper.selectExecutionAmounts(ids, tenantId);
        if (rows != null) {
            for (OrderExecutionAmountVo row : rows) {
                if (row.getOrderId() != null) {
                    result.put(row.getOrderId(), nvl(row.getAmount()));
                }
            }
        }
        return result;
    }

    /**
     * 订单详情行视图：批量带出商品编码/名称/规格/单位/数量精度，并算未交量。
     *
     * @param orderId 订单头ID
     * @return 行视图
     */
    private List<ErpSaleOrderVo.ItemVo> loadItems(Long orderId) {
        List<ErpSaleOrderItem> items = loadOrderItems(orderId);
        Map<Long, ErpProduct> products = new HashMap<>();
        List<Long> productIds = items.stream().map(ErpSaleOrderItem::getProductId).filter(Objects::nonNull).distinct().toList();
        if (!productIds.isEmpty()) {
            for (ErpProduct product : productMapper.selectByIds(productIds)) {
                products.put(product.getId(), product);
            }
        }
        List<ErpSaleOrderVo.ItemVo> result = new ArrayList<>(items.size());
        for (ErpSaleOrderItem item : items) {
            ErpSaleOrderVo.ItemVo vo = new ErpSaleOrderVo.ItemVo();
            vo.setId(item.getId());
            vo.setSeq(item.getSeq());
            vo.setProductId(item.getProductId());
            vo.setQty(item.getQty());
            vo.setPrice(item.getPrice());
            vo.setAmount(item.getAmount());
            vo.setTaxType(item.getTaxType());
            vo.setTaxRate(item.getTaxRate());
            vo.setDueDate(item.getDueDate());
            vo.setPromisedDate(item.getPromisedDate());
            vo.setExecutedQty(nvl(item.getExecutedQty()));
            vo.setClosedQty(nvl(item.getClosedQty()));
            vo.setClosedFlag(item.getClosedFlag());
            vo.setCloseReason(StringUtils.blankToDefault(item.getCloseReason(), ""));
            vo.setRemain(remain(item));
            vo.setRemark(item.getRemark());
            ErpProduct product = products.get(item.getProductId());
            if (product != null) {
                vo.setProductCode(product.getCode());
                vo.setProductName(product.getName());
                vo.setSpec(product.getSpec());
                vo.setUnit(product.getUnit());
                vo.setQtyScale(product.getQtyScale());
            }
            result.add(vo);
        }
        return result;
    }

    /**
     * 头视图装配。
     *
     * @param head          订单头
     * @param customerNames 客户ID → 名称
     * @param sum           数量合计，可空（详情由明细汇总）
     * @return 视图
     */
    private ErpSaleOrderVo toVo(ErpSaleOrder head, Map<Long, String> customerNames, QtySum sum,
                                BigDecimal executionAmount) {
        ErpSaleOrderVo vo = new ErpSaleOrderVo();
        vo.setId(head.getId());
        vo.setNo(head.getNo());
        vo.setCustomerId(head.getCustomerId());
        vo.setCustomerName(head.getCustomerId() == null ? null : customerNames.get(head.getCustomerId()));
        vo.setBizDate(head.getBizDate());
        vo.setDueDate(head.getDueDate());
        vo.setStatus(head.getStatus());
        vo.setStatusText(StatusText.bill(head.getStatus()));
        vo.setExecStatus(head.getExecStatus());
        vo.setCustomerOrderNo(head.getCustomerOrderNo());
        vo.setSalespersonId(head.getSalespersonId());
        vo.setSalespersonName(head.getSalespersonName());
        vo.setContactName(head.getContactName());
        vo.setContactPhone(head.getContactPhone());
        vo.setDeliveryAddress(head.getDeliveryAddress());
        vo.setAllowDelayDays(head.getAllowDelayDays());
        vo.setOverTolPct(head.getOverTolPct());
        vo.setTaxType(head.getTaxType());
        vo.setTaxRate(head.getTaxRate());
        vo.setGoodsAmount(head.getGoodsAmount());
        vo.setRemark(head.getRemark());
        vo.setConfirmedAt(head.getConfirmedAt());
        vo.setWithdrawnAt(head.getWithdrawnAt());
        vo.setVoidedAt(head.getVoidedAt());
        vo.setCreateTime(head.getCreateTime());
        // 列表列默认 0，避免无明细的异常数据让前端进度条出现 null
        vo.setTotalQty(sum == null ? BigDecimal.ZERO : sum.qty);
        vo.setExecutedQty(sum == null ? BigDecimal.ZERO : sum.executedQty);
        vo.setClosedQty(sum == null ? BigDecimal.ZERO : sum.closedQty);
        vo.setRemainQty(sum == null ? BigDecimal.ZERO : sum.remain());
        vo.setExecutedAmount(executionAmount == null
            ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
            : executionAmount.setScale(2, RoundingMode.HALF_UP));
        // §6.1.3：CLOSED 的语义是「没有未交量但含放弃量」，文案必须带出放弃量
        vo.setExecStatusText(execText(head.getExecStatus(), vo.getTotalQty(), vo.getClosedQty()));
        return vo;
    }

    /**
     * 用明细汇总回填头的数量四列（详情页）。
     *
     * @param vo    头视图
     * @param items 明细视图
     */
    private void fillQtySummary(ErpSaleOrderVo vo, List<ErpSaleOrderVo.ItemVo> items) {
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal executedQty = BigDecimal.ZERO;
        BigDecimal closedQty = BigDecimal.ZERO;
        BigDecimal remainQty = BigDecimal.ZERO;
        for (ErpSaleOrderVo.ItemVo item : items) {
            totalQty = totalQty.add(nvl(item.getQty()));
            executedQty = executedQty.add(nvl(item.getExecutedQty()));
            closedQty = closedQty.add(nvl(item.getClosedQty()));
            remainQty = remainQty.add(nvl(item.getRemain()));
        }
        vo.setTotalQty(totalQty);
        vo.setExecutedQty(executedQty);
        vo.setClosedQty(closedQty);
        vo.setRemainQty(remainQty);
        vo.setExecStatusText(execText(vo.getExecStatus(), totalQty, closedQty));
    }

    /**
     * 执行进度文案：CLOSED 且存在放弃量时带出「放弃 40/100」（DOC/31 §6.1.3）。
     *
     * @param execStatus 执行状态码
     * @param totalQty   订单量合计
     * @param closedQty  已关闭（放弃）量合计
     * @return 状态文案；非 CLOSED 或未放弃时沿用 StatusText.exec
     */
    private String execText(String execStatus, BigDecimal totalQty, BigDecimal closedQty) {
        String text = StatusText.exec(execStatus);
        if (!"CLOSED".equals(execStatus) || closedQty.compareTo(BigDecimal.ZERO) <= 0) {
            return text;
        }
        return text + "（放弃 " + closedQty.stripTrailingZeros().toPlainString() + "/"
            + totalQty.stripTrailingZeros().toPlainString() + "）";
    }

    /**
     * 未交量 = max(0, 订单量 − 已执行 − 已关闭)。
     *
     * @param item 订单行
     * @return 未交量
     */
    private BigDecimal remain(ErpSaleOrderItem item) {
        return nvl(item.getQty()).subtract(nvl(item.getExecutedQty())).subtract(nvl(item.getClosedQty()))
            .max(BigDecimal.ZERO);
    }

    /**
     * 行金额 = 数量 × 单价，2 位 HALF_UP。
     *
     * @param qty   数量
     * @param price 单价
     * @return 金额
     */
    private BigDecimal amount(BigDecimal qty, BigDecimal price) {
        return nvl(qty).multiply(nvl(price)).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 行是否已关闭。
     *
     * @param item 订单行
     * @return true=已关闭剩余
     */
    private boolean isClosed(ErpSaleOrderItem item) {
        return Integer.valueOf(1).equals(item.getClosedFlag());
    }

    /**
     * 空值兜底为 0。
     *
     * @param value 原值，可空
     * @return 非空值
     */
    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * Integer 空值兜底为 0。
     *
     * @param value 原值，可空
     * @return 非空值
     */
    private static int nvlInt(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * 日期空值兜底为 0（仅用于比较是否变化）。
     *
     * @param value 原值，可空
     * @return 毫秒值
     */
    private static long nvlTime(Date value) {
        return value == null ? 0L : value.getTime();
    }

    /**
     * 数量字符串化（去尾零），用于留痕与错误提示。
     *
     * @param value 数量，可空
     * @return 字符串
     */
    private static String fmt(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    /**
     * 日期字符串化 yyyy-MM-dd。
     *
     * @param value 日期，可空
     * @return 字符串
     */
    private static String fmtDate(Date value) {
        return value == null ? "" : new SimpleDateFormat("yyyy-MM-dd").format(value);
    }

    /**
     * 对象字符串化（留痕用），null 返回 ""。
     *
     * @param value 原值
     * @return 字符串
     */
    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 原生执行金额 SQL 必须显式绑定当前租户；缺少上下文直接拒绝。
     */
    private String currentTenantId() {
        String tenantId = TenantHelper.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new ServiceException("缺少租户上下文，拒绝查询订单执行金额", 40901);
        }
        return tenantId;
    }

    /**
     * 当前登录人ID；非请求线程返回 null（与各单据服务一致）。
     *
     * @return 用户ID
     */
    private Long currentUserId() {
        try {
            return LoginHelper.getUserId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 当前登录人姓名快照；非请求线程返回 ""。
     *
     * @return 用户姓名
     */
    private String currentUserName() {
        try {
            return StringUtils.blankToDefault(LoginHelper.getUsername(), "");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 一次改单中的单个字段变化（留痕单元）。
     *
     * @param itemId         订单行ID，头级变更为 null
     * @param fieldKey       留痕字段键
     * @param oldValue       原值
     * @param newValue       新值
     * @param reasonRequired 是否必须填原因
     * @param label          字段中文名
     */
    private record Change(Long itemId, String fieldKey, String oldValue, String newValue,
                          boolean reasonRequired, String label) {
    }

    /**
     * 转单行：订单行 + 本次数量。
     *
     * @param item 订单行
     * @param qty  本次出库数量
     */
    private record DeliveryLine(ErpSaleOrderItem item, BigDecimal qty) {
    }

    /**
     * 列表数量合计（可变累加器）。
     */
    private static final class QtySum {

        private BigDecimal qty = BigDecimal.ZERO;
        private BigDecimal executedQty = BigDecimal.ZERO;
        private BigDecimal closedQty = BigDecimal.ZERO;

        /**
         * 累加一条订单行。
         *
         * @param item 订单行
         */
        private void add(ErpSaleOrderItem item) {
            qty = qty.add(nvl(item.getQty()));
            executedQty = executedQty.add(nvl(item.getExecutedQty()));
            closedQty = closedQty.add(nvl(item.getClosedQty()));
        }

        /**
         * 未交量合计 = max(0, 订单量 − 已执行 − 已关闭)。
         *
         * @return 未交量
         */
        private BigDecimal remain() {
            return qty.subtract(executedQty).subtract(closedQty).max(BigDecimal.ZERO);
        }
    }
}
