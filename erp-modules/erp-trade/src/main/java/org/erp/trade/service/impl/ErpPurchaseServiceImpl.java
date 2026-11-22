package org.erp.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.erp.common.enums.StatusText;
import org.erp.common.sequence.SequenceService;
import org.erp.fin.service.IFinArApService;
import org.erp.fin.service.IFinOpeningGuardService;
import org.erp.master.domain.ErpProduct;
import org.erp.master.domain.ErpSupplier;
import org.erp.master.mapper.ErpProductMapper;
import org.erp.master.mapper.ErpSupplierMapper;
import org.erp.master.service.IErpProductService;
import org.erp.master.service.IErpWarehouseService;
import org.erp.stock.service.IInventoryService;
import org.erp.trade.domain.ErpPurchase;
import org.erp.trade.domain.ErpPurchaseItem;
import org.erp.trade.domain.ErpPurchaseOrder;
import org.erp.trade.domain.ErpPurchaseOrderItem;
import org.erp.trade.domain.FreightAllocator;
import org.erp.trade.domain.bo.ErpPurchaseBo;
import org.erp.trade.domain.vo.ErpPurchaseVo;
import org.erp.trade.mapper.ErpPurchaseItemMapper;
import org.erp.trade.mapper.ErpPurchaseOrderItemMapper;
import org.erp.trade.mapper.ErpPurchaseOrderMapper;
import org.erp.trade.mapper.ErpPurchaseReturnMapper;
import org.erp.trade.mapper.ErpPurchaseMapper;
import org.erp.trade.service.IErpOrderProgressService;
import org.erp.trade.service.IErpPurchaseService;
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
import java.util.Objects;
import java.util.Set;

/**
 * 采购入库服务实现
 *
 * 规则（DOC/11 §8.1/8.2、DOC/02 D4/D5/R24、DOC/26 §6）：
 * - 发票类型/税率在表头整单统一，保存时继承写入明细快照；
 * - 运杂费固定按数量分摊（前台不再提供分摊方式），尾差入最后一行，Σ行=总额；
 * - 成本单价 = 采购价（增值税按不含税）+ 每件运杂费；
 * - 确认：库存增加（PURCHASE 流水）、应付由财务模块生成（I4 接入）；
 * - 确认后回写 md_product.cost_price 为最近到岸成本（仅参考）；
 * - 撤回：反向流水 + 流水重放重算加权成本。
 */
@RequiredArgsConstructor
@Service
public class ErpPurchaseServiceImpl implements IErpPurchaseService {

    /** 表头默认发票类型 */
    private static final String DEFAULT_TAX_TYPE = "VAT_INVOICE";
    /** 表头默认税率% */
    private static final BigDecimal DEFAULT_TAX_RATE = BigDecimal.valueOf(13);
    /** 运杂费固定按数量分摊 */
    private static final String FREIGHT_ALLOC_BY_QTY = "BY_QTY";

    private final ErpPurchaseMapper purchaseMapper;
    private final ErpPurchaseReturnMapper purchaseReturnMapper;
    private final ErpPurchaseItemMapper itemMapper;
    private final ErpPurchaseOrderMapper purchaseOrderMapper;
    private final ErpPurchaseOrderItemMapper purchaseOrderItemMapper;
    private final ErpProductMapper productMapper;
    private final ErpSupplierMapper supplierMapper;
    private final IInventoryService inventoryService;
    private final IErpProductService productService;
    private final IErpWarehouseService warehouseService;
    private final SequenceService sequenceService;
    private final IFinArApService finArApService;
    private final IErpOrderProgressService orderProgressService;
    private final IFinOpeningGuardService openingGuardService;

    @Override
    public TableDataInfo<ErpPurchaseVo> selectPage(String keyword, Long supplierId, String status, PageQuery pageQuery) {
        LambdaQueryWrapper<ErpPurchase> lqw = new LambdaQueryWrapper<>();
        lqw.eq(supplierId != null, ErpPurchase::getSupplierId, supplierId);
        lqw.eq(status != null && !status.isBlank(), ErpPurchase::getStatus, status);
        if (keyword != null && !keyword.isBlank()) {
            lqw.like(ErpPurchase::getNo, keyword);
        }
        lqw.orderByDesc(ErpPurchase::getId);
        Page<ErpPurchase> page = purchaseMapper.selectPage(pageQuery.build(), lqw);
        Page<ErpPurchaseVo> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        Map<Long, String> supplierNames = loadSupplierNames(page.getRecords());
        voPage.setRecords(page.getRecords().stream().map(h -> toVo(h, supplierNames)).toList());
        return TableDataInfo.build(voPage);
    }

    @Override
    public ErpPurchaseVo selectById(Long id) {
        ErpPurchase head = purchaseMapper.selectById(id);
        if (head == null) {
            throw new ServiceException("采购单不存在");
        }
        ErpPurchaseVo vo = toVo(head, loadSupplierNames(List.of(head)));
        vo.setItems(loadItems(id));
        return vo;
    }

    /**
     * 批量加载供应商名称，避免列表接口 N+1 查询。
     */
    private Map<Long, String> loadSupplierNames(Collection<ErpPurchase> heads) {
        Map<Long, String> names = new HashMap<>();
        List<Long> ids = heads.stream()
            .map(ErpPurchase::getSupplierId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (ids.isEmpty()) {
            return names;
        }
        for (ErpSupplier supplier : supplierMapper.selectByIds(ids)) {
            names.put(supplier.getId(), supplier.getName());
        }
        return names;
    }

    private ErpPurchaseVo toVo(ErpPurchase head, Map<Long, String> supplierNames) {
        ErpPurchaseVo vo = new ErpPurchaseVo();
        vo.setId(head.getId());
        vo.setNo(head.getNo());
        // 来源订单/联系人快照必须回填，前端据此显示「本单来自订单」并锁定行操作（DOC/31 D13、R1）
        vo.setSourceOrderId(head.getSourceOrderId());
        vo.setSourceOrderNo(head.getSourceOrderNo());
        vo.setContactName(head.getContactName());
        vo.setContactPhone(head.getContactPhone());
        vo.setSupplierId(head.getSupplierId());
        vo.setSupplierName(head.getSupplierId() == null ? null : supplierNames.get(head.getSupplierId()));
        vo.setBizDate(head.getBizDate());
        vo.setStatus(head.getStatus());
        vo.setStatusText(StatusText.bill(head.getStatus()));
        vo.setTaxType(head.getTaxType());
        vo.setTaxRate(head.getTaxRate());
        vo.setGoodsAmount(head.getGoodsAmount());
        vo.setFreightAmount(head.getFreightAmount());
        vo.setFreightAllocMethod(head.getFreightAllocMethod());
        vo.setTotalAmount(head.getTotalAmount());
        vo.setRemark(head.getRemark());
        vo.setCreateTime(head.getCreateTime());
        return vo;
    }

    private List<ErpPurchaseVo.ItemVo> loadItems(Long orderId) {
        List<ErpPurchaseItem> items = itemMapper.selectList(new LambdaQueryWrapper<ErpPurchaseItem>()
            .eq(ErpPurchaseItem::getOrderId, orderId)
            .orderByAsc(ErpPurchaseItem::getId));
        List<Long> productIds = items.stream().map(ErpPurchaseItem::getProductId)
            .filter(Objects::nonNull).filter(id -> id != 0L).distinct().toList();
        Map<Long, ErpProduct> products = new HashMap<>();
        if (!productIds.isEmpty()) {
            for (ErpProduct product : productMapper.selectByIds(productIds)) {
                products.put(product.getId(), product);
            }
        }
        List<ErpPurchaseVo.ItemVo> result = new ArrayList<>(items.size());
        for (ErpPurchaseItem row : items) {
            ErpPurchaseVo.ItemVo vo = new ErpPurchaseVo.ItemVo();
            vo.setId(row.getId());
            // 行级来源是订单进度聚合的唯一关联键，详情与列表都必须回传（DOC/31 D13）
            vo.setSourceOrderItemId(row.getSourceOrderItemId());
            vo.setProductId(row.getProductId());
            vo.setQty(row.getQty());
            vo.setPrice(row.getPrice());
            vo.setTaxType(row.getTaxType());
            vo.setTaxRate(row.getTaxRate());
            vo.setAmount(row.getAmount());
            vo.setFreightAmount(row.getFreightAmount());
            vo.setFreightPerUnit(row.getFreightPerUnit());
            vo.setCostPrice(row.getCostPrice());
            vo.setRemark(row.getRemark());
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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpPurchaseVo insert(ErpPurchaseBo bo) {
        ErpPurchase head = saveHead(bo, null);
        saveItems(head, bo);
        if (Boolean.TRUE.equals(bo.getConfirm())) {
            confirmInternal(head);
        }
        return selectById(head.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpPurchaseVo update(Long id, ErpPurchaseBo bo) {
        ErpPurchase head = purchaseMapper.selectById(id);
        if (head == null) {
            throw new ServiceException("采购单不存在");
        }
        if (!"DRAFT".equals(head.getStatus())) {
            throw new ServiceException("仅草稿可编辑", 40902);
        }
        // 判据取持久化值：已挂订单才走原地 upsert；直开单（含本次首次挂单）仍删旧行重插（D13）
        boolean attached = head.getSourceOrderId() != null;
        head = saveHead(bo, id);
        if (!attached) {
            itemMapper.delete(new LambdaQueryWrapper<ErpPurchaseItem>().eq(ErpPurchaseItem::getOrderId, id));
            saveItems(head, bo);
        } else {
            updateOrderItems(head, bo);
        }
        if (Boolean.TRUE.equals(bo.getConfirm())) {
            confirmInternal(head);
        }
        return selectById(id);
    }

    private ErpPurchase saveHead(ErpPurchaseBo bo, Long existId) {
        ErpPurchase head = existId == null ? new ErpPurchase() : purchaseMapper.selectById(existId);
        if (existId == null) {
            head.setNo(sequenceService.nextNo("CG"));
        }
        // D13：来源订单只在原值为空时写入，编辑保存不得用 null 覆盖已存的转单关联
        if (head.getSourceOrderId() == null && bo.getSourceOrderId() != null) {
            ErpPurchaseOrder order = purchaseOrderMapper.selectById(bo.getSourceOrderId());
            if (order == null) {
                throw new ServiceException("来源采购订单不存在：" + bo.getSourceOrderId(), 40905);
            }
            if (!"CONFIRMED".equals(order.getStatus())) {
                throw new ServiceException("来源采购订单未确认或已作废，不能入库", 40902);
            }
            // 供应商必须与订单一致，否则应付会记到错误的往来单位
            if (bo.getSupplierId() != null && !bo.getSupplierId().equals(order.getSupplierId())) {
                throw new ServiceException("挂订单的采购入库单供应商必须与订单一致", 40902);
            }
            head.setSourceOrderId(order.getId());
            head.setSourceOrderNo(order.getNo());
            // R1：联系人/电话随单快照，编辑保存不回查订单（DOC/31 §0.1）
            head.setContactName(order.getContactName());
            head.setContactPhone(order.getContactPhone());
        } else if (head.getSourceOrderId() != null && bo.getSourceOrderId() != null
            && !head.getSourceOrderId().equals(bo.getSourceOrderId())) {
            throw new ServiceException("已挂订单的单据不允许更换来源订单", 40902);
        }
        head.setSupplierId(bo.getSupplierId());
        head.setBizDate(bo.getBizDate());
        head.setTaxType(bo.getTaxType() == null ? DEFAULT_TAX_TYPE : bo.getTaxType());
        head.setTaxRate(bo.getTaxRate() == null ? DEFAULT_TAX_RATE : bo.getTaxRate());
        head.setFreightAmount(nvl(bo.getFreightAmount()));
        // 分摊方式固定按数量，前台不再提供选择（DOC/26 §6.3）
        head.setFreightAllocMethod(FREIGHT_ALLOC_BY_QTY);
        head.setRemark(bo.getRemark());
        head.setStatus("DRAFT");
        if (existId == null) {
            purchaseMapper.insert(head);
        } else {
            purchaseMapper.updateById(head);
        }
        return head;
    }

    private void saveItems(ErpPurchase head, ErpPurchaseBo bo) {
        // 挂订单时校验整行集：行级来源必须非空、不重复且属于该订单，否则拒绝保存（DOC/31 §6.7）
        Set<Long> orderItemIds = loadOrderItemIds(head.getSourceOrderId());
        Set<Long> submittedSourceIds = new HashSet<>();
        BigDecimal goodsTotal = BigDecimal.ZERO;
        List<ErpPurchaseItem> rows = new ArrayList<>();
        int seq = 0;
        for (ErpPurchaseBo.PurchaseItemBo item : bo.getItems()) {
            if (head.getSourceOrderId() != null) {
                Long sourceOrderItemId = item.getSourceOrderItemId();
                if (sourceOrderItemId == null) {
                    throw new ServiceException("挂订单的采购入库单不能手工加行，请删除该行或改成直开单", 40902);
                }
                if (!submittedSourceIds.add(sourceOrderItemId)) {
                    throw new ServiceException("明细中重复引用采购订单行：" + sourceOrderItemId, 40902);
                }
                if (!orderItemIds.contains(sourceOrderItemId)) {
                    throw new ServiceException("明细行不属于来源采购订单：" + sourceOrderItemId, 40902);
                }
            }
            ErpProduct product = productMapper.selectById(item.getProductId());
            if (product == null) {
                throw new ServiceException("产品不存在：" + item.getProductId());
            }
            if (item.getQty() == null || item.getQty().compareTo(BigDecimal.ZERO) <= 0) {
                throw new ServiceException("数量必须大于0");
            }
            BigDecimal amount = item.getQty().multiply(nvl(item.getPrice())).setScale(2, RoundingMode.HALF_UP);
            goodsTotal = goodsTotal.add(amount);

            ErpPurchaseItem row = new ErpPurchaseItem();
            row.setOrderId(head.getId());
            // 行级来源必须落库，订单进度按 source_order_item_id 聚合（DOC/31 §6.1.3）
            row.setSourceOrderItemId(item.getSourceOrderItemId());
            row.setProductId(item.getProductId());
            row.setQty(item.getQty());
            row.setPrice(item.getPrice());
            // 发票类型/税率由表头继承写入，明细列仅作历史快照
            row.setTaxType(head.getTaxType());
            row.setTaxRate(head.getTaxRate());
            row.setAmount(amount);
            row.setFreightAmount(BigDecimal.ZERO);
            row.setFreightPerUnit(BigDecimal.ZERO);
            row.setCostPrice(BigDecimal.ZERO);
            row.setRemark(item.getRemark());
            row.setSeq(seq++);
            rows.add(row);
        }

        BigDecimal freightTotal = nvl(bo.getFreightAmount());
        List<BigDecimal> freights = FreightAllocator.byQty(rows.stream().map(ErpPurchaseItem::getQty).toList(), freightTotal);
        for (int i = 0; i < rows.size(); i++) {
            ErpPurchaseItem row = rows.get(i);
            row.setFreightAmount(freights.get(i));
            BigDecimal fpu = row.getFreightAmount().divide(row.getQty(), 4, RoundingMode.HALF_UP);
            row.setFreightPerUnit(fpu);
            row.setCostPrice(lineCostPrice(row.getPrice(), row.getTaxType(), row.getTaxRate(), fpu));
            itemMapper.insert(row);
        }

        head.setGoodsAmount(goodsTotal.setScale(2, RoundingMode.HALF_UP));
        head.setTotalAmount(goodsTotal.add(freightTotal).setScale(2, RoundingMode.HALF_UP));
        purchaseMapper.updateById(head);
    }

    @Override
    public BigDecimal lineCostPrice(BigDecimal price, String taxType, BigDecimal taxRate, BigDecimal freightPerUnit) {
        BigDecimal base;
        if ("VAT_INVOICE".equals(taxType) && taxRate != null && taxRate.compareTo(BigDecimal.ZERO) > 0) {
            base = price.divide(BigDecimal.ONE.add(taxRate.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)), 6, RoundingMode.HALF_UP);
        } else {
            base = price;
        }
        return base.add(nvl(freightPerUnit)).setScale(4, RoundingMode.HALF_UP);
    }

    @Override
    public List<ErpPurchaseVo.ItemVo> previewAllocation(ErpPurchaseBo bo) {
        // 复用保存口径，不落库：税值取表头，运杂费固定按数量分摊
        String taxType = bo.getTaxType() == null ? DEFAULT_TAX_TYPE : bo.getTaxType();
        BigDecimal taxRate = bo.getTaxRate() == null ? DEFAULT_TAX_RATE : bo.getTaxRate();
        List<ErpPurchaseVo.ItemVo> result = new ArrayList<>();
        List<BigDecimal> qtys = new ArrayList<>();
        List<Long> productIds = bo.getItems().stream().map(ErpPurchaseBo.PurchaseItemBo::getProductId)
            .filter(Objects::nonNull).distinct().toList();
        Map<Long, ErpProduct> products = new HashMap<>();
        if (!productIds.isEmpty()) {
            for (ErpProduct product : productMapper.selectByIds(productIds)) {
                products.put(product.getId(), product);
            }
        }
        for (ErpPurchaseBo.PurchaseItemBo item : bo.getItems()) {
            ErpProduct product = products.get(item.getProductId());
            if (product == null) {
                throw new ServiceException("产品不存在：" + item.getProductId());
            }
            if (item.getQty() == null || item.getQty().compareTo(BigDecimal.ZERO) <= 0) {
                throw new ServiceException("数量必须大于0");
            }
            ErpPurchaseVo.ItemVo vo = new ErpPurchaseVo.ItemVo();
            vo.setProductId(item.getProductId());
            vo.setProductCode(product.getCode());
            vo.setProductName(product.getName());
            vo.setQty(item.getQty());
            vo.setPrice(item.getPrice());
            vo.setTaxType(taxType);
            vo.setTaxRate(taxRate);
            vo.setAmount(item.getQty().multiply(nvl(item.getPrice())).setScale(2, RoundingMode.HALF_UP));
            result.add(vo);
            qtys.add(item.getQty());
        }
        List<BigDecimal> freights = FreightAllocator.byQty(qtys, nvl(bo.getFreightAmount()));
        for (int i = 0; i < result.size(); i++) {
            ErpPurchaseVo.ItemVo vo = result.get(i);
            vo.setFreightAmount(freights.get(i));
            BigDecimal fpu = freights.get(i).divide(vo.getQty(), 4, RoundingMode.HALF_UP);
            vo.setFreightPerUnit(fpu);
            vo.setCostPrice(lineCostPrice(vo.getPrice(), vo.getTaxType(), vo.getTaxRate(), fpu));
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirm(Long id) {
        // 第一把锁必须是执行单自身单头（DOC/31 §6.8.2 防线 2）
        ErpPurchase head = purchaseMapper.lockById(id);
        if (head == null) {
            throw new ServiceException("采购单不存在");
        }
        if (!"DRAFT".equals(head.getStatus())) {
            throw new ServiceException("仅草稿可确认", 40902);
        }
        confirmInternal(head);
    }

    private void confirmInternal(ErpPurchase head) {
        // 第一把锁仍是执行单头（DOC/31 §6.8.1），保存并确认也从锁定行取值。
        head = purchaseMapper.lockById(head.getId());
        if (head == null) {
            throw new ServiceException("采购单不存在");
        }
        // 锁内重读状态：update(confirm=true) 与并发确认同时到达时，后到的只能失败（§6.8.2 防线 2/3）
        if (!"DRAFT".equals(head.getStatus())) {
            throw new ServiceException("仅草稿可确认", 40902);
        }
        List<ErpPurchaseItem> items = itemMapper.selectList(new LambdaQueryWrapper<ErpPurchaseItem>()
            .eq(ErpPurchaseItem::getOrderId, head.getId())
            .orderByAsc(ErpPurchaseItem::getSeq));
        // 确认前校验整行集、行归属与超收容差（DOC/31 §5.4 改动点 3、§6.7）
        orderProgressService.lockAndValidate(IErpOrderProgressService.TYPE_PURCHASE, head.getSourceOrderId(),
            items.stream().map(row -> new IErpOrderProgressService.ExecLine(row.getSourceOrderItemId(), row.getQty())).toList());
        // 沿用单头 → 订单头/行的顺序；在库存与台账写入前锁往来单位并校验期初。
        openingGuardService.lockAndCheckBusinessDate("AP", head.getSupplierId(), head.getBizDate());
        // 再按 product_id 升序一次性锁齐库存行，避免两张单商品顺序相反时成环（§6.8.1 规则 3）
        inventoryService.lockStockRows(items.stream().map(ErpPurchaseItem::getProductId).toList());
        int seq = 0;
        Date bizDate = head.getBizDate();
        for (ErpPurchaseItem row : items) {
            inventoryService.inbound("PURCHASE", head.getNo(), head.getId(),
                row.getProductId(), defaultWarehouseId(), row.getQty(), row.getCostPrice(),
                "SUPPLIER", head.getSupplierId(), bizDate, seq++);
        }
        // 状态 CAS：只有 DRAFT 能改成 CONFIRMED，并发双击/跨节点时第二个请求返回 40902（§6.8.2 防线 3）
        head.setConfirmedAt(new Date());
        head.setConfirmedBy(currentUserId());
        int rows = purchaseMapper.update(null, new UpdateWrapper<ErpPurchase>()
            .eq("id", head.getId()).eq("status", "DRAFT")
            .set("status", "CONFIRMED")
            .set("confirmed_at", head.getConfirmedAt())
            .set("confirmed_by", head.getConfirmedBy()));
        if (rows == 0) {
            throw new ServiceException("单据状态已变更，请刷新后重试", 40902);
        }
        // 内存实体同步 CAS 结果，后续按 head 组装返回值与日志时状态不落后
        head.setStatus("CONFIRMED");

        // 状态落库后重算订单执行进度，executed_qty/exec_status 只由 recalc 覆盖写（DOC/31 §6.1.3）
        orderProgressService.recalc(IErpOrderProgressService.TYPE_PURCHASE, head.getSourceOrderId(), null);

        // D4：回写最近到岸成本（仅参考）
        for (ErpPurchaseItem row : items) {
            productService.updateCostPrice(row.getProductId(), row.getCostPrice());
        }

        // 应付台账：AP = 货品 + 运杂费
        finArApService.createByPurchase(head.getId(), head.getNo(), head.getSupplierId(),
            head.getTotalAmount(), head.getBizDate());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void withdraw(Long id) {
        // 单据头锁定读：加锁顺序 单据头 → 订单头 → 订单行 → 台账 → 库存（DOC/31 §6.8.1）
        ErpPurchase head = purchaseMapper.lockById(id);
        if (head == null) {
            throw new ServiceException("采购单不存在");
        }
        if (!"CONFIRMED".equals(head.getStatus())) {
            throw new ServiceException("仅已确认单据可撤回", 40902);
        }
        // 入口先锁订单头，再按 id 升序锁订单行，之后才动台账与库存，避免与确认路径 ABBA
        orderProgressService.lockOrder(IErpOrderProgressService.TYPE_PURCHASE, head.getSourceOrderId());
        // 原单保护（DOC/24 §6.4）：存在非 VOID 关联退货单时不可撤回
        Long returnCount = purchaseReturnMapper.selectCount(new LambdaQueryWrapper<org.erp.trade.domain.ErpPurchaseReturn>()
            .eq(org.erp.trade.domain.ErpPurchaseReturn::getPurchaseId, id)
            .ne(org.erp.trade.domain.ErpPurchaseReturn::getStatus, "VOID"));
        if (returnCount != null && returnCount > 0) {
            throw new ServiceException("存在关联退货单，请先撤回或作废退货单", 40902);
        }
        // 应付已核销须先反核销（40904 由台账服务抛出）
        finArApService.voidBySource("PURCHASE", id);
        // P0-2：按单据整体冲销，同商品多行也只冲一次（DOC/31 §13.4）
        inventoryService.reverseByBill(head.getNo(), head.getId(), head.getBizDate());
        // 状态 CAS：只有 CONFIRMED 能撤回
        int rows = purchaseMapper.update(null, new UpdateWrapper<ErpPurchase>()
            .eq("id", id).eq("status", "CONFIRMED")
            .set("status", "DRAFT")
            .set("withdrawn_at", new Date())
            .set("withdrawn_by", currentUserId()));
        if (rows == 0) {
            throw new ServiceException("单据状态已变更，请刷新后重试", 40902);
        }

        // 状态落库后重算，把本单的已执行量从订单进度中扣回（DOC/31 §5.4 改动点 4）
        orderProgressService.recalc(IErpOrderProgressService.TYPE_PURCHASE, head.getSourceOrderId(), id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void voidBill(Long id) {
        ErpPurchase head = purchaseMapper.lockById(id);
        if (head == null) {
            throw new ServiceException("采购单不存在");
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
            head = purchaseMapper.lockById(id);
            if (head == null) {
                throw new ServiceException("采购单不存在");
            }
        }
        // 状态 CAS：撤回后必为 DRAFT，只有它能落 VOID
        int rows = purchaseMapper.update(null, new UpdateWrapper<ErpPurchase>()
            .eq("id", id).eq("status", "DRAFT")
            .set("status", "VOID")
            .set("voided_at", new Date())
            .set("voided_by", currentUserId()));
        if (rows == 0) {
            throw new ServiceException("单据状态已变更，请刷新后重试", 40902);
        }
        // 作废落库后再重算一次，覆盖「作废后」这一触发时机（DOC/31 §6.1.3）
        orderProgressService.recalc(IErpOrderProgressService.TYPE_PURCHASE, head.getSourceOrderId(), id);
    }

    private Long defaultWarehouseId() {
        return warehouseService.defaultWarehouseId();
    }

    private Long currentUserId() {
        try {
            return LoginHelper.getUserId();
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * 删除草稿采购单：仅 DRAFT 可删。明细与主表均逻辑删除（del_flag=1），
     * 单号不回收、不产生库存流水与应付台账。
     *
     * @param id 采购单ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDraft(Long id) {
        ErpPurchase head = purchaseMapper.selectById(id);
        if (head == null) {
            throw new ServiceException("采购单不存在");
        }
        if (!"DRAFT".equals(head.getStatus())) {
            throw new ServiceException("仅草稿单据可删除", 40902);
        }
        itemMapper.delete(new LambdaQueryWrapper<ErpPurchaseItem>().eq(ErpPurchaseItem::getOrderId, id));
        purchaseMapper.deleteById(id);
    }

    /**
     * 挂订单执行单的原地改行：行集合必须与已存行一一对应，只允许改数量/单价/备注，
     * 行 ID 保持稳定，退货行锚点与订单进度聚合不被破坏（DOC/31 D13、§5.4 改动点 2）。
     *
     * @param head 采购单头（来源订单已持久化）
     * @param bo   编辑入参
     */
    private void updateOrderItems(ErpPurchase head, ErpPurchaseBo bo) {
        List<ErpPurchaseItem> existItems = itemMapper.selectList(new LambdaQueryWrapper<ErpPurchaseItem>()
            .eq(ErpPurchaseItem::getOrderId, head.getId())
            .orderByAsc(ErpPurchaseItem::getSeq));
        Map<Long, ErpPurchaseItem> bySource = new HashMap<>();
        for (ErpPurchaseItem row : existItems) {
            if (row.getSourceOrderItemId() == null) {
                throw new ServiceException("执行单存在无来源订单行的明细，请删除后重新转单", 40902);
            }
            bySource.put(row.getSourceOrderItemId(), row);
        }
        if (bo.getItems().size() != existItems.size()) {
            throw new ServiceException("挂订单的执行单不能增删商品行", 40902);
        }
        Set<Long> orderItemIds = loadOrderItemIds(head.getSourceOrderId());
        List<ErpPurchaseItem> rows = new ArrayList<>();
        int seq = 0;
        for (ErpPurchaseBo.PurchaseItemBo item : bo.getItems()) {
            ErpPurchaseItem row = item.getSourceOrderItemId() == null ? null : bySource.remove(item.getSourceOrderItemId());
            if (row == null) {
                throw new ServiceException("执行单明细不属于该单据：" + item.getSourceOrderItemId(), 40902);
            }
            if (!orderItemIds.contains(item.getSourceOrderItemId())) {
                throw new ServiceException("明细行不属于来源采购订单：" + item.getSourceOrderItemId(), 40902);
            }
            if (!row.getProductId().equals(item.getProductId())) {
                throw new ServiceException("挂订单的执行单不能更换商品", 40902);
            }
            if (item.getQty() == null || item.getQty().compareTo(BigDecimal.ZERO) <= 0) {
                throw new ServiceException("数量必须大于0");
            }
            row.setQty(item.getQty());
            row.setPrice(item.getPrice());
            // 发票类型/税率由表头继承写入，明细列仅作历史快照
            row.setTaxType(head.getTaxType());
            row.setTaxRate(head.getTaxRate());
            row.setAmount(item.getQty().multiply(nvl(item.getPrice())).setScale(2, RoundingMode.HALF_UP));
            row.setRemark(item.getRemark());
            row.setSeq(seq++);
            rows.add(row);
        }
        if (!bySource.isEmpty()) {
            throw new ServiceException("挂订单的执行单不能增删商品行", 40902);
        }
        // 运杂费按数量重新分摊，成本单价同步重算（与 saveItems 同一口径）
        BigDecimal freightTotal = nvl(head.getFreightAmount());
        List<BigDecimal> freights = FreightAllocator.byQty(rows.stream().map(ErpPurchaseItem::getQty).toList(), freightTotal);
        BigDecimal goodsTotal = BigDecimal.ZERO;
        for (int i = 0; i < rows.size(); i++) {
            ErpPurchaseItem row = rows.get(i);
            row.setFreightAmount(freights.get(i));
            BigDecimal fpu = row.getFreightAmount().divide(row.getQty(), 4, RoundingMode.HALF_UP);
            row.setFreightPerUnit(fpu);
            row.setCostPrice(lineCostPrice(row.getPrice(), row.getTaxType(), row.getTaxRate(), fpu));
            itemMapper.updateById(row);
            goodsTotal = goodsTotal.add(row.getAmount());
        }
        head.setGoodsAmount(goodsTotal.setScale(2, RoundingMode.HALF_UP));
        head.setTotalAmount(goodsTotal.add(freightTotal).setScale(2, RoundingMode.HALF_UP));
        purchaseMapper.updateById(head);
    }

    /**
     * 查询来源采购订单的全部订单行ID，供保存时校验行归属。
     *
     * @param orderId 来源采购订单ID，可空
     * @return 订单行ID集合；无来源订单返回空集合
     */
    private Set<Long> loadOrderItemIds(Long orderId) {
        Set<Long> ids = new HashSet<>();
        if (orderId == null) {
            return ids;
        }
        for (ErpPurchaseOrderItem item : purchaseOrderItemMapper.selectList(new LambdaQueryWrapper<ErpPurchaseOrderItem>()
            .eq(ErpPurchaseOrderItem::getOrderId, orderId))) {
            ids.add(item.getId());
        }
        return ids;
    }
}
