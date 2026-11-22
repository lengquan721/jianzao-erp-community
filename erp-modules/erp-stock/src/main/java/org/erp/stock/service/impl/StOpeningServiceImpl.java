package org.erp.stock.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.erp.common.enums.StatusText;
import org.erp.master.domain.ErpProduct;
import org.erp.master.mapper.ErpProductMapper;
import org.erp.master.service.IErpProductService;
import org.erp.stock.domain.StOpening;
import org.erp.stock.domain.bo.StOpeningBo;
import org.erp.stock.domain.bo.StockPage;
import org.erp.stock.domain.vo.StOpeningVo;
import org.erp.stock.mapper.StOpeningMapper;
import org.erp.stock.service.IInventoryService;
import org.erp.stock.service.IStOpeningService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.List;

/**
 * 期初库存服务实现
 *
 * 规则（DOC/17 §2、DOC/12 §7.2）：
 * - 录入即 upsert（product+warehouse 唯一键），CONFIRMED 行拒改（40902）；
 * - confirm：对每行写 OPENING 入库流水（inbound），初始化 st_stock 快照，行状态锁定；
 * - unconfirm（v1 管理员）：reverse 回退 OPENING 流水 + D2 重放，行回 DRAFT。
 */
@RequiredArgsConstructor
@Service
public class StOpeningServiceImpl implements IStOpeningService {

    private final StOpeningMapper openingMapper;
    private final ErpProductMapper productMapper;
    private final IInventoryService inventoryService;
    private final IErpProductService productService;

    private static final int EXPORT_LIMIT = 5000;

    @Override
    @Transactional(readOnly = true)
    public TableDataInfo<StOpeningVo> pageDetails(Long warehouseId, int pageNum, int pageSize) {
        StockPage page = StockPage.of(pageNum, pageSize);
        long total = openingMapper.countDetails(warehouseId);
        List<StOpeningVo> rows = page.offset() >= total ? List.of()
            : openingMapper.selectDetails(warehouseId, page.offset(), page.size());
        fillStatus(rows);
        return new TableDataInfo<>(rows, total);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StOpeningVo> listForExport(Long warehouseId) {
        // 取上限+1 哨兵，超过拒绝；与 UI 的分页边界解耦，绝不只导出第一页。
        List<StOpeningVo> rows = openingMapper.selectDetails(warehouseId, 0, EXPORT_LIMIT + 1);
        if (rows.size() > EXPORT_LIMIT) {
            throw new ServiceException("期初库存单次最多导出 5000 条，请按仓库筛选后重试", 400);
        }
        fillStatus(rows);
        return rows;
    }

    private void fillStatus(List<StOpeningVo> rows) {
        rows.forEach(vo -> vo.setStatusText(StatusText.bill(vo.getStatus())));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StOpeningVo save(StOpeningBo bo) {
        StOpening exist = openingMapper.selectOne(new LambdaQueryWrapper<StOpening>()
            .eq(StOpening::getProductId, bo.getProductId())
            .eq(StOpening::getWarehouseId, bo.getWarehouseId()));
        if (exist != null && "CONFIRMED".equals(exist.getStatus())) {
            throw new ServiceException("期初已确认锁定，请先反确认", 40902);
        }
        checkOpeningCost(bo.getProductId(), bo.getQty(), bo.getUnitCost());
        BigDecimal qty = bo.getQty().setScale(3, RoundingMode.HALF_UP);
        BigDecimal unitCost = bo.getUnitCost().setScale(4, RoundingMode.HALF_UP);
        StOpening row;
        if (exist != null) {
            row = exist;
            row.setQty(qty);
            row.setUnitCost(unitCost);
            row.setStatus("DRAFT");
            openingMapper.updateById(row);
        } else {
            row = new StOpening();
            row.setProductId(bo.getProductId());
            row.setWarehouseId(bo.getWarehouseId());
            row.setQty(qty);
            row.setUnitCost(unitCost);
            row.setStatus("DRAFT");
            // 唯一键 uk_st_opening 不含 del_flag：同产品+仓库的已删除行仍占键，需复活原行而不是插入新行
            Long deletedId = openingMapper.selectDeletedId(bo.getProductId(), bo.getWarehouseId());
            if (deletedId != null && deletedId > 0) {
                // 复活语句已写入 qty/unit_cost/status，这里只回填主键
                openingMapper.reviveDeleted(deletedId, qty, unitCost);
                row.setId(deletedId);
            } else {
                openingMapper.insert(row);
            }
        }
        StOpeningVo vo = new StOpeningVo();
        vo.setId(row.getId());
        vo.setProductId(row.getProductId());
        vo.setWarehouseId(row.getWarehouseId());
        vo.setQty(row.getQty());
        vo.setUnitCost(row.getUnitCost());
        vo.setStatus(row.getStatus());
        vo.setStatusText(StatusText.bill(row.getStatus()));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirm(Long id) {
        StOpening row = openingMapper.selectById(id);
        if (row == null) {
            throw new ServiceException("期初记录不存在");
        }
        if (!"DRAFT".equals(row.getStatus())) {
            throw new ServiceException("期初已确认", 40902);
        }
        // 历史草稿可能存着 0 成本，确认前再拦一次（DOC/26 §2.4）
        checkOpeningCost(row.getProductId(), row.getQty(), row.getUnitCost());
        // inbound 幂等由 st_stock_log 无 OPENING 记录保证；已存在则拒绝重复确认
        int seq = 0;
        inventoryService.inbound("OPENING", "QC" + row.getId(), row.getId(),
            row.getProductId(), row.getWarehouseId(), row.getQty(), row.getUnitCost(),
            "NONE", null, new Date(), seq);
        row.setStatus("CONFIRMED");
        openingMapper.updateById(row);
        // D4：回写最近入库成本（DOC/26 §9.4），撤回不回退
        productService.updateCostPrice(row.getProductId(), row.getUnitCost());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unconfirm(Long id) {
        StOpening row = openingMapper.selectById(id);
        if (row == null) {
            throw new ServiceException("期初记录不存在");
        }
        if (!"CONFIRMED".equals(row.getStatus())) {
            throw new ServiceException("仅已确认期初可反确认", 40902);
        }
        // P0-2：按单据整体冲销 OPENING 流水（DOC/31 §13.4）
        inventoryService.reverseByBill("QC" + row.getId(), row.getId(), new Date());
        row.setStatus("DRAFT");
        openingMapper.updateById(row);
    }

    /**
     * 删除草稿期初库存：仅 DRAFT 可删。
     *
     * 逻辑删除（del_flag=1），草稿未写 OPENING 流水、不影响 st_stock 与成本；
     * 唯一键 uk_st_opening 不含 del_flag，删除后同产品+仓库重建由 save() 复活原行。
     *
     * @param id 期初行ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDraft(Long id) {
        StOpening row = openingMapper.selectById(id);
        if (row == null) {
            throw new ServiceException("期初记录不存在");
        }
        if (!"DRAFT".equals(row.getStatus())) {
            throw new ServiceException("仅草稿单据可删除", 40902);
        }
        openingMapper.deleteById(id);
    }

    /**
     * 成本锚点校验（DOC/26 §2）：有数量的期初必须有成本，否则 avg_cost=0 会让后续出库成本全为 0。
     *
     * @param productId 产品 ID
     * @param qty       期初数量
     * @param unitCost  期初成本单价
     */
    private void checkOpeningCost(Long productId, BigDecimal qty, BigDecimal unitCost) {
        if (qty.compareTo(BigDecimal.ZERO) <= 0 || unitCost.compareTo(BigDecimal.ZERO) > 0) {
            return;
        }
        ErpProduct product = productMapper.selectById(productId);
        String name = product == null ? String.valueOf(productId) : product.getName();
        throw new ServiceException("商品「" + name + "」期初成本必须大于 0");
    }
}
