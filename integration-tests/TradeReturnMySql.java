import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.context.SaTokenContextForThreadLocal;
import cn.dev33.satoken.context.mock.SaRequestForMock;
import cn.dev33.satoken.context.mock.SaResponseForMock;
import cn.dev33.satoken.context.mock.SaStorageForMock;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.jwt.StpLogicJwtForSimple;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.dromara.common.core.domain.model.LoginUser;
import org.dromara.common.core.enums.UserType;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.common.mybatis.handler.InjectionMetaObjectHandler;
import org.dromara.common.satoken.core.service.SaPermissionImpl;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.handle.PlusTenantLineHandler;
import org.dromara.common.tenant.helper.TenantHelper;
import org.erp.common.sequence.SequenceService;
import org.erp.fin.domain.bo.FinOpeningBo;
import org.erp.fin.mapper.FinAccountMapper;
import org.erp.fin.mapper.FinArApMapper;
import org.erp.fin.mapper.FinOpeningMapper;
import org.erp.fin.mapper.FinReceiptAllocMapper;
import org.erp.fin.mapper.FinReceiptMapper;
import org.erp.fin.mapper.FinSourceNoMapper;
import org.erp.fin.service.IFinArApService;
import org.erp.fin.service.IFinOpeningGuardService;
import org.erp.fin.service.IFinOpeningService;
import org.erp.fin.service.impl.FinArApServiceImpl;
import org.erp.fin.service.impl.FinOpeningGuardServiceImpl;
import org.erp.fin.service.impl.FinOpeningServiceImpl;
import org.erp.fin.service.impl.FinReceiptServiceImpl;
import org.erp.master.mapper.ErpCustomerMapper;
import org.erp.master.mapper.ErpProductMapper;
import org.erp.master.mapper.ErpSupplierMapper;
import org.erp.master.mapper.ErpWarehouseMapper;
import org.erp.master.service.IErpWarehouseService;
import org.erp.master.service.impl.ErpWarehouseServiceImpl;
import org.erp.stock.mapper.ErpStockLogMapper;
import org.erp.stock.mapper.ErpStockMapper;
import org.erp.stock.service.IInventoryService;
import org.erp.stock.service.impl.InventoryServiceImpl;
import org.erp.trade.domain.bo.ErpSaleReturnBo;
import org.erp.trade.mapper.ErpSaleItemMapper;
import org.erp.trade.mapper.ErpSaleMapper;
import org.erp.trade.mapper.ErpSaleReturnItemMapper;
import org.erp.trade.mapper.ErpSaleReturnMapper;
import org.erp.trade.service.IErpSaleReturnService;
import org.erp.trade.service.impl.ErpOrderProgressServiceImpl;
import org.erp.trade.service.impl.ErpSaleReturnServiceImpl;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Date;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

/** Direct application-service integration, starting with a prepared sale and return draft, not an HTTP boot. */
public final class TradeReturnMySql {
    private static final Date SALE_DATE = Date.valueOf("2026-11-18");
    private static final Date RETURN_DATE = Date.valueOf("2026-11-20");
    private static final Date DUE_DATE = Date.valueOf("2026-11-30");
    private static final String CONFIRM_PERMISSION = "erp:finance:receipt:confirm";
    private static final String WITHDRAW_PERMISSION = "erp:finance:receipt:withdraw";
    private static final String CUTOFF_CASE = "opening_cutoff_blocks_trade_confirm_and_rolls_back_save_confirm";
    private static final Actor A = new Actor("927101", 7101L, 101L, 201L, 301L);
    private static final Actor A2 = new Actor(A.tenant, 7102L, A.customer, A.account, A.warehouse);
    private static final Actor B = new Actor("927102", 7103L, 102L, 202L, 302L);
    private record Actor(String tenant, long user, long customer, long account, long warehouse) {}
    private record Sale(Actor owner, long id, long item, long order, long orderItem, long product, long ledger) {
        String no() { return "XS-AUDIT-" + id; }
    }
    private record Return(Sale sale, long id, String no, int qty, int refund, Long account) {
        Actor owner() { return sale.owner; }
        int amount() { return qty * 10; }
    }
    private enum Op {
        CONFIRM("confirm", "仅草稿可确认"), WITHDRAW("withdraw", "仅已确认单据可撤回");
        final String method, repeatError;
        Op(String method, String repeatError) { this.method = method; this.repeatError = repeatError; }
        void call(IErpSaleReturnService svc, long id) { if (this == CONFIRM) svc.confirm(id); else svc.withdraw(id); }
    }
    private record Probe(Op op, long id, LongConsumer before, Runnable after) {}
    private static final class InjectedPostChangeFailure extends RuntimeException {}

    private final TradeReturnAuditDatabase db;
    private final JdbcTemplate sql, reader;
    private final TransactionTemplate seedTransaction;
    private final SaTokenContextForThreadLocal identity;
    private final IErpSaleReturnService trade;
    private final IInventoryService inventory;
    private final IFinArApService arAp;
    private final IFinOpeningService openings;
    private final IErpWarehouseService warehouses;
    private final FinAccountMapper accounts;
    private final ErpStockLogMapper stockLogs;
    private final PurchaseOpeningMySql purchaseOpening;
    private final ThreadLocal<Probe> probes = new ThreadLocal<>();
    private final ThreadLocal<int[]> receiptCalls = new ThreadLocal<>();
    private long nextId = 1000;

    private TradeReturnMySql(TradeReturnAuditDatabase db, SaTokenContextForThreadLocal identity) throws Exception {
        this.db = db;
        this.identity = identity;
        sql = new JdbcTemplate(db.dataSource());
        reader = db.reader();
        var tx = new DataSourceTransactionManager(db.dataSource());
        tx.setDefaultTimeout(22);
        seedTransaction = new TransactionTemplate(tx);
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setLogImpl(NoLoggingImpl.class);
        var global = GlobalConfigUtils.defaults().setBanner(false)
            .setIdentifierGenerator(new DefaultIdentifierGenerator(1, 2)).setMetaObjectHandler(new InjectionMetaObjectHandler());
        var interceptors = new MybatisPlusInterceptor();
        interceptors.addInnerInterceptor(new TenantLineInnerInterceptor(new PlusTenantLineHandler()));
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setConfiguration(config);
        factory.setGlobalConfig(global);
        factory.setDataSource(db.dataSource());
        factory.setTransactionFactory(new SpringManagedTransactionFactory());
        factory.setPlugins(interceptors);
        var sessionFactory = Objects.requireNonNull(factory.getObject());
        for (Class<?> mapper : List.of(ErpSaleReturnMapper.class, ErpSaleReturnItemMapper.class, ErpSaleMapper.class,
            ErpSaleItemMapper.class, ErpCustomerMapper.class, ErpProductMapper.class, ErpSupplierMapper.class,
            ErpWarehouseMapper.class, ErpStockMapper.class, ErpStockLogMapper.class, FinAccountMapper.class,
            FinReceiptMapper.class, FinReceiptAllocMapper.class, FinArApMapper.class, FinSourceNoMapper.class,
            FinOpeningMapper.class)) {
            config.addMapper(mapper);
        }
        PurchaseOpeningMySql.registerMappers(config);
        var session = new SqlSessionTemplate(sessionFactory);
        var customer = session.getMapper(ErpCustomerMapper.class);
        var product = session.getMapper(ErpProductMapper.class);
        var supplier = session.getMapper(ErpSupplierMapper.class);
        accounts = session.getMapper(FinAccountMapper.class);
        stockLogs = session.getMapper(ErpStockLogMapper.class);
        var sequence = txProxy(tx, SequenceService.class, new SequenceService(sql), null);
        warehouses = txProxy(tx, ErpWarehouseServiceImpl.class,
            new ErpWarehouseServiceImpl(session.getMapper(ErpWarehouseMapper.class)), null);
        var openingMapper = session.getMapper(FinOpeningMapper.class);
        IFinOpeningGuardService openingGuard = txProxy(tx, FinOpeningGuardServiceImpl.class,
            new FinOpeningGuardServiceImpl(openingMapper), null);
        arAp = txProxy(tx, FinArApServiceImpl.class, new FinArApServiceImpl(session.getMapper(FinArApMapper.class),
            session.getMapper(FinSourceNoMapper.class), customer, supplier, openingMapper, openingGuard), null);
        openings = txProxy(tx, FinOpeningServiceImpl.class,
            new FinOpeningServiceImpl(openingMapper, customer, supplier, arAp, openingGuard), null);
        var finance = txProxy(tx, FinReceiptServiceImpl.class, new FinReceiptServiceImpl(session.getMapper(FinReceiptMapper.class),
            accounts, session.getMapper(FinReceiptAllocMapper.class), customer, arAp, sequence, openingGuard), this::observeReceipt);
        inventory = txProxy(tx, InventoryServiceImpl.class, new InventoryServiceImpl(session.getMapper(ErpStockMapper.class),
            stockLogs, product, supplier, customer), null);
        // Production order progress joins the trade transaction by sharing the same JdbcTemplate datasource.
        var progress = new ErpOrderProgressServiceImpl(sql);
        trade = txProxy(tx, ErpSaleReturnServiceImpl.class, new ErpSaleReturnServiceImpl(session.getMapper(ErpSaleReturnMapper.class),
            session.getMapper(ErpSaleReturnItemMapper.class), session.getMapper(ErpSaleMapper.class),
            session.getMapper(ErpSaleItemMapper.class), product, customer, inventory, warehouses, arAp, finance,
            sequence, progress, openingGuard), this::observeTrade);
        purchaseOpening = new PurchaseOpeningMySql(db, tx, session, new PurchaseOpeningMySql.Identity() {
            @Override public <T> T as(boolean peer, Supplier<T> action) {
                return TradeReturnMySql.this.as(peer ? A2 : A, action);
            }
        }, inventory, warehouses, arAp, openings, openingGuard, sequence, progress);
    }

    static <T> T txProxy(DataSourceTransactionManager manager, Class<T> type, T target, MethodInterceptor probe) {
        var advice = new TransactionInterceptor();
        advice.setTransactionManager(manager);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        advice.afterPropertiesSet();
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(advice);
        if (probe != null) factory.addAdvice(probe); // Inside production annotation-driven transactional advice.
        T proxy = type.cast(factory.getProxy());
        must(AopUtils.isCglibProxy(proxy) && AopUtils.getTargetClass(proxy) == type, "wrong target proxy: " + type.getSimpleName());
        return proxy;
    }

    private Object observeTrade(MethodInvocation invocation) throws Throwable {
        Probe p = probes.get();
        if (p == null) return invocation.proceed();
        eq(p.op.method, invocation.getMethod().getName(), "trade probe method");
        eq(p.id, invocation.getArguments()[0], "trade probe return id");
        must(TransactionSynchronizationManager.isActualTransactionActive(), "trade annotation did not start a real transaction");
        sql.execute("SET SESSION innodb_lock_wait_timeout=9");
        eq("READ-COMMITTED", sql.queryForObject("SELECT @@transaction_isolation", String.class), "trade JDBC isolation");
        if (p.before != null) p.before.accept(scalar(sql, "SELECT CONNECTION_ID()"));
        Object result = invocation.proceed();
        if (p.after != null) p.after.run();  // The real service and recalc have returned; still before outermost commit.
        return result;
    }

    private Object observeReceipt(MethodInvocation invocation) throws Throwable {
        int[] calls = receiptCalls.get();
        if (calls != null) {
            if (invocation.getMethod().getName().equals("insertSaleReturnRefund")) calls[0]++;
            if (invocation.getMethod().getName().equals("withdrawSaleReturnRefund")) calls[1]++;
        }
        return invocation.proceed();
    }

    private static GenericApplicationContext tenantSpring() {
        var env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(new MapPropertySource("trade-return-audit", Map.of("tenant.enable", "true")));
        var context = new GenericApplicationContext();
        context.setEnvironment(env);
        context.registerBean(SpringUtils.class);
        context.refresh();
        return context;
    }

    private static SaTokenContextForThreadLocal installSecurity(SaTokenDaoDefaultImpl dao) {
        SaManager.setConfig(new SaTokenConfig().setIsPrint(false).setIsLog(false).setIsWriteHeader(false)
            .setIsReadCookie(false).setDataRefreshPeriod(-1).setJwtSecretKey(UUID.randomUUID().toString()));
        SaManager.setSaTokenDao(dao);
        SaManager.setStpInterface(new SaPermissionImpl());
        StpUtil.setStpLogic(new StpLogicJwtForSimple());
        var identity = new SaTokenContextForThreadLocal();
        SaManager.setSaTokenContext(identity);
        return identity;
    }

    private <T> T as(Actor actor, Supplier<T> action) {
        must(!identity.isValid(), "identity context leaked on this thread");
        identity.setContext(new SaRequestForMock(), new SaResponseForMock(), new SaStorageForMock());
        try {
            var user = new LoginUser();
            user.setUserId(actor.user);
            user.setUsername("trade-audit-actor-" + actor.user);
            user.setUserType(UserType.SYS_USER.getUserType());
            user.setDeptId(80L);
            user.setTenantId(actor.tenant);
            user.setMenuPermission(Set.of(CONFIRM_PERMISSION, WITHDRAW_PERMISSION));
            LoginHelper.login(user, null);
            return TenantHelper.dynamic(actor.tenant, () -> {
                eq(actor.tenant, TenantHelper.getDynamic(), "explicit thread tenant");
                eq(actor.user, LoginHelper.getUserId(), "fixture actor identity");
                return action.get();
            });
        } finally {
            try { StpUtil.logout(); } finally { identity.clearContext(); }
        }
    }

    private void call(Actor actor, Op op, Return doc, LongConsumer before, Runnable after) {
        as(actor, () -> {
            must(!TransactionSynchronizationManager.isActualTransactionActive() && probes.get() == null,
                "trade test must enter its own production transaction");
            probes.set(new Probe(op, doc.id, before, after));
            try { op.call(trade, doc.id); } finally { probes.remove(); }
            return null;
        });
    }

    private void call(Actor actor, Op op, Return doc) { call(actor, op, doc, null, null); }

    private void seedOwners() {
        for (Actor a : List.of(A, B)) {
            one(sql.update("INSERT INTO md_customer(id,tenant_id,code,name) VALUES(?,?,'CUSTOMER',?)", a.customer, a.tenant,
                "Audit customer " + a.tenant), "customer fixture");
            one(sql.update("INSERT INTO md_supplier(id,tenant_id,code,name) VALUES(?,?,'SUPPLIER',?)", a.customer, a.tenant,
                "Audit supplier " + a.tenant), "supplier fixture");
            one(sql.update("INSERT INTO fin_account(id,tenant_id,code,name,type) VALUES(?,?,'BANK',?,'BANK')", a.account, a.tenant,
                accountName(a)), "account fixture");
            one(sql.update("INSERT INTO md_warehouse(id,tenant_id,code,name,status) VALUES(?,?,'MAIN','Fixture main',1)",
                a.warehouse, a.tenant), "enabled warehouse fixture");
            one(sql.update("INSERT INTO md_warehouse(id,tenant_id,code,name,status) VALUES(?,?,'OFF','Fixture disabled',0)",
                a.warehouse - 100, a.tenant), "disabled earlier warehouse fixture");
            eq(a.warehouse, as(a, warehouses::defaultWarehouseId), "real defaultWarehouseId with tenant and enabled condition");
        }
    }

    private static String accountName(Actor a) { return "Audit bank " + a.tenant; }

    private Sale seedSale(Actor a) { return seedSale(a, SALE_DATE); }

    private Sale seedSale(Actor a, Date saleDate) {
        return as(a, () -> seedTransaction.execute(status -> {
            long order = ++nextId, orderItem = ++nextId, sale = ++nextId, item = ++nextId, product = ++nextId;
            String saleNo = "XS-AUDIT-" + sale, orderNo = "XSD-AUDIT-" + order;
            one(sql.update("INSERT INTO md_product(id,tenant_id,code,name,qty_scale) VALUES(?,?,?,?,0)", product, a.tenant,
                "PRODUCT-" + product, "Audit product " + product), "product fixture");
            one(sql.update("""
                INSERT INTO biz_sale_order(id,tenant_id,no,customer_id,biz_date,due_date,status,exec_status)
                VALUES(?,?,?,?,?,?,'CONFIRMED','FULL')
                """, order, a.tenant, orderNo, a.customer, saleDate, DUE_DATE), "linked order fixture");
            one(sql.update("""
                INSERT INTO biz_sale_order_item(id,tenant_id,order_id,product_id,qty,executed_qty,due_date) VALUES(?,?,?,?,10,10,?)
                """, orderItem, a.tenant, order, product, DUE_DATE), "linked order line fixture");
            one(sql.update("""
                INSERT INTO biz_sale(id,tenant_id,no,customer_id,biz_date,status,goods_amount,total_amount,cost_amount,
                  gross_profit,source_order_id,source_order_no) VALUES(?,?,?,?,?,'CONFIRMED',100,100,60,40,?,?)
                """, sale, a.tenant, saleNo, a.customer, saleDate, order, orderNo), "confirmed sale fixture");
            one(sql.update("""
                INSERT INTO biz_sale_item(id,tenant_id,order_id,source_order_item_id,product_id,qty,price,amount,unit_cost,
                  cost_amount,gross_profit) VALUES(?,?,?,?,?,10,10,100,6,60,40)
                """, item, a.tenant, sale, orderItem, product), "confirmed sale line fixture");
            long warehouse = warehouses.defaultWarehouseId();
            // Coherent replay history is written by production InventoryServiceImpl, not fabricated stock rows.
            inventory.inbound("OPENING", "OPEN-AUDIT-" + sale, sale, product, warehouse, bd(20), bd(6), "NONE", null, saleDate, 0);
            inventory.outbound("SALE", saleNo, sale, product, warehouse, bd(10), "CUSTOMER", a.customer, saleDate, 0);
            inventory.inbound("PURCHASE", "IN-AUDIT-" + sale, sale, product, warehouse, bd(10), bd(10), "NONE", null, RETURN_DATE, 0);
            arAp.createBySale(sale, saleNo, a.customer, bd(100), saleDate);
            var ar = Objects.requireNonNull(arAp.getBySource("SALE", sale), "fixture SALE AR missing");
            arAp.settle(ar.getId(), bd(70), "AR", a.customer);
            var s = new Sale(a, sale, item, order, orderItem, product, ar.getId());
            checkStock(sql, s, "20", "8");
            checkOrder(sql, s, "10", "FULL");
            checkSaleLedger(sql, s, "70");
            return s;
        }));
    }

    private Return seedReturn(Sale s, int qty, int refund) {
        return seedReturn(s, qty, refund, refund == 0 ? null : s.owner.account, null, "DRAFT");
    }

    private Return seedReturn(Sale s, int qty, int refund, Long account, String sharedNo, String status) {
        long id = ++nextId;
        var doc = new Return(s, id, sharedNo == null ? "XT-AUDIT-" + id : sharedNo, qty, refund, account);
        one(sql.update("""
            INSERT INTO biz_sale_return(id,tenant_id,no,sale_id,sale_no,customer_id,biz_date,status,total_amount,
              need_refund,refund_amount,refund_account_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
            """, id, s.owner.tenant, doc.no, s.id, s.no(), s.owner.customer, RETURN_DATE, status,
            doc.amount(), refund == 0 ? 0 : 1, refund, account), "return head fixture");
        one(sql.update("""
            INSERT INTO biz_sale_return_item(id,tenant_id,order_id,sale_item_id,product_id,qty,price,amount)
            VALUES(?,?,?,?,?,?,10,?)
            """, ++nextId, s.owner.tenant, id, s.item, s.product, qty, doc.amount()), "return line fixture");
        return doc;
    }

    private static BigDecimal bd(int n) { return BigDecimal.valueOf(n); }
    private static void must(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private static void eq(Object expected, Object actual, String reason) { must(Objects.equals(expected, actual), reason); }
    private static void one(int rows, String reason) { eq(1, rows, reason); }
    private static long number(Map<String, Object> row, String key) { return ((Number) Objects.requireNonNull(row.get(key), key)).longValue(); }
    private static long scalar(JdbcTemplate db, String sql, Object... params) {
        return Objects.requireNonNull(db.queryForObject(sql, Long.class, params));
    }
    private static void dec(String expected, Object actual, String label) {
        must(actual instanceof BigDecimal n && n.compareTo(new BigDecimal(expected)) == 0,
            label + ": expected " + expected + ", actual " + actual);
    }
    private static void dec(Map<String, Object> row, String key, String expected) { dec(expected, row.get(key), key); }
    private static <T extends RuntimeException> T expect(Class<T> exception, Runnable action) {
        try { action.run(); }
        catch (RuntimeException error) { if (exception.isInstance(error)) return exception.cast(error); throw error; }
        throw new AssertionError("expected " + exception.getSimpleName());
    }
    private static void reject(Integer code, String text, Runnable action) {
        var error = expect(ServiceException.class, action);
        eq(code, error.getCode(), "unexpected domain rejection code: " + error.getCode());
        must(error.getMessage().contains(text), "unexpected domain rejection: " + error.getMessage());
    }

    private long sequence(Actor a) {
        return scalar(reader, "SELECT COALESCE(SUM(current_value),0) FROM sys_sequence WHERE tenant_id=? AND biz_type='SK'", a.tenant);
    }

    private static void checkStock(JdbcTemplate jdbc, Sale s, String qty, String avg) {
        var stock = jdbc.queryForMap("SELECT * FROM st_stock WHERE tenant_id=? AND product_id=? AND warehouse_id=?",
            s.owner.tenant, s.product, s.owner.warehouse);
        eq("0", stock.get("del_flag"), "stock row is live");
        dec(stock, "qty", qty);
        dec(stock, "avg_cost", avg);
    }

    private static void checkOrder(JdbcTemplate jdbc, Sale s, String executed, String status) {
        var head = jdbc.queryForMap("SELECT * FROM biz_sale_order WHERE id=?", s.order);
        var line = jdbc.queryForMap("SELECT * FROM biz_sale_order_item WHERE id=?", s.orderItem);
        eq(s.owner.tenant, head.get("tenant_id"), "order owner");
        eq(s.owner.tenant, line.get("tenant_id"), "order line owner");
        eq(s.order, number(line, "order_id"), "order link");
        eq("CONFIRMED", head.get("status"), "original order status unchanged");
        eq(status, head.get("exec_status"), "real derived order exec status");
        eq(DUE_DATE, head.get("due_date"), "real derived order due date");
        dec(line, "executed_qty", executed);
        dec(line, "closed_qty", "0");
    }

    private static void checkSaleLedger(JdbcTemplate jdbc, Sale s, String settled) {
        var row = jdbc.queryForMap("SELECT * FROM fin_ar_ap WHERE id=?", s.ledger);
        eq(s.owner.tenant, row.get("tenant_id"), "original SALE ledger owner");
        eq(s.id, number(row, "source_id"), "original SALE ledger source id");
        eq("SALE", row.get("source_type"), "original SALE ledger source type");
        eq("AR", row.get("party_type"), "original SALE AR");
        eq(s.owner.customer, number(row, "party_id"), "original SALE AR customer");
        eq("PARTIAL", row.get("status"), "original SALE AR status");
        dec(row, "amount", "100");
        dec(row, "settled_amount", settled);
    }

    private static Map<String, Object> head(JdbcTemplate jdbc, Return d) {
        var head = jdbc.queryForMap("SELECT * FROM biz_sale_return WHERE id=?", d.id);
        eq(d.owner().tenant, head.get("tenant_id"), "return tenant");
        eq(d.sale.id, number(head, "sale_id"), "real original SALE id in return");
        eq(d.owner().customer, number(head, "customer_id"), "return customer");
        eq(d.account, head.get("refund_account_id"), "return refund account not overwritten");
        dec(head, "total_amount", String.valueOf(d.amount()));
        dec(head, "refund_amount", String.valueOf(d.refund));
        return head;
    }

    private static void checkLineCost(JdbcTemplate jdbc, Return d) {
        // update(confirm=true) logically deletes the old line before inserting its replacement.
        // Assert the single live business line; db.snapshot() deliberately still includes history.
        var line = jdbc.queryForMap("SELECT * FROM biz_sale_return_item WHERE order_id=? AND del_flag='0'", d.id);
        eq(d.owner().tenant, line.get("tenant_id"), "return line tenant");
        eq(d.sale.item, number(line, "sale_item_id"), "return line targets original sale item");
        eq(d.sale.product, number(line, "product_id"), "return line product");
        dec(line, "qty", String.valueOf(d.qty));
        dec(line, "unit_cost", "6");
        dec(line, "return_cost", String.valueOf(d.qty * 6));
    }

    private static void checkReturnLedger(JdbcTemplate jdbc, Return d, String state) {
        var ar = jdbc.queryForMap("SELECT * FROM fin_ar_ap WHERE source_type='SALE_RETURN' AND source_id=?", d.id);
        eq(d.owner().tenant, ar.get("tenant_id"), "negative AR tenant");
        eq("AR", ar.get("party_type"), "SALE_RETURN is AR");
        eq(d.owner().customer, number(ar, "party_id"), "negative AR customer");
        eq(state, ar.get("status"), "negative AR status");
        dec(ar, "amount", String.valueOf(-d.amount()));
        dec(ar, "settled_amount", "0");
        eq("VOID".equals(state) ? null : 1, ar.get("active_flag"), "D15 active AR key state");
    }

    private static void checkReceipt(JdbcTemplate jdbc, Return d, long id, boolean isVoid) {
        var receipt = jdbc.queryForMap("SELECT * FROM fin_receipt WHERE id=?", id);
        eq(d.owner().tenant, receipt.get("tenant_id"), "red receipt tenant");
        eq(d.owner().customer, number(receipt, "customer_id"), "red receipt customer");
        eq(d.account, receipt.get("account_id"), "red receipt account link");
        eq(accountName(d.owner()), receipt.get("account"), "red receipt account name snapshot");
        eq(1L, number(receipt, "negative"), "red receipt has outgoing direction (not negative magnitude)");
        dec(receipt, "amount", String.valueOf(d.refund));
        eq(isVoid ? "VOID" : "CONFIRMED", receipt.get("status"), "red receipt state");
        eq("0", receipt.get("del_flag"), "receipt retained");
        must(((String) receipt.get("no")).matches("SK[0-9]{12}"), "real SK numbering format");
        eq(d.owner().user, number(receipt, "confirmed_by"), "receipt confirmed by actual fixture identity");
        must(receipt.get("confirmed_at") != null, "receipt confirm timestamp");
        if (isVoid) {
            eq(d.owner().user, number(receipt, "voided_by"), "receipt void user");
            eq(d.owner().user, number(receipt, "withdrawn_by"), "receipt withdrawal user");
            must(receipt.get("voided_at") != null && receipt.get("withdrawn_at") != null, "receipt VOID/withdraw timestamps");
        }
        var alloc = jdbc.queryForMap("SELECT * FROM fin_receipt_alloc WHERE receipt_id=?", id);
        eq(d.owner().tenant, alloc.get("tenant_id"), "receipt allocation tenant");
        eq(d.sale.ledger, number(alloc, "ar_ap_id"), "red receipt allocated to ORIGINAL SALE AR");
        dec(alloc, "amount", String.valueOf(d.refund));
        eq("0", alloc.get("del_flag"), "receipt allocation retained");
    }

    private static long checkStockLogs(JdbcTemplate jdbc, Return d, boolean withdrawn) {
        return checkStockLogs(jdbc, d, withdrawn, RETURN_DATE);
    }

    private static long checkStockLogs(JdbcTemplate jdbc, Return d, boolean withdrawn, Date date) {
        var logs = jdbc.queryForList("SELECT * FROM st_stock_log WHERE biz_id=? AND biz_no=? ORDER BY id", d.id, d.no);
        eq(withdrawn ? 2 : 1, logs.size(), "exactly one return movement and at most one VOID movement");
        for (var row : logs) {
            eq(d.owner().tenant, row.get("tenant_id"), "stock ledger tenant");
            eq(d.sale.product, number(row, "product_id"), "stock ledger original product");
            eq(d.owner().warehouse, number(row, "warehouse_id"), "stock ledger real enabled default warehouse");
            eq("0", row.get("del_flag"), "stock log not replaced/deleted");
            eq(date, row.get("biz_date"), "stock ledger return business date");
            dec(row, "unit_cost", "6");
        }
        var original = logs.get(0);
        eq("SALE_RETURN", original.get("biz_type"), "stock inward return log");
        eq("CUSTOMER", original.get("partner_type"), "stock inward partner type");
        eq(d.owner().customer, number(original, "partner_id"), "stock inward partner id");
        dec(original, "qty", String.valueOf(d.qty));
        dec(original, "amount", String.valueOf(d.qty * 6));
        dec(original, "balance_after", "25");
        if (withdrawn) {
            var reversal = logs.get(1);
            eq("VOID", reversal.get("biz_type"), "stock reversal ledger type");
            eq("NONE", reversal.get("partner_type"), "stock reversal partner type");
            eq(number(original, "id"), number(reversal, "partner_id"), "stock reversal points at exact original log id");
            dec(reversal, "qty", String.valueOf(-d.qty));
            dec(reversal, "amount", String.valueOf(-d.qty * 6));
            dec(reversal, "balance_after", "20");
        }
        return number(original, "id");
    }

    // Single 5-unit case at fixture opening quantity 20/current average 8: 25 @ 7.6000.
    private static Long confirmed(JdbcTemplate jdbc, Return d) {
        return confirmed(jdbc, d, RETURN_DATE);
    }

    private static Long confirmed(JdbcTemplate jdbc, Return d, Date date) {
        var head = head(jdbc, d);
        eq("CONFIRMED", head.get("status"), "actual return confirmed");
        eq(d.owner().user, number(head, "confirmed_by"), "return confirmation identity");
        must(head.get("confirmed_at") != null, "return confirmed timestamp");
        dec(head, "return_cost_amount", "30");
        checkLineCost(jdbc, d);
        checkStock(jdbc, d.sale, "25", "7.6");
        checkStockLogs(jdbc, d, false, date);
        checkOrder(jdbc, d.sale, "5", "PARTIAL");
        checkReturnLedger(jdbc, d, "OPEN");
        checkSaleLedger(jdbc, d.sale, Integer.toString(70 - d.refund));
        Long id = (Long) head.get("refund_receipt_id");
        if (d.refund == 0) eq(null, id, "no refund receipt link for non-refunding return");
        else checkReceipt(jdbc, d, Objects.requireNonNull(id, "missing refund receipt link"), false);
        return id;
    }

    private static long withdrawn(JdbcTemplate jdbc, Return d, Long receipt) {
        var head = head(jdbc, d);
        eq("DRAFT", head.get("status"), "actual return withdrawn to DRAFT");
        eq(null, head.get("refund_receipt_id"), "refund link cleared in persisted return");
        eq(d.owner().user, number(head, "withdrawn_by"), "return withdrawal identity");
        must(head.get("withdrawn_at") != null, "return withdrawn timestamp");
        dec(head, "return_cost_amount", "0");
        checkLineCost(jdbc, d); // Withdrawal clears head cost, not the historical line snapshot.
        checkStock(jdbc, d.sale, "20", "8");
        checkOrder(jdbc, d.sale, "10", "FULL");
        checkReturnLedger(jdbc, d, "VOID");
        checkSaleLedger(jdbc, d.sale, "70");
        if (receipt != null) checkReceipt(jdbc, d, receipt, true);
        return checkStockLogs(jdbc, d, true);
    }

    private void returnable(Sale sale, String returned, String available) {
        var rows = as(sale.owner, () -> trade.originalItems(sale.id));
        eq(1, rows.size(), "real originalItems result");
        var line = rows.get(0);
        eq(sale.item, line.getSaleItemId(), "computed available qty mapped to sale line");
        dec("10", line.getOriginalQty(), "original qty");
        dec(returned, line.getReturnedQty(), "confirmed returned qty");
        dec(available, line.getReturnableQty(), "remaining returnable qty");
    }

    private void deltas(Map<String, List<Map<String, Object>>> before, int receipts, int allocs, int ars, int logs) {
        var after = db.snapshot();
        for (var item : Map.of("fin_receipt", receipts, "fin_receipt_alloc", allocs, "fin_ar_ap", ars, "st_stock_log", logs).entrySet()) {
            eq(before.get(item.getKey()).size() + item.getValue(), after.get(item.getKey()).size(), "row delta for " + item.getKey());
        }
    }

    private void roundTrip(Map<String, Object> evidence) {
        Sale sale = seedSale(A);
        Return doc = seedReturn(sale, 5, 20);
        returnable(sale, "0", "10");
        var beforeConfirm = db.snapshot();
        long seq = sequence(A);
        int[] calls = new int[2];
        receiptCalls.set(calls);
        try {
            call(A, Op.CONFIRM, doc, null, () -> {
                confirmed(sql, doc);
                eq(beforeConfirm, db.snapshot(), "uncommitted confirm leaked to observer");
                eq(seq + 1, sequence(A), "SK number committed only via REQUIRES_NEW");
            });
            Long receipt = confirmed(reader, doc);
            long originalStockRow = checkStockLogs(reader, doc, false);
            deltas(beforeConfirm, 1, 1, 1, 1);
            returnable(sale, "5", "5");
            var beforeWithdraw = db.snapshot();
            call(A, Op.WITHDRAW, doc, null, () -> {
                withdrawn(sql, doc, receipt);
                eq(beforeWithdraw, db.snapshot(), "uncommitted withdrawal leaked to observer");
            });
            eq(originalStockRow, withdrawn(reader, doc, receipt), "original stock ledger row persisted after reversal");
            deltas(beforeWithdraw, 0, 0, 0, 1);
            returnable(sale, "0", "10");
        } finally { receiptCalls.remove(); }
        eq(1, calls[0], "actual confirm called the finance service exactly once");
        eq(1, calls[1], "actual withdraw called the finance service exactly once");
        eq(seq + 1, sequence(A), "withdrawal did not number another receipt");
        evidence.put("return_status", List.of("DRAFT", "CONFIRMED", "DRAFT"));
        evidence.put("returnable_qty", List.of(10, 5, 10));
        evidence.put("stock_qty", List.of(20, 25, 20));
        evidence.put("stock_avg_cost", List.of("8.0000", "7.6000", "8.0000"));
        evidence.put("stock_return_and_void", List.of("+5 @ 6 / +30", "-5 @ 6 / -30, referring to original log"));
        evidence.put("order_executed_qty", List.of(10, 5, 10));
        evidence.put("order_execution_status", List.of("FULL", "PARTIAL", "FULL"));
        evidence.put("sale_ar_settled", List.of(70, 50, 70));
        evidence.put("sale_return_ar", List.of("-50 OPEN", "-50 VOID"));
        evidence.put("refund_link_to_red_receipt_and_exact_allocation", true);
        evidence.put("red_receipt_status", List.of("CONFIRMED", "VOID"));
        evidence.put("different_connection_cannot_see_either_uncommitted_transition", true);
    }

    private void cumulativeQty(Map<String, Object> evidence) {
        Sale sale = seedSale(A);
        Return prior = seedReturn(sale, 5, 0);
        call(A, Op.CONFIRM, prior);
        confirmed(reader, prior);
        seedReturn(sale, 9, 0, null, null, "VOID"); // Non-active historical fixture; should not reserve quantity.
        Return tooMuch = seedReturn(sale, 6, 20);
        Return exact = seedReturn(sale, 5, 0);
        returnable(sale, "5", "5");
        long seq = sequence(A);
        var before = db.snapshot();
        reject(40902, "累计退货数量超过原行可退数量", () -> call(A, Op.CONFIRM, tooMuch));
        eq(before, db.snapshot(), "over-quantity rejection caused business writes");
        eq(seq, sequence(A), "over-quantity rejection reached refund numbering");
        call(A, Op.CONFIRM, exact);
        eq("CONFIRMED", head(reader, exact).get("status"), "exact remaining quantity accepted");
        returnable(sale, "10", "0");
        checkStock(reader, sale, "30", "7.3333");
        checkOrder(reader, sale, "0", "NOT_STARTED");
        checkReturnLedger(reader, exact, "OPEN");
        deltas(before, 0, 0, 1, 1);
        evidence.put("original_already_confirmed_remaining", List.of(10, 5, 5));
        evidence.put("request_6_rejected_40902_without_mutation_request_5_accepted", true);
        evidence.put("draft_and_void_excluded_from_cumulative_quantity", true);
    }

    private void rollbackAfterBusinessDml(Map<String, Object> evidence) {
        Sale sale = seedSale(A);
        Return doc = seedReturn(sale, 5, 20);
        returnable(sale, "0", "10");
        var before = db.snapshot();
        long sequenceBefore = sequence(A);
        int[] calls = new int[2];
        receiptCalls.set(calls);
        try {
            expect(InjectedPostChangeFailure.class, () -> call(A, Op.CONFIRM, doc, null, () -> {
                // The real trade service has returned after the finance, inventory, item-cost,
                // return-state and order-progress DML. Verify those writes on the transaction's
                // own connection before injecting an unchecked failure inside its Spring proxy.
                confirmed(sql, doc);
                checkStock(sql, sale, "25", "7.6");
                checkOrder(sql, sale, "5", "PARTIAL");
                checkStockLogs(sql, doc, false);
                eq(before, db.snapshot(), "uncommitted fault-injection writes visible on independent connection");
                throw new InjectedPostChangeFailure();
            }));
        } finally {
            receiptCalls.remove();
        }
        eq(before, db.snapshot(), "full business snapshot changed after injected transaction failure");
        checkStock(reader, sale, "20", "8");
        checkOrder(reader, sale, "10", "FULL");
        checkSaleLedger(reader, sale, "70");
        eq(0L, scalar(reader, "SELECT COUNT(*) FROM fin_receipt WHERE remark=?", "销售退货退款 " + doc.no()),
            "red receipt remains after rollback");
        eq(0L, scalar(reader, "SELECT COUNT(*) FROM fin_ar_ap WHERE source_type='SALE_RETURN' AND source_id=?", doc.id),
            "negative SALE_RETURN ledger remains after rollback");
        eq("DRAFT", head(reader, doc).get("status"), "return status not rolled back");
        eq(null, head(reader, doc).get("refund_receipt_id"), "refund link not rolled back");
        eq(sequenceBefore + 1, sequence(A), "independent receipt number is retained after batch rollback");
        eq(1, calls[0], "real refund service called once before rollback");
        eq(0, calls[1], "withdrawal service not called in confirmation rollback case");
        evidence.put("failure_injected_after_real_finance_inventory_and_order_dml", true);
        evidence.put("transactional_business_tables_restored_exactly", true);
        evidence.put("return_draft_link_receipt_alloc_ledger_stock_logs_stock_and_order_restored", true);
        evidence.put("independent_sequence_increment_retained", true);
    }

    private void openingCutoffOnTradeReturn(Map<String, Object> evidence) {
        Date nextDay = Date.valueOf("2024-01-11");
        Sale sale = seedSale(A, nextDay);
        // The real original SALE and its ledger are strictly after this cutoff, so opening confirmation is legal.
        var cutoff = nextDay.toLocalDate().minusDays(1);
        var opening = new FinOpeningBo();
        opening.setPartyType("AR");
        opening.setPartyId(A.customer);
        opening.setAmount(bd(100));
        opening.setOpeningDate(cutoff);
        long openingId = as(A, () -> {
            long id = openings.save(opening).getId();
            openings.confirm(id);
            return id;
        });
        eq(1L, scalar(reader, """
            SELECT COUNT(*) FROM fin_opening WHERE id=? AND tenant_id=? AND status='CONFIRMED' AND opening_date=?
            """, openingId, A.tenant, Date.valueOf(cutoff)), "confirmed real opening fixture");

        var request = new ErpSaleReturnBo();
        request.setSaleId(sale.id);
        request.setBizDate(Date.valueOf(cutoff));
        request.setNeedRefund(true);
        request.setRefundAmount(bd(20));
        request.setRefundAccountId(A.account);
        var item = new ErpSaleReturnBo.ItemBo();
        item.setSaleItemId(sale.item);
        item.setPrice(bd(10));
        item.setQty(bd(5));
        request.setItems(List.of(item));
        var draft = as(A, () -> trade.insert(request));
        var doc = new Return(sale, draft.getId(), draft.getNo(), 5, 20, A.account);
        eq("DRAFT", head(reader, doc).get("status"), "trade can save draft on cutoff");
        eq(Date.valueOf(cutoff), head(reader, doc).get("biz_date"), "cutoff is persisted on return draft");
        var before = db.snapshot();
        long seq = sequence(A);
        int[] calls = new int[2];
        receiptCalls.set(calls);
        try {
            String denial = "不能早于或等于已确认期初截止日";
            reject(40902, denial, () -> call(A, Op.CONFIRM, doc));
            eq(before, db.snapshot(), "cutoff denial changed stock, order, finance or return rows");
            request.setConfirm(true);
            item.setPrice(bd(11)); // Would change the draft head/item; the failed save-and-confirm must roll it back.
            reject(40902, denial, () -> as(A, () -> trade.update(doc.id, request)));
            eq(before, db.snapshot(), "cutoff save-and-confirm failed to restore original draft head/items");
            eq(seq, sequence(A), "cutoff reached protected receipt numbering");
            eq(0, calls[0], "cutoff was not rejected by trade before even calling the protected refund API");

            item.setPrice(bd(10));
            request.setBizDate(nextDay); // Exact day after cutoff, not merely an unrelated later date.
            as(A, () -> trade.update(doc.id, request));
        } finally { receiptCalls.remove(); }
        Long receipt = confirmed(reader, doc, nextDay);
        eq(2L, scalar(reader, "SELECT COUNT(*) FROM biz_sale_return_item WHERE order_id=?", doc.id),
            "successful update retains old soft-deleted item plus exactly one replacement");
        eq(1L, scalar(reader, "SELECT COUNT(*) FROM biz_sale_return_item WHERE order_id=? AND del_flag='1'", doc.id),
            "successful update must retain (not erase) the historical item");
        eq(nextDay, reader.queryForObject("SELECT biz_date FROM fin_receipt WHERE id=?", Date.class, receipt),
            "allowed return's persisted date reached the red receipt");
        eq(nextDay, reader.queryForObject("SELECT biz_date FROM fin_ar_ap WHERE source_type='SALE_RETURN' AND source_id=?",
            Date.class, doc.id), "allowed return's persisted date reached the red ledger");
        eq(1, calls[0], "only the allowed confirmation reached real refund service");
        eq(seq + 1, sequence(A), "only allowed confirmation consumed an SK number");
        deltas(before, 1, 1, 1, 1);
        evidence.put("opening_cutoff", cutoff.toString());
        evidence.put("cutoff_draft_allowed_direct_and_save_confirm_blocked_40902", true);
        evidence.put("no_refund_call_and_full_snapshots_unchanged_before_retry", true);
        evidence.put("following_day_trade_refund_stock_and_order_committed", true);
    }

    private static void caseResult(Map<String, Object> cases, String name, Runnable action) {
        var row = new LinkedHashMap<String, Object>();
        row.put("status", "failed");
        try {
            action.run();
            row.put("status", "passed");
        } catch (Throwable error) {
            row.put("error_class", error.getClass().getSimpleName());
            row.put("harness_frames", java.util.Arrays.stream(error.getStackTrace())
                .filter(frame -> frame.getClassName().startsWith("TradeReturn"))
                .limit(6).map(StackTraceElement::toString).toList());
            String message = error.getMessage();
            if (message != null && !message.isBlank()) row.put("message", message.substring(0, Math.min(message.length(), 800)));
        }
        cases.put(name, row);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("expected one result path");
        Path resultPath = Path.of(args[0]).toAbsolutePath();
        var suite = new LinkedHashMap<String, Object>();
        var cases = new LinkedHashMap<String, Object>();
        var database = new LinkedHashMap<String, Object>();
        suite.put("cases", cases);
        suite.put("database", database);
        suite.put("passed", false);
        RefundAuditDatabase.Contract contract = null;
        try (var spring = tenantSpring()) {
            ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger("ROOT").setLevel(Level.ERROR);
            var identity = installSecurity(new SaTokenDaoDefaultImpl());
            contract = RefundAuditDatabase.Contract.fromExec();
            try (var db = new TradeReturnAuditDatabase(contract, database)) {
                db.prepare();
                var test = new TradeReturnMySql(db, identity);
                test.seedOwners();
                var roundTrip = new LinkedHashMap<String, Object>();
                caseResult(cases, "real_confirm_withdraw_atomic_round_trip", () -> test.roundTrip(roundTrip));
                if ("passed".equals(((Map<?, ?>) cases.get("real_confirm_withdraw_atomic_round_trip")).get("status"))) {
                    cases.put("real_confirm_withdraw_atomic_round_trip_evidence", roundTrip);
                }
                var rollback = new LinkedHashMap<String, Object>();
                caseResult(cases, "fault_after_finance_inventory_dml_rolls_back_whole_confirm", () ->
                    test.rollbackAfterBusinessDml(rollback));
                if ("passed".equals(((Map<?, ?>) cases.get("fault_after_finance_inventory_dml_rolls_back_whole_confirm")).get("status"))) {
                    cases.put("fault_after_finance_inventory_dml_rolls_back_whole_confirm_evidence", rollback);
                }
                var cutoff = new LinkedHashMap<String, Object>();
                caseResult(cases, CUTOFF_CASE, () -> test.openingCutoffOnTradeReturn(cutoff));
                if ("passed".equals(((Map<?, ?>) cases.get(CUTOFF_CASE)).get("status"))) {
                    cases.put(CUTOFF_CASE + "_evidence", cutoff);
                }
                test.purchaseOpening.runCases(cases);
            }
            suite.put("passed", Boolean.TRUE.equals(database.get("cleanup_verified"))
                && cases.values().stream().filter(Map.class::isInstance)
                    .map(Map.class::cast).filter(row -> row.containsKey("status"))
                    .allMatch(row -> "passed".equals(row.get("status")))
                && cases.containsKey("real_confirm_withdraw_atomic_round_trip")
                && cases.containsKey("fault_after_finance_inventory_dml_rolls_back_whole_confirm")
                && cases.containsKey(CUTOFF_CASE)
                && cases.keySet().containsAll(PurchaseOpeningMySql.requiredCases()));
            suite.put("scope", Map.of(
                "real_services", List.of("ErpSaleReturnServiceImpl", "ErpPurchaseServiceImpl", "ErpPurchaseReturnServiceImpl",
                    "ErpProductServiceImpl/updateCostPrice", "FinReceiptServiceImpl", "FinArApServiceImpl",
                    "FinOpeningServiceImpl", "FinOpeningGuardServiceImpl/MANDATORY",
                    "InventoryServiceImpl", "ErpOrderProgressServiceImpl"),
                "real_components", List.of("production MyBatis mappers", "Spring annotation transaction proxies",
                    "MySQL 8.0/InnoDB", "production SequenceService/REQUIRES_NEW"),
                "deferred", List.of("HTTP", "capacity", "cross-order multi-product trade concurrency"),
                "fixture_limitations", List.of("in-process synthetic identity/session and menu permissions",
                    "synthetic minimal customer, account, warehouse, supplier, opening and confirmed sale/order/stock history fixtures",
                    "not production identity provisioning or full database migrations")));
        } catch (Throwable error) {
            suite.put("passed", false);
            suite.put("setup_error_class", error.getClass().getSimpleName());
            if (contract != null && error.getMessage() != null) {
                suite.put("setup_error", contract.redact(error.getMessage()).substring(0,
                    Math.min(contract.redact(error.getMessage()).length(), 1200)));
            }
        }
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(resultPath.toFile(), suite);
        System.out.println("TRADE_RETURN_CHAIN: " + (Boolean.TRUE.equals(suite.get("passed")) ? "PASS" : "FAIL"));
        if (Boolean.TRUE.equals(suite.get("passed"))) System.exit(0);
        System.exit(1);
    }
}
