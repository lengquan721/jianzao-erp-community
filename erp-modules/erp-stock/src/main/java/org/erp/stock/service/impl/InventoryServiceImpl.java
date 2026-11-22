package org.erp.stock.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.erp.master.domain.ErpCustomer;
import org.erp.master.domain.ErpProduct;
import org.erp.master.domain.ErpSupplier;
import org.erp.master.mapper.ErpCustomerMapper;
import org.erp.master.mapper.ErpProductMapper;
import org.erp.master.mapper.ErpSupplierMapper;
import org.erp.stock.domain.ErpStock;
import org.erp.stock.domain.ErpStockLog;
import org.erp.stock.domain.bo.StockPage;
import org.erp.stock.domain.vo.ErpStockLogPageVo;
import org.erp.stock.domain.vo.ErpStockLogVo;
import org.erp.stock.domain.vo.ErpStockQuantityVo;
import org.erp.stock.domain.vo.ErpStockVo;
import org.erp.stock.mapper.ErpStockLogMapper;
import org.erp.stock.mapper.ErpStockMapper;
import org.erp.stock.service.IInventoryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 库存服务实现
 *
 * 规则（DOC/11 §8.2/8.3）：
 * - 出库条件扣减防超卖；入库回补并移动加权；
 * - 流水只增不改；撤回/作废写反向流水后按流水重放重算 avg_cost；
 * - 中间精度 6 位，入库精度：数量 3 位、成本 4 位；
 * - 新增入库/出库/盘点差异按商品 md_product.qty_scale 校验数量小数位（DOC/26 §10）。
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class InventoryServiceImpl implements IInventoryService {

    private final ErpStockMapper stockMapper;
    private final ErpStockLogMapper stockLogMapper;
    private final ErpProductMapper productMapper;
    private final ErpSupplierMapper supplierMapper;
    private final ErpCustomerMapper customerMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BigDecimal outbound(String bizType, String bizNo, Long bizId, Long productId, Long warehouseId,
                               BigDecimal qty, String partnerType, Long partnerId, Date bizDate, int seq) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ServiceException("出库数量必须大于0");
        }
        checkQtyScale(productId, qty);
        ErpStock locked = lockMovementRow(productId, warehouseId);
        int rows = stockMapper.deductQty(productId, warehouseId, qty);
        if (rows == 0) {
            throw new ServiceException("库存不足，单据保存失败", 40901);
        }
        BigDecimal avgCost = locked.getAvgCost();
        writeLogSnapshot(bizType, bizNo, bizId, productId, warehouseId, qty.negate(),
            avgCost, avgCost.multiply(qty).setScale(2, RoundingMode.HALF_UP),
            partnerType, partnerId, bizDate, seq, locked.getQty().subtract(qty));
        return avgCost;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void outbound(String bizType, String bizNo, Long bizId, Long productId, Long warehouseId,
                         BigDecimal qty, BigDecimal unitCost, String partnerType, Long partnerId,
                         Date bizDate, int seq) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ServiceException("出库数量必须大于0");
        }
        checkQtyScale(productId, qty);
        ErpStock locked = lockMovementRow(productId, warehouseId);
        int rows = stockMapper.deductQty(productId, warehouseId, qty);
        if (rows == 0) {
            throw new ServiceException("库存不足，单据保存失败", 40901);
        }
        // 成本快照由调用方指定（退货按原行成本回冲），不取当前 avg_cost
        BigDecimal cost = unitCost == null ? BigDecimal.ZERO : unitCost;
        writeLogSnapshot(bizType, bizNo, bizId, productId, warehouseId, qty.negate(),
            cost, cost.multiply(qty).setScale(2, RoundingMode.HALF_UP),
            partnerType, partnerId, bizDate, seq, locked.getQty().subtract(qty));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void inbound(String bizType, String bizNo, Long bizId, Long productId, Long warehouseId,
                        BigDecimal qty, BigDecimal unitCost, String partnerType, Long partnerId,
                        Date bizDate, int seq) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ServiceException("入库数量必须大于0");
        }
        checkQtyScale(productId, qty);
        ErpStock locked = lockMovementRow(productId, warehouseId);
        BigDecimal oldQty = locked.getQty();
        BigDecimal oldCost = locked.getAvgCost();
        BigDecimal cost = unitCost == null ? BigDecimal.ZERO : unitCost;

        BigDecimal totalQty = oldQty.add(qty);
        BigDecimal newAvg;
        if (totalQty.compareTo(BigDecimal.ZERO) > 0
            && oldQty.multiply(oldCost).add(qty.multiply(cost)).compareTo(BigDecimal.ZERO) > 0) {
            newAvg = oldQty.multiply(oldCost).add(qty.multiply(cost))
                .divide(totalQty, 6, RoundingMode.HALF_UP)
                .setScale(4, RoundingMode.HALF_UP);
        } else {
            newAvg = cost.setScale(4, RoundingMode.HALF_UP);
        }

        stockMapper.addQty(productId, warehouseId, qty);
        stockMapper.updateAvgCost(productId, warehouseId, newAvg);
        writeLogSnapshot(bizType, bizNo, bizId, productId, warehouseId, qty,
            cost, cost.multiply(qty).setScale(2, RoundingMode.HALF_UP),
            partnerType, partnerId, bizDate, seq, totalQty);
    }

    private ErpStock lockMovementRow(Long productId, Long warehouseId) {
        ensureStockRow(productId, warehouseId);
        // RR 下普通读可沿用锁等待前的快照；计算只使用 FOR UPDATE 返回的当前行。
        ErpStock locked = stockMapper.selectForUpdate(productId, warehouseId);
        if (locked == null) {
            throw new ServiceException("库存记录不存在，无法锁定：" + productId, 40902);
        }
        return locked;
    }

    private record StockKey(Long productId, Long warehouseId) {
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reverse(String bizNo, Long bizId, Long productId, Long warehouseId, Date bizDate, int seq) {
        StockKey key = new StockKey(productId, warehouseId);
        ErpStock locked = lockAndRead(key);
        List<ErpStockLog> pending = pendingReversals(
            stockLogMapper.selectBillRowsForUpdate(bizNo, productId, warehouseId), bizNo);
        BigDecimal balance = locked.getQty();
        for (ErpStockLog row : pending) {
            balance = reverseLockedRow(bizNo, bizId, bizDate, seq, row, balance);
        }
        replayLockedAvgCost(productId, warehouseId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reverseByBill(String bizNo, Long bizId, Date bizDate) {
        // 这里只发现锁键，不提前读取全行或判断 VOID；固定商品+仓库锁序，保持同步事务。
        List<StockKey> keys = stockLogMapper.selectBillStockKeys(bizNo).stream()
            .map(r -> new StockKey(r.getProductId(), r.getWarehouseId())).distinct()
            .sorted(Comparator.comparing(StockKey::productId).thenComparing(StockKey::warehouseId)).toList();
        if (keys.isEmpty()) {
            throw new ServiceException("未找到可回退的库存流水：" + bizNo);
        }
        Map<StockKey, BigDecimal> balances = new HashMap<>();
        for (StockKey key : keys) {
            balances.put(key, lockAndRead(key).getQty());
        }
        // 库存锁后当前读，一次读原始行及已冲销配对；避免等待锁之后沿用旧 RR 快照写双份 VOID。
        List<ErpStockLog> pending = pendingReversals(stockLogMapper.selectBillRowsForUpdate(bizNo, null, null), bizNo);
        if (pending.stream().anyMatch(r -> !balances.containsKey(new StockKey(r.getProductId(), r.getWarehouseId())))) {
            throw new ServiceException("单据库存范围已变更，请刷新后重试：" + bizNo, 40902);
        }
        Set<StockKey> replayed = new HashSet<>();
        int seq = 0;
        for (ErpStockLog row : pending) {
            StockKey key = new StockKey(row.getProductId(), row.getWarehouseId());
            balances.put(key, reverseLockedRow(bizNo, bizId, bizDate, seq++, row, balances.get(key)));
            replayed.add(key);
        }
        // Set 去重：每个商品+仓库仅全量重放一次。检查点没有逐笔/并发等价证据，绝不启用。
        for (StockKey key : keys) {
            if (replayed.contains(key)) {
                replayLockedAvgCost(key.productId(), key.warehouseId());
            }
        }
    }

    private ErpStock lockAndRead(StockKey key) {
        ErpStock stock = stockMapper.selectForUpdate(key.productId(), key.warehouseId());
        if (stock == null) {
            ensureStockRow(key.productId(), key.warehouseId());
            stock = stockMapper.selectForUpdate(key.productId(), key.warehouseId());
        }
        if (stock == null) {
            throw new ServiceException("库存记录不存在，无法锁定：" + key.productId(), 40902);
        }
        return stock;
    }

    private List<ErpStockLog> pendingReversals(List<ErpStockLog> rows, String bizNo) {
        Set<Long> voidedOriginIds = new HashSet<>();
        for (ErpStockLog row : rows) {
            if ("VOID".equals(row.getBizType()) && row.getPartnerId() != null) {
                voidedOriginIds.add(row.getPartnerId());
            }
        }
        List<ErpStockLog> origins = rows.stream().filter(r -> !"VOID".equals(r.getBizType())).toList();
        if (origins.isEmpty()) {
            throw new ServiceException("未找到可回退的库存流水：" + bizNo);
        }
        // 先回补出库，再扣回入库；同类保持 productId、原始 id 序，不改现录现卖与生产单冲销语义。
        List<ErpStockLog> pending = origins.stream().filter(r -> !voidedOriginIds.contains(r.getId()))
            .sorted(Comparator.comparing((ErpStockLog r) -> r.getQty().compareTo(BigDecimal.ZERO) > 0)
                .thenComparing(ErpStockLog::getProductId).thenComparing(ErpStockLog::getId)).toList();
        if (pending.isEmpty()) {
            throw new ServiceException("该单据已撤回过，禁止重复回退：" + bizNo);
        }
        return pending;
    }

    private BigDecimal reverseLockedRow(String bizNo, Long bizId, Date bizDate, int seq,
                                       ErpStockLog row, BigDecimal balance) {
        BigDecimal reverseQty = row.getQty().negate();
        if (reverseQty.signum() == 0) {
            throw new ServiceException("库存流水中存在零数量记录：" + row.getId());
        }
        int affected = reverseQty.signum() > 0
            ? stockMapper.addQty(row.getProductId(), row.getWarehouseId(), reverseQty)
            : stockMapper.deductQty(row.getProductId(), row.getWarehouseId(), reverseQty.negate());
        if (affected == 0) {
            throw new ServiceException("回退后库存不足，无法撤回：" + bizNo, 40901);
        }
        BigDecimal balanceAfter = balance.add(reverseQty);
        // 已持有行锁，用同一事务内的结存累加，免去每冲销一行再 selectOne 一次。
        writeLogSnapshot("VOID", bizNo, bizId, row.getProductId(), row.getWarehouseId(), reverseQty,
            row.getUnitCost(), row.getAmount().negate(), "NONE", row.getId(), bizDate, seq, balanceAfter);
        return balanceAfter;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replayAvgCost(Long productId, Long warehouseId) {
        lockAndRead(new StockKey(productId, warehouseId));
        replayLockedAvgCost(productId, warehouseId);
    }

    /** 调用方必须已拿库存行锁；只读算法需要的四列，仍在原事务同步重放全部历史。 */
    private void replayLockedAvgCost(Long productId, Long warehouseId) {
        // D2：不引入检查点或锁外异步。当前读避免外层 RR 一致性快照漏行。
        List<ErpStockLog> logs = new ArrayList<>(stockLogMapper.selectReplayRowsForUpdate(productId, warehouseId));
        // 重放必须按"写入序"（OPENING 最前，其余按 id 升序，雪花 id 天然等于写入时间），
        // 而非业务日期序：出库流水写入时账面必然够扣（deductQty 熔断保证），
        // 按写入序重演永远不会中途为负。若按业务日期排序，后补录入库单（业务日期晚于
        // 旧出库单）会被排到出库之前，重放中途库存变负，导致撤回/作废报
        // 「流水重放出现负库存，数据异常」。业务日期只影响报表分组，不决定成本重放因果序。
        logs.sort(Comparator
            .comparing((ErpStockLog row) -> "OPENING".equals(row.getBizType()) ? 0 : 1)
            .thenComparing(ErpStockLog::getId));

        BigDecimal qty = BigDecimal.ZERO;
        BigDecimal avgCost = BigDecimal.ZERO;
        for (ErpStockLog row : logs) {
            BigDecimal delta = row.getQty();
            BigDecimal cost = row.getUnitCost() == null ? BigDecimal.ZERO : row.getUnitCost();
            boolean isVoid = "VOID".equals(row.getBizType());
            if (delta.compareTo(BigDecimal.ZERO) > 0) {
                if (isVoid) {
                    // 出库的反向流水：回补，不改变 avg_cost（出库本就不改均价）
                    qty = qty.add(delta);
                } else {
                    BigDecimal totalQty = qty.add(delta);
                    if (totalQty.compareTo(BigDecimal.ZERO) > 0) {
                        avgCost = qty.multiply(avgCost).add(delta.multiply(cost))
                            .divide(totalQty, 6, RoundingMode.HALF_UP)
                            .setScale(4, RoundingMode.HALF_UP);
                    }
                    qty = totalQty;
                }
            } else if (delta.compareTo(BigDecimal.ZERO) < 0) {
                if (isVoid) {
                    // 入库的反向流水：按其原入库成本从加权中剔除
                    BigDecimal remainQty = qty.add(delta);
                    if (remainQty.compareTo(BigDecimal.ZERO) > 0 && cost.compareTo(BigDecimal.ZERO) > 0) {
                        avgCost = qty.multiply(avgCost).subtract(delta.negate().multiply(cost))
                            .divide(remainQty, 6, RoundingMode.HALF_UP)
                            .setScale(4, RoundingMode.HALF_UP);
                    } else if (remainQty.compareTo(BigDecimal.ZERO) == 0) {
                        avgCost = BigDecimal.ZERO;
                    }
                    qty = remainQty;
                } else {
                    // 正常出库：按当前 avg_cost 转出，avg_cost 不变
                    qty = qty.add(delta);
                }
                if (qty.compareTo(BigDecimal.ZERO) < 0) {
                    throw new ServiceException("流水重放出现负库存，数据异常：产品" + productId);
                }
            } else {
                throw new ServiceException("库存流水中存在零数量记录：" + row.getId());
            }
        }
        stockMapper.updateAvgCost(productId, warehouseId, avgCost.setScale(4, RoundingMode.HALF_UP));
        if (log.isDebugEnabled()) {
            log.debug("replay avg_cost product={} warehouse={} qty={} avg={}", productId, warehouseId, qty, avgCost);
        }
    }

    private void writeLogSnapshot(String bizType, String bizNo, Long bizId, Long productId, Long warehouseId,
                                  BigDecimal qty, BigDecimal unitCost, BigDecimal amount,
                                  String partnerType, Long partnerId, Date bizDate, int seq, BigDecimal balanceAfter) {
        ErpStockLog row = new ErpStockLog();
        row.setBizType(bizType);
        row.setBizNo(bizNo);
        row.setBizId(bizId);
        row.setProductId(productId);
        row.setWarehouseId(warehouseId);
        row.setQty(qty);
        row.setUnitCost(unitCost == null ? BigDecimal.ZERO : unitCost);
        row.setAmount(amount);
        row.setPartnerType(StringUtils.isBlank(partnerType) ? "NONE" : partnerType);
        row.setPartnerId(partnerId);
        row.setBalanceAfter(balanceAfter);
        row.setBizDate(bizDate);
        row.setSeq(seq);
        stockLogMapper.insert(row);
    }

    private void ensureStockRow(Long productId, Long warehouseId) {
        Long count = stockMapper.selectCount(new LambdaQueryWrapper<ErpStock>()
            .eq(ErpStock::getProductId, productId)
            .eq(ErpStock::getWarehouseId, warehouseId));
        if (count == null || count == 0) {
            ErpStock row = new ErpStock();
            row.setProductId(productId);
            row.setWarehouseId(warehouseId);
            row.setQty(BigDecimal.ZERO);
            row.setAvgCost(BigDecimal.ZERO);
            try {
                stockMapper.insert(row);
            } catch (Exception e) {
                // 并发下唯一键冲突视为已存在
                log.debug("stock row already exists: product={} warehouse={}", productId, warehouseId);
            }
        }
    }

    @Override
    public BigDecimal getQty(Long productId, Long warehouseId) {
        ErpStock row = stockMapper.selectOne(new LambdaQueryWrapper<ErpStock>()
            .eq(ErpStock::getProductId, productId)
            .eq(ErpStock::getWarehouseId, warehouseId));
        return row == null ? BigDecimal.ZERO : row.getQty();
    }

    @Override
    public BigDecimal getAvgCost(Long productId, Long warehouseId) {
        ErpStock row = stockMapper.selectOne(new LambdaQueryWrapper<ErpStock>()
            .eq(ErpStock::getProductId, productId)
            .eq(ErpStock::getWarehouseId, warehouseId));
        return row == null ? BigDecimal.ZERO : row.getAvgCost();
    }

    @Override
    @Transactional(readOnly = true)
    public TableDataInfo<ErpStockVo> pageStock(String keyword, String type, int pageNum, int pageSize) {
        StockPage page = StockPage.of(pageNum, pageSize);
        type = StringUtils.isBlank(type) ? null : type.strip();
        if (type != null && !"PRODUCT".equals(type) && !"MATERIAL".equals(type)) {
            throw new ServiceException("type 只能为 PRODUCT 或 MATERIAL", 400);
        }
        String pattern = null;
        if (keyword != null && !keyword.isBlank()) {
            String value = keyword.strip().toLowerCase(Locale.ROOT);
            if (value.codePointCount(0, value.length()) > 100) {
                throw new ServiceException("keyword 最多 100 个字符", 400);
            }
            pattern = "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        }
        long total = stockMapper.countBalances(pattern, type);
        List<ErpStockVo> rows = page.offset() >= total ? List.of()
            : stockMapper.selectBalancePage(pattern, type, page.offset(), page.size());
        for (ErpStockVo vo : rows) {
            vo.setStockAmount(vo.getQty().multiply(vo.getAvgCost()).setScale(2, RoundingMode.HALF_UP));
            vo.setCostMissing(vo.getQty().signum() > 0 && vo.getAvgCost().signum() <= 0);
        }
        return new TableDataInfo<>(rows, total);
    }

    @Override
    public List<ErpStockQuantityVo> quantityTotals(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 200 || ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new ServiceException("ids 必须包含 1–200 个正 ID", 400);
        }
        return stockMapper.selectQuantityTotals(new ArrayList<>(new LinkedHashSet<>(ids)));
    }

    @Override
    public ErpStockVo snapshot(Long productId) {
        // 单产品库存快照：主仓（最小 warehouseId）口径，与 snapshotCost 一致
        List<ErpStock> rows = stockMapper.selectList(new LambdaQueryWrapper<ErpStock>()
            .eq(ErpStock::getProductId, productId)
            .orderByAsc(ErpStock::getWarehouseId));
        ErpStockVo vo = new ErpStockVo();
        vo.setProductId(productId);
        if (!rows.isEmpty()) {
            ErpStock row = rows.get(0);
            vo.setWarehouseId(row.getWarehouseId());
            vo.setQty(row.getQty());
            vo.setAvgCost(row.getAvgCost());
            vo.setStockAmount(row.getQty().multiply(row.getAvgCost()).setScale(2, RoundingMode.HALF_UP));
            vo.setCostMissing(row.getQty().signum() > 0 && row.getAvgCost().signum() <= 0);
        } else {
            vo.setQty(BigDecimal.ZERO);
            vo.setAvgCost(BigDecimal.ZERO);
            vo.setStockAmount(BigDecimal.ZERO);
            vo.setCostMissing(false);
        }
        ErpProduct p = productMapper.selectById(productId);
        if (p != null) {
            vo.setProductCode(p.getCode());
            vo.setProductName(p.getName());
            vo.setSpec(p.getSpec());
            vo.setUnit(p.getUnit());
            vo.setType(p.getType());
        }
        return vo;
    }

    @Override
    public ErpStockLogPageVo pageLogs(Long productId, LocalDate startDate, LocalDate endDate, int pageSize,
                                     LocalDate beforeDate, Long beforeId) {
        StockPage.checkSize(pageSize);
        if (productId == null || productId <= 0 || startDate == null || endDate == null
            || endDate.isBefore(startDate) || ChronoUnit.DAYS.between(startDate, endDate) > 365) {
            throw new ServiceException("必须提供正 productId 及至多 366 天（含首尾）的 startDate/endDate", 400);
        }
        if ((beforeDate == null) != (beforeId == null) || (beforeId != null && (beforeId <= 0
            || beforeDate.isBefore(startDate) || beforeDate.isAfter(endDate)))) {
            throw new ServiceException("beforeDate/beforeId 必须成对提供，且在查询日期窗内", 400);
        }
        List<ErpStockLogVo> fetched = stockLogMapper.selectLogPage(productId, startDate, endDate, beforeDate, beforeId,
            pageSize + 1);
        boolean hasMore = fetched.size() > pageSize;
        List<ErpStockLogVo> rows = hasMore ? new ArrayList<>(fetched.subList(0, pageSize)) : fetched;
        // 往来单位名称批量查，避免 N+1
        List<Long> supplierIds = rows.stream()
            .filter(r -> "SUPPLIER".equals(r.getPartnerType()))
            .map(ErpStockLogVo::getPartnerId).filter(Objects::nonNull).distinct().toList();
        List<Long> customerIds = rows.stream()
            .filter(r -> "CUSTOMER".equals(r.getPartnerType()))
            .map(ErpStockLogVo::getPartnerId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> supplierNames = new HashMap<>();
        if (!supplierIds.isEmpty()) {
            for (ErpSupplier s : supplierMapper.selectByIds(supplierIds)) {
                supplierNames.put(s.getId(), s.getName());
            }
        }
        Map<Long, String> customerNames = new HashMap<>();
        if (!customerIds.isEmpty()) {
            for (ErpCustomer c : customerMapper.selectByIds(customerIds)) {
                customerNames.put(c.getId(), c.getName());
            }
        }
        for (ErpStockLogVo row : rows) {
            row.setPartnerName(resolvePartnerName(row.getPartnerType(), row.getPartnerId(), supplierNames, customerNames));
        }
        ErpStockLogVo last = hasMore ? rows.get(rows.size() - 1) : null;
        return new ErpStockLogPageVo(rows, hasMore, last == null ? null : last.getBizDate(), last == null ? null : last.getId());
    }

    private String resolvePartnerName(String partnerType, Long partnerId,
                                      Map<Long, String> supplierNames, Map<Long, String> customerNames) {
        if (partnerId == null || partnerType == null) {
            return null;
        }
        if ("SUPPLIER".equals(partnerType)) {
            return supplierNames.get(partnerId);
        }
        if ("CUSTOMER".equals(partnerType)) {
            return customerNames.get(partnerId);
        }
        // NONE / 其他类型无往来单位
        return null;
    }

    @Override
    public BigDecimal snapshotCost(Long productId) {
        // 默认主仓：取最小 warehouseId 的 avg_cost；v1 单仓
        List<ErpStock> rows = stockMapper.selectList(new LambdaQueryWrapper<ErpStock>()
            .eq(ErpStock::getProductId, productId)
            .orderByAsc(ErpStock::getWarehouseId));
        return rows.isEmpty() ? BigDecimal.ZERO : rows.get(0).getAvgCost();
    }

    @Override
    public Map<Long, BigDecimal> snapshotCostBatch(List<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Map.of();
        }
        // 全量捞出后按 warehouseId 升序取每个产品第一条（主仓口径，与 snapshotCost 一致），避免逐个查询
        List<ErpStock> rows = stockMapper.selectList(new LambdaQueryWrapper<ErpStock>()
            .in(ErpStock::getProductId, productIds)
            .orderByAsc(ErpStock::getWarehouseId));
        Map<Long, BigDecimal> result = new HashMap<>();
        for (ErpStock row : rows) {
            result.putIfAbsent(row.getProductId(), row.getAvgCost() == null ? BigDecimal.ZERO : row.getAvgCost());
        }
        return result;
    }

    @Override
    public BigDecimal totalStockAmount() {
        // 库存资产口径：全仓 Σ 数量 × 移动加权均价，与余额行金额口径一致
        return stockMapper.selectList(new LambdaQueryWrapper<ErpStock>()
                .select(ErpStock::getQty, ErpStock::getAvgCost))
            .stream()
            .map(r -> (r.getQty() == null ? BigDecimal.ZERO : r.getQty())
                .multiply(r.getAvgCost() == null ? BigDecimal.ZERO : r.getAvgCost()))
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 数量精度校验（DOC/26 §10.5）：按商品数量精度判断有效小数位，超精度拒绝写入。
     * 仅拦截新增写入的数量；撤回 reverse 与重放 replayAvgCost 不经过此方法，历史数据不受影响。
     *
     * @param productId 商品ID（查不到时跳过校验，由调用方保证商品存在）
     * @param qty       待写入数量，正负均可，按绝对值判断（盘点差异为负）
     */
    private void checkQtyScale(Long productId, BigDecimal qty) {
        ErpProduct product = productMapper.selectById(productId);
        if (product == null) {
            return;
        }
        int allowed = product.getQtyScale() == null ? 0 : product.getQtyScale();
        // 去掉末尾 0 后的有效小数位，避免 1.500 被误判成 3 位
        int scale = Math.max(0, qty.stripTrailingZeros().scale());
        if (scale > allowed) {
            throw new ServiceException("商品「" + product.getName() + "」数量最多支持 " + allowed + " 位小数");
        }
    }

    /**
     * 按 product_id 升序一次性锁齐本单涉及的全部库存行（DOC/31 §6.8.1）。
     * 去重、排序在这里完成，调用方明细顺序任意；加锁顺序固定后，与 inbound/outbound 的逐行补锁不会成环。
     *
     * @param productIds 本单涉及的商品ID集合，可空（空则不加锁）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void lockStockRows(List<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return;
        }
        List<Long> ordered = productIds.stream().filter(Objects::nonNull).distinct().sorted().toList();
        if (ordered.isEmpty()) {
            return;
        }
        // 库存行可能尚不存在（首次入库），此时无锁可拿，由后续 inbound 的 ensureStockRow 兜底
        stockMapper.selectByProductIdsForUpdate(ordered);
    }
}
