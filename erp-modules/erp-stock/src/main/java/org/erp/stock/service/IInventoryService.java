package org.erp.stock.service;

import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.erp.stock.domain.vo.ErpStockLogPageVo;
import org.erp.stock.domain.vo.ErpStockQuantityVo;
import org.erp.stock.domain.vo.ErpStockVo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 库存服务接口：所有库存变更唯一入口
 */
public interface IInventoryService {

    /**
     * 出库（条件扣减，防超卖）。库存不足抛 40901。
     *
     * @return 写入流水的出库单位成本（出库仓锁定行的当前 avg_cost），供同单据冻结成本
     */
    BigDecimal outbound(String bizType, String bizNo, Long bizId, Long productId, Long warehouseId,
                        BigDecimal qty, String partnerType, Long partnerId, Date bizDate, int seq);

    /**
     * 出库（条件扣减，防超卖），成本快照由调用方指定（DOC/24 §2.6：退货按原单行成本回冲）。
     * 仅落流水 unit_cost/amount，不影响均价——正常出库在重放中本就不改 avg_cost。
     *
     * @param unitCost 出库成本快照（调用方给定，不取当前 avg_cost）
     */
    void outbound(String bizType, String bizNo, Long bizId, Long productId, Long warehouseId,
                  BigDecimal qty, BigDecimal unitCost, String partnerType, Long partnerId,
                  Date bizDate, int seq);

    /**
     * 入库（回补 + 加权成本更新）。
     */
    void inbound(String bizType, String bizNo, Long bizId, Long productId, Long warehouseId,
                 BigDecimal qty, BigDecimal unitCost, String partnerType, Long partnerId,
                 Date bizDate, int seq);

    /**
     * 写反向流水并回补库存（撤回/作废），然后按流水重放重算 avg_cost。
     */
    void reverse(String bizNo, Long bizId, Long productId, Long warehouseId, Date bizDate, int seq);

    /**
     * 单据级撤回：一次冲销该单据全部未冲销流水，同商品多行也只冲一次。
     * 先按 product_id 升序锁齐本单涉及的库存行，再回退，最后逐商品重放均价（P0-2）。
     *
     * @param bizNo   单据号
     * @param bizId   单据ID
     * @param bizDate 冲销业务日期
     */
    void reverseByBill(String bizNo, Long bizId, Date bizDate);

    /**
     * 按 product_id 升序一次性锁齐本单涉及的全部 st_stock 行（事务内 FOR UPDATE）。
     * 确认路径必须在逐行 inbound/outbound 之前先调用：逐行加锁时两张单据商品顺序相反会互相等待成环
     * （DOC/31 §6.8.1）。去重、排序在此完成，调用方传入任意顺序均可。
     *
     * @param productIds 本单涉及的商品ID集合，可空；空则不加锁
     */
    void lockStockRows(List<Long> productIds);



    /**
     * 持库存行锁重演全部历史数量并重算 avg_cost（D2 算法），数量快照仍由原库存变更更新。
     * 没有逐笔/并发证据的检查点不启用，不允许锁外异步重算。
     */
    void replayAvgCost(Long productId, Long warehouseId);

    BigDecimal getQty(Long productId, Long warehouseId);

    BigDecimal getAvgCost(Long productId, Long warehouseId);

    TableDataInfo<ErpStockVo> pageStock(String keyword, String type, int pageNum, int pageSize);

    List<ErpStockQuantityVo> quantityTotals(List<Long> ids);

    /**
     * 单产品库存快照（主仓口径：最小 warehouseId），含产品编码/名称/规格/单位/类型。
     */
    ErpStockVo snapshot(Long productId);

    ErpStockLogPageVo pageLogs(Long productId, LocalDate startDate, LocalDate endDate, int pageSize,
                              LocalDate beforeDate, Long beforeId);

    /**
     * 按当前 avg_cost 快照出库成本。
     */
    BigDecimal snapshotCost(Long productId);

    /**
     * 批量按当前 avg_cost 快照出库成本（主仓口径，与 snapshotCost 一致），供列表预估成本使用。
     *
     * @param productIds 产品ID集合
     * @return productId → 主仓 avg_cost；无库存记录的产品不在结果中
     */
    Map<Long, BigDecimal> snapshotCostBatch(List<Long> productIds);

    /**
     * 全仓库存金额 = Σ st_stock.qty × avg_cost（2 位小数，DOC/26 §9.5 综合成本参考块）。
     */
    BigDecimal totalStockAmount();
}
