package org.erp.print.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.erp.print.domain.PrintTemplate;
import org.erp.print.service.impl.PrintContext;
import org.erp.print.service.impl.PrintLedgerMovement;
import org.erp.print.service.impl.PrintStatementLine;
import org.erp.print.service.impl.PrintLedgerSource;
import org.erp.print.service.impl.PrintDocumentNo;
import org.erp.print.service.impl.PrintCustomerName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Tenant-scoped finance projections used only by print/reconciliation. */
@Mapper
public interface PrintFinanceMapper {

    /** Null means no effective source ledger; a settled source still returns zero. */
    @Select("""
        select sum(amount - settled_amount)
        from fin_ar_ap
        where tenant_id = #{tenantId}
          and party_type = #{partyType}
          and party_id = #{partyId}
          and source_type = #{sourceType}
          and source_id = #{sourceId}
          and status in ('OPEN', 'PARTIAL', 'SETTLED')
          and del_flag = '0'
        """)
    BigDecimal sumUnsettledBySource(@Param("tenantId") String tenantId,
                                    @Param("partyType") String partyType,
                                    @Param("partyId") Long partyId,
                                    @Param("sourceType") String sourceType,
                                    @Param("sourceId") Long sourceId);

    /** Called only after the independent print-profit permission has been checked. */
    @Select("""
        select cost_amount as costAmount, gross_profit as grossProfit, gross_margin as grossMargin
        from biz_sale
        where tenant_id = #{tenantId} and id = #{saleId} and del_flag = '0'
        """)
    PrintContext.Profit selectSaleProfit(@Param("tenantId") String tenantId, @Param("saleId") Long saleId);

    /** Signed movement totals at the period boundary, using current effective documents, not current settlement. */
    @Select("""
        select 'OPENING' as sourceType,
               sum(case when coalesce(o.opening_date, a.biz_date) < #{from} then a.amount else 0 end) as priorAmount,
               sum(case when coalesce(o.opening_date, a.biz_date) >= #{from} then a.amount else 0 end) as periodAmount
        from fin_ar_ap a
        left join fin_opening o on o.id = a.source_id and o.tenant_id = a.tenant_id and o.del_flag = '0'
        where a.tenant_id = #{tenantId} and a.party_type = 'AR' and a.party_id = #{partyId}
          and a.source_type = 'OPENING' and a.status in ('OPEN', 'PARTIAL', 'SETTLED')
          and a.del_flag = '0' and coalesce(o.opening_date, a.biz_date) < #{to}
        union all
        select 'SALE',
               sum(case when s.biz_date < #{from} then s.total_amount else 0 end),
               sum(case when s.biz_date >= #{from} then s.total_amount else 0 end)
        from biz_sale s
        where s.tenant_id = #{tenantId} and s.customer_id = #{partyId}
          and s.status = 'CONFIRMED' and s.del_flag = '0' and s.biz_date < #{to}
        union all
        select 'SALE_RETURN',
               sum(case when r.biz_date < #{from} then -r.total_amount else 0 end),
               sum(case when r.biz_date >= #{from} then -r.total_amount else 0 end)
        from biz_sale_return r
        where r.tenant_id = #{tenantId} and r.customer_id = #{partyId}
          and r.status = 'CONFIRMED' and r.del_flag = '0' and r.biz_date < #{to}
        union all
        select case when coalesce(f.negative, 0) = 1 then 'REFUND' else 'RECEIPT' end,
               sum(case when f.biz_date < #{from} then
                       case when coalesce(f.negative, 0) = 1 then f.amount else -f.amount end else 0 end),
               sum(case when f.biz_date >= #{from} then
                       case when coalesce(f.negative, 0) = 1 then f.amount else -f.amount end else 0 end)
        from fin_receipt f
        where f.tenant_id = #{tenantId} and f.customer_id = #{partyId}
          and f.status = 'CONFIRMED' and f.del_flag = '0' and f.biz_date < #{to}
        group by case when coalesce(f.negative, 0) = 1 then 'REFUND' else 'RECEIPT' end
        """)
    List<PrintLedgerMovement> selectStatementMovements(@Param("tenantId") String tenantId,
                                                        @Param("partyId") Long partyId,
                                                        @Param("from") LocalDate from,
                                                        @Param("to") LocalDate to);

    /** Batched statement lines; no per-sale item/product Mapper fanout. */
    @Select("""
        select line.eventDate, line.documentNo, line.content, line.productName, line.qty,
               line.qtyScale, line.price, line.signedAmount
        from (
          select s.biz_date as eventDate, s.no as documentNo, '销售' as content,
                 p.name as productName, i.qty as qty, p.qty_scale as qtyScale,
                 i.price as price, case when i.id is null then s.total_amount else coalesce(i.amount, 0) end as signedAmount,
                 1 as kind, s.id as docId, i.seq as lineSeq, i.id as lineId
          from biz_sale s
          left join biz_sale_item i on i.order_id = s.id and i.tenant_id = s.tenant_id and i.del_flag = '0'
          left join md_product p on p.id = i.product_id and p.tenant_id = i.tenant_id and p.del_flag = '0'
          where s.tenant_id = #{tenantId} and s.customer_id = #{partyId} and s.status = 'CONFIRMED'
            and s.del_flag = '0' and s.biz_date >= #{from} and s.biz_date < #{to}
          union all
          select r.biz_date, r.no, '销售退货', p.name, i.qty, p.qty_scale, i.price,
                 case when i.id is null then -r.total_amount else -coalesce(i.amount, 0) end,
                 2, r.id, i.seq, i.id
          from biz_sale_return r
          left join biz_sale_return_item i on i.order_id = r.id and i.tenant_id = r.tenant_id and i.del_flag = '0'
          left join md_product p on p.id = i.product_id and p.tenant_id = i.tenant_id and p.del_flag = '0'
          where r.tenant_id = #{tenantId} and r.customer_id = #{partyId} and r.status = 'CONFIRMED'
            and r.del_flag = '0' and r.biz_date >= #{from} and r.biz_date < #{to}
          union all
          select f.biz_date, f.no, case when coalesce(f.negative, 0) = 1 then '退款' else '收款' end,
                 null, null, null, null, case when coalesce(f.negative, 0) = 1 then f.amount else -f.amount end,
                 3, f.id, null, null
          from fin_receipt f
          where f.tenant_id = #{tenantId} and f.customer_id = #{partyId}
            and f.status = 'CONFIRMED' and f.del_flag = '0' and f.biz_date >= #{from} and f.biz_date < #{to}
          union all
          select coalesce(o.opening_date, a.biz_date), '期初', '建账期初', null, null, null, null, a.amount, 0, a.id, null, null
          from fin_ar_ap a
          left join fin_opening o on o.id = a.source_id and o.tenant_id = a.tenant_id and o.del_flag = '0'
          where a.tenant_id = #{tenantId} and a.party_type = 'AR' and a.party_id = #{partyId}
            and a.source_type = 'OPENING' and a.status in ('OPEN', 'PARTIAL', 'SETTLED')
            and a.del_flag = '0' and coalesce(o.opening_date, a.biz_date) >= #{from}
            and coalesce(o.opening_date, a.biz_date) < #{to}
        ) line
        order by line.eventDate, line.documentNo, line.kind, line.docId, line.lineSeq, line.lineId
        limit 5001
        """)
    List<PrintStatementLine> selectStatementLines(@Param("tenantId") String tenantId,
                                                   @Param("partyId") Long partyId,
                                                   @Param("from") LocalDate from,
                                                   @Param("to") LocalDate to);

    @Select("""
        <script>
        select id, source_type as sourceType, source_id as sourceId, biz_date as bizDate
        from fin_ar_ap where tenant_id = #{tenantId} and del_flag = '0' and id in
          <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>
        </script>
        """)
    List<PrintLedgerSource> selectLedgerSources(@Param("tenantId") String tenantId,
                                                 @Param("ids") List<Long> ids);

    @Select("""
        <script>
        select id, no as documentNo from biz_sale where tenant_id = #{tenantId} and del_flag = '0' and id in
          <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>
        </script>
        """)
    List<PrintDocumentNo> selectSaleNumbers(@Param("tenantId") String tenantId,
                                             @Param("ids") List<Long> ids);

    @Select("""
        <script>
        select id, no as documentNo from biz_purchase where tenant_id = #{tenantId} and del_flag = '0' and id in
          <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>
        </script>
        """)
    List<PrintDocumentNo> selectPurchaseNumbers(@Param("tenantId") String tenantId,
                                                 @Param("ids") List<Long> ids);

    @Select("""
        <script>
        select id, name from md_customer where tenant_id = #{tenantId} and del_flag = '0' and id in
          <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>
        </script>
        """)
    List<PrintCustomerName> selectCustomerNames(@Param("tenantId") String tenantId,
                                                 @Param("ids") List<Long> ids);

    /** Narrow list projection; content/schema_json/variables_json are not fetched. */
    @Select("""
        <script>
        select id, code, name, scene, customer_id, paper, version, is_default, status
        from print_template
        where tenant_id = #{tenantId} and del_flag = '0'
        <if test='scene != null and scene.trim() != ""'>and scene = #{scene}</if>
        <if test='keyword != null and keyword.trim() != ""'>
          and (name like concat('%', #{keyword}, '%') or code like concat('%', #{keyword}, '%'))
        </if>
        order by id desc
        </script>
        """)
    IPage<PrintTemplate> selectLightPage(IPage<PrintTemplate> page,
                                         @Param("tenantId") String tenantId,
                                         @Param("scene") String scene,
                                         @Param("keyword") String keyword);
}
