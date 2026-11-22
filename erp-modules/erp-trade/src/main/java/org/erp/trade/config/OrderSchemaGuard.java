package org.erp.trade.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 订单层启动守卫（DOC/31 §12.6）。
 *
 * <p>新代码 + 老库必然 Unknown column（MyBatis-Plus 按实体生成显式列清单），所以宁可拒绝启动，
 * 也不让采购/销售列表与确认入口整片 500。检查清单与 DDL 是同一份事实，改 DDL 必须同步改这里：
 * §12.6 的 8 列 + R5 的 5 列（联系人/送货地址快照）+ 唯一键 uk_fin_ar_ap_source。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OrderSchemaGuard implements ApplicationRunner {

    /**
     * 必须存在的列：{表名, 列名}
     */
    private static final String[][] COLUMNS = {
        {"biz_purchase", "source_order_id"},
        {"biz_purchase", "source_order_no"},
        {"biz_purchase", "contact_name"},
        {"biz_purchase", "contact_phone"},
        {"biz_purchase_item", "source_order_item_id"},
        {"biz_purchase_item", "seq"},
        {"biz_sale", "source_order_id"},
        {"biz_sale", "source_order_no"},
        {"biz_sale", "contact_name"},
        {"biz_sale", "contact_phone"},
        {"biz_sale", "delivery_address"},
        {"biz_sale_item", "source_order_item_id"},
        {"biz_sale_return", "refund_account_id"},
        {"fin_ar_ap", "active_flag"}
    };

    /**
     * 必须存在的唯一键（D15：同一来源只允许一条活跃台账）
     */
    private static final String INDEX_TABLE = "fin_ar_ap";
    private static final String INDEX_NAME = "uk_fin_ar_ap_source";

    private final JdbcTemplate jdbcTemplate;
    private final ApplicationContext applicationContext;

    /**
     * 启动后核对订单层结构，缺任一列/唯一键即打印中文原因并退出（退出码 1），不对外提供服务。
     *
     * @param args 启动参数
     */
    @Override
    public void run(ApplicationArguments args) {
        List<String> missing = new ArrayList<>();
        for (String[] column : COLUMNS) {
            Integer count = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.columns where table_schema = database() and table_name = ? and column_name = ?",
                Integer.class, column[0], column[1]);
            if (count == null || count == 0) {
                missing.add(column[0] + "." + column[1]);
            }
        }
        Integer indexCount = jdbcTemplate.queryForObject(
            "select count(*) from information_schema.statistics where table_schema = database() and table_name = ? and index_name = ?",
            Integer.class, INDEX_TABLE, INDEX_NAME);
        if (indexCount == null || indexCount == 0) {
            missing.add(INDEX_TABLE + "." + INDEX_NAME);
        }
        if (missing.isEmpty()) {
            return;
        }
        log.error("数据库结构未升级到订单层，缺少：{}。请先执行订单层补丁（deploy/mysql/patches）后重启，服务已停止。",
            missing);
        SpringApplication.exit(applicationContext, () -> 1);
    }
}
