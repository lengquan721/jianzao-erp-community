package org.erp.trade.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.erp.trade.domain.vo.DashboardAggregateVo;

import java.util.Date;
import java.util.List;

/**
 * 工作台聚合查询。原生汇总 SQL 不依赖租户拦截器，所有表分支显式限定 tenant_id 与 del_flag。
 */
@Mapper
public interface DashboardAggregateMapper {

    @Select("""
        select sum(s.total_amount) as amount, sum(s.gross_profit) as grossProfit
        from biz_sale s
        where s.tenant_id = #{tenantId} and s.del_flag = '0' and s.status = 'CONFIRMED'
          and s.biz_date >= #{from} and s.biz_date < #{to}
        """)
    DashboardAggregateVo monthSales(@Param("tenantId") String tenantId, @Param("from") Date from, @Param("to") Date to);

    @Select("""
        select sum(p.total_amount) as amount
        from biz_purchase p
        where p.tenant_id = #{tenantId} and p.del_flag = '0' and p.status = 'CONFIRMED'
          and p.biz_date >= #{from} and p.biz_date < #{to}
        """)
    DashboardAggregateVo monthPurchases(@Param("tenantId") String tenantId, @Param("from") Date from, @Param("to") Date to);

    @Select("""
        select sum(case when r.negative = 1 then -r.amount else r.amount end) as amount
        from fin_receipt r
        where r.tenant_id = #{tenantId} and r.del_flag = '0' and r.status = 'CONFIRMED'
          and r.biz_date >= #{from} and r.biz_date < #{to}
        """)
    DashboardAggregateVo monthReceipts(@Param("tenantId") String tenantId, @Param("from") Date from, @Param("to") Date to);

    @Select("""
        select date_format(s.biz_date, '%Y-%m') as month,
               sum(s.total_amount) as amount, sum(s.cost_amount) as cost
        from biz_sale s
        where s.tenant_id = #{tenantId} and s.del_flag = '0' and s.status = 'CONFIRMED'
          and s.biz_date >= #{from} and s.biz_date < #{to}
        group by date_format(s.biz_date, '%Y-%m')
        """)
    List<DashboardAggregateVo> trendSales(@Param("tenantId") String tenantId, @Param("from") Date from, @Param("to") Date to);

    @Select("""
        select date_format(r.biz_date, '%Y-%m') as month,
               sum(case when r.negative = 1 then -r.amount else r.amount end) as amount
        from fin_receipt r
        where r.tenant_id = #{tenantId} and r.del_flag = '0' and r.status = 'CONFIRMED'
          and r.biz_date >= #{from} and r.biz_date < #{to}
        group by date_format(r.biz_date, '%Y-%m')
        """)
    List<DashboardAggregateVo> trendReceipts(@Param("tenantId") String tenantId, @Param("from") Date from, @Param("to") Date to);

    @Select("""
        select sum(c.amount) as amount
        from fin_cost_ledger c
        where c.tenant_id = #{tenantId} and c.del_flag = '0' and c.status = 'ACTIVE'
          and c.cost_type = #{costType}
          and c.biz_date >= #{from} and c.biz_date < #{to}
        """)
    DashboardAggregateVo costLedgerSum(@Param("tenantId") String tenantId, @Param("costType") String costType,
                                       @Param("from") Date from, @Param("to") Date to);

    @Select("""
        select sum(s.total_amount) as amount, sum(s.cost_amount) as cost
        from biz_sale s
        where s.tenant_id = #{tenantId} and s.del_flag = '0' and s.status = 'CONFIRMED'
          and s.biz_date >= #{from} and s.biz_date < #{to}
        """)
    DashboardAggregateVo salesCostSummary(@Param("tenantId") String tenantId, @Param("from") Date from,
                                          @Param("to") Date to);

    /** 成本报表按退货单业务日期汇总，复用销售汇总的 amount/cost 投影。 */
    @SelectProvider(type = SaleReturnCostSql.class, method = "summary")
    DashboardAggregateVo salesReturnCostSummary(@Param("tenantId") String tenantId, @Param("from") Date from,
                                                @Param("to") Date to);

    final class SaleReturnCostSql {
        private SaleReturnCostSql() {
        }

        public static String summary() {
            return """
                select sum(r.total_amount) as amount, sum(r.return_cost_amount) as cost
                from biz_sale_return r
                where r.tenant_id = #{tenantId} and r.del_flag = '0' and r.status = 'CONFIRMED'
                  and r.biz_date >= #{from} and r.biz_date < #{to}
                """;
        }
    }

    @Select("""
        select date_format(c.biz_date, '%Y-%m') as month, sum(c.amount) as amount
        from fin_cost_ledger c
        where c.tenant_id = #{tenantId} and c.del_flag = '0' and c.status = 'ACTIVE'
          and c.cost_type in ('OTHER_OUT', 'OTHER_EXPENSE')
          and c.biz_date >= #{from} and c.biz_date < #{to}
        group by date_format(c.biz_date, '%Y-%m')
        """)
    List<DashboardAggregateVo> trendOtherCosts(@Param("tenantId") String tenantId, @Param("from") Date from, @Param("to") Date to);

    @Select("""
        select sum(s.total_amount) as amount, sum(s.gross_profit) as grossProfit, count(*) as docCount
        from biz_sale s
        where s.tenant_id = #{tenantId} and s.del_flag = '0' and s.status = 'CONFIRMED'
          and s.customer_id = #{partyId} and s.biz_date >= #{from} and s.biz_date < #{to}
        """)
    DashboardAggregateVo customerSales(@Param("tenantId") String tenantId, @Param("partyId") Long partyId,
                                       @Param("from") Date from, @Param("to") Date to);

    @Select("""
        select sum(case when r.negative = 1 then -r.amount else r.amount end) as amount
        from fin_receipt r
        where r.tenant_id = #{tenantId} and r.del_flag = '0' and r.status = 'CONFIRMED'
          and r.customer_id = #{partyId} and r.biz_date >= #{from} and r.biz_date < #{to}
        """)
    DashboardAggregateVo customerReceipts(@Param("tenantId") String tenantId, @Param("partyId") Long partyId,
                                          @Param("from") Date from, @Param("to") Date to);

    @Select("""
        select sum(p.total_amount) as amount, count(*) as docCount
        from biz_purchase p
        where p.tenant_id = #{tenantId} and p.del_flag = '0' and p.status = 'CONFIRMED'
          and p.supplier_id = #{partyId} and p.biz_date >= #{from} and p.biz_date < #{to}
        """)
    DashboardAggregateVo supplierPurchases(@Param("tenantId") String tenantId, @Param("partyId") Long partyId,
                                           @Param("from") Date from, @Param("to") Date to);

    @Select("""
        select sum(p.amount) as amount
        from fin_payment p
        where p.tenant_id = #{tenantId} and p.del_flag = '0' and p.status = 'CONFIRMED'
          and p.supplier_id = #{partyId} and p.biz_date >= #{from} and p.biz_date < #{to}
        """)
    DashboardAggregateVo supplierPayments(@Param("tenantId") String tenantId, @Param("partyId") Long partyId,
                                          @Param("from") Date from, @Param("to") Date to);

    @Select("""
        select s.customer_id as partyId, sum(s.total_amount) as amount,
               sum(s.gross_profit) as grossProfit, count(*) as docCount
        from biz_sale s
        where s.tenant_id = #{tenantId} and s.del_flag = '0' and s.status = 'CONFIRMED'
          and s.customer_id is not null and s.biz_date >= #{from} and s.biz_date < #{to}
        group by s.customer_id
        """)
    List<DashboardAggregateVo> rankCustomerSales(@Param("tenantId") String tenantId, @Param("from") Date from, @Param("to") Date to);

    @Select("""
        select r.customer_id as partyId,
               sum(case when r.negative = 1 then -r.amount else r.amount end) as amount
        from fin_receipt r
        where r.tenant_id = #{tenantId} and r.del_flag = '0' and r.status = 'CONFIRMED'
          and r.customer_id is not null and r.biz_date >= #{from} and r.biz_date < #{to}
        group by r.customer_id
        """)
    List<DashboardAggregateVo> rankCustomerReceipts(@Param("tenantId") String tenantId, @Param("from") Date from, @Param("to") Date to);

    @Select("""
        select p.supplier_id as partyId, sum(p.total_amount) as amount, count(*) as docCount
        from biz_purchase p
        where p.tenant_id = #{tenantId} and p.del_flag = '0' and p.status = 'CONFIRMED'
          and p.supplier_id is not null and p.biz_date >= #{from} and p.biz_date < #{to}
        group by p.supplier_id
        """)
    List<DashboardAggregateVo> rankSupplierPurchases(@Param("tenantId") String tenantId, @Param("from") Date from, @Param("to") Date to);

    @Select("""
        select p.supplier_id as partyId, sum(p.amount) as amount
        from fin_payment p
        where p.tenant_id = #{tenantId} and p.del_flag = '0' and p.status = 'CONFIRMED'
          and p.supplier_id is not null and p.biz_date >= #{from} and p.biz_date < #{to}
        group by p.supplier_id
        """)
    List<DashboardAggregateVo> rankSupplierPayments(@Param("tenantId") String tenantId, @Param("from") Date from, @Param("to") Date to);
}
