import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.context.SaTokenContextForThreadLocal;
import cn.dev33.satoken.context.mock.SaRequestForMock;
import cn.dev33.satoken.context.mock.SaResponseForMock;
import cn.dev33.satoken.context.mock.SaStorageForMock;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.jwt.StpLogicJwtForSimple;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.session.SqlSessionFactory;
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
import org.erp.fin.domain.FinAccount;
import org.erp.fin.domain.FinArAp;
import org.erp.fin.domain.FinReceiptAlloc;
import org.erp.fin.mapper.FinAccountMapper;
import org.erp.fin.mapper.FinArApMapper;
import org.erp.fin.mapper.FinOpeningMapper;
import org.erp.fin.mapper.FinPaymentAllocMapper;
import org.erp.fin.mapper.FinPaymentMapper;
import org.erp.fin.mapper.FinReceiptAllocMapper;
import org.erp.fin.mapper.FinReceiptMapper;
import org.erp.fin.mapper.FinSourceNoMapper;
import org.erp.fin.service.IFinArApService;
import org.erp.fin.service.IFinOpeningGuardService;
import org.erp.fin.service.IFinReceiptService;
import org.erp.fin.service.impl.FinArApServiceImpl;
import org.erp.fin.service.impl.FinOpeningGuardServiceImpl;
import org.erp.fin.service.impl.FinOpeningServiceImpl;
import org.erp.fin.service.impl.FinPaymentServiceImpl;
import org.erp.fin.service.impl.FinReceiptServiceImpl;
import org.erp.master.mapper.ErpCustomerMapper;
import org.erp.master.mapper.ErpSupplierMapper;
import org.erp.trade.domain.ErpSaleReturn;
import org.erp.trade.mapper.ErpSaleReturnMapper;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Fin integration acceptance, not an application boot or full trade workflow. */
public final class RefundBusinessMySql {
    private static final String CONFIRM = "erp:finance:receipt:confirm";
    private static final String WITHDRAW = "erp:finance:receipt:withdraw";
    private static final String BAD_PENDING = "退货单未处于可退款草稿状态";
    private static final String BAD_WITHDRAWAL = "退货单没有可撤回的已确认退款关联";
    private static final Date BIZ_DATE = Date.valueOf("2026-11-20");
    static final Actor A = new Actor("918001", 701L, 101L, 501L);
    static final Actor A2 = new Actor(A.tenant, 702L, A.customer, A.account);
    private static final Actor DENIED = new Actor(A.tenant, 703L, A.customer, A.account);
    static final Actor B = new Actor("918002", 704L, 102L, 502L);
    record Actor(String tenant, long user, long customer, long account) {}
    private record Scenario(Actor owner, long returnId, long saleId, long arId, int originalSettled, int refund) {
        String no() { return "XT-FIXTURE-" + returnId; }
    }

    @FunctionalInterface
    private interface Case { void run(RefundBusinessMySql suite, Map<String, Object> evidence) throws Exception; }
    private static final class InjectedAfterFinance extends RuntimeException {}

    private final RefundAuditDatabase db;
    private final RefundAuditDatabase.Contract contract;
    private final JdbcTemplate sql;
    private final JdbcTemplate observed;
    private final TransactionTemplate outer;
    private final SaTokenContextForThreadLocal tokenContext;
    private final IFinReceiptService receipts;
    private final IFinArApService arAp;
    private final IFinOpeningGuardService openingGuard;
    private final FinOpeningMySql openingCases;
    private final FinReceiptMapper receiptMapper;
    private final FinReceiptAllocMapper allocationMapper;
    private final FinAccountMapper accountMapper;
    private final FinArApMapper arMapper;
    private final ErpSaleReturnMapper returnMapper;
    private long fixtureId = 1000;

    private RefundBusinessMySql(RefundAuditDatabase db, RefundAuditDatabase.Contract contract,
                               SaTokenContextForThreadLocal tokenContext) throws Exception {
        this.db = db;
        this.contract = contract;
        this.tokenContext = tokenContext;
        sql = new JdbcTemplate(db.dataSource());
        observed = db.observer();
        var manager = new DataSourceTransactionManager(db.dataSource());
        outer = new TransactionTemplate(manager);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        outer.setTimeout(22);

        MybatisConfiguration config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setLogImpl(NoLoggingImpl.class);
        var global = GlobalConfigUtils.defaults().setBanner(false).setMetaObjectHandler(new InjectionMetaObjectHandler());
        global.setIdentifierGenerator(new DefaultIdentifierGenerator(1, 1)); // No machine/network identity lookup.
        var plugins = new MybatisPlusInterceptor();
        plugins.addInnerInterceptor(new TenantLineInnerInterceptor(new PlusTenantLineHandler()));
        var bean = new MybatisSqlSessionFactoryBean();
        bean.setConfiguration(config);
        bean.setGlobalConfig(global);
        bean.setDataSource(db.dataSource());
        bean.setTransactionFactory(new SpringManagedTransactionFactory());
        bean.setPlugins(plugins);
        SqlSessionFactory factory = Objects.requireNonNull(bean.getObject());
        for (Class<?> mapper : List.of(FinReceiptMapper.class, FinReceiptAllocMapper.class, FinAccountMapper.class,
            FinArApMapper.class, FinSourceNoMapper.class, FinOpeningMapper.class, FinPaymentMapper.class,
            FinPaymentAllocMapper.class, ErpCustomerMapper.class, ErpSupplierMapper.class, ErpSaleReturnMapper.class)) {
            factory.getConfiguration().addMapper(mapper);
        }
        SqlSessionTemplate session = new SqlSessionTemplate(factory);
        receiptMapper = session.getMapper(FinReceiptMapper.class);
        allocationMapper = session.getMapper(FinReceiptAllocMapper.class);
        accountMapper = session.getMapper(FinAccountMapper.class);
        arMapper = session.getMapper(FinArApMapper.class);
        returnMapper = session.getMapper(ErpSaleReturnMapper.class);
        var customer = session.getMapper(ErpCustomerMapper.class);
        var supplier = session.getMapper(ErpSupplierMapper.class);
        var openingMapper = session.getMapper(FinOpeningMapper.class);
        var sequence = txProxy(manager, SequenceService.class, new SequenceService(sql));
        openingGuard = txProxy(manager, FinOpeningGuardServiceImpl.class, new FinOpeningGuardServiceImpl(openingMapper));
        arAp = txProxy(manager, FinArApServiceImpl.class, new FinArApServiceImpl(arMapper,
            session.getMapper(FinSourceNoMapper.class), customer, supplier, openingMapper, openingGuard));
        receipts = txProxy(manager, FinReceiptServiceImpl.class, new FinReceiptServiceImpl(receiptMapper,
            accountMapper, allocationMapper, customer, arAp, sequence, openingGuard));
        var openings = txProxy(manager, FinOpeningServiceImpl.class,
            new FinOpeningServiceImpl(openingMapper, customer, supplier, arAp, openingGuard));
        var payments = txProxy(manager, FinPaymentServiceImpl.class,
            new FinPaymentServiceImpl(session.getMapper(FinPaymentMapper.class), accountMapper,
                session.getMapper(FinPaymentAllocMapper.class), supplier, arAp, sequence, openingGuard));
        openingCases = new FinOpeningMySql(this, sql, observed, manager, openings, arAp, receipts, payments);
    }

    private static <T> T txProxy(DataSourceTransactionManager manager, Class<T> type, T target) {
        TransactionInterceptor advice = new TransactionInterceptor();
        advice.setTransactionManager(manager);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        advice.afterPropertiesSet();
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(advice);
        T proxy = type.cast(factory.getProxy());
        must(AopUtils.isCglibProxy(proxy) && AopUtils.getTargetClass(proxy) == type,
            "not the annotation-driven target proxy: " + type.getSimpleName());
        return proxy;
    }

    private static GenericApplicationContext tenantContext() {
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("standalone-tenant-enabled", Map.of("tenant.enable", "true")));
        var context = new GenericApplicationContext();
        context.setEnvironment(environment);
        context.registerBean(SpringUtils.class);
        context.refresh();
        return context;
    }

    private static SaTokenContextForThreadLocal security(SaTokenDaoDefaultImpl memoryDao) {
        SaManager.setConfig(new SaTokenConfig().setIsPrint(false).setIsLog(false).setIsWriteHeader(false)
            .setIsReadCookie(false).setDataRefreshPeriod(-1).setJwtSecretKey(UUID.randomUUID().toString()));
        SaManager.setSaTokenDao(memoryDao);
        SaManager.setStpInterface(new SaPermissionImpl());
        StpUtil.setStpLogic(new StpLogicJwtForSimple());
        var context = new SaTokenContextForThreadLocal();
        SaManager.setSaTokenContext(context);
        return context;
    }

    <T> T as(Actor actor, Supplier<T> action) {
        must(!tokenContext.isValid(), "identity fixture leaked across calls");
        tokenContext.setContext(new SaRequestForMock(), new SaResponseForMock(), new SaStorageForMock());
        try {
            var user = new LoginUser();
            user.setUserId(actor.user);
            user.setUsername("audit-fixture-" + actor.user);
            user.setUserType(UserType.SYS_USER.getUserType());
            user.setDeptId(99L);
            user.setTenantId(actor.tenant);
            user.setMenuPermission(actor == DENIED ? Set.of() : Set.of(CONFIRM, WITHDRAW));
            LoginHelper.login(user, null);
            return TenantHelper.dynamic(actor.tenant, () -> {
                eq(actor.user, LoginHelper.getUserId(), "fixture actor identity");
                eq(actor.tenant, TenantHelper.getDynamic(), "explicit per-thread tenant");
                return action.get();
            });
        } finally {
            try { StpUtil.logout(); } finally { tokenContext.clearContext(); }
        }
    }

    private <T> T tx(Supplier<T> action) {
        return outer.execute(status -> {
            must(TransactionSynchronizationManager.isActualTransactionActive(), "no actual outer transaction");
            sql.execute("SET SESSION innodb_lock_wait_timeout=10");
            return action.get();
        });
    }

    private <T> T in(Actor actor, Supplier<T> action) { return as(actor, () -> tx(action)); }

    private void seedOwners() {
        for (Actor actor : List.of(A, B)) {
            eq(1, sql.update("INSERT INTO md_customer (id,tenant_id,code,name) VALUES (?,?,?,?)",
                actor.customer, actor.tenant, "CUSTOMER", "Customer " + actor.tenant), "fixture customer insert");
            eq(1, sql.update("INSERT INTO md_supplier (id,tenant_id,code,name) VALUES (?,?,?,?)",
                actor.customer, actor.tenant, "SUPPLIER", "Supplier " + actor.tenant), "fixture supplier insert");
            eq(1, sql.update("INSERT INTO fin_account (id,tenant_id,code,name) VALUES (?,?,?,?)",
                actor.account, actor.tenant, "BANK", accountName(actor)), "fixture account insert");
        }
    }

    private static String accountName(Actor actor) { return "Audit account " + actor.tenant; }

    private Scenario seed(Actor actor, int settled, int refund) {
        return in(actor, () -> {
            long sale = ++fixtureId, ret = ++fixtureId;
            arAp.createBySale(sale, "XS-FIXTURE-" + sale, actor.customer, money(100), BIZ_DATE);
            var ar = arAp.getBySource("SALE", sale);
            must(ar != null, "real SALE ledger not readable in owner tenant");
            arAp.settle(ar.getId(), money(settled), "AR", actor.customer);
            var scenario = new Scenario(actor, ret, sale, ar.getId(), settled, refund);
            seedReturn(actor, ret, sale, refund, actor.account);
            return scenario;
        });
    }

    private void seedReturn(Actor owner, long ret, long sale, int refund, long account) {
        eq(1, sql.update("""
            INSERT INTO biz_sale_return (id,tenant_id,no,sale_id,sale_no,customer_id,biz_date,
              total_amount,need_refund,refund_amount,refund_account_id) VALUES (?,?,?,?,?,?,?,80,1,?,?)
            """, ret, owner.tenant, "XT-FIXTURE-" + ret, sale, "XS-FIXTURE-" + sale, owner.customer,
            BIZ_DATE, money(refund), account), "fixture return insert");
    }

    private int link(long ret, long receipt) {
        return returnMapper.update(null, new UpdateWrapper<ErpSaleReturn>()
            .eq("id", ret).eq("status", "DRAFT").isNull("refund_receipt_id")
            .set("status", "CONFIRMED").set("refund_receipt_id", receipt));
    }

    // Caller choreography ONLY: no ErpSaleReturnService/stock/order progress. The two ledger calls are real.
    private long confirmCaller(Scenario scenario) {
        long receipt = receipts.insertSaleReturnRefund(scenario.returnId);
        arAp.createBySaleReturn(scenario.returnId, scenario.no(), scenario.owner.customer, money(-80), BIZ_DATE);
        eq(1, link(scenario.returnId, receipt), "caller status/link CAS confirm");
        return receipt;
    }

    private long withdrawCaller(Scenario scenario, long receipt) {
        receipts.withdrawSaleReturnRefund(scenario.returnId);
        arAp.voidBySource("SALE_RETURN", scenario.returnId);
        eq(1, returnMapper.update(null, new UpdateWrapper<ErpSaleReturn>().eq("id", scenario.returnId)
            .eq("status", "CONFIRMED").eq("refund_receipt_id", receipt)
            .set("status", "DRAFT").set("refund_receipt_id", null)), "caller status/link CAS withdraw");
        return receipt;
    }

    private Map<String, List<Map<String, Object>>> snapshot() { return RefundAuditDatabase.snapshot(observed); }

    private static BigDecimal money(int value) { return BigDecimal.valueOf(value).setScale(2); }

    private static void must(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private static void eq(Object expected, Object actual, String label) {
        must(Objects.equals(expected, actual), label);
    }

    private static long number(Map<String, Object> row, String column) {
        return ((Number) Objects.requireNonNull(row.get(column), column)).longValue();
    }

    private static long scalar(JdbcTemplate jdbc, String statement, Object... arguments) {
        return Objects.requireNonNull(jdbc.queryForObject(statement, Long.class, arguments));
    }

    private long sequenceValue(Actor actor) {
        return scalar(observed, "SELECT COALESCE(SUM(current_value),0) FROM sys_sequence WHERE tenant_id=? AND biz_type='SK'",
            actor.tenant);
    }

    private static void ledger(JdbcTemplate reader, Scenario s, int settled, String status) {
        var row = reader.queryForMap("SELECT * FROM fin_ar_ap WHERE id=?", s.arId);
        eq(s.owner.tenant, row.get("tenant_id"), "SALE ledger tenant");
        eq("SALE", row.get("source_type"), "original ledger source type");
        eq(s.saleId, number(row, "source_id"), "original ledger SALE id");
        eq("AR", row.get("party_type"), "original ledger type");
        eq(s.owner.customer, number(row, "party_id"), "original ledger customer");
        eq(money(100), row.get("amount"), "original ledger face value unchanged");
        eq(money(settled), row.get("settled_amount"), "original ledger settled amount");
        eq(status, row.get("status"), "original ledger state");
    }

    private static void source(JdbcTemplate reader, Scenario s, String status, Long linkedReceipt) {
        var row = reader.queryForMap("SELECT tenant_id,status,refund_receipt_id FROM biz_sale_return WHERE id=?", s.returnId);
        eq(s.owner.tenant, row.get("tenant_id"), "caller return tenant");
        eq(status, row.get("status"), "caller return status");
        eq(linkedReceipt, row.get("refund_receipt_id"), "caller return refund link");
    }

    private static void returnLedger(JdbcTemplate reader, Scenario s, String status) {
        var row = reader.queryForMap("SELECT * FROM fin_ar_ap WHERE source_type='SALE_RETURN' AND source_id=?", s.returnId);
        eq(s.owner.tenant, row.get("tenant_id"), "negative return ledger tenant");
        eq(s.owner.customer, number(row, "party_id"), "negative return ledger customer");
        eq("AR", row.get("party_type"), "negative return ledger party type");
        eq(money(-80), row.get("amount"), "negative return ledger total");
        eq(money(0), row.get("settled_amount"), "negative return ledger is not settled");
        eq(status, row.get("status"), "negative return ledger status");
    }

    private static void refundRows(JdbcTemplate reader, Scenario s, long id, String expectedState) {
        var row = reader.queryForMap("SELECT * FROM fin_receipt WHERE id=?", id);
        eq(s.owner.tenant, row.get("tenant_id"), "red receipt tenant");
        eq(s.owner.customer, number(row, "customer_id"), "red receipt customer");
        eq(s.owner.account, number(row, "account_id"), "red receipt settlement account id");
        eq(accountName(s.owner), row.get("account"), "red receipt settlement account display");
        eq(1L, number(row, "negative"), "red receipt direction");
        eq(money(s.refund), row.get("amount"), "red receipt amount (positive magnitude)");
        eq("0", row.get("del_flag"), "red receipt not soft-deleted");
        eq(expectedState, row.get("status"), "red receipt status");
        must(((String) row.get("no")).matches("SK[0-9]{12}"), "expected real SK sequence format");
        if (!expectedState.equals("DRAFT")) {
            must(row.get("confirmed_at") != null, "receipt confirm timestamp missing");
            eq(s.owner.user, number(row, "confirmed_by"), "receipt confirm actor");
        }
        if (expectedState.equals("VOID")) {
            must(row.get("withdrawn_at") != null, "receipt withdraw timestamp missing");
            eq(row.get("withdrawn_at"), row.get("voided_at"), "receipt void timestamp");
            eq(s.owner.user, number(row, "withdrawn_by"), "receipt withdrawal actor");
            eq(s.owner.user, number(row, "voided_by"), "receipt void actor");
        }
        var allocation = reader.queryForMap("SELECT * FROM fin_receipt_alloc WHERE receipt_id=?", id);
        eq(s.owner.tenant, allocation.get("tenant_id"), "allocation tenant");
        eq(s.arId, number(allocation, "ar_ap_id"), "allocation must target original SALE AR");
        eq(money(s.refund), allocation.get("amount"), "allocation amount");
        eq("0", allocation.get("del_flag"), "allocation not soft-deleted");
    }

    private static void confirmed(JdbcTemplate reader, Scenario s, long id) {
        int settled = s.originalSettled - s.refund;
        ledger(reader, s, settled, settled == 0 ? "OPEN" : "PARTIAL");
        refundRows(reader, s, id, "CONFIRMED");
        returnLedger(reader, s, "OPEN");
        source(reader, s, "CONFIRMED", id);
    }

    private static void withdrawn(JdbcTemplate reader, Scenario s, long id) {
        ledger(reader, s, s.originalSettled, s.originalSettled == 100 ? "SETTLED" : "PARTIAL");
        refundRows(reader, s, id, "VOID");
        returnLedger(reader, s, "VOID");
        source(reader, s, "DRAFT", null);
    }

    private static <E extends RuntimeException> E expect(Class<E> type, Runnable action) {
        try { action.run(); }
        catch (RuntimeException error) {
            if (type.isInstance(error)) return type.cast(error);
            throw error;
        }
        throw new AssertionError("expected exception not raised: " + type.getSimpleName());
    }

    private static void rejected(Integer code, String message, Runnable action) {
        ServiceException error = expect(ServiceException.class, action);
        eq(code, error.getCode(), "unexpected ERP rejection code: expected " + code);
        must(error.getMessage().contains(message), "unexpected ERP rejection branch: expected " + message);
    }

    private void existingObjects(Map<String, Object> evidence) throws Exception {
        var before = snapshot();
        Map<String, Object> collisionReport = new LinkedHashMap<>();
        try (var probe = new RefundAuditDatabase(contract, collisionReport)) {
            try {
                probe.prepare();
                throw new AssertionError("fixed table preflight unexpectedly allowed reuse");
            } catch (IllegalStateException error) {
                must(error.getMessage().contains("fixed fixture table/view already exists"), "wrong preflight rejection");
            }
        }
        eq(List.of(), collisionReport.get("created_tables"), "collision must issue no CREATE");
        eq(List.of(), collisionReport.get("dropped_tables"), "collision must issue no DROP");
        eq(RefundAuditDatabase.FIXTURE_TABLES, collisionReport.get("preexisting_objects"),
            "all fixed fixture names were preflighted");
        eq(before, snapshot(), "collision path modified another runner's fixtures");
        evidence.put("preexisting_names_detected", RefundAuditDatabase.FIXTURE_TABLES.size());
        evidence.put("created_or_dropped_by_probe", 0);
    }

    private void mandatory(Map<String, Object> evidence) {
        Scenario pending = seed(A, 100, 25);
        Scenario linked = seed(A, 70, 20);
        in(A, () -> confirmCaller(linked));
        var before = snapshot();
        long sequence = sequenceValue(A);
        as(A, () -> {
            must(!TransactionSynchronizationManager.isActualTransactionActive(), "MANDATORY probe accidentally in a transaction");
            expect(IllegalTransactionStateException.class, () -> receipts.insertSaleReturnRefund(pending.returnId));
            expect(IllegalTransactionStateException.class, () -> receipts.withdrawSaleReturnRefund(linked.returnId));
            expect(IllegalTransactionStateException.class, () -> openingGuard.lockParty("AR", A.customer));
            expect(IllegalTransactionStateException.class,
                () -> openingGuard.lockAndCheckBusinessDate("AP", A.customer, BIZ_DATE));
            return null;
        });
        eq(before, snapshot(), "missing transaction caused writes");
        eq(sequence, sequenceValue(A), "missing transaction must not allocate a number");
        evidence.put("both_entrypoints_rejected", true);
        evidence.put("both_opening_guard_entrypoints_are_mandatory", true);
        evidence.put("spring_exception", "IllegalTransactionStateException");
    }

    private void permission(Map<String, Object> evidence) {
        Scenario pending = seed(A, 100, 25);
        Scenario linked = seed(A, 70, 20);
        in(A, () -> confirmCaller(linked));
        var before = snapshot();
        long sequence = sequenceValue(A);
        expect(NotPermissionException.class, () -> in(DENIED, () -> receipts.insertSaleReturnRefund(pending.returnId)));
        expect(NotPermissionException.class, () -> in(DENIED, () -> {
            receipts.withdrawSaleReturnRefund(linked.returnId);
            return null;
        }));
        eq(before, snapshot(), "auth denial mutated fixture data");
        eq(sequence, sequenceValue(A), "auth denial allocated a number");
        evidence.put("production_permission_check_denies_fixture_without_menu_permissions", true);
    }

    private void refundCommit(Map<String, Object> evidence) {
        // Use the full available settlement to also verify the exact-zero OPEN transition.
        Scenario s = seed(A, 25, 25);
        var before = snapshot();
        long sequence = sequenceValue(A);
        long id = in(A, () -> {
            long receipt = confirmCaller(s);
            confirmed(sql, s, receipt);
            eq(before, snapshot(), "uncommitted refund bundle visible to independent observer");
            eq(sequence + 1, sequenceValue(A), "actual REQUIRES_NEW sequence not independently committed");
            return receipt;
        });
        confirmed(observed, s, id);
        checkCountDelta(before, 1, 1, 1);
        evidence.put("original_sale_settled", List.of(25, 0));
        evidence.put("original_sale_status_after", "OPEN");
        evidence.put("receipt_and_allocation_commit_together", true);
        evidence.put("numbering_commits_independently", true);
    }

    private void linkedWithdrawal(Map<String, Object> evidence) {
        Scenario correct = seed(A, 100, 25);
        long receipt = in(A, () -> confirmCaller(correct));
        Scenario wrong = seed(A, 70, 25);
        eq(1, in(A, () -> link(wrong.returnId, receipt)), "set wrong SALE association fixture");
        var before = snapshot();
        rejected(40902, "退款只能关联原销售单应收台账", () -> in(A, () -> {
            receipts.withdrawSaleReturnRefund(wrong.returnId);
            return null;
        }));
        rejected(40902, "红字退款单不可在收款单中修改", () -> as(A, () -> {
            receipts.withdraw(receipt);
            return null;
        }));
        eq(before, snapshot(), "invalid link/ordinary withdrawal mutated business state");
        in(A, () -> {
            withdrawCaller(correct, receipt);
            withdrawn(sql, correct, receipt);
            eq(before, snapshot(), "uncommitted withdrawal leaked to independent observer");
            return null;
        });
        withdrawn(observed, correct, receipt);
        ledger(observed, wrong, 70, "PARTIAL");
        source(observed, wrong, "CONFIRMED", receipt); // Untouched deliberately wrong fixture.
        checkCountDelta(before, 0, 0, 0);
        evidence.put("original_sale_settled", List.of(100, 75, 100));
        evidence.put("wrong_sale_and_ordinary_withdrawal_rejected", true);
        evidence.put("receipt_after", "VOID");
    }

    private void overRefund(Map<String, Object> evidence) {
        // 50 <= return total 80; only the original SALE's settled balance 40 is insufficient.
        Scenario s = seed(A, 40, 50);
        var before = snapshot();
        long sequence = sequenceValue(A);
        as(A, () -> expect(UnexpectedRollbackException.class, () -> outer.execute(status -> {
            rejected(null, "反核销金额超过已核销金额", () -> receipts.insertSaleReturnRefund(s.returnId));
            must(status.isRollbackOnly(), "participating MANDATORY advice did not mark outer transaction rollback-only");
            long tmpReceipt = scalar(sql, "SELECT id FROM fin_receipt WHERE remark=?", "销售退货退款 " + s.no());
            refundRows(sql, s, tmpReceipt, "DRAFT"); // Prove draft AND alloc existed before rollback.
            ledger(sql, s, 40, "PARTIAL");
            source(sql, s, "DRAFT", null);
            eq(before, snapshot(), "over-limit transient draft/allocation leaked to observer");
            return null; // A commit attempt must fail because inner MANDATORY advice set rollback-only.
        })));
        eq(before, snapshot(), "over-refund rollback left business row changes (including draft/allocation)");
        eq(sequence + 1, sequenceValue(A), "independent number must remain allocated after rollback");
        evidence.put("requested_vs_available", List.of(50, 40));
        evidence.put("draft_and_allocation_existed_inside_outer_transaction", true);
        evidence.put("rollback_only_then_unexpected_rollback", true);
        evidence.put("business_snapshot_unchanged", true);
    }

    private void faultRollback(Map<String, Object> evidence) {
        Scenario s = seed(A, 60, 20);
        var beforeConfirm = snapshot();
        long sequence = sequenceValue(A);
        expect(InjectedAfterFinance.class, () -> in(A, () -> {
            long receipt = confirmCaller(s);
            confirmed(sql, s, receipt);
            eq(beforeConfirm, snapshot(), "injected confirm case leaked uncommitted finance changes");
            throw new InjectedAfterFinance();
        }));
        eq(beforeConfirm, snapshot(), "fault after accounting did not unwind complete confirm bundle");
        eq(sequence + 1, sequenceValue(A), "numbering is independent of batch rollback");
        long receipt = in(A, () -> confirmCaller(s)); // Same source may be safely retried after rollback.
        var beforeWithdraw = snapshot();
        expect(InjectedAfterFinance.class, () -> in(A, () -> {
            withdrawCaller(s, receipt);
            withdrawn(sql, s, receipt);
            eq(beforeWithdraw, snapshot(), "injected withdrawal case leaked uncommitted finance changes");
            throw new InjectedAfterFinance();
        }));
        eq(beforeWithdraw, snapshot(), "fault after restorative accounting did not unwind withdrawal bundle");
        evidence.put("fault_injected_after_sql_visible_finance_and_link_changes", List.of("refund", "withdrawal"));
        evidence.put("full_business_snapshots_unchanged", true);
    }

    private void checkCountDelta(Map<String, List<Map<String, Object>>> before, int receipts, int allocations, int ledgers) {
        var after = snapshot();
        eq(before.get("fin_receipt").size() + receipts, after.get("fin_receipt").size(), "receipt table count delta");
        eq(before.get("fin_receipt_alloc").size() + allocations, after.get("fin_receipt_alloc").size(), "allocation table count delta");
        eq(before.get("fin_ar_ap").size() + ledgers, after.get("fin_ar_ap").size(), "ledger table count delta");
    }

    private void raceRefund(Map<String, Object> evidence) throws Exception {
        // The balance allows a second 20 refund (50 >= 20): we are testing serialization, not the amount guard.
        Scenario s = seed(A, 70, 20);
        var before = snapshot();
        long sequence = sequenceValue(A);
        long receipt = contend(() -> confirmCaller(s), () -> confirmCaller(s), BAD_PENDING, before, evidence);
        confirmed(observed, s, receipt);
        checkCountDelta(before, 1, 1, 1);
        eq(sequence + 1, sequenceValue(A), "duplicate refund must not allocate another receipt number");
        evidence.put("original_sale_settled", List.of(70, 50));
        evidence.put("new_receipts_allocations_return_ledgers", List.of(1, 1, 1));
    }

    private void raceWithdraw(Map<String, Object> evidence) throws Exception {
        // A second restore would also fit (90 <= 100); the CONFIRMED association must be consumed exactly once.
        Scenario s = seed(A, 70, 20);
        long receipt = in(A, () -> confirmCaller(s));
        var before = snapshot();
        long sequence = sequenceValue(A);
        eq(receipt, contend(() -> withdrawCaller(s, receipt), () -> withdrawCaller(s, receipt), BAD_WITHDRAWAL,
            before, evidence), "withdrawal targeted another receipt");
        withdrawn(observed, s, receipt);
        checkCountDelta(before, 0, 0, 0);
        eq(sequence, sequenceValue(A), "withdrawal must not allocate receipt numbers");
        evidence.put("original_sale_settled", List.of(50, 70));
        evidence.put("receipt_after", "VOID");
    }

    private long contend(Supplier<Long> winnerCall, Supplier<Long> competingCall, String losingMessage,
                         Map<String, List<Map<String, Object>>> before, Map<String, Object> evidence) throws Exception {
        CountDownLatch winnerHasWritten = new CountDownLatch(1);
        CountDownLatch contenderHasConnection = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong winnerId = new AtomicLong(), contenderId = new AtomicLong();
        long observerId = scalar(observed, "SELECT CONNECTION_ID()");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Long> winner = pool.submit(() -> in(A, () -> {
                winnerId.set(scalar(sql, "SELECT CONNECTION_ID()"));
                long id = winnerCall.get(); // Do not release the real InnoDB locks yet.
                winnerHasWritten.countDown();
                await(release, 15, "winner not released by observer");
                return id;
            }));
            if (!winnerHasWritten.await(7, TimeUnit.SECONDS)) {
                if (winner.isDone()) winner.get(); // Preserve the real error instead of an opaque timeout.
                throw new AssertionError("winner did not reach pre-commit latch");
            }
            Future<?> loser = pool.submit(() -> rejected(40902, losingMessage, () -> in(A2, () -> {
                contenderId.set(scalar(sql, "SELECT CONNECTION_ID()"));
                contenderHasConnection.countDown();
                return competingCall.get();
            })));
            if (!contenderHasConnection.await(7, TimeUnit.SECONDS)) {
                if (loser.isDone()) loser.get();
                throw new AssertionError("contender had no own Spring JDBC connection");
            }
            must(winnerId.get() != contenderId.get() && contenderId.get() != observerId && winnerId.get() != observerId,
                "must use independent physical writer/contender/observer connections");
            int samples = 0;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (System.nanoTime() < deadline && samples < 2 && !loser.isDone()) {
                if (sourceLockQueryActive(contenderId.get())) samples++;
                Thread.sleep(60); // Poll MySQL, not a timing-only race criterion.
            }
            must(samples >= 2 && !loser.isDone(), "did not observe the competing finance SELECT ... FOR UPDATE waiting");
            eq(before, snapshot(), "uncommitted race winner visible from independent connection");
            release.countDown();
            long result = winner.get(22, TimeUnit.SECONDS);
            loser.get(22, TimeUnit.SECONDS); // Exact post-lock ERP branch; a SQL timeout/deadlock is a failure.
            evidence.put("mysql_connection_ids", Map.of("winner", winnerId.get(), "contender", contenderId.get(), "observer", observerId));
            evidence.put("observed_waiting_finance_source_lock_samples", samples);
            evidence.put("contender_erp_rejection_code", 40902);
            return result;
        } finally {
            release.countDown();
            pool.shutdown();
            if (!pool.awaitTermination(24, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                must(pool.awaitTermination(20, TimeUnit.SECONDS), "workers still active; concurrency case not accepted");
            }
        }
    }

    private boolean sourceLockQueryActive(long id) {
        return Boolean.TRUE.equals(observed.query("SHOW FULL PROCESSLIST", rows -> {
            while (rows.next()) {
                String query = rows.getString("Info");
                if (id == rows.getLong("Id") && query != null && "Query".equals(rows.getString("Command"))) {
                    // Read only public statement shape from our own contender; do not log process-list rows or SQL.
                    String normalized = query.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
                    return normalized.contains("from biz_sale_return") && normalized.contains("for update")
                        && normalized.contains("refund_receipt_id");
                }
            }
            return false;
        }));
    }

    private static void await(CountDownLatch latch, int seconds, String label) {
        try { must(latch.await(seconds, TimeUnit.SECONDS), label); }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(label, error);
        }
    }

    private void tenantIsolation(Map<String, Object> evidence) {
        Scenario pendingA = seed(A, 70, 20);
        Scenario confirmedA = seed(A, 70, 20);
        long aReceipt = in(A, () -> confirmCaller(confirmedA));
        Scenario ownedB = seed(B, 70, 20);
        long bReceipt = in(B, () -> confirmCaller(ownedB)); // Positive control: B has working services & permission.
        confirmed(observed, ownedB, bReceipt);
        Scenario crossAccount = new Scenario(B, ++fixtureId, ownedB.saleId, ownedB.arId, 50, 20);
        seedReturn(B, crossAccount.returnId, ownedB.saleId, 20, A.account);
        long crossSale = ++fixtureId;
        seedReturn(B, crossSale, pendingA.saleId, 20, B.account);
        var before = snapshot();
        in(B, () -> {
            must(receiptMapper.lockRefundSource(ownedB.returnId) != null, "B cannot read its own source");
            must(accountMapper.selectById(B.account) != null, "B cannot read its own account");
            must(arAp.getBySource("SALE", ownedB.saleId) != null, "B cannot read its own SALE ledger");
            eq(null, receiptMapper.lockRefundSource(pendingA.returnId), "B leaked A source projection");
            eq(null, accountMapper.selectById(A.account), "B leaked A settlement account");
            eq(null, arAp.getBySource("SALE", pendingA.saleId), "B leaked A SALE ledger by source");
            eq(null, arMapper.selectByIdForUpdate(pendingA.arId), "B leaked A ledger locking mapper");
            eq(null, receiptMapper.lockById(aReceipt), "B leaked A red receipt");
            eq(0L, allocationMapper.selectCount(new QueryWrapper<FinReceiptAlloc>().eq("receipt_id", aReceipt)),
                "B leaked A red allocation");
            eq(0, returnMapper.update(null, new UpdateWrapper<ErpSaleReturn>().eq("id", pendingA.returnId)
                .set("status", "VOID")), "B changed A source through production mapper");
            eq(0, accountMapper.update(null, new UpdateWrapper<FinAccount>().eq("id", A.account)
                .set("status", 0)), "B changed A account through production mapper");
            eq(0, arMapper.update(null, new UpdateWrapper<FinArAp>().eq("id", pendingA.arId)
                .set("settled_amount", money(0))), "B changed A ledger through production mapper");
            return null;
        });
        rejected(40902, BAD_PENDING, () -> in(B, () -> receipts.insertSaleReturnRefund(pendingA.returnId)));
        rejected(40902, BAD_WITHDRAWAL, () -> in(B, () -> {
            receipts.withdrawSaleReturnRefund(confirmedA.returnId);
            return null;
        }));
        rejected(null, "结算账户不存在", () -> in(B, () -> receipts.insertSaleReturnRefund(crossAccount.returnId)));
        rejected(40902, "原销售单应收台账不存在", () -> in(B, () -> receipts.insertSaleReturnRefund(crossSale)));
        rejected(null, "应收应付台账不存在", () -> in(B, () -> {
            arAp.unsettleSaleReturnRefund(pendingA.arId, money(20), A.customer, pendingA.saleId);
            return null;
        }));
        var missing = expect(RuntimeException.class, () -> accountMapper.selectById(A.account));
        boolean rejectedMissingTenant = false;
        for (Throwable cause = missing; cause != null; cause = cause.getCause()) {
            if (cause instanceof IllegalStateException && cause.getMessage() != null
                && cause.getMessage().contains("缺少租户上下文")) rejectedMissingTenant = true;
        }
        must(rejectedMissingTenant, "production tenant handler did not fail closed without a tenant");
        eq(before, snapshot(), "forbidden tenant calls wrote business rows");
        evidence.put("B_own_refund_committed", true);
        evidence.put("B_blocked_reads_and_writes", List.of("return_source", "settlement_account", "SALE_ledger"));
        evidence.put("B_blocked_red_receipt_allocation_reads", true);
        evidence.put("missing_tenant_rejected", true);
    }

    private record CaseSpec(String name, Case action) {}
    private static final List<CaseSpec> CASES = List.of(
        new CaseSpec("fixed_fixture_collisions_refuse_without_drop", RefundBusinessMySql::existingObjects),
        new CaseSpec("mandatory_without_outer_tx", RefundBusinessMySql::mandatory),
        new CaseSpec("confirm_and_withdraw_permission_with_fixture_identity", RefundBusinessMySql::permission),
        new CaseSpec("refund_atomic_commit", RefundBusinessMySql::refundCommit),
        new CaseSpec("linked_withdraw_void_and_wrong_sale_rejected", RefundBusinessMySql::linkedWithdrawal),
        new CaseSpec("over_refund_rolls_back_draft_and_alloc", RefundBusinessMySql::overRefund),
        new CaseSpec("fault_after_finance_rolls_back_bundle", RefundBusinessMySql::faultRollback),
        new CaseSpec("concurrent_same_return_refund_once", RefundBusinessMySql::raceRefund),
        new CaseSpec("concurrent_same_return_withdraw_once", RefundBusinessMySql::raceWithdraw),
        new CaseSpec("cross_tenant_refund_account_source_ledger", RefundBusinessMySql::tenantIsolation),
        new CaseSpec("opening_first_no_existing_opening_ar_stable_lock_and_rr",
            (suite, evidence) -> suite.openingCases.openingFirst(evidence)),
        new CaseSpec("business_first_no_existing_opening_ap_payment_stable_lock_and_rr",
            (suite, evidence) -> suite.openingCases.businessFirst(evidence)),
        new CaseSpec("opening_legacy_null_and_explicit_reconfirm",
            (suite, evidence) -> suite.openingCases.legacyNullAndExplicitReconfirm(evidence)),
        new CaseSpec("opening_receipt_cutoff_settlement_controlled_withdrawal_and_rollback",
            (suite, evidence) -> suite.openingCases.settlementAndControlledWithdrawal(evidence))
    );

    private static List<Map<String, Object>> describe(Throwable error, RefundAuditDatabase.Contract contract) {
        List<Map<String, Object>> causes = new ArrayList<>();
        for (Throwable cause = error; cause != null && causes.size() < 8; cause = cause.getCause()) {
            var detail = new LinkedHashMap<String, Object>();
            detail.put("type", cause.getClass().getName());
            if (contract != null && cause.getMessage() != null) {
                String text = contract.redact(cause.getMessage()).replaceAll("[\\p{Cntrl}]+", " ");
                detail.put("message", text.substring(0, Math.min(text.length(), 450)));
            }
            if (cause instanceof SQLException sqlError) {
                detail.put("sql_state", sqlError.getSQLState());
                detail.put("vendor_code", sqlError.getErrorCode());
            }
            if (cause.getStackTrace().length > 0) {
                detail.put("at", cause.getStackTrace()[0].toString());
                for (StackTraceElement frame : cause.getStackTrace()) {
                    if (frame.getClassName().startsWith("RefundBusinessMySql")
                        || frame.getClassName().startsWith("RefundAuditDatabase")
                        || frame.getClassName().startsWith("FinOpeningMySql")) {
                        detail.put("suite_frame", frame.toString());
                        break;
                    }
                }
            }
            causes.add(detail);
        }
        return causes;
    }

    public static void main(String[] args) throws Exception {
        if (!"1".equals(System.getenv("ERP_AUDIT_REAL_MYSQL"))) {
            System.err.println("REFUSED: missing explicit isolated MySQL opt-in");
            System.exit(2);
        }
        if (args.length != 1) throw new IllegalArgumentException("result file argument required");
        var report = new LinkedHashMap<String, Object>();
        var dbReport = new LinkedHashMap<String, Object>();
        var cases = new LinkedHashMap<String, Map<String, Object>>();
        CASES.forEach(spec -> {
            var item = new LinkedHashMap<String, Object>();
            item.put("status", "not-run");
            cases.put(spec.name(), item);
        });
        report.put("database", dbReport);
        report.put("cases", cases);
        report.put("java_runtime", System.getProperty("java.runtime.version"));
        report.put("scope", Map.of(
            "real", List.of("FinReceiptServiceImpl", "FinArApServiceImpl", "FinPaymentServiceImpl", "FinOpeningServiceImpl",
                "FinOpeningGuardServiceImpl/MANDATORY", "MyBatis-Plus mappers and production tenant handler",
                "Spring annotation transaction proxies", "MySQL8/InnoDB", "SequenceService/REQUIRES_NEW",
                "ConfirmPermission, SaPermissionImpl and Sa-Token JWT logic"),
            "fixture", List.of("synthetic customer/account/sale/return and original settlement", "in-process Sa-Token request/session identity and permission grants",
                "minimal caller return status/link choreography via ErpSaleReturnMapper, not ErpSaleReturnServiceImpl",
                "FinOpeningMySql real two-transaction opening/ledger and opening/payment races; receipt lifecycle cases"),
            "not_covered", List.of("login/authentication/HTTP/controller or database role grants", "Redis and distributed sessions",
                "inventory/stock/return limits/order progress/full trade workflow", "production deployment or complete schema migrations")));
        RefundAuditDatabase.Contract contract = null;
        try {
            contract = RefundAuditDatabase.Contract.fromExec();
            if (LoggerFactory.getILoggerFactory() instanceof LoggerContext logs) logs.getLogger("ROOT").setLevel(Level.OFF);
            try (var database = new RefundAuditDatabase(contract, dbReport)) {
                database.prepare();
                var memoryDao = new SaTokenDaoDefaultImpl();
                try (var context = tenantContext()) {
                    var suite = new RefundBusinessMySql(database, contract, security(memoryDao));
                    suite.seedOwners();
                    for (CaseSpec spec : CASES) {
                        Map<String, Object> item = cases.get(spec.name());
                        try {
                            spec.action().run(suite, item);
                            item.put("status", "passed");
                        } catch (Exception | AssertionError | LinkageError failure) {
                            item.put("status", "failed");
                            item.put("causes", describe(failure, contract));
                        }
                    }
                } finally {
                    memoryDao.destroy();
                }
            }
        } catch (Exception | AssertionError | LinkageError failure) {
            report.put("fatal", describe(failure, contract));
        }
        boolean passed = !report.containsKey("fatal") && Boolean.TRUE.equals(dbReport.get("cleanup_verified"))
            && cases.values().stream().allMatch(item -> "passed".equals(item.get("status")));
        report.put("passed", passed);
        report.put("checked_at_utc", Instant.now().toString());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(), report);
        System.exit(passed ? 0 : 1);
    }
}
