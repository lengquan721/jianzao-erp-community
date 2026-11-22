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
import org.erp.master.domain.ErpProduct;
import org.erp.master.domain.ErpSupplier;
import org.erp.master.mapper.ErpProductMapper;
import org.erp.master.mapper.ErpSupplierMapper;
import org.erp.master.service.IErpWarehouseService;
import org.erp.stock.service.IInventoryService;
import org.erp.trade.domain.ErpPurchase;
import org.erp.trade.domain.ErpPurchaseItem;
import org.erp.trade.domain.ErpPurchaseReturn;
import org.erp.trade.domain.ErpPurchaseReturnItem;
import org.erp.trade.domain.bo.ErpPurchaseReturnBo;
import org.erp.trade.domain.vo.ErpPurchaseReturnVo;
import org.erp.trade.domain.vo.ErpReturnQtyAggregateVo;
import org.erp.trade.mapper.ErpPurchaseItemMapper;
import org.erp.trade.mapper.ErpPurchaseMapper;
import org.erp.trade.mapper.ErpPurchaseReturnItemMapper;
import org.erp.trade.mapper.ErpPurchaseReturnMapper;
import org.erp.trade.service.IErpOrderProgressService;
import org.erp.trade.service.IErpPurchaseReturnService;
import org.springframework.stereotype.Service;
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
 * 采购退货服务实现（DOC/24 §6.5，对称销售退货、无退款）
 *
 * - 确认：锁原采购单头 → 强校验累计可退 → 逐行 outbound(PURCHASE_RETURN, 原行 cost_price 快照)（防超卖）→
 *   负数 AP 行（金额=Σ qty×退货单价，不含原单分摊运杂费；差额视为沉没成本不回冲）；
 * - 撤回：VOID 负数行 → 库存 reverse（货已售出报 40901）。
 */
@RequiredArgsConstructor
@Service
public class ErpPurchaseReturnServiceImpl implements IErpPurchaseReturnService {

    /** 订单类型：采购（订单进度服务按此选表） */
    private static final String ORDER_TYPE = IErpOrderProgressService.TYPE_PURCHASE;

    private final ErpPurchaseReturnMapper returnMapper;
    private final ErpPurchaseReturnItemMapper itemMapper;
    private final ErpPurchaseMapper purchaseMapper;
    private final ErpPurchaseItemMapper purchaseItemMapper;
    private final ErpProductMapper productMapper;
    private final ErpSupplierMapper supplierMapper;
    private final IInventoryService inventoryService;
    private final IErpWarehouseService warehouseService;
    private final IFinArApService arApService;
    private final SequenceService sequenceService;
    private final IErpOrderProgressService orderProgressService;
    private final IFinOpeningGuardService openingGuardService;

    @Override
    public TableDataInfo<ErpPurchaseReturnVo> selectPage(String keyword, Long supplierId, String status, PageQuery pageQuery) {
        LambdaQueryWrapper<ErpPurchaseReturn> lqw = new LambdaQueryWrapper<>();
        lqw.eq(supplierId != null, ErpPurchaseReturn::getSupplierId, supplierId);
        lqw.eq(status != null && !status.isBlank(), ErpPurchaseReturn::getStatus, status);
        if (keyword != null && !keyword.isBlank()) {
            lqw.and(w -> w.like(ErpPurchaseReturn::getNo, keyword).or().like(ErpPurchaseReturn::getPurchaseNo, keyword));
        }
        lqw.orderByDesc(ErpPurchaseReturn::getId);
        Page<ErpPurchaseReturn> page = returnMapper.selectPage(pageQuery.build(), lqw);
        Page<ErpPurchaseReturnVo> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toVo).toList());
        return TableDataInfo.build(voPage);
    }

    @Override
    public List<ErpPurchaseReturnVo> returnableOrders(Long supplierId) {
        List<ErpPurchase> purchases = purchaseMapper.selectList(new LambdaQueryWrapper<ErpPurchase>()
            .eq(supplierId != null, ErpPurchase::getSupplierId, supplierId)
            .eq(ErpPurchase::getStatus, "CONFIRMED")
            .orderByDesc(ErpPurchase::getBizDate)
            .orderByDesc(ErpPurchase::getId));
        List<ErpPurchaseReturnVo> result = new ArrayList<>();
        for (ErpPurchase purchase : purchases) {
            ErpPurchaseReturnVo vo = new ErpPurchaseReturnVo();
            vo.setPurchaseId(purchase.getId());
            vo.setPurchaseNo(purchase.getNo());
            vo.setSupplierId(purchase.getSupplierId());
            vo.setBizDate(purchase.getBizDate());
            vo.setTotalAmount(purchase.getTotalAmount());
            result.add(vo);
        }
        return result;
    }

    @Override
    public List<ErpPurchaseReturnVo.ItemVo> originalItems(Long purchaseId) {
        ErpPurchase purchase = purchaseMapper.selectById(purchaseId);
        if (purchase == null) {
            throw new ServiceException("采购单不存在");
        }
        List<ErpPurchaseItem> items = purchaseItemMapper.selectList(new LambdaQueryWrapper<ErpPurchaseItem>()
            .eq(ErpPurchaseItem::getOrderId, purchaseId)
            .orderByAsc(ErpPurchaseItem::getSeq));
        Map<Long, ErpProduct> products = loadProducts(items.stream().map(ErpPurchaseItem::getProductId).toList());
        Map<Long, BigDecimal> returnedByItem = loadReturnedQty(items.stream().map(ErpPurchaseItem::getId).toList(), null);
        List<ErpPurchaseReturnVo.ItemVo> result = new ArrayList<>();
        for (ErpPurchaseItem item : items) {
            BigDecimal returned = returnedByItem.getOrDefault(item.getId(), BigDecimal.ZERO);
            ErpPurchaseReturnVo.ItemVo vo = new ErpPurchaseReturnVo.ItemVo();
            vo.setPurchaseItemId(item.getId());
            vo.setProductId(item.getProductId());
            vo.setPrice(item.getPrice());
            vo.setUnitCost(item.getCostPrice());
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
    public ErpPurchaseReturnVo selectById(Long id) {
        ErpPurchaseReturn head = returnMapper.selectById(id);
        if (head == null) {
            throw new ServiceException("退货单不存在");
        }
        ErpPurchaseReturnVo vo = toVo(head);
        vo.setItems(loadItems(id));
        return vo;
    }

    private ErpPurchaseReturnVo toVo(ErpPurchaseReturn head) {
        ErpPurchaseReturnVo vo = new ErpPurchaseReturnVo();
        vo.setId(head.getId());
        vo.setNo(head.getNo());
        vo.setPurchaseId(head.getPurchaseId());
        vo.setPurchaseNo(head.getPurchaseNo());
        vo.setSupplierId(head.getSupplierId());
        ErpSupplier s = supplierMapper.selectById(head.getSupplierId());
        vo.setSupplierName(s == null ? null : s.getName());
        vo.setBizDate(head.getBizDate());
        vo.setStatus(head.getStatus());
        vo.setStatusText(StatusText.bill(head.getStatus()));
        vo.setTotalAmount(head.getTotalAmount());
        vo.setReturnCostAmount(head.getReturnCostAmount());
        vo.setRemark(head.getRemark());
        vo.setCreateTime(head.getCreateTime());
        return vo;
    }

    private List<ErpPurchaseReturnVo.ItemVo> loadItems(Long orderId) {
        List<ErpPurchaseReturnItem> items = itemMapper.selectList(new LambdaQueryWrapper<ErpPurchaseReturnItem>()
            .eq(ErpPurchaseReturnItem::getOrderId, orderId)
            .orderByAsc(ErpPurchaseReturnItem::getSeq));
        Map<Long, ErpProduct> products = loadProducts(items.stream().map(ErpPurchaseReturnItem::getProductId).toList());
        List<Long> originalItemIds = items.stream().map(ErpPurchaseReturnItem::getPurchaseItemId)
            .filter(id -> id != null).distinct().toList();
        Map<Long, ErpPurchaseItem> originals = loadPurchaseItems(originalItemIds);
        Map<Long, BigDecimal> returnedByItem = loadReturnedQty(originalItemIds, null);
        List<ErpPurchaseReturnVo.ItemVo> result = new ArrayList<>();
        for (ErpPurchaseReturnItem row : items) {
            ErpPurchaseReturnVo.ItemVo vo = new ErpPurchaseReturnVo.ItemVo();
            vo.setId(row.getId());
            vo.setPurchaseItemId(row.getPurchaseItemId());
            vo.setProductId(row.getProductId());
            vo.setQty(row.getQty());
            vo.setPrice(row.getPrice());
            vo.setAmount(row.getAmount());
            vo.setUnitCost(row.getUnitCost());
            vo.setReturnCost(row.getReturnCost());
            vo.setRemark(row.getRemark());
            // 详情页需展示「原行数量 / 已退 / 可退」：按原采购行补算，口径与 originalItems 一致
            ErpPurchaseItem original = originals.get(row.getPurchaseItemId());
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

    private Map<Long, ErpPurchaseItem> loadPurchaseItems(Collection<Long> purchaseItemIds) {
        Map<Long, ErpPurchaseItem> originals = new HashMap<>();
        if (purchaseItemIds.isEmpty()) {
            return originals;
        }
        for (ErpPurchaseItem original : purchaseItemMapper.selectByIds(purchaseItemIds)) {
            originals.put(original.getId(), original);
        }
        return originals;
    }

    /** 当前租户下只累计未软删且单头 CONFIRMED 的原采购行退货数量。 */
    private Map<Long, BigDecimal> loadReturnedQty(Collection<Long> purchaseItemIds, Long excludeReturnId) {
        Map<Long, BigDecimal> returned = new HashMap<>();
        List<Long> ids = purchaseItemIds.stream().filter(id -> id != null).distinct().toList();
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
    @Transactional(rollbackFor = Exception.class)
    public ErpPurchaseReturnVo insert(ErpPurchaseReturnBo bo) {
        ErpPurchaseReturn head = new ErpPurchaseReturn();
        head.setNo(sequenceService.nextNo("CT"));
        saveHead(head, bo);
        saveItems(head, bo);
        if (Boolean.TRUE.equals(bo.getConfirm())) {
            confirmInternal(head);
        }
        return selectById(head.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpPurchaseReturnVo update(Long id, ErpPurchaseReturnBo bo) {
        ErpPurchaseReturn head = returnMapper.selectById(id);
        if (head == null) {
            throw new ServiceException("退货单不存在");
        }
        if (!"DRAFT".equals(head.getStatus())) {
            throw new ServiceException("仅草稿可编辑", 40902);
        }
        saveHead(head, bo);
        itemMapper.delete(new LambdaQueryWrapper<ErpPurchaseReturnItem>()
            .eq(ErpPurchaseReturnItem::getOrderId, id));
        saveItems(head, bo);
        if (Boolean.TRUE.equals(bo.getConfirm())) {
            confirmInternal(head);
        }
        return selectById(id);
    }

    private void saveHead(ErpPurchaseReturn head, ErpPurchaseReturnBo bo) {
        ErpPurchase purchase = purchaseMapper.selectById(bo.getPurchaseId());
        if (purchase == null) {
            throw new ServiceException("原采购单不存在");
        }
        if (!"CONFIRMED".equals(purchase.getStatus())) {
            throw new ServiceException("原采购单未确认，不能退货", 40902);
        }
        head.setPurchaseId(purchase.getId());
        head.setPurchaseNo(purchase.getNo());
        head.setSupplierId(purchase.getSupplierId());
        head.setBizDate(bo.getBizDate());
        head.setRemark(bo.getRemark());
        head.setStatus("DRAFT");
        if (head.getId() == null) {
            returnMapper.insert(head);
        } else {
            returnMapper.updateById(head);
        }
    }

    private void saveItems(ErpPurchaseReturn head, ErpPurchaseReturnBo bo) {
        Set<Long> purchaseItemIds = new HashSet<>();
        List<Long> originalItemIds = bo.getItems().stream().map(ErpPurchaseReturnBo.ItemBo::getPurchaseItemId)
            .filter(id -> id != null).distinct().toList();
        Map<Long, ErpPurchaseItem> originals = loadPurchaseItems(originalItemIds);
        BigDecimal amountTotal = BigDecimal.ZERO;
        int seq = 0;
        for (ErpPurchaseReturnBo.ItemBo item : bo.getItems()) {
            if (!purchaseItemIds.add(item.getPurchaseItemId())) {
                throw new ServiceException("明细中重复引用原采购行：" + item.getPurchaseItemId());
            }
            ErpPurchaseItem original = originals.get(item.getPurchaseItemId());
            if (original == null || !original.getOrderId().equals(head.getPurchaseId())) {
                throw new ServiceException("原采购明细行不属于该原单：" + item.getPurchaseItemId());
            }
            ErpPurchaseReturnItem row = new ErpPurchaseReturnItem();
            row.setOrderId(head.getId());
            row.setPurchaseItemId(original.getId());
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
    @Transactional(rollbackFor = Exception.class)
    public void confirm(Long id) {
        // 第一把锁必须是退货单自身单头（DOC/31 §6.8.2 防线 2）
        ErpPurchaseReturn head = returnMapper.lockById(id);
        if (head == null) {
            throw new ServiceException("退货单不存在");
        }
        if (!"DRAFT".equals(head.getStatus())) {
            throw new ServiceException("仅草稿可确认", 40902);
        }
        confirmInternal(head);
    }

    private void confirmInternal(ErpPurchaseReturn head) {
        // 锁自身单头（insert(confirm=true) 也要走这把锁），再锁原单头行与订单
        head = returnMapper.lockById(head.getId());
        if (head == null) {
            throw new ServiceException("退货单不存在");
        }
        // 锁内重读状态：update(confirm=true) 与并发确认同时到达时，后到的只能失败（§6.8.2 防线 2/3）
        if (!"DRAFT".equals(head.getStatus())) {
            throw new ServiceException("仅草稿可确认", 40902);
        }
        returnMapper.lockPurchaseForReturn(head.getPurchaseId());
        ErpPurchase purchase = purchaseMapper.selectById(head.getPurchaseId());
        if (purchase == null || !"CONFIRMED".equals(purchase.getStatus())) {
            throw new ServiceException("原采购单未确认或已撤回，不能退货", 40902);
        }
        // 解析来源订单：为 NULL 直接跳过订单逻辑；非空时先锁订单头、再按 id 升序锁订单行（DOC/31 §5.4 改动点 5、§6.8.1）
        Long sourceOrderId = purchase.getSourceOrderId();
        orderProgressService.lockOrder(ORDER_TYPE, sourceOrderId);
        List<ErpPurchaseReturnItem> items = itemMapper.selectList(new LambdaQueryWrapper<ErpPurchaseReturnItem>()
            .eq(ErpPurchaseReturnItem::getOrderId, head.getId())
            .orderByAsc(ErpPurchaseReturnItem::getSeq));
        Map<Long, ErpPurchaseItem> originalRows = checkReturnable(head, items);

        // 退货单头、原单头和订单头/行已依序加锁；先校验锁定退货单的日期，再处理库存与台账。
        openingGuardService.lockAndCheckBusinessDate("AP", head.getSupplierId(), head.getBizDate());

        // 库存回减：按原行 cost_price（含运杂费）快照出库，防超卖 40901；动库存前按 product_id 升序锁齐库存行
        inventoryService.lockStockRows(items.stream().map(ErpPurchaseReturnItem::getProductId).toList());
        int seq = 0;
        BigDecimal costTotal = BigDecimal.ZERO;
        for (ErpPurchaseReturnItem row : items) {
            ErpPurchaseItem original = originalRows.get(row.getPurchaseItemId());
            BigDecimal costPrice = original.getCostPrice() == null ? BigDecimal.ZERO : original.getCostPrice();
            inventoryService.outbound("PURCHASE_RETURN", head.getNo(), head.getId(), row.getProductId(),
                warehouseService.defaultWarehouseId(), row.getQty(), costPrice,
                "SUPPLIER", head.getSupplierId(), head.getBizDate(), seq++);
            row.setUnitCost(costPrice);
            row.setReturnCost(row.getQty().multiply(costPrice).setScale(2, RoundingMode.HALF_UP));
            itemMapper.updateById(row);
            costTotal = costTotal.add(row.getReturnCost());
        }
        head.setReturnCostAmount(costTotal.setScale(2, RoundingMode.HALF_UP));

        // 应付红冲：负数 AP 行（金额=退货货款，不含原单分摊运杂费，差额沉没不回冲）
        arApService.createByPurchaseReturn(head.getId(), head.getNo(), head.getSupplierId(),
            head.getTotalAmount().negate(), head.getBizDate());

        // 状态 CAS：只有 DRAFT 能改成 CONFIRMED，并发双击/跨节点时第二个请求返回 40902（§6.8.2 防线 3）
        head.setConfirmedAt(new Date());
        head.setConfirmedBy(currentUserId());
        int rows = returnMapper.update(null, new LambdaUpdateWrapper<ErpPurchaseReturn>()
            .eq(ErpPurchaseReturn::getId, head.getId()).eq(ErpPurchaseReturn::getStatus, "DRAFT")
            .set(ErpPurchaseReturn::getStatus, "CONFIRMED")
            .set(ErpPurchaseReturn::getReturnCostAmount, head.getReturnCostAmount())
            .set(ErpPurchaseReturn::getConfirmedAt, head.getConfirmedAt())
            .set(ErpPurchaseReturn::getConfirmedBy, head.getConfirmedBy()));
        if (rows == 0) {
            throw new ServiceException("单据状态已变更，请刷新后重试", 40902);
        }
        // 退货状态落库后重算订单执行量，净额 = 已确认执行 − 已确认退货（DOC/31 §6.1.3、§6.5）
        orderProgressService.recalc(ORDER_TYPE, sourceOrderId, null);
    }

    private Map<Long, ErpPurchaseItem> checkReturnable(ErpPurchaseReturn head, List<ErpPurchaseReturnItem> items) {
        List<Long> originalItemIds = items.stream().map(ErpPurchaseReturnItem::getPurchaseItemId)
            .filter(id -> id != null).distinct().toList();
        Map<Long, ErpPurchaseItem> originals = loadPurchaseItems(originalItemIds);
        for (ErpPurchaseReturnItem row : items) {
            if (originals.get(row.getPurchaseItemId()) == null) {
                throw new ServiceException("原采购明细行不存在：" + row.getPurchaseItemId());
            }
        }
        Map<Long, BigDecimal> returnedByItem = loadReturnedQty(originalItemIds, head.getId());
        for (ErpPurchaseReturnItem row : items) {
            ErpPurchaseItem original = originals.get(row.getPurchaseItemId());
            BigDecimal returned = returnedByItem.getOrDefault(row.getPurchaseItemId(), BigDecimal.ZERO);
            BigDecimal returnable = original.getQty().subtract(returned);
            if (row.getQty().compareTo(returnable) > 0) {
                throw new ServiceException("累计退货数量超过原行可退数量（原行 " + original.getQty()
                    + "，已退 " + returned + "，本次 " + row.getQty() + "）", 40902);
            }
        }
        return originals;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void withdraw(Long id) {
        // 单据头锁定读：加锁顺序 退货单头 → 原执行单头 → 订单头 → 订单行 → 台账 → 库存
        ErpPurchaseReturn head = returnMapper.lockById(id);
        if (head == null) {
            throw new ServiceException("退货单不存在");
        }
        if (!"CONFIRMED".equals(head.getStatus())) {
            throw new ServiceException("仅已确认单据可撤回", 40902);
        }
        // 先锁原执行单头，再由原执行单解析来源订单，之后才动台账与库存（DOC/31 §6.8.1 退货侧同样）
        returnMapper.lockPurchaseForReturn(head.getPurchaseId());
        Long sourceOrderId = sourceOrderIdOf(head.getPurchaseId());
        orderProgressService.lockOrder(ORDER_TYPE, sourceOrderId);
        arApService.voidBySource("PURCHASE_RETURN", head.getId());
        // P0-2：按单据整体冲销，同商品多行也只冲一次（DOC/31 §13.4）
        inventoryService.reverseByBill(head.getNo(), head.getId(), head.getBizDate());
        // 状态 CAS：只有 CONFIRMED 能撤回
        int rows = returnMapper.update(null, new LambdaUpdateWrapper<ErpPurchaseReturn>()
            .eq(ErpPurchaseReturn::getId, id).eq(ErpPurchaseReturn::getStatus, "CONFIRMED")
            .set(ErpPurchaseReturn::getStatus, "DRAFT")
            .set(ErpPurchaseReturn::getReturnCostAmount, BigDecimal.ZERO)
            .set(ErpPurchaseReturn::getWithdrawnAt, new Date())
            .set(ErpPurchaseReturn::getWithdrawnBy, currentUserId()));
        if (rows == 0) {
            throw new ServiceException("单据状态已变更，请刷新后重试", 40902);
        }
        // 状态落库后重算：退货单状态已变为 DRAFT，无需 excludeBillId（它只排除同 ID 的执行单）
        orderProgressService.recalc(ORDER_TYPE, sourceOrderId, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void voidBill(Long id) {
        ErpPurchaseReturn head = returnMapper.lockById(id);
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
        int rows = returnMapper.update(null, new LambdaUpdateWrapper<ErpPurchaseReturn>()
            .eq(ErpPurchaseReturn::getId, id).eq(ErpPurchaseReturn::getStatus, "DRAFT")
            .set(ErpPurchaseReturn::getStatus, "VOID")
            .set(ErpPurchaseReturn::getVoidedAt, new Date())
            .set(ErpPurchaseReturn::getVoidedBy, currentUserId()));
        if (rows == 0) {
            throw new ServiceException("单据状态已变更，请刷新后重试", 40902);
        }
        // 作废落库后再重算一次，覆盖「退货单作废后」这一触发时机（DOC/31 §6.1.3）
        orderProgressService.recalc(ORDER_TYPE, sourceOrderIdOf(head.getPurchaseId()), null);
    }

    private Long currentUserId() {
        try {
            return LoginHelper.getUserId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 删除草稿采购退货单：仅 DRAFT 可删。明细与主表均逻辑删除（del_flag=1），
     * 单号不回收；草稿不占原采购单可退数量，无需回滚原单。
     *
     * @param id 采购退货单ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDraft(Long id) {
        ErpPurchaseReturn head = returnMapper.selectById(id);
        if (head == null) {
            throw new ServiceException("退货单不存在");
        }
        if (!"DRAFT".equals(head.getStatus())) {
            throw new ServiceException("仅草稿单据可删除", 40902);
        }
        itemMapper.delete(new LambdaQueryWrapper<ErpPurchaseReturnItem>()
            .eq(ErpPurchaseReturnItem::getOrderId, id));
        returnMapper.deleteById(id);
    }

    /**
     * 由原采购入库单反查来源采购订单ID：退货服务手上只有 purchase_id，订单加锁前必须先走这一跳。
     *
     * @param purchaseId 原采购入库单ID，可空
     * @return 来源采购订单ID；原单不存在或直开单返回 null
     */
    private Long sourceOrderIdOf(Long purchaseId) {
        if (purchaseId == null) {
            return null;
        }
        ErpPurchase purchase = purchaseMapper.selectById(purchaseId);
        return purchase == null ? null : purchase.getSourceOrderId();
    }
}
