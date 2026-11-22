package org.erp.fin.service.impl;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.erp.fin.mapper.FinOpeningMapper;
import org.erp.fin.mapper.FinSourceNoMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** SQL 实际渲染/租户解析合同，无数据库。 */
@Tag("dev")
class FinOpeningGuardSqlContractTest {
    @Test void stableMasterAndOpeningLocksAreTenantScopedCurrentReadsEvenWhenNoOpeningExists() {
        for (String name : List.of("lockCustomer", "lockSupplier", "selectByPartyForUpdate", "selectByIdForUpdate")) {
            BoundSql sql = render(FinOpeningMapper.class, name);
            String raw = normalized(sql.getSql());
            assertTrue(raw.endsWith("for update"), raw);
            assertTrue(raw.contains("del_flag = '0'"), raw);
            assertFalse(raw.contains("${"), raw);
            assertTenant(sql);
        }
        assertTrue(normalized(render(FinOpeningMapper.class, "lockCustomer").getSql()).contains("from md_customer"));
        assertTrue(normalized(render(FinOpeningMapper.class, "lockSupplier").getSql()).contains("from md_supplier"));
        String opening = normalized(render(FinOpeningMapper.class, "selectByPartyForUpdate").getSql());
        assertTrue(opening.contains("party_type = ? and party_id = ?"));
        assertFalse(opening.contains("status ="), "历史 NULL/草稿/确认状态必须锁后判断");
    }

    @Test void overlapCurrentReadsIncludeAllNonOpeningBusinessAndBothKindsOfSettlement() {
        for (String name : List.of("countLedgerBeforeForUpdate", "countReceiptsBeforeForUpdate", "countPaymentsBeforeForUpdate")) {
            BoundSql sql = render(FinOpeningMapper.class, name);
            String raw = normalized(sql.getSql());
            assertTrue(raw.contains("biz_date < ?"), raw);
            assertTrue(raw.endsWith("for update"), "普通 count 在 RR 下可能读到旧快照: " + raw);
            assertTrue(raw.contains("del_flag = '0'"), raw);
            assertTenant(sql);
            assertFalse(raw.contains("biz_sale"));
            assertFalse(raw.contains("biz_purchase"));
            if (name.contains("Ledger")) {
                assertTrue(raw.contains("source_type != 'opening'"));
                assertTrue(raw.contains("status != 'void'"));
            } else {
                assertTrue(raw.contains("status = 'confirmed'"));
                assertFalse(raw.contains("negative ="), "红字和普通收款都算已确认业务");
            }
        }
    }

    @Test void sourceNumberProjectionsCarryOwnerAndTenantScopeForAllFourNamespaces() {
        for (String name : List.of("selectSaleNos", "selectSaleReturnNos", "selectPurchaseNos", "selectPurchaseReturnNos")) {
            BoundSql sql = render(FinSourceNoMapper.class, name);
            String raw = normalized(sql.getSql());
            assertTrue(raw.contains("as sourceid"), raw);
            assertTrue(raw.contains("as partyid"), raw);
            assertTrue(raw.contains("del_flag = '0'"), raw);
            assertTenant(sql);
        }
    }

    private void assertTenant(BoundSql sql) {
        String scoped = normalized(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override public Expression getTenantId() { return new StringValue("TENANT_A"); }
        }).parserSingle(sql.getSql(), null));
        assertTrue(scoped.contains("tenant_id = 'tenant_a'"), scoped);
    }
    private BoundSql render(Class<?> mapper, String name) {
        var method = Arrays.stream(mapper.getDeclaredMethods()).filter(value -> name.equals(value.getName())).findFirst().orElseThrow();
        Map<String, Object> values = new HashMap<>();
        values.put("partyType", "AR"); values.put("partyId", 9L); values.put("id", 1L);
        values.put("before", java.sql.Date.valueOf("2020-10-01")); values.put("ids", List.of(77L));
        return new XMLLanguageDriver().createSqlSource(new Configuration(),
            String.join(" ", method.getAnnotation(Select.class).value()), Map.class).getBoundSql(values);
    }
    private String normalized(String sql) { return sql.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT); }
}
