package org.erp.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.erp.common.enums.StatusText;
import org.erp.common.sequence.SequenceService;
import org.erp.fin.service.IFinArApService;
import org.erp.fin.service.IFinOpeningGuardService;
import org.erp.master.domain.ErpCustomer;
import org.erp.master.domain.ErpProduct;
import org.erp.master.domain.bo.ErpProductBo;
import org.erp.master.mapper.ErpCustomerMapper;
import org.erp.master.mapper.ErpProductMapper;
import org.erp.master.service.IErpProductService;
import org.erp.master.service.IErpWarehouseService;
import org.erp.stock.service.IInventoryService;
import org.erp.trade.domain.ErpSale;
import org.erp.trade.domain.ErpSaleItem;
import org.erp.trade.domain.ErpSaleOrder;
import org.erp.trade.domain.ErpSaleOrderItem;
import org.erp.trade.domain.FreightAllocator;
import org.erp.trade.domain.bo.ErpSaleBo;
import org.erp.trade.domain.vo.ErpSaleVo;
import org.erp.trade.mapper.ErpSaleItemMapper;
import org.erp.trade.mapper.ErpSaleOrderItemMapper;
import org.erp.trade.mapper.ErpSaleOrderMapper;
import org.erp.trade.mapper.ErpSaleReturnMapper;
import org.erp.trade.mapper.ErpSaleMapper;
import org.erp.trade.service.IErpOrderProgressService;
import org.erp.trade.service.IErpSaleService;
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
 * 销售出库服务实现
 *
 * 规则：一单一价（price 不回写资料）；现录现卖（草稿只存商品快照，确认时建品+入库+出库）；
 * 发票类型/税率在表头整单统一，保存时继承写入明细快照；运杂费固定按数量分摊并计入成本；
 * 确认时按库存 avg_cost 快照成本，计算毛利/毛利率；撤回走库存 reverse。
 */
@RequiredArgsConstructor
@Service
public class ErpSaleServiceImpl implements IErpSaleService {

    /** 表头默认发票类型 */
    private static final String DEFAULT_TAX_TYPE = "VAT_INVOICE";
    /** 表头默认税率% */
    private static final BigDecimal DEFAULT_TAX_RATE = BigDecimal.valueOf(13);
    /** 运杂费固定按数量分摊 */
    private static final String FREIGHT_ALLOC_BY_QTY = "BY_QTY";

    /** 现录现卖快照 JSON 序列化（不依赖 Spring 上下文，便于单测） */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ErpSaleMapper saleMapper;
    private final ErpSaleReturnMapper saleReturnMapper;
    private final ErpSaleItemMapper itemMapper;
    private final ErpSaleOrderMapper saleOrderMapper;
    private final ErpSaleOrderItemMapper saleOrderItemMapper;
    private final ErpProductMapper productMapper;
    private final IErpProductService productService;
    private final ErpCustomerMapper customerMapper;
    private final IInventoryService inventoryService;
    private final IErpWarehouseService warehouseService;
    private final SequenceService sequenceService;
    private final IFinArApService finArApService;
    private final IErpOrderProgressService orderProgressService;
    private final IFinOpeningGuardService openingGuardService;

    @Override
    public TableDataInfo<ErpSaleVo> selectPage(String keyword, Long customerId, String status, PageQuery pageQuery) {
        LambdaQueryWrapper<ErpSale> lqw = new LambdaQueryWrapper<>();
        lqw.eq(customerId != null, ErpSale::getCustomerId, customerId);
        lqw.eq(status != null && !status.isBlank(), ErpSale::getStatus, status);
        if (keyword != null && !keyword.isBlank()) {
            lqw.like(ErpSale::getNo, keyword);
        }
        lqw.orderByDesc(ErpSale::getId);
        Page<ErpSale> page = saleMapper.selectPage(pageQuery.build(), lqw);
        Page<ErpSaleVo> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        Map<Long, String> customerNames = loadCustomerNames(page.getRecords());
        Map<Long, BigDecimal> estCosts = loadEstimatedCosts(page.getRecords());
        voPage.setRecords(page.getRecords().stream().map(h -> toVo(h, customerNames, estCosts)).toList());
        return TableDataInfo.build(voPage);
    }

    @Override
    public ErpSaleVo selectById(Long id) {
        ErpSale head = saleMapper.selectById(id);
        if (head == null) {
            throw new ServiceException("销售单不存在");
        }
        ErpSaleVo vo = toVo(head, loadCustomerNames(List.of(head)), loadEstimatedCosts(List.of(head)));
        vo.setItems(loadItems(id));
        return vo;
    }

    /**
     * 批量加载客户名称，避免列表接口 N+1 查询。
     */
    private Map<Long, String> loadCustomerNames(Collection<ErpSale> heads) {
        Map<Long, String> names = new HashMap<>();
        List<Long> ids = heads.stream()
            .map(ErpSale::getCustomerId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (ids.isEmpty()) {
            return names;
        }
        for (ErpCustomer customer : customerMapper.selectByIds(ids)) {
            names.put(customer.getId(), customer.getName());
        }
        return names;
    }

    /**
     * 草稿单预估成本（按确认同口径：当前库存均价 + 按数量分摊运杂费），仅回显不落库，确认后以快照为准。
     * 现录现卖行草稿阶段未建品，无法预估，按 0 计。
     */
    private Map<Long, BigDecimal> loadEstimatedCosts(Collection<ErpSale> heads) {
        Map<Long, BigDecimal> est = new HashMap<>();
        List<ErpSale> drafts = heads.stream().filter(h -> "DRAFT".equals(h.getStatus())).toList();
        if (drafts.isEmpty()) {
            return est;
        }
        List<Long> productIds = new ArrayList<>();
        Map<Long, List<ErpSaleItem>> draftItems = new HashMap<>();
        for (ErpSale head : drafts) {
            List<ErpSaleItem> items = itemMapper.selectList(new LambdaQueryWrapper<ErpSaleItem>()
                .eq(ErpSaleItem::getOrderId, head.getId())
                .orderByAsc(ErpSaleItem::getSeq));
            draftItems.put(head.getId(), items);
            for (ErpSaleItem row : items) {
                if (row.getProductId() != null && row.getProductId() != 0L) {
                    productIds.add(row.getProductId());
                }
            }
        }
        Map<Long, BigDecimal> avgCosts = productIds.isEmpty() ? Map.of() : inventoryService.snapshotCostBatch(productIds);
        for (ErpSale head : drafts) {
            List<ErpSaleItem> items = draftItems.get(head.getId());
            if (items.isEmpty()) {
                continue;
            }
            List<BigDecimal> freights = FreightAllocator.byQty(items.stream().map(ErpSaleItem::getQty).toList(),
                nvl(head.getFreightAmount()));
            BigDecimal costTotal = BigDecimal.ZERO;
            for (int i = 0; i < items.size(); i++) {
                ErpSaleItem row = items.get(i);
                BigDecimal avgCost = row.getProductId() == null || row.getProductId() == 0L
                    ? BigDecimal.ZERO : avgCosts.getOrDefault(row.getProductId(), BigDecimal.ZERO);
                BigDecimal freightPerUnit = row.getQty().compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                    : freights.get(i).divide(row.getQty(), 4, RoundingMode.HALF_UP);
                costTotal = costTotal.add(row.getQty().multiply(avgCost.add(freightPerUnit)).setScale(2, RoundingMode.HALF_UP));
            }
            est.put(head.getId(), costTotal.setScale(2, RoundingMode.HALF_UP));
        }
        return est;
    }

    private ErpSaleVo toVo(ErpSale head, Map<Long, String> customerNames, Map<Long, BigDecimal> estCosts) {
        ErpSaleVo vo = new ErpSaleVo();
        vo.setId(head.getId());
        vo.setNo(head.getNo());
        // 来源订单/联系人快照必须回填，前端据此显示「本单来自订单」并锁定行操作（DOC/31 D13、R1）
        vo.setSourceOrderId(head.getSourceOrderId());
        vo.setSourceOrderNo(head.getSourceOrderNo());
        vo.setContactName(head.getContactName());
        vo.setContactPhone(head.getContactPhone());
        vo.setDeliveryAddress(head.getDeliveryAddress());
        vo.setCustomerId(head.getCustomerId());
        vo.setCustomerName(head.getCustomerId() == null ? null : customerNames.get(head.getCustomerId()));
        vo.setBizDate(head.getBizDate());
        vo.setStatus(head.getStatus());
        vo.setStatusText(StatusText.bill(head.getStatus()));
        vo.setTaxType(head.getTaxType());
        vo.setTaxRate(head.getTaxRate());
        vo.setGoodsAmount(head.getGoodsAmount());
        vo.setFreightAmount(head.getFreightAmount());
        vo.setFreightAllocMethod(head.getFreightAllocMethod());
        vo.setTotalAmount(head.getTotalAmount());
        // 草稿单表头成本字段为 0，用预估成本回显（确认后以落库快照为准）
        boolean draft = "DRAFT".equals(head.getStatus());
        BigDecimal cost = draft ? estCosts.getOrDefault(head.getId(), BigDecimal.ZERO) : nvl(head.getCostAmount());
        vo.setCostAmount(cost);
        BigDecimal total = nvl(head.getTotalAmount());
        BigDecimal profit = total.subtract(cost);
        vo.setGrossProfit(profit.setScale(2, RoundingMode.HALF_UP));
        vo.setGrossMargin(total.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
            : profit.multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP));
        vo.setRemark(head.getRemark());
        vo.setCreateTime(head.getCreateTime());
        return vo;
    }

    private List<ErpSaleVo.ItemVo> loadItems(Long orderId) {
        List<ErpSaleItem> items = itemMapper.selectList(new LambdaQueryWrapper<ErpSaleItem>()
            .eq(ErpSaleItem::getOrderId, orderId)
            .orderByAsc(ErpSaleItem::getId));
        List<Long> productIds = items.stream().map(ErpSaleItem::getProductId)
            .filter(Objects::nonNull).filter(id -> id != 0L).distinct().toList();
        Map<Long, ErpProduct> products = new HashMap<>();
        if (!productIds.isEmpty()) {
            for (ErpProduct product : productMapper.selectByIds(productIds)) {
                products.put(product.getId(), product);
            }
        }
        List<ErpSaleVo.ItemVo> result = new ArrayList<>(items.size());
        for (ErpSaleItem row : items) {
            ErpSaleVo.ItemVo vo = new ErpSaleVo.ItemVo();
            vo.setId(row.getId());
            // 行级来源是订单进度聚合的唯一关联键，详情与列表都必须回传（DOC/31 D13）
            vo.setSourceOrderItemId(row.getSourceOrderItemId());
            vo.setProductId(row.getProductId());
            vo.setQty(row.getQty());
            vo.setPrice(row.getPrice());
            vo.setTaxType(row.getTaxType());
            vo.setTaxRate(row.getTaxRate());
            vo.setAmount(row.getAmount());
            vo.setUnitCost(row.getUnitCost());
            vo.setCostAmount(row.getCostAmount());
            vo.setGrossProfit(row.getGrossProfit());
            vo.setRemark(row.getRemark());
            ErpProduct p = products.get(row.getProductId());
            if (p != null) {
                vo.setProductCode(p.getCode());
                vo.setProductName(p.getName());
                vo.setSpec(p.getSpec());
                vo.setUnit(p.getUnit());
                vo.setQtyScale(p.getQtyScale());
            }
            if (StringUtils.isNotBlank(row.getNewProductSnapshot())) {
                // 现录现卖快照必须始终回传：确认后商品已回填，撤回再编辑保存仍靠它恢复 QUICK_IN 入库（DOC/26 §8.3）
                ErpSaleBo.NewProduct np = readSnapshot(row.getNewProductSnapshot());
                vo.setNewProduct(np);
                if (p == null) {
                    // 待建品：商品尚未创建（草稿），用快照回显名称/规格/单位
                    vo.setProductName(np.getName());
                    vo.setSpec(np.getSpec());
                    vo.setUnit(np.getUnit());
                    vo.setQtyScale(np.getQtyScale());
                }
            }
            result.add(vo);
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpSaleVo insert(ErpSaleBo bo) {
        ErpSale head = saveHead(bo, null);
        saveItems(head, bo);
        if (Boolean.TRUE.equals(bo.getConfirm())) {
            return confirmInternal(head);
        }
        return selectById(head.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpSaleVo update(Long id, ErpSaleBo bo) {
        ErpSale head = saleMapper.selectById(id);
        if (head == null) {
            throw new ServiceException("销售单不存在");
        }
        if (!"DRAFT".equals(head.getStatus())) {
            throw new ServiceException("仅草稿可编辑", 40902);
        }
        // 判据取持久化值：已挂订单才走原地 upsert；直开单（含本次首次挂单）仍删旧行重插（D13）
        boolean attached = head.getSourceOrderId() != null;
        head = saveHead(bo, id);
        if (!attached) {
            itemMapper.delete(new LambdaQueryWrapper<ErpSaleItem>().eq(ErpSaleItem::getOrderId, id));
            saveItems(head, bo);
        } else {
            updateOrderItems(head, bo);
        }
        if (Boolean.TRUE.equals(bo.getConfirm())) {
            return confirmInternal(head);
        }
        return selectById(id);
    }

    private ErpSale saveHead(ErpSaleBo bo, Long existId) {
        ErpSale head = existId == null ? new ErpSale() : saleMapper.selectById(existId);
        if (existId == null) {
            head.setNo(sequenceService.nextNo("XS"));
        }
        // D13：来源订单只在原值为空时写入，编辑保存不得用 null 覆盖已存的转单关联
        if (head.getSourceOrderId() == null && bo.getSourceOrderId() != null) {
            ErpSaleOrder order = saleOrderMapper.selectById(bo.getSourceOrderId());
            if (order == null) {
                throw new ServiceException("来源销售订单不存在：" + bo.getSourceOrderId(), 40905);
            }
            if (!"CONFIRMED".equals(order.getStatus())) {
                throw new ServiceException("来源销售订单未确认或已作废，不能出库", 40902);
            }
            // 客户必须与订单一致，否则应收会记到错误的往来单位
            if (bo.getCustomerId() != null && !bo.getCustomerId().equals(order.getCustomerId())) {
                throw new ServiceException("挂订单的销售出库单客户必须与订单一致", 40902);
            }
            head.setSourceOrderId(order.getId());
            head.setSourceOrderNo(order.getNo());
            // R1：联系人/电话/送货地址随单快照，编辑保存不回查订单（DOC/31 §0.1）
            head.setContactName(order.getContactName());
            head.setContactPhone(order.getContactPhone());
            head.setDeliveryAddress(order.getDeliveryAddress());
        } else if (head.getSourceOrderId() != null && bo.getSourceOrderId() != null
            && !head.getSourceOrderId().equals(bo.getSourceOrderId())) {
            throw new ServiceException("已挂订单的单据不允许更换来源订单", 40902);
        }
        head.setCustomerId(bo.getCustomerId());
        head.setBizDate(bo.getBizDate());
        String taxType = bo.getTaxType() == null ? DEFAULT_TAX_TYPE : bo.getTaxType();
        if (!"NO_TAX".equals(taxType) && !"VAT_INVOICE".equals(taxType) && !"GENERAL_INVOICE".equals(taxType)) {
            throw new ServiceException("发票类型不合法");
        }
        BigDecimal taxRate = bo.getTaxRate() == null ? DEFAULT_TAX_RATE : bo.getTaxRate();
        if (taxRate.signum() < 0 || taxRate.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new ServiceException("税率必须在 0~100 之间");
        }
        BigDecimal freightAmount = nvl(bo.getFreightAmount());
        if (freightAmount.signum() < 0) {
            throw new ServiceException("运杂费不能为负");
        }
        head.setTaxType(taxType);
        head.setTaxRate(taxRate);
        head.setFreightAmount(freightAmount);
        // 分摊方式固定按数量，前台不再提供选择（DOC/26 §6.3）
        head.setFreightAllocMethod(FREIGHT_ALLOC_BY_QTY);
        head.setRemark(bo.getRemark());
        head.setStatus("DRAFT");
        if (existId == null) {
            saleMapper.insert(head);
        } else {
            saleMapper.updateById(head);
        }
        return head;
    }

    /**
     * 写明细并汇总表头金额。现录现卖行只落商品快照（product_id=0），不建商品、不动库存，
     * 建品/入库统一挪到确认时执行，因此草稿反复保存不会重复建品（DOC/26 §8.3）。
     */
    private void saveItems(ErpSale head, ErpSaleBo bo) {
        // 挂订单时校验整行集：行级来源必须非空且属于该订单，否则拒绝保存（DOC/31 §6.7）
        Set<Long> orderItemIds = loadOrderItemIds(head.getSourceOrderId());
        Set<Long> submittedSourceIds = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        int seq = 0;
        for (ErpSaleBo.SaleItemBo item : bo.getItems()) {
            if (head.getSourceOrderId() != null) {
                Long sourceOrderItemId = item.getSourceOrderItemId();
                if (sourceOrderItemId == null) {
                    throw new ServiceException("挂订单的销售出库单不能手工加行，请删除该行或改成直开单", 40902);
                }
                if (!submittedSourceIds.add(sourceOrderItemId)) {
                    throw new ServiceException("明细中重复引用销售订单行：" + sourceOrderItemId, 40902);
                }
                if (!orderItemIds.contains(sourceOrderItemId)) {
                    throw new ServiceException("明细行不属于来源销售订单：" + sourceOrderItemId, 40902);
                }
            }
            if (item.getQty() == null || item.getQty().compareTo(BigDecimal.ZERO) <= 0) {
                throw new ServiceException("数量必须大于0");
            }
            BigDecimal price = item.getPrice();
            if (price == null || price.compareTo(BigDecimal.ZERO) < 0) {
                throw new ServiceException("销售单价不能为空");
            }
            ErpSaleItem row = new ErpSaleItem();
            row.setOrderId(head.getId());
            // 行级来源必须落库，订单进度按 source_order_item_id 聚合（DOC/31 §6.1.3）
            row.setSourceOrderItemId(item.getSourceOrderItemId());
            Long productId = item.getProductId();
            ErpSaleBo.NewProduct np = item.getNewProduct();
            if (productId != null && productId != 0L) {
                ErpProduct product = productMapper.selectById(productId);
                if (product == null) {
                    throw new ServiceException("产品不存在：" + productId);
                }
                if (!"PRODUCT".equals(product.getType())) {
                    throw new ServiceException("销售出库只能销售成品：" + product.getName());
                }
            } else if (np == null || StringUtils.isBlank(np.getName())) {
                throw new ServiceException("现录现卖需提供产品信息");
            }
            // 现录现卖行 product_id=0；撤回后重新保存时商品已存在，保留回填的 product_id 与快照
            row.setProductId(productId == null ? 0L : productId);
            if (np != null) {
                if (np.getCostPrice() != null && np.getCostPrice().signum() < 0) {
                    throw new ServiceException("现录现卖成本单价不能为负");
                }
                BigDecimal inQty = np.getQty() == null ? item.getQty() : np.getQty();
                if (inQty.compareTo(item.getQty()) < 0) {
                    throw new ServiceException("现录现卖「" + np.getName() + "」入库数量不能小于销售数量");
                }
                np.setQty(inQty);
                row.setNewProductSnapshot(writeSnapshot(np));
            }
            row.setQty(item.getQty());
            row.setPrice(price);
            // 发票类型/税率由表头继承写入，明细列仅作快照
            row.setTaxType(head.getTaxType());
            row.setTaxRate(head.getTaxRate());
            row.setAmount(item.getQty().multiply(price).setScale(2, RoundingMode.HALF_UP));
            row.setUnitCost(BigDecimal.ZERO);
            row.setCostAmount(BigDecimal.ZERO);
            row.setGrossProfit(BigDecimal.ZERO);
            row.setRemark(item.getRemark());
            row.setSeq(seq++);
            itemMapper.insert(row);

            total = total.add(row.getAmount());
        }
        head.setGoodsAmount(total.setScale(2, RoundingMode.HALF_UP));
        head.setTotalAmount(total.setScale(2, RoundingMode.HALF_UP));
        saleMapper.updateById(head);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpSaleVo confirm(Long id) {
        // 第一把锁必须是执行单自身单头（DOC/31 §6.8.2 防线 2）
        ErpSale head = saleMapper.lockById(id);
        if (head == null) {
            throw new ServiceException("销售单不存在");
        }
        if (!"DRAFT".equals(head.getStatus())) {
            throw new ServiceException("仅草稿可确认", 40902);
        }
        return confirmInternal(head);
    }

    /**
     * 确认内部实现：按 avg_cost + 每件运杂费冻结每行成本、写 SALE 出库流水、汇总毛利并生成应收。
     * @param head 销售单头
     * @return 确认后的单据；costWarnings 为成本为 0 的商品名（仅提示，不阻断）
     */
    private ErpSaleVo confirmInternal(ErpSale head) {
        // 第一把锁仍是执行单头（DOC/31 §6.8.1），保存并确认也从锁定行取值。
        head = saleMapper.lockById(head.getId());
        if (head == null) {
            throw new ServiceException("销售单不存在");
        }
        // 锁内重读状态：update(confirm=true) 与并发确认同时到达时，后到的只能失败（§6.8.2 防线 2/3）
        if (!"DRAFT".equals(head.getStatus())) {
            throw new ServiceException("仅草稿可确认", 40902);
        }
        List<ErpSaleItem> items = itemMapper.selectList(new LambdaQueryWrapper<ErpSaleItem>()
            .eq(ErpSaleItem::getOrderId, head.getId())
            .orderByAsc(ErpSaleItem::getSeq));
        // 确认前校验整行集、行归属与超发容差（DOC/31 §5.4 改动点 3、§6.7）
        orderProgressService.lockAndValidate(IErpOrderProgressService.TYPE_SALE, head.getSourceOrderId(),
            items.stream().map(row -> new IErpOrderProgressService.ExecLine(row.getSourceOrderItemId(), row.getQty())).toList());
        // 沿用单头 → 订单头/行的顺序；在库存与台账写入前锁往来单位并校验期初。
        openingGuardService.lockAndCheckBusinessDate("AR", head.getCustomerId(), head.getBizDate());
        // 再按 product_id 升序一次性锁齐库存行，避免两张单商品顺序相反时成环（§6.8.1 规则 3）
        inventoryService.lockStockRows(items.stream().map(ErpSaleItem::getProductId)
            .filter(productId -> productId != null && productId != 0L).toList());
        BigDecimal costTotal = BigDecimal.ZERO;
        List<String> costWarnings = new ArrayList<>();
        // 表头运杂费按数量分摊到行，每件运杂费计入销售成本（DOC/26 §6.5，与采购同一分摊算法）
        List<BigDecimal> freights = FreightAllocator.byQty(items.stream().map(ErpSaleItem::getQty).toList(),
            nvl(head.getFreightAmount()));
        int seq = 0;
        for (int i = 0; i < items.size(); i++) {
            ErpSaleItem row = items.get(i);
            // 现录现卖：快照行每次确认都补入库（撤回会反向冲销入库），首代确认时才建品
            Long productId = StringUtils.isNotBlank(row.getNewProductSnapshot())
                ? confirmQuickProduct(head, row, seq) : row.getProductId();
            // 用出库流水同一份锁定均价，不再按旧事务快照单独读取销售行成本。
            BigDecimal snapshotCost = inventoryService.outbound("SALE", head.getNo(), head.getId(),
                productId, defaultWarehouseId(), row.getQty(),
                "CUSTOMER", head.getCustomerId(), head.getBizDate(), seq++);
            // 库存均价本身为不含税口径，直接加计每件运杂费，不做含税还原（与采购 lineCostPrice 的 NO_TAX 分支一致）
            BigDecimal freightPerUnit = row.getQty().compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                : freights.get(i).divide(row.getQty(), 4, RoundingMode.HALF_UP);
            BigDecimal unitCost = snapshotCost.add(freightPerUnit).setScale(4, RoundingMode.HALF_UP);
            BigDecimal costAmount = row.getQty().multiply(unitCost).setScale(2, RoundingMode.HALF_UP);
            BigDecimal profit = row.getAmount().subtract(costAmount);

            row.setUnitCost(unitCost);
            row.setCostAmount(costAmount);
            row.setGrossProfit(profit);
            itemMapper.updateById(row);

            costTotal = costTotal.add(costAmount);
            // 成本锚点缺失（DOC/26 §2.4 防线②）：记录商品名随结果回传，允许继续确认
            if (snapshotCost.compareTo(BigDecimal.ZERO) == 0) {
                ErpProduct p = productMapper.selectById(productId);
                costWarnings.add(p == null ? String.valueOf(productId) : p.getName());
            }
        }
        BigDecimal total = head.getTotalAmount() == null ? BigDecimal.ZERO : head.getTotalAmount();
        BigDecimal profit = total.subtract(costTotal);
        head.setCostAmount(costTotal.setScale(2, RoundingMode.HALF_UP));
        head.setGrossProfit(profit.setScale(2, RoundingMode.HALF_UP));
        head.setGrossMargin(total.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
            : profit.multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP));
        // 状态 CAS：只有 DRAFT 能改成 CONFIRMED，并发双击/跨节点时第二个请求返回 40902（§6.8.2 防线 3）
        head.setConfirmedAt(new Date());
        head.setConfirmedBy(currentUserId());
        int rows = saleMapper.update(null, new UpdateWrapper<ErpSale>()
            .eq("id", head.getId()).eq("status", "DRAFT")
            .set("status", "CONFIRMED")
            .set("cost_amount", head.getCostAmount())
            .set("gross_profit", head.getGrossProfit())
            .set("gross_margin", head.getGrossMargin())
            .set("confirmed_at", head.getConfirmedAt())
            .set("confirmed_by", head.getConfirmedBy()));
        if (rows == 0) {
            throw new ServiceException("单据状态已变更，请刷新后重试", 40902);
        }
        // 内存实体同步 CAS 结果，后续按 head 组装返回值与日志时状态不落后
        head.setStatus("CONFIRMED");

        // 状态落库后重算订单执行进度，executed_qty/exec_status 只由 recalc 覆盖写（DOC/31 §6.1.3）
        orderProgressService.recalc(IErpOrderProgressService.TYPE_SALE, head.getSourceOrderId(), null);

        // 应收台账：AR = 销售金额
        finArApService.createBySale(head.getId(), head.getNo(), head.getCustomerId(),
            head.getTotalAmount(), head.getBizDate());

        ErpSaleVo vo = selectById(head.getId());
        vo.setCostWarnings(costWarnings);
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void withdraw(Long id) {
        // 单据头锁定读：加锁顺序 单据头 → 订单头 → 订单行 → 台账 → 库存（DOC/31 §6.8.1）
        ErpSale head = saleMapper.lockById(id);
        if (head == null) {
            throw new ServiceException("销售单不存在");
        }
        if (!"CONFIRMED".equals(head.getStatus())) {
            throw new ServiceException("仅已确认单据可撤回", 40902);
        }
        // 入口先锁订单头，再按 id 升序锁订单行，之后才动台账与库存，避免与确认路径 ABBA
        orderProgressService.lockOrder(IErpOrderProgressService.TYPE_SALE, head.getSourceOrderId());
        // 原单保护（DOC/24 §6.4）：存在非 VOID 关联退货单时不可撤回，防止退货成本快照失去锚点
        Long returnCount = saleReturnMapper.selectCount(new LambdaQueryWrapper<org.erp.trade.domain.ErpSaleReturn>()
            .eq(org.erp.trade.domain.ErpSaleReturn::getSaleId, id)
            .ne(org.erp.trade.domain.ErpSaleReturn::getStatus, "VOID"));
        if (returnCount != null && returnCount > 0) {
            throw new ServiceException("存在关联退货单，请先撤回或作废退货单", 40902);
        }
        // 已核销应收需先反核销（40904 由台账服务抛出）
        finArApService.voidBySource("SALE", id);
        // P0-2：按单据整体冲销，同商品多行也只冲一次（DOC/31 §13.4）
        inventoryService.reverseByBill(head.getNo(), head.getId(), head.getBizDate());
        // 状态 CAS：只有 CONFIRMED 能撤回
        int rows = saleMapper.update(null, new UpdateWrapper<ErpSale>()
            .eq("id", id).eq("status", "CONFIRMED")
            .set("status", "DRAFT")
            .set("cost_amount", BigDecimal.ZERO)
            .set("gross_profit", BigDecimal.ZERO)
            .set("gross_margin", BigDecimal.ZERO)
            .set("withdrawn_at", new Date())
            .set("withdrawn_by", currentUserId()));
        if (rows == 0) {
            throw new ServiceException("单据状态已变更，请刷新后重试", 40902);
        }

        // 状态落库后重算，把本单的已执行量从订单进度中扣回（DOC/31 §5.4 改动点 4）
        orderProgressService.recalc(IErpOrderProgressService.TYPE_SALE, head.getSourceOrderId(), id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void voidBill(Long id) {
        ErpSale head = saleMapper.lockById(id);
        if (head == null) {
            throw new ServiceException("销售单不存在");
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
            head = saleMapper.lockById(id);
            if (head == null) {
                throw new ServiceException("销售单不存在");
            }
        }
        // 状态 CAS：撤回后必为 DRAFT，只有它能落 VOID
        int rows = saleMapper.update(null, new UpdateWrapper<ErpSale>()
            .eq("id", id).eq("status", "DRAFT")
            .set("status", "VOID")
            .set("voided_at", new Date())
            .set("voided_by", currentUserId()));
        if (rows == 0) {
            throw new ServiceException("单据状态已变更，请刷新后重试", 40902);
        }
        // 作废落库后再重算一次，覆盖「作废后」这一触发时机（DOC/31 §6.1.3）
        orderProgressService.recalc(IErpOrderProgressService.TYPE_SALE, head.getSourceOrderId(), id);
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
     * 现录现卖确认：首代按明细快照建商品（编码自动）并回填 product_id，每次确认都按入库数量入库（QUICK_IN）。
     * 撤回时入库已被反向冲销，重新确认必须再入库一次，才能正常出库。
     * @param head 销售单头，提供单号/单据id/业务日期写库存流水
     * @param row  销售明细行，含 new_product_snapshot 与可能已回填的 product_id
     * @param seq  单据内行序，用于库存流水排序
     * @return 商品ID
     */
    private Long confirmQuickProduct(ErpSale head, ErpSaleItem row, int seq) {
        ErpSaleBo.NewProduct np = readSnapshot(row.getNewProductSnapshot());
        Long productId = row.getProductId();
        if (productId == null || productId == 0L) {
            // 建品统一走商品资料同一套逻辑：编码留空按名称自动生成、编码唯一校验、库存上下限校验
            ErpProductBo bo = new ErpProductBo();
            bo.setCode(StringUtils.isBlank(np.getCode()) ? null : np.getCode());
            bo.setName(np.getName());
            bo.setSpec(np.getSpec());
            bo.setUnit(StringUtils.isBlank(np.getUnit()) ? "件" : np.getUnit());
            bo.setType("PRODUCT");
            bo.setCategoryId(np.getCategoryId());
            bo.setSupplierId(np.getSupplierId());
            bo.setQtyScale(np.getQtyScale() == null ? 0 : np.getQtyScale());
            bo.setTaxType(np.getTaxType() == null ? "NO_TAX" : np.getTaxType());
            bo.setPurchasePrice(np.getPurchasePrice());
            bo.setFreight(np.getFreight());
            bo.setMinStock(np.getMinStock());
            bo.setMaxStock(np.getMaxStock());
            bo.setRemark(np.getRemark());
            bo.setStatus(1);
            productId = productService.insert(bo);
            row.setProductId(productId);
        }
        // 先入库后出库：入库数量默认等于销售数量，可改大（多进的留在库存），不允许负库存
        BigDecimal inQty = np.getQty() == null ? row.getQty() : np.getQty();
        BigDecimal cost = np.getCostPrice() == null ? BigDecimal.ZERO : np.getCostPrice();
        inventoryService.inbound("QUICK_IN", head.getNo(), head.getId(), productId, defaultWarehouseId(),
            inQty, cost, "NONE", null, head.getBizDate(), seq);
        // 建品时成本单价被 insert 归零（该列只由入库回写），入库后按本次入库成本回写，与其他入库口径一致
        productService.updateCostPrice(productId, cost);
        return productId;
    }

    /**
     * 现录现卖快照序列化，写入 biz_sale_item.new_product_snapshot。
     * @param np 现录现卖商品信息（含入库数量）
     * @return JSON 字符串
     */
    private String writeSnapshot(ErpSaleBo.NewProduct np) {
        try {
            return JSON.writeValueAsString(np);
        } catch (JsonProcessingException e) {
            throw new ServiceException("现录现卖商品快照序列化失败：" + np.getName());
        }
    }

    /**
     * 现录现卖快照反序列化。
     * @param json new_product_snapshot 列值
     * @return 现录现卖商品信息
     */
    private ErpSaleBo.NewProduct readSnapshot(String json) {
        try {
            return JSON.readValue(json, ErpSaleBo.NewProduct.class);
        } catch (JsonProcessingException e) {
            throw new ServiceException("现录现卖商品快照解析失败");
        }
    }

    /**
     * 删除草稿销售单：仅 DRAFT 可删。明细与主表均逻辑删除（del_flag=1），
     * 单号不回收、不产生库存流水与应收台账。
     *
     * @param id 销售单ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDraft(Long id) {
        ErpSale head = saleMapper.selectById(id);
        if (head == null) {
            throw new ServiceException("销售单不存在");
        }
        if (!"DRAFT".equals(head.getStatus())) {
            throw new ServiceException("仅草稿单据可删除", 40902);
        }
        itemMapper.delete(new LambdaQueryWrapper<ErpSaleItem>().eq(ErpSaleItem::getOrderId, id));
        saleMapper.deleteById(id);
    }

    /**
     * 挂订单执行单的原地改行：行集合必须与已存行一一对应，只允许改数量/单价/备注，
     * 行 ID 保持稳定，退货行锚点与订单进度聚合不被破坏（DOC/31 D13、§5.4 改动点 2）。
     *
     * @param head 销售单头（来源订单已持久化）
     * @param bo   编辑入参
     */
    private void updateOrderItems(ErpSale head, ErpSaleBo bo) {
        List<ErpSaleItem> existItems = itemMapper.selectList(new LambdaQueryWrapper<ErpSaleItem>()
            .eq(ErpSaleItem::getOrderId, head.getId())
            .orderByAsc(ErpSaleItem::getSeq));
        Map<Long, ErpSaleItem> bySource = new HashMap<>();
        for (ErpSaleItem row : existItems) {
            if (row.getSourceOrderItemId() == null) {
                throw new ServiceException("执行单存在无来源订单行的明细，请删除后重新转单", 40902);
            }
            bySource.put(row.getSourceOrderItemId(), row);
        }
        if (bo.getItems().size() != existItems.size()) {
            throw new ServiceException("挂订单的执行单不能增删商品行", 40902);
        }
        Set<Long> orderItemIds = loadOrderItemIds(head.getSourceOrderId());
        BigDecimal total = BigDecimal.ZERO;
        int seq = 0;
        for (ErpSaleBo.SaleItemBo item : bo.getItems()) {
            ErpSaleItem row = item.getSourceOrderItemId() == null ? null : bySource.remove(item.getSourceOrderItemId());
            if (row == null) {
                throw new ServiceException("执行单明细不属于该单据：" + item.getSourceOrderItemId(), 40902);
            }
            if (!orderItemIds.contains(item.getSourceOrderItemId())) {
                throw new ServiceException("明细行不属于来源销售订单：" + item.getSourceOrderItemId(), 40902);
            }
            if (item.getQty() == null || item.getQty().compareTo(BigDecimal.ZERO) <= 0) {
                throw new ServiceException("数量必须大于0");
            }
            if (item.getPrice() == null || item.getPrice().compareTo(BigDecimal.ZERO) < 0) {
                throw new ServiceException("销售单价不能为空");
            }
            row.setQty(item.getQty());
            row.setPrice(item.getPrice());
            // 发票类型/税率由表头继承写入，明细列仅作快照
            row.setTaxType(head.getTaxType());
            row.setTaxRate(head.getTaxRate());
            row.setAmount(item.getQty().multiply(item.getPrice()).setScale(2, RoundingMode.HALF_UP));
            row.setRemark(item.getRemark());
            row.setSeq(seq++);
            itemMapper.updateById(row);
            total = total.add(row.getAmount());
        }
        if (!bySource.isEmpty()) {
            throw new ServiceException("挂订单的执行单不能增删商品行", 40902);
        }
        // 销售运杂费计入成本，不计入客户应付，totalAmount 仍等于货品金额
        head.setGoodsAmount(total.setScale(2, RoundingMode.HALF_UP));
        head.setTotalAmount(total.setScale(2, RoundingMode.HALF_UP));
        saleMapper.updateById(head);
    }

    /**
     * 查询来源销售订单的全部订单行ID，供保存时校验行归属。
     *
     * @param orderId 来源销售订单ID，可空
     * @return 订单行ID集合；无来源订单返回空集合
     */
    private Set<Long> loadOrderItemIds(Long orderId) {
        Set<Long> ids = new HashSet<>();
        if (orderId == null) {
            return ids;
        }
        for (ErpSaleOrderItem item : saleOrderItemMapper.selectList(new LambdaQueryWrapper<ErpSaleOrderItem>()
            .eq(ErpSaleOrderItem::getOrderId, orderId))) {
            ids.add(item.getId());
        }
        return ids;
    }
}
