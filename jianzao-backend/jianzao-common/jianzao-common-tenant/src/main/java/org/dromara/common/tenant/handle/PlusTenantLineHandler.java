package org.dromara.common.tenant.handle;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.core.utils.sql.SqlUtil;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.common.tenant.properties.TenantProperties;

import java.util.Set;
import java.util.function.Supplier;

/**
 * 自定义租户处理器
 *
 * @author Lion Li
 */
@Slf4j
public class PlusTenantLineHandler implements TenantLineHandler {

    /** Exact framework/system tables that intentionally have no tenant column. */
    private static final Set<String> DEFAULT_SYSTEM_TABLES = Set.of(
        "gen_table", "gen_table_column",
        "sys_menu", "sys_tenant", "sys_tenant_package", "sys_role_dept", "sys_role_menu",
        "sys_user_post", "sys_user_role", "sys_client", "sys_oss_config", "flow_spel"
    );

    private final Set<String> systemTables;
    private final Supplier<String> tenantIdProvider;

    public PlusTenantLineHandler() {
        this(null, TenantHelper::getTenantId);
    }

    public PlusTenantLineHandler(TenantProperties properties) {
        this(properties, TenantHelper::getTenantId);
    }

    PlusTenantLineHandler(Supplier<String> tenantIdProvider) {
        this(null, tenantIdProvider);
    }

    private PlusTenantLineHandler(TenantProperties properties, Supplier<String> tenantIdProvider) {
        this.tenantIdProvider = tenantIdProvider;
        if (properties != null && properties.getExcludes() != null) {
            for (String configuredTable : properties.getExcludes()) {
                if (StringUtils.isNotBlank(configuredTable)
                    && DEFAULT_SYSTEM_TABLES.stream().noneMatch(configuredTable::equalsIgnoreCase)) {
                    throw new IllegalArgumentException("拒绝将未批准的表加入租户排除名单");
                }
            }
        }
        this.systemTables = DEFAULT_SYSTEM_TABLES;
    }

    @Override
    public Expression getTenantId() {
        String tenantId = tenantIdProvider.get();
        if (StringUtils.isBlank(tenantId)) {
            log.error("拒绝访问租户业务表：当前线程缺少显式租户上下文");
            throw new IllegalStateException("缺少租户上下文，拒绝执行租户业务 SQL");
        }
        return new StringValue(SqlUtil.escapeOrderBySql(tenantId));
    }

    @Override
    public boolean ignoreTable(String tableName) {
        // TenantLineHandler evaluates this before getTenantId, so exact system tables remain usable
        // without tenant context; business tables always proceed through the fail-closed tenant path.
        return tableName != null && systemTables.stream().anyMatch(tableName::equalsIgnoreCase);
    }

}
