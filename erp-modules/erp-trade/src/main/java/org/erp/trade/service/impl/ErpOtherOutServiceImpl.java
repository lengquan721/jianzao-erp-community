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
import org.erp.fin.service.IFinCostLedgerService;
import org.erp.master.domain.ErpProduct;
import org.erp.master.mapper.ErpProductMapper;
import org.erp.master.service.IErpWarehouseService;
import org.erp.stock.service.IInventoryService;
import org.erp.trade.domain.ErpOtherOut;
import org.erp.trade.domain.ErpOtherOutItem;
import org.erp.trade.domain.bo.ErpOtherOutBo;
import org.erp.trade.domain.vo.ErpOtherOutVo;
import org.erp.trade.mapper.ErpOtherOutItemMapper;
import org.erp.trade.mapper.ErpOtherOutMapper;
import org.erp.trade.service.IErpOtherOutService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 其他出库服务实现
 *
 * 规则（DOC/02 S3/R17、DOC/11 §7、DOC/12 §7.1）：
 * - 确认：逐行按库存 avg_cost 快照成本 → 条件扣减（OTHER_OUT）→ Σ行成本写 fin_cost_ledger；
 * - 不产生应收应付；撤回：库存 reverse + 成本台账 ACTIVE→REVERSED；
 * - v1 单仓（warehouse=1）；成本分类决定 fin_cost_ledger.cost_type=OTHER_OUT。
 */
@RequiredArgsConstructor
@Service
public class ErpOtherOutServiceImpl implements IErpOtherOutService {

    private final ErpOtherOutMapper otherOutMapper;
    private final ErpOtherOutItemMapper itemMapper;
    private final ErpProductMapper productMapper;
    private final IInventoryService inventoryService;
    private final IErpWarehouseService warehouseService;
    private final IFinCostLedgerService costLedgerService;
    private final SequenceService sequenceService;

    @Override
    public TableDataInfo<ErpOtherOutVo> selectPage(String keyword, String costCategory, String status,
                                                   PageQuery pageQuery) {
        LambdaQueryWrapper<ErpOtherOut> lqw = new LambdaQueryWrapper<>();
        lqw.eq(costCategory != null && !costCategory.isBlank(), ErpOtherOut::getCostCategory, costCategory);
        lqw.eq(status != null && !status.isBlank(), ErpOtherOut::getStatus, status);
        if (keyword != null && !keyword.isBlank()) {
            lqw.like(ErpOtherOut::getNo, keyword);
        }
        lqw.orderByDesc(ErpOtherOut::getId);
        Page<ErpOtherOut> page = otherOutMapper.selectPage(pageQuery.build(), lqw);
        Page<ErpOtherOutVo> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toVo).toList());
        return TableDataInfo.build(voPage);
    }

    @Override
    public ErpOtherOutVo selectById(Long id) {
        ErpOtherOut head = otherOutMapper.selectById(id);
        if (head == null) {
            throw new ServiceException("其他出库单不存在");
        }
        ErpOtherOutVo vo = toVo(head);
        vo.setItems(loadItems(id));
        return vo;
    }

    private ErpOtherOutVo toVo(ErpOtherOut head) {
        ErpOtherOutVo vo = new ErpOtherOutVo();
        vo.setId(head.getId());
        vo.setNo(head.getNo());
        vo.setBizDate(head.getBizDate());
        vo.setStatus(head.getStatus());
        vo.setStatusText(StatusText.bill(head.getStatus()));
        vo.setCostCategory(head.getCostCategory());
        vo.setCostAmount(head.getCostAmount());
        vo.setRemark(head.getRemark());
        vo.setCreateTime(head.getCreateTime());
        return vo;
    }

    private List<ErpOtherOutVo.ItemVo> loadItems(Long orderId) {
        List<ErpOtherOutItem> items = itemMapper.selectList(new LambdaQueryWrapper<ErpOtherOutItem>()
            .eq(ErpOtherOutItem::getOrderId, orderId)
            .orderByAsc(ErpOtherOutItem::getId));
        List<ErpOtherOutVo.ItemVo> result = new ArrayList<>();
        for (ErpOtherOutItem row : items) {
            ErpOtherOutVo.ItemVo vo = new ErpOtherOutVo.ItemVo();
            vo.setId(row.getId());
            vo.setProductId(row.getProductId());
            vo.setQty(row.getQty());
            vo.setUnitCost(row.getUnitCost());
            vo.setAmount(row.getAmount());
            vo.setRemark(row.getRemark());
            ErpProduct p = productMapper.selectById(row.getProductId());
            if (p != null) {
                vo.setProductCode(p.getCode());
                vo.setProductName(p.getName());
                vo.setQtyScale(p.getQtyScale());
            }
            result.add(vo);
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpOtherOutVo insert(ErpOtherOutBo bo) {
        ErpOtherOut head = new ErpOtherOut();
        head.setNo(sequenceService.nextNo("QT"));
        saveHead(head, bo);
        saveItems(head, bo);
        if (Boolean.TRUE.equals(bo.getConfirm())) {
            confirmInternal(head);
        }
        return selectById(head.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpOtherOutVo update(Long id, ErpOtherOutBo bo) {
        ErpOtherOut head = otherOutMapper.selectById(id);
        if (head == null) {
            throw new ServiceException("其他出库单不存在");
        }
        if (!"DRAFT".equals(head.getStatus())) {
            throw new ServiceException("仅草稿可编辑", 40902);
        }
        saveHead(head, bo);
        itemMapper.delete(new LambdaQueryWrapper<ErpOtherOutItem>()
            .eq(ErpOtherOutItem::getOrderId, id));
        saveItems(head, bo);
        if (Boolean.TRUE.equals(bo.getConfirm())) {
            confirmInternal(head);
        }
        return selectById(id);
    }

    private void saveHead(ErpOtherOut head, ErpOtherOutBo bo) {
        head.setBizDate(bo.getBizDate());
        head.setCostCategory(bo.getCostCategory());
        head.setRemark(bo.getRemark());
        head.setStatus("DRAFT");
        if (head.getId() == null) {
            otherOutMapper.insert(head);
        } else {
            otherOutMapper.updateById(head);
        }
    }

    private void saveItems(ErpOtherOut head, ErpOtherOutBo bo) {
        for (ErpOtherOutBo.ItemBo item : bo.getItems()) {
            ErpProduct product = productMapper.selectById(item.getProductId());
            if (product == null) {
                throw new ServiceException("产品不存在：" + item.getProductId());
            }
            ErpOtherOutItem row = new ErpOtherOutItem();
            row.setOrderId(head.getId());
            row.setProductId(item.getProductId());
            row.setQty(item.getQty());
            row.setUnitCost(BigDecimal.ZERO);
            row.setAmount(BigDecimal.ZERO);
            row.setRemark(item.getRemark());
            itemMapper.insert(row);
        }
        head.setCostAmount(BigDecimal.ZERO);
        otherOutMapper.updateById(head);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirm(Long id) {
        // 行锁：确认/撤回/作废的第一把锁必须是单据头，与销售单加锁顺序一致
        ErpOtherOut head = otherOutMapper.lockById(id);
        if (head == null) {
            throw new ServiceException("其他出库单不存在");
        }
        if (!"DRAFT".equals(head.getStatus())) {
            throw new ServiceException("仅草稿可确认", 40902);
        }
        confirmInternal(head);
    }

    private void confirmInternal(ErpOtherOut head) {
        List<ErpOtherOutItem> items = itemMapper.selectList(new LambdaQueryWrapper<ErpOtherOutItem>()
            .eq(ErpOtherOutItem::getOrderId, head.getId())
            .orderByAsc(ErpOtherOutItem::getId));
        BigDecimal costTotal = BigDecimal.ZERO;
        int seq = 0;
        for (ErpOtherOutItem row : items) {
            BigDecimal unitCost = inventoryService.outbound("OTHER_OUT", head.getNo(), head.getId(),
                row.getProductId(), warehouseService.defaultWarehouseId(), row.getQty(),
                "NONE", null, head.getBizDate(), seq++);
            BigDecimal amount = row.getQty().multiply(unitCost).setScale(2, RoundingMode.HALF_UP);
            row.setUnitCost(unitCost);
            row.setAmount(amount);
            itemMapper.updateById(row);
            costTotal = costTotal.add(amount);
        }
        head.setCostAmount(costTotal.setScale(2, RoundingMode.HALF_UP));
        // 状态 CAS：只有 DRAFT 能改成 CONFIRMED，并发双击/跨节点时第二个请求返回 40902
        head.setConfirmedAt(new Date());
        head.setConfirmedBy(currentUserId());
        int rows = otherOutMapper.update(null, new UpdateWrapper<ErpOtherOut>()
            .eq("id", head.getId()).eq("status", "DRAFT")
            .set("status", "CONFIRMED")
            .set("cost_amount", head.getCostAmount())
            .set("confirmed_at", head.getConfirmedAt())
            .set("confirmed_by", head.getConfirmedBy()));
        if (rows == 0) {
            throw new ServiceException("单据状态已变更，请刷新后重试", 40902);
        }
        // 内存实体同步 CAS 结果，后续按 head 写台账时状态不落后
        head.setStatus("CONFIRMED");

        // 成本台账：OTHER_OUT（R20 综合成本口径）
        costLedgerService.record("OTHER_OUT", "OTHER_OUT", head.getId(), head.getNo(),
            head.getBizDate(), head.getCostAmount(), head.getRemark());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void withdraw(Long id) {
        ErpOtherOut head = otherOutMapper.lockById(id);
        if (head == null) {
            throw new ServiceException("其他出库单不存在");
        }
        if (!"CONFIRMED".equals(head.getStatus())) {
            throw new ServiceException("仅已确认单据可撤回", 40902);
        }
        // P0-2：按单据整体冲销，同商品多行也只冲一次（DOC/31 §13.4）
        inventoryService.reverseByBill(head.getNo(), head.getId(), head.getBizDate());
        costLedgerService.reverseBySource("OTHER_OUT", id);
        // 状态 CAS：只有 CONFIRMED 能改回 DRAFT，并发撤回时第二个请求返回 40902
        head.setWithdrawnAt(new Date());
        head.setWithdrawnBy(currentUserId());
        int rows = otherOutMapper.update(null, new UpdateWrapper<ErpOtherOut>()
            .eq("id", head.getId()).eq("status", "CONFIRMED")
            .set("status", "DRAFT")
            .set("cost_amount", BigDecimal.ZERO)
            .set("withdrawn_at", head.getWithdrawnAt())
            .set("withdrawn_by", head.getWithdrawnBy()));
        if (rows == 0) {
            throw new ServiceException("单据状态已变更，请刷新后重试", 40902);
        }
        head.setStatus("DRAFT");
        head.setCostAmount(BigDecimal.ZERO);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void voidBill(Long id) {
        // 行锁：作废与撤回/确认共用单据头锁，避免并发路径交错
        ErpOtherOut head = otherOutMapper.lockById(id);
        if (head == null) {
            throw new ServiceException("其他出库单不存在");
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
        }
        head = otherOutMapper.selectById(id);
        head.setVoidedAt(new Date());
        head.setVoidedBy(currentUserId());
        // 状态 CAS：只有 DRAFT 能改成 VOID（撤回后必定落在 DRAFT）
        int rows = otherOutMapper.update(null, new UpdateWrapper<ErpOtherOut>()
            .eq("id", head.getId()).eq("status", "DRAFT")
            .set("status", "VOID")
            .set("voided_at", head.getVoidedAt())
            .set("voided_by", head.getVoidedBy()));
        if (rows == 0) {
            throw new ServiceException("单据状态已变更，请刷新后重试", 40902);
        }
        head.setStatus("VOID");
    }

    private Long currentUserId() {
        try {
            return LoginHelper.getUserId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 删除草稿其他出库单：仅 DRAFT 可删。明细与主表均逻辑删除（del_flag=1），
     * 单号不回收、不产生库存流水与成本台账。
     *
     * @param id 其他出库单ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDraft(Long id) {
        ErpOtherOut head = otherOutMapper.selectById(id);
        if (head == null) {
            throw new ServiceException("其他出库单不存在");
        }
        if (!"DRAFT".equals(head.getStatus())) {
            throw new ServiceException("仅草稿单据可删除", 40902);
        }
        itemMapper.delete(new LambdaQueryWrapper<ErpOtherOutItem>()
            .eq(ErpOtherOutItem::getOrderId, id));
        otherOutMapper.deleteById(id);
    }
}
