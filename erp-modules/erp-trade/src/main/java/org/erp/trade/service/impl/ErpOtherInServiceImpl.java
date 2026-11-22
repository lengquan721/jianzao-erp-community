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
import org.erp.master.domain.ErpProduct;
import org.erp.master.mapper.ErpProductMapper;
import org.erp.master.service.IErpProductService;
import org.erp.master.service.IErpWarehouseService;
import org.erp.stock.service.IInventoryService;
import org.erp.trade.domain.ErpOtherIn;
import org.erp.trade.domain.ErpOtherInItem;
import org.erp.trade.domain.bo.ErpOtherInBo;
import org.erp.trade.domain.vo.ErpOtherInVo;
import org.erp.trade.mapper.ErpOtherInItemMapper;
import org.erp.trade.mapper.ErpOtherInMapper;
import org.erp.trade.service.IErpOtherInService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 其他入库服务实现
 *
 * 规则（DOC/21）：
 * - 明细单价必填 > 0，草稿即保存录入值；确认时原样传给 inbound（OTHER_IN）移动加权更新 avg_cost；
 * - 不产生应收应付、不写 fin_cost_ledger；撤回：库存 reverse + 重放重算；
 * - v1 单仓（warehouse=默认仓）；明细不允许重复产品。
 */
@RequiredArgsConstructor
@Service
public class ErpOtherInServiceImpl implements IErpOtherInService {

    private final ErpOtherInMapper otherInMapper;
    private final ErpOtherInItemMapper itemMapper;
    private final ErpProductMapper productMapper;
    private final IInventoryService inventoryService;
    private final IErpProductService productService;
    private final IErpWarehouseService warehouseService;
    private final SequenceService sequenceService;

    @Override
    public TableDataInfo<ErpOtherInVo> selectPage(String keyword, String reason, String status,
                                                  PageQuery pageQuery) {
        LambdaQueryWrapper<ErpOtherIn> lqw = new LambdaQueryWrapper<>();
        lqw.eq(reason != null && !reason.isBlank(), ErpOtherIn::getReason, reason);
        lqw.eq(status != null && !status.isBlank(), ErpOtherIn::getStatus, status);
        if (keyword != null && !keyword.isBlank()) {
            lqw.like(ErpOtherIn::getNo, keyword);
        }
        lqw.orderByDesc(ErpOtherIn::getId);
        Page<ErpOtherIn> page = otherInMapper.selectPage(pageQuery.build(), lqw);
        Page<ErpOtherInVo> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toVo).toList());
        return TableDataInfo.build(voPage);
    }

    @Override
    public ErpOtherInVo selectById(Long id) {
        ErpOtherIn head = otherInMapper.selectById(id);
        if (head == null) {
            throw new ServiceException("其他入库单不存在");
        }
        ErpOtherInVo vo = toVo(head);
        vo.setItems(loadItems(id));
        return vo;
    }

    private ErpOtherInVo toVo(ErpOtherIn head) {
        ErpOtherInVo vo = new ErpOtherInVo();
        vo.setId(head.getId());
        vo.setNo(head.getNo());
        vo.setBizDate(head.getBizDate());
        vo.setStatus(head.getStatus());
        vo.setStatusText(StatusText.bill(head.getStatus()));
        vo.setReason(head.getReason());
        vo.setAmount(head.getAmount());
        vo.setRemark(head.getRemark());
        vo.setCreateTime(head.getCreateTime());
        return vo;
    }

    private List<ErpOtherInVo.ItemVo> loadItems(Long orderId) {
        List<ErpOtherInItem> items = itemMapper.selectList(new LambdaQueryWrapper<ErpOtherInItem>()
            .eq(ErpOtherInItem::getOrderId, orderId)
            .orderByAsc(ErpOtherInItem::getId));
        List<ErpOtherInVo.ItemVo> result = new ArrayList<>();
        for (ErpOtherInItem row : items) {
            ErpOtherInVo.ItemVo vo = new ErpOtherInVo.ItemVo();
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
    public ErpOtherInVo insert(ErpOtherInBo bo) {
        ErpOtherIn head = new ErpOtherIn();
        head.setNo(sequenceService.nextNo("QR"));
        saveHead(head, bo);
        saveItems(head, bo);
        if (Boolean.TRUE.equals(bo.getConfirm())) {
            confirmInternal(head);
        }
        return selectById(head.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpOtherInVo update(Long id, ErpOtherInBo bo) {
        ErpOtherIn head = otherInMapper.selectById(id);
        if (head == null) {
            throw new ServiceException("其他入库单不存在");
        }
        if (!"DRAFT".equals(head.getStatus())) {
            throw new ServiceException("仅草稿可编辑", 40902);
        }
        saveHead(head, bo);
        itemMapper.delete(new LambdaQueryWrapper<ErpOtherInItem>()
            .eq(ErpOtherInItem::getOrderId, id));
        saveItems(head, bo);
        if (Boolean.TRUE.equals(bo.getConfirm())) {
            confirmInternal(head);
        }
        return selectById(id);
    }

    private void saveHead(ErpOtherIn head, ErpOtherInBo bo) {
        head.setBizDate(bo.getBizDate());
        head.setReason(bo.getReason());
        head.setRemark(bo.getRemark());
        head.setStatus("DRAFT");
        if (head.getId() == null) {
            otherInMapper.insert(head);
        } else {
            otherInMapper.updateById(head);
        }
    }

    private void saveItems(ErpOtherIn head, ErpOtherInBo bo) {
        Set<Long> productIds = new HashSet<>();
        BigDecimal amountTotal = BigDecimal.ZERO;
        for (ErpOtherInBo.ItemBo item : bo.getItems()) {
            if (!productIds.add(item.getProductId())) {
                throw new ServiceException("明细中产品重复：" + item.getProductId());
            }
            ErpProduct product = productMapper.selectById(item.getProductId());
            if (product == null) {
                throw new ServiceException("产品不存在：" + item.getProductId());
            }
            ErpOtherInItem row = new ErpOtherInItem();
            row.setOrderId(head.getId());
            row.setProductId(item.getProductId());
            row.setQty(item.getQty());
            row.setUnitCost(item.getUnitCost());
            row.setAmount(item.getQty().multiply(item.getUnitCost()).setScale(2, RoundingMode.HALF_UP));
            row.setRemark(item.getRemark());
            itemMapper.insert(row);
            amountTotal = amountTotal.add(row.getAmount());
        }
        head.setAmount(amountTotal.setScale(2, RoundingMode.HALF_UP));
        otherInMapper.updateById(head);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirm(Long id) {
        // 行锁：确认/撤回/作废的第一把锁必须是单据头，与销售单加锁顺序一致
        ErpOtherIn head = otherInMapper.lockById(id);
        if (head == null) {
            throw new ServiceException("其他入库单不存在");
        }
        if (!"DRAFT".equals(head.getStatus())) {
            throw new ServiceException("仅草稿可确认", 40902);
        }
        confirmInternal(head);
    }

    private void confirmInternal(ErpOtherIn head) {
        List<ErpOtherInItem> items = itemMapper.selectList(new LambdaQueryWrapper<ErpOtherInItem>()
            .eq(ErpOtherInItem::getOrderId, head.getId())
            .orderByAsc(ErpOtherInItem::getId));
        int seq = 0;
        for (ErpOtherInItem row : items) {
            inventoryService.inbound("OTHER_IN", head.getNo(), head.getId(),
                row.getProductId(), warehouseService.defaultWarehouseId(), row.getQty(),
                row.getUnitCost(), "NONE", null, head.getBizDate(), seq++);
        }
        // 状态 CAS：只有 DRAFT 能改成 CONFIRMED，并发双击/跨节点时第二个请求返回 40902
        head.setConfirmedAt(new Date());
        head.setConfirmedBy(currentUserId());
        int rows = otherInMapper.update(null, new UpdateWrapper<ErpOtherIn>()
            .eq("id", head.getId()).eq("status", "DRAFT")
            .set("status", "CONFIRMED")
            .set("confirmed_at", head.getConfirmedAt())
            .set("confirmed_by", head.getConfirmedBy()));
        if (rows == 0) {
            throw new ServiceException("单据状态已变更，请刷新后重试", 40902);
        }
        head.setStatus("CONFIRMED");
        // D4：回写最近入库成本（DOC/26 §9.4），仅参考、撤回不回退
        for (ErpOtherInItem row : items) {
            productService.updateCostPrice(row.getProductId(), row.getUnitCost());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void withdraw(Long id) {
        ErpOtherIn head = otherInMapper.lockById(id);
        if (head == null) {
            throw new ServiceException("其他入库单不存在");
        }
        if (!"CONFIRMED".equals(head.getStatus())) {
            throw new ServiceException("仅已确认单据可撤回", 40902);
        }
        // P0-2：按单据整体冲销，同商品多行也只冲一次（DOC/31 §13.4）
        inventoryService.reverseByBill(head.getNo(), head.getId(), head.getBizDate());
        // 状态 CAS：只有 CONFIRMED 能改回 DRAFT，并发撤回时第二个请求返回 40902
        head.setWithdrawnAt(new Date());
        head.setWithdrawnBy(currentUserId());
        int rows = otherInMapper.update(null, new UpdateWrapper<ErpOtherIn>()
            .eq("id", head.getId()).eq("status", "CONFIRMED")
            .set("status", "DRAFT")
            .set("withdrawn_at", head.getWithdrawnAt())
            .set("withdrawn_by", head.getWithdrawnBy()));
        if (rows == 0) {
            throw new ServiceException("单据状态已变更，请刷新后重试", 40902);
        }
        head.setStatus("DRAFT");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void voidBill(Long id) {
        // 行锁：作废与撤回/确认共用单据头锁，避免并发路径交错
        ErpOtherIn head = otherInMapper.lockById(id);
        if (head == null) {
            throw new ServiceException("其他入库单不存在");
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
        head = otherInMapper.selectById(id);
        head.setVoidedAt(new Date());
        head.setVoidedBy(currentUserId());
        // 状态 CAS：只有 DRAFT 能改成 VOID（撤回后必定落在 DRAFT）
        int rows = otherInMapper.update(null, new UpdateWrapper<ErpOtherIn>()
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
     * 删除草稿其他入库单：仅 DRAFT 可删。明细与主表均逻辑删除（del_flag=1），
     * 单号不回收、不产生库存流水。
     *
     * @param id 其他入库单ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDraft(Long id) {
        ErpOtherIn head = otherInMapper.selectById(id);
        if (head == null) {
            throw new ServiceException("其他入库单不存在");
        }
        if (!"DRAFT".equals(head.getStatus())) {
            throw new ServiceException("仅草稿单据可删除", 40902);
        }
        itemMapper.delete(new LambdaQueryWrapper<ErpOtherInItem>()
            .eq(ErpOtherInItem::getOrderId, id));
        otherInMapper.deleteById(id);
    }
}

