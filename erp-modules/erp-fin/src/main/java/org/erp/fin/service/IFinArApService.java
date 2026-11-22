package org.erp.fin.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.erp.fin.domain.vo.FinArApSummaryVo;
import org.erp.fin.domain.vo.FinArApSummaryPageVo;
import org.erp.fin.domain.vo.FinArApVo;
import org.erp.fin.domain.vo.FinArApSourceVo;
import java.util.Collection;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 应收应付台账服务
 *
 * 台账只增不改（除核销/状态流转）；业务单据确认时写入，撤回/作废时冲销。
 */
public interface IFinArApService {

    /**
     * 确认销售单时生成应收（partyType=AR, sourceType=SALE）。
     * 幂等：同 source 已存在非 VOID 台账则抛 40902。
     */
    void createBySale(Long saleId, String saleNo, Long customerId, BigDecimal amount, Date bizDate);

    /**
     * 确认采购单时生成应付（partyType=AP, sourceType=PURCHASE，金额含运杂费）。
     * 幂等：同 source 已存在非 VOID 台账则抛 40902。
     */
    void createByPurchase(Long purchaseId, String purchaseNo, Long supplierId, BigDecimal amount, Date bizDate);

    /**
     * 期初确认（D1）：fin_opening 确认后落台账 sourceType=OPENING。
     * 同 party 同类型已存在 OPENING 台账则先冲销旧期初（期初可重录，取最新）。
     */
    void createOpening(String partyType, Long partyId, BigDecimal amount, Long openingId, Date bizDate);

    /**
     * 确认销售退货时生成负数应收行（DOC/24 §6.3，sourceType=SALE_RETURN，amount<0）。
     * 幂等：同 source 已存在非 VOID 台账则抛 40902；原销售单 AR 行不动。
     */
    void createBySaleReturn(Long returnId, String returnNo, Long customerId, BigDecimal amount, Date bizDate);

    /**
     * 确认采购退货时生成负数应付行（DOC/24 §6.5，sourceType=PURCHASE_RETURN，amount<0）。
     */
    void createByPurchaseReturn(Long returnId, String returnNo, Long supplierId, BigDecimal amount, Date bizDate);

    /**
     * 按来源取非 VOID 台账行（DOC/24 §3.1）：退货退款取原单 AR 行 id 与 settled_amount。
     * 不存在时返回 null。
     */
    FinArApVo getBySource(String sourceType, Long sourceId);

    /**
     * 单据撤回/作废时冲销台账：将该 source 下全部非 VOID 台账置 VOID。
     * settled_amount > 0 抛 40904（已核销需先反核销）；
     * 无台账时幂等成功（生产单等无台账来源）。
     */
    void voidBySource(String sourceType, Long sourceId);

    /**
     * 台账分页查询。
     */
    TableDataInfo<FinArApVo> selectPage(String partyType, Long partyId, String status, PageQuery pageQuery);

    /**
     * 兼容旧汇总形状：完整返回至多 5000 个往来单位，超过则明确拒绝，绝不截断。
     * 交互页面应使用 summaryPage；金额始终为全部匹配非 VOID 台账的合计。
     */
    FinArApSummaryVo summary(String partyType);

    /** 金额为全量数据库聚合，往来单位分组在数据库分页（每页 1..200）。 */
    FinArApSummaryPageVo summaryPage(String partyType, int pageNum, int pageSize);

    /**
     * 兼容旧明细：完整返回至多 5000 条非 VOID 明细，超过明确拒绝。
     * 不隐式排除 SETTLED/负数行，不把第一页冒充完整列表。
     */
    List<FinArApVo> listByParty(String partyType, Long partyId);

    /** 收/付款表单：完整返回至多 5000 条余额为正的非 VOID 台账，超限拒绝，不截断。 */
    List<FinArApVo> listAllocatableByParty(String partyType, Long partyId);

    /** 单客户/供应商明细在数据库分页；按业务日期、ID 升序，每页 1..200。 */
    TableDataInfo<FinArApVo> pageByParty(String partyType, Long partyId, int pageNum, int pageSize);

    /**
     * 核销：累计 settled_amount 并推进状态（OPEN/PARTIAL → SETTLED）。
     * 校验：台账存在且未作废、归属该 party、行核销 ≤ 未核销余额。
     */
    void settle(Long arApId, BigDecimal amount, String partyType, Long partyId);

    /**
     * 反核销：settled_amount 减回并回退状态（SETTLED → PARTIAL/OPEN）。
     * 校验：台账存在、反核销额 ≤ 已核销额。
     */
    void unsettle(Long arApId, BigDecimal amount, String partyType, Long partyId);

    /**
     * 对销售退货退款反核销：严格验证 AR、客户归属及 SALE 来源，金额不能超过已核销余额。
     */
    void unsettleSaleReturnRefund(Long arApId, BigDecimal amount, Long customerId, Long saleId);

    /**
     * 撤回受控销售退货退款后恢复原 SALE 应收的已核销额。
     */
    void settleSaleReturnRefund(Long arApId, BigDecimal amount, Long customerId, Long saleId);

    /** 持统一往来锁后按当前值统计非 OPENING 台账及已确认收付款，时间上界不含。 */
    long countBusinessBefore(String partyType, Long partyId, Date before);

    /** 来源回显必须绑定当前收/付款的方向和往来单位；数据库逐批至多 200 个去重 ID。 */
    List<FinArApSourceVo> listSourceInfo(Collection<Long> arApIds, String partyType, Long partyId);

    /** 旧签名仅保留二进制/源码契约；非空调用明确拒绝，不能推断授权范围。 */
    @Deprecated
    List<FinArApSourceVo> listSourceInfo(Collection<Long> arApIds);
}
