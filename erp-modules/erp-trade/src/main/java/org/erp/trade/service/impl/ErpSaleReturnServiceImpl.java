package org.erp.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.erp.common.enums.StatusText;
import org.erp.common.sequence.SequenceService;
import org.erp.fin.service.IFinArApService;
import org.erp.fin.service.IFinOpeningGuardService;
import org.erp.fin.service.IFinReceiptService;
import org.erp.master.domain.ErpCustomer;
import org.erp.master.domain.ErpProduct;
import org.erp.master.mapper.ErpCustomerMapper;
import org.erp.master.mapper.ErpProductMapper;
import org.erp.master.service.IErpWarehouseService;
import org.erp.stock.service.IInventoryService;
import org.erp.trade.domain.ErpSale;
import org.erp.trade.domain.ErpSaleItem;
import org.erp.trade.domain.ErpSaleReturn;
import org.erp.trade.domain.ErpSaleReturnItem;
import org.erp.trade.domain.bo.ErpSaleReturnBo;
import org.erp.trade.domain.vo.ErpReturnQtyAggregateVo;
import org.erp.trade.domain.vo.ErpSaleReturnVo;
import org.erp.trade.mapper.ErpSaleItemMapper;
import org.erp.trade.mapper.ErpSaleMapper;
import org.erp.trade.mapper.ErpSaleReturnItemMapper;
import org.erp.trade.mapper.ErpSaleReturnMapper;
import org.erp.trade.service.IErpOrderProgressService;
import org.erp.trade.service.IErpSaleReturnService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 销售退货服务实现（DOC/24）
 *
 * 规则：
 * - 必须关联 CONFIRMED 原销售单；累计可退 = 原行 qty − Σ(CONFIRMED 退货单行)，DRAFT/VOID 不占量；
 * - 确认（一事务）：锁原单头 → 强校验累计 → 逐行 inbound(SALE_RETURN, 原行 unit_cost 快照) →
 *   负数 AR 行（createBySaleReturn）→ 可选红字收款单退款（need_refund=1，unsettle 原单 AR）；
 * - 退款红线：refund_amount ≤ 原单 AR settled_amount（unsettle 既有校验兜底）；
 * - 撤回顺序刚性：先撤退款单 → VOID 负数行 → 库存 reverse；
 * - 原单保护：存在非 VOID 退货单时原销售单不可撤回（ErpSaleServiceImpl 前置校验）。
 */
@RequiredArgsConstructor
@Service
public class ErpSaleReturnServiceImpl implements IErpSaleReturnService {

    /** 订单类型：销售（订单进度服务按此选表） */
    private static final String ORDER_TYPE = IErpOrderProgressService.TYPE_SALE;

    private final ErpSaleReturnMapper returnMapper;
    private final ErpSaleReturnItemMapper itemMapper;
    private final ErpSaleMapper saleMapper;
    private final ErpSaleItemMapper saleItemMapper;
    private final ErpProductMapper productMapper;
    private final ErpCustomerMapper customerMapper;
    private final IInventoryService inventoryService;
    private final IErpWarehouseService warehouseService;
    private final IFinArApService arApService;
    private final IFinReceiptService receiptService;
    private final SequenceService sequenceService;
    private final IErpOrderProgressService orderProgressService;
    private final IFinOpeningGuardService openingGuardService;

    @Override
    public TableDataInfo<ErpSaleReturnVo> selectPage(String keyword, Long customerId, String status, PageQuery pageQuery) {
        LambdaQueryWrapper<ErpSaleReturn> lqw = new LambdaQueryWrapper<>();
        lqw.eq(customerId != null, ErpSaleReturn::getCustomerId, customerId);
        lqw.eq(status != null && !status.isBlank(), ErpSaleReturn::getStatus, status);
        if (keyword != null && !keyword.isBlank()) {
            lqw.and(w -> w.like(ErpSaleReturn::getNo, keyword).or().like(ErpSaleReturn::getSaleNo, keyword));
        }
        lqw.orderByDesc(ErpSaleReturn::getId);
        Page<ErpSaleReturn> page = returnMapper.selectPage(pageQuery.build(), lqw);
        Page<ErpSaleReturnVo> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toVo).toList());
        return TableDataInfo.build(voPage);
    }

    @Override
    public List<ErpSaleReturnVo> returnableOrders(Long customerId) {
        // 该客户 CONFIRMED 且存在可退余量的销售单（简化：全部 CONFIRMED 单，前端按 returnableQty 过滤展示）
        List<ErpSale> sales = saleMapper.selectList(new LambdaQueryWrapper<ErpSale>()
            .eq(customerId != null, ErpSale::getCustomerId, customerId)
            .eq(ErpSale::getStatus, "CONFIRMED")
            .orderByDesc(ErpSale::getBizDate)
            .orderByDesc(ErpSale::getId));
        List<ErpSaleReturnVo> result = new ArrayList<>();
        for (ErpSale sale : sales) {
            ErpSaleReturnVo vo = new ErpSaleReturnVo();
            vo.setSaleId(sale.getId());
            vo.setSaleNo(sale.getNo());
            vo.setCustomerId(sale.getCustomerId());
            vo.setBizDate(sale.getBizDate());
            vo.setTotalAmount(sale.getTotalAmount());
            result.add(vo);
        }
        return result;
    }

    @Override
    public List<ErpSaleReturnVo.ItemVo> originalItems(Long saleId) {
        ErpSale sale = saleMapper.selectById(saleId);
        if (sale == null) {
            throw new ServiceException("销售单不存在");
        }
        List<ErpSaleItem> items = saleItemMapper.selectList(new LambdaQueryWrapper<ErpSaleItem>()
            .eq(ErpSaleItem::getOrderId, saleId)
            .orderByAsc(ErpSaleItem::getSeq));
        Map<Long, ErpProduct> products = loadProducts(items.stream().map(ErpSaleItem::getProductId).toList());
        Map<Long, BigDecimal> returnedByItem = loadReturnedQty(items.stream().map(ErpSaleItem::getId).toList(), null);
        List<ErpSaleReturnVo.ItemVo> result = new ArrayList<>();
        for (ErpSaleItem item : items) {
            BigDecimal returned = returnedByItem.getOrDefault(item.getId(), BigDecimal.ZERO);
            ErpSaleReturnVo.ItemVo vo = new ErpSaleReturnVo.ItemVo();
            vo.setSaleItemId(item.getId());
            vo.setProductId(item.getProductId());
            vo.setPrice(item.getPrice());
            vo.setUnitCost(item.getUnitCost());
            vo.setOriginalQty(item.getQty());
            vo.setReturnedQty(returned);
            vo.setReturnableQty(item.getQty().subtract(returned));
            ErpProduct p = products.get(item.getProductId());
            if (p != null) {
                vo.setProductCode(p.getCode());
                vo.setProductName(p.getName());
                vo.setSpec(p.getSpec());
                vo.setUnit(p.getUnit());
                vo.setQtyScale(p.getQtyScale());
            }
            result.add(vo);
        }
        return result;
    }

    @Override
    public ErpSaleReturnVo selectById(Long id) {
        ErpSaleReturn head = returnMapper.selectById(id);
        if (head == null) {
            throw new ServiceException("退货单不存在");
        }
        ErpSaleReturnVo vo = toVo(head);
        vo.setItems(loadItems(id));
        return vo;
    }

    private ErpSaleReturnVo toVo(ErpSaleReturn head) {
        ErpSaleReturnVo vo = new ErpSaleReturnVo();
        vo.setId(head.getId());
        vo.setNo(head.getNo());
        vo.setSaleId(head.getSaleId());
        vo.setSaleNo(head.getSaleNo());
        vo.setCustomerId(head.getCustomerId());
        ErpCustomer c = customerMapper.selectById(head.getCustomerId());
        vo.setCustomerName(c == null ? null : c.getName());
        vo.setBizDate(head.getBizDate());
        vo.setStatus(head.getStatus());
        vo.setStatusText(StatusText.bill(head.getStatus()));
        vo.setTotalAmount(head.getTotalAmount());
        vo.setReturnCostAmount(head.getReturnCostAmount());
        vo.setNeedRefund(head.getNeedRefund());
        vo.setRefundAmount(head.getRefundAmount());
        vo.setRefundAccountId(head.getRefundAccountId());
        vo.setRefundReceiptId(head.getRefundReceiptId());
        vo.setRemark(head.getRemark());
        vo.setCreateTime(head.getCreateTime());
        return vo;
    }

    private List<ErpSaleReturnVo.ItemVo> loadItems(Long orderId) {
        List<ErpSaleReturnItem> items = itemMapper.selectList(new LambdaQueryWrapper<ErpSaleReturnItem>()
            .eq(ErpSaleReturnItem::getOrderId, orderId)
            .orderByAsc(ErpSaleReturnItem::getSeq));
        Map<Long, ErpProduct> products = loadProducts(items.stream().map(ErpSaleReturnItem::getProductId).toList());
        List<Long> originalItemIds = items.stream().map(ErpSaleReturnItem::getSaleItemId)
            .filter(id -> id != null).distinct().toList();
        Map<Long, ErpSaleItem> originals = loadSaleItems(originalItemIds);
        Map<Long, BigDecimal> returnedByItem = loadReturnedQty(originalItemIds, null);
        List<ErpSaleReturnVo.ItemVo> result = new ArrayList<>();
        for (ErpSaleReturnItem row : items) {
            ErpSaleReturnVo.ItemVo vo = new ErpSaleReturnVo.ItemVo();
            vo.setId(row.getId());
            vo.setSaleItemId(row.getSaleItemId());
            vo.setProductId(row.getProductId());
            vo.setQty(row.getQty());
            vo.setPrice(row.getPrice());
            vo.setAmount(row.getAmount());
            vo.setUnitCost(row.getUnitCost());
            vo.setReturnCost(row.getReturnCost());
            vo.setRemark(row.getRemark());
            // 详情页需展示「原行数量 / 已退 / 可退」：按原销售行补算，口径与 originalItems 一致
            ErpSaleItem original = originals.get(row.getSaleItemId());
            if (original != null) {
                BigDecimal returned = returnedByItem.getOrDefault(original.getId(), BigDecimal.ZERO);
                vo.setOriginalQty(original.getQty());
                vo.setReturnedQty(returned);
                vo.setReturnableQty(original.getQty().subtract(returned));
            }
            ErpProduct p = products.get(row.getProductId());
            if (p != null) {
                vo.setProductCode(p.getCode());
                vo.setProductName(p.getName());
                vo.setSpec(p.getSpec());
                vo.setUnit(p.getUnit());
                vo.setQtyScale(p.getQtyScale());
            }
            result.add(vo);
        }
        return result;
    }

    private Map<Long, ErpProduct> loadProducts(List<Long> productIds) {
        Map<Long, ErpProduct> products = new HashMap<>();
        List<Long> ids = productIds.stream().filter(id -> id != null).distinct().toList();
        if (ids.isEmpty()) {
            return products;
        }
        for (ErpProduct p : productMapper.selectByIds(ids)) {
            products.put(p.getId(), p);
        }
        return products;
    }

    private Map<Long, ErpSaleItem> loadSaleItems(Collection<Long> saleItemIds) {
        Map<Long, ErpSaleItem> originals = new HashMap<>();
        if (saleItemIds.isEmpty()) {
            return originals;
        }
        for (ErpSaleItem original : saleItemMapper.selectByIds(saleItemIds)) {
            originals.put(original.getId(), original);
        }
        return originals;
    }

    /**
     * 当前租户下按原销售行汇总 CONFIRMED 且未软删的退货明细；DRAFT/VOID 不占用可退量。
     */
    private Map<Long, BigDecimal> loadReturnedQty(Collection<Long> saleItemIds, Long excludeReturnId) {
        Map<Long, BigDecimal> returned = new HashMap<>();
        List<Long> ids = saleItemIds.stream().filter(id -> id != null).distinct().toList();
        if (ids.isEmpty()) {
            return returned;
        }
        for (ErpReturnQtyAggregateVo aggregate : itemMapper.sumConfirmedReturnedQty(
            ids, currentTenantId(), excludeReturnId)) {
            returned.put(aggregate.getOriginalItemId(), aggregate.getReturnedQty());
        }
        return returned;
    }

    private String currentTenantId() {
        String tenantId = TenantHelper.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new ServiceException("缺少租户上下文，拒绝查询退货数量", 40901);
        }
        return tenantId;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public ErpSaleReturnVo insert(ErpSaleReturnBo bo) {
        ErpSaleReturn head = new ErpSaleReturn();
        head.setNo(sequenceService.nextNo("XT"));
        saveHead(head, bo);
        saveItems(head, bo);
        if (Boolean.TRUE.equals(bo.getConfirm())) {
            confirmInternal(head.getId());
        }
        return selectById(head.getId());
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public ErpSaleReturnVo update(Long id, ErpSaleReturnBo bo) {
        ErpSaleReturn head = returnMapper.lockById(id);
        if (head == null) {
            throw new ServiceException("退货单不存在");
        }
        if (!"DRAFT".equals(head.getStatus())) {
            throw new ServiceException("仅草稿可编辑", 40902);
        }
        saveHead(head, bo);
        itemMapper.delete(new LambdaQueryWrapper<ErpSaleReturnItem>()
            .eq(ErpSaleReturnItem::getOrderId, id));
        saveItems(head, bo);
        if (Boolean.TRUE.equals(bo.getConfirm())) {
            confirmInternal(id);
        }
        return selectById(id);
    }

    private void saveHead(ErpSaleReturn head, ErpSaleReturnBo bo) {
        ErpSale sale = saleMapper.selectById(bo.getSaleId());
        if (sale == null) {
            throw new ServiceException("原销售单不存在");
        }
        // 保存时提示级校验原单状态；确认时强校验
        if (!"CONFIRMED".equals(sale.getStatus())) {
            throw new ServiceException("原销售单未确认，不能退货", 40902);
        }
        head.setSaleId(sale.getId());
        head.setSaleNo(sale.getNo());
        head.setCustomerId(sale.getCustomerId());
        head.setBizDate(bo.getBizDate());
        boolean refund = Boolean.TRUE.equals(bo.getNeedRefund());
        head.setNeedRefund(refund ? 1 : 0);
        head.setRefundAmount(refund && bo.getRefundAmount() != null
            ? bo.getRefundAmount().setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO);
        head.setRefundAccountId(refund ? bo.getRefundAccountId() : null);
        head.setRemark(bo.getRemark());
        head.setStatus("DRAFT");
        if (head.getId() == null) {
            returnMapper.insert(head);
        } else {
            returnMapper.updateById(head);
        }
    }

    private void saveItems(ErpSaleReturn head, ErpSaleReturnBo bo) {
        Set<Long> saleItemIds = new HashSet<>();
        List<Long> originalItemIds = bo.getItems().stream().map(ErpSaleReturnBo.ItemBo::getSaleItemId)
            .filter(id -> id != null).distinct().toList();
        Map<Long, ErpSaleItem> originals = loadSaleItems(originalItemIds);
        BigDecimal amountTotal = BigDecimal.ZERO;
        int seq = 0;
        for (ErpSaleReturnBo.ItemBo item : bo.getItems()) {
            if (!saleItemIds.add(item.getSaleItemId())) {
                throw new ServiceException("明细中重复引用原销售行：" + item.getSaleItemId());
            }
            ErpSaleItem original = originals.get(item.getSaleItemId());
            if (original == null || !original.getOrderId().equals(head.getSaleId())) {
                throw new ServiceException("原销售明细行不属于该原单：" + item.getSaleItemId());
            }
            ErpSaleReturnItem row = new ErpSaleReturnItem();
            row.setOrderId(head.getId());
            row.setSaleItemId(original.getId());
            row.setProductId(original.getProductId());
            row.setQty(item.getQty());
            row.setPrice(item.getPrice().setScale(4, RoundingMode.HALF_UP));
            row.setAmount(item.getQty().multiply(item.getPrice()).setScale(2, RoundingMode.HALF_UP));
            row.setRemark(item.getRemark());
            row.setSeq(seq++);
            itemMapper.insert(row);
            amountTotal = amountTotal.add(row.getAmount());
        }
        head.setTotalAmount(amountTotal.setScale(2, RoundingMode.HALF_UP));
        returnMapper.updateById(head);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public void confirm(Long id) {
        confirmInternal(id);
    }

    private void confirmInternal(Long id) {
        // 保存并确认可能已读过原单。READ COMMITTED 保证拿到原单锁后看见前序已提交退货，
        // 不在 REPEATABLE READ 的旧快照上继续累加可退数量。
        ErpSaleReturn head = returnMapper.lockById(id);
        if (head == null) {
            throw new ServiceException("退货单不存在");
        }
        // 锁内重读状态：update(confirm=true) 与并发确认同时到达时，后到的只能失败（§6.8.2 防线 2/3）
        if (!"DRAFT".equals(head.getStatus())) {
            throw new ServiceException("仅草稿可确认", 40902);
        }
        ErpSale sale = saleMapper.lockById(head.getSaleId());
        if (sale == null || !"CONFIRMED".equals(sale.getStatus()) || !sale.getCustomerId().equals(head.getCustomerId())) {
            throw new ServiceException("原销售单未确认或已撤回，不能退货", 40902);
        }
        // 2. 解析来源订单：为 NULL 直接跳过订单逻辑；非空时先锁订单头、再按 id 升序锁订单行（DOC/31 §5.4 改动点 5、§6.8.1）
        Long sourceOrderId = sale.getSourceOrderId();
        orderProgressService.lockOrder(ORDER_TYPE, sourceOrderId);
        List<ErpSaleReturnItem> items = itemMapper.selectList(new LambdaQueryWrapper<ErpSaleReturnItem>()
            .eq(ErpSaleReturnItem::getOrderId, head.getId())
            .orderByAsc(ErpSaleReturnItem::getSeq));
        Map<Long, ErpSaleItem> originalRows = checkReturnable(head, items);

        // 退货单头、原单头和订单头/行已依序加锁；先校验锁定退货单的日期，再处理退款、台账与库存。
        openingGuardService.lockAndCheckBusinessDate("AR", head.getCustomerId(), head.getBizDate());

        // 3. 先处理台账，再动库存；退款读取锁定退货单上的账户与金额，单独确认与保存并确认共用此路径。
        if (Integer.valueOf(1).equals(head.getNeedRefund())) {
            if (head.getRefundAccountId() == null) {
                throw new ServiceException("退款必须选择结算账户，旧草稿请编辑后重试", 40902);
            }
            head.setRefundReceiptId(receiptService.insertSaleReturnRefund(head.getId()));
        }
        arApService.createBySaleReturn(head.getId(), head.getNo(), head.getCustomerId(),
            head.getTotalAmount().negate(), head.getBizDate());

        // 4. 库存回增（原行 unit_cost 快照走 inbound）；动库存前按 product_id 升序锁齐库存行（§6.8.1 规则 3）
        inventoryService.lockStockRows(items.stream().map(ErpSaleReturnItem::getProductId).toList());
        int seq = 0;
        BigDecimal costTotal = BigDecimal.ZERO;
        for (ErpSaleReturnItem row : items) {
            ErpSaleItem original = originalRows.get(row.getSaleItemId());
            BigDecimal unitCost = original.getUnitCost() == null ? BigDecimal.ZERO : original.getUnitCost();
            inventoryService.inbound("SALE_RETURN", head.getNo(), head.getId(), row.getProductId(),
                warehouseService.defaultWarehouseId(), row.getQty(), unitCost,
                "CUSTOMER", head.getCustomerId(), head.getBizDate(), seq++);
            row.setUnitCost(unitCost);
            row.setReturnCost(row.getQty().multiply(unitCost).setScale(2, RoundingMode.HALF_UP));
            itemMapper.updateById(row);
            costTotal = costTotal.add(row.getReturnCost());
        }
        head.setReturnCostAmount(costTotal.setScale(2, RoundingMode.HALF_UP));

        // 状态 CAS：只有 DRAFT 能改成 CONFIRMED，并发双击/跨节点时第二个请求返回 40902（§6.8.2 防线 3）
        head.setConfirmedAt(new Date());
        head.setConfirmedBy(currentUserId());
        int rows = returnMapper.update(null, new LambdaUpdateWrapper<ErpSaleReturn>()
            .eq(ErpSaleReturn::getId, head.getId()).eq(ErpSaleReturn::getStatus, "DRAFT")
            .set(ErpSaleReturn::getStatus, "CONFIRMED")
            .set(ErpSaleReturn::getReturnCostAmount, head.getReturnCostAmount())
            .set(ErpSaleReturn::getRefundReceiptId, head.getRefundReceiptId())
            .set(ErpSaleReturn::getConfirmedAt, head.getConfirmedAt())
            .set(ErpSaleReturn::getConfirmedBy, head.getConfirmedBy()));
        if (rows == 0) {
            throw new ServiceException("单据状态已变更，请刷新后重试", 40902);
        }
        // 退货状态落库后重算订单执行量，净额 = 已确认执行 − 已确认退货（DOC/31 §6.1.3、§6.5）
        orderProgressService.recalc(ORDER_TYPE, sourceOrderId, null);
    }

    /**
     * 强校验累计可退：确认事务内，排除自身已确认行后重算占用。
     */
    private Map<Long, ErpSaleItem> checkReturnable(ErpSaleReturn head, List<ErpSaleReturnItem> items) {
        List<Long> saleItemIds = items.stream().map(ErpSaleReturnItem::getSaleItemId)
            .filter(id -> id != null).distinct().toList();
        Map<Long, ErpSaleItem> originals = loadSaleItems(saleItemIds);
        for (ErpSaleReturnItem row : items) {
            if (originals.get(row.getSaleItemId()) == null) {
                throw new ServiceException("原销售明细行不存在：" + row.getSaleItemId());
            }
        }
        Map<Long, BigDecimal> returnedByItem = loadReturnedQty(saleItemIds, head.getId());
        for (ErpSaleReturnItem row : items) {
            ErpSaleItem original = originals.get(row.getSaleItemId());
            BigDecimal returned = returnedByItem.getOrDefault(row.getSaleItemId(), BigDecimal.ZERO);
            BigDecimal returnable = original.getQty().subtract(returned);
            if (row.getQty().compareTo(returnable) > 0) {
                throw new ServiceException("累计退货数量超过原行可退数量（原行 " + original.getQty()
                    + "，已退 " + returned + "，本次 " + row.getQty() + "）", 40902);
            }
        }
        return originals;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public void withdraw(Long id) {
        // 单据头锁定读：加锁顺序 退货单头 → 原执行单头 → 订单头 → 订单行 → 台账 → 库存
        ErpSaleReturn head = returnMapper.lockById(id);
        if (head == null) {
            throw new ServiceException("退货单不存在");
        }
        if (!"CONFIRMED".equals(head.getStatus())) {
            throw new ServiceException("仅已确认单据可撤回", 40902);
        }
        // 先锁原执行单头，再由原执行单解析来源订单，之后才动台账与库存（DOC/31 §6.8.1 退货侧同样）
        ErpSale lockedSale = saleMapper.lockById(head.getSaleId());
        if (lockedSale == null || !lockedSale.getCustomerId().equals(head.getCustomerId())) {
            throw new ServiceException("原销售单不存在或往来单位不匹配", 40902);
        }
        Long sourceOrderId = lockedSale.getSourceOrderId();
        orderProgressService.lockOrder(ORDER_TYPE, sourceOrderId);
        // 顺序刚性（DOC/24 §6.4）：只按退货单可信外键撤回受控退款，再冲销红字台账与库存。
        if (head.getRefundReceiptId() != null) {
            receiptService.withdrawSaleReturnRefund(head.getId());
        }
        arApService.voidBySource("SALE_RETURN", head.getId());
        // P0-2：按单据整体冲销，同商品多行也只冲一次（DOC/31 §13.4）
        inventoryService.reverseByBill(head.getNo(), head.getId(), head.getBizDate());
        // 状态 CAS：只有 CONFIRMED 能撤回；refund_receipt_id 必须显式置 null 才能真正清空
        int rows = returnMapper.update(null, new LambdaUpdateWrapper<ErpSaleReturn>()
            .eq(ErpSaleReturn::getId, id).eq(ErpSaleReturn::getStatus, "CONFIRMED")
            .set(ErpSaleReturn::getStatus, "DRAFT")
            .set(ErpSaleReturn::getRefundReceiptId, null)
            .set(ErpSaleReturn::getReturnCostAmount, BigDecimal.ZERO)
            .set(ErpSaleReturn::getWithdrawnAt, new Date())
            .set(ErpSaleReturn::getWithdrawnBy, currentUserId()));
        if (rows == 0) {
            throw new ServiceException("单据状态已变更，请刷新后重试", 40902);
        }
        // 状态落库后重算：退货单状态已变为 DRAFT，无需 excludeBillId（它只排除同 ID 的执行单）
        orderProgressService.recalc(ORDER_TYPE, sourceOrderId, null);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public void voidBill(Long id) {
        ErpSaleReturn head = returnMapper.lockById(id);
        if (head == null) {
            throw new ServiceException("退货单不存在");
        }
        if ("VOID".equals(head.getStatus())) {
            throw new ServiceException("单据已作废");
        }
        // 草稿不再走作废（会留下无意义的作废痕迹），改走删除
        if ("DRAFT".equals(head.getStatus())) {
            throw new ServiceException("草稿单据请使用删除", 40902);
        }
        if ("CONFIRMED".equals(head.getStatus())) {
            withdraw(id);
            head = returnMapper.lockById(id);
            if (head == null) {
                throw new ServiceException("退货单不存在");
            }
        }
        // 状态 CAS：撤回后必为 DRAFT，只有它能落 VOID
        int rows = returnMapper.update(null, new LambdaUpdateWrapper<ErpSaleReturn>()
            .eq(ErpSaleReturn::getId, id).eq(ErpSaleReturn::getStatus, "DRAFT")
            .set(ErpSaleReturn::getStatus, "VOID")
            .set(ErpSaleReturn::getVoidedAt, new Date())
            .set(ErpSaleReturn::getVoidedBy, currentUserId()));
        if (rows == 0) {
            throw new ServiceException("单据状态已变更，请刷新后重试", 40902);
        }
        // 作废落库后再重算一次，覆盖「退货单作废后」这一触发时机（DOC/31 §6.1.3）
        orderProgressService.recalc(ORDER_TYPE, sourceOrderIdOf(head.getSaleId()), null);
    }

    private Long currentUserId() {
        try {
            return LoginHelper.getUserId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 删除草稿销售退货单：仅 DRAFT 可删。明细与主表均逻辑删除（del_flag=1），
     * 单号不回收；草稿不占原销售单可退数量，无需回滚原单。
     *
     * @param id 销售退货单ID
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public void deleteDraft(Long id) {
        ErpSaleReturn head = returnMapper.lockById(id);
        if (head == null) {
            throw new ServiceException("退货单不存在");
        }
        if (!"DRAFT".equals(head.getStatus())) {
            throw new ServiceException("仅草稿单据可删除", 40902);
        }
        itemMapper.delete(new LambdaQueryWrapper<ErpSaleReturnItem>()
            .eq(ErpSaleReturnItem::getOrderId, id));
        returnMapper.deleteById(id);
    }

    /**
     * 由原销售出库单反查来源销售订单ID：退货服务手上只有 sale_id，订单加锁前必须先走这一跳。
     *
     * @param saleId 原销售出库单ID，可空
     * @return 来源销售订单ID；原单不存在或直开单返回 null
     */
    private Long sourceOrderIdOf(Long saleId) {
        if (saleId == null) {
            return null;
        }
        ErpSale sale = saleMapper.selectById(saleId);
        return sale == null ? null : sale.getSourceOrderId();
    }
}
