import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.aopalliance.intercept.MethodInvocation;
import org.dromara.common.core.exception.ServiceException;
import org.erp.common.sequence.SequenceService;
import org.erp.fin.domain.bo.FinOpeningBo;
import org.erp.fin.service.IFinArApService;
import org.erp.fin.service.IFinOpeningGuardService;
import org.erp.fin.service.IFinOpeningService;
import org.erp.master.mapper.ErpProductMapper;
import org.erp.master.mapper.ErpProductRecipeMapper;
import org.erp.master.mapper.ErpSupplierMapper;
import org.erp.master.service.IErpWarehouseService;
import org.erp.master.service.impl.ErpProductServiceImpl;
import org.erp.stock.service.IInventoryService;
import org.erp.trade.domain.bo.ErpPurchaseBo;
import org.erp.trade.domain.bo.ErpPurchaseReturnBo;
import org.erp.trade.mapper.ErpPurchaseMapper;
import org.erp.trade.mapper.ErpPurchaseItemMapper;
import org.erp.trade.mapper.ErpPurchaseOrderMapper;
import org.erp.trade.mapper.ErpPurchaseOrderItemMapper;
import org.erp.trade.mapper.ErpPurchaseReturnMapper;
import org.erp.trade.mapper.ErpPurchaseReturnItemMapper;
import org.erp.trade.service.IErpOrderProgressService;
import org.erp.trade.service.IErpPurchaseService;
import org.erp.trade.service.IErpPurchaseReturnService;
import org.erp.trade.service.impl.ErpPurchaseServiceImpl;
import org.erp.trade.service.impl.ErpPurchaseReturnServiceImpl;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** AP cutoff matrix and deterministic RR races through real trade service Spring proxies. */
final class PurchaseOpeningMySql {
    interface Identity { <T> T as(boolean peer, Supplier<T> action); }
    enum Kind {
        PURCHASE("biz_purchase", "biz_purchase_item", "PURCHASE", "CG"),
        PURCHASE_RETURN("biz_purchase_return", "biz_purchase_return_item", "PURCHASE_RETURN", "CT");
        final String head, item, source, sequence;
        Kind(String head, String item, String source, String sequence) {
            this.head = head; this.item = item; this.source = source; this.sequence = sequence;
        }
    }
    enum Entry { CONFIRM, INSERT_CONFIRM, UPDATE_CONFIRM }
    private record Fixture(long supplier, long product, long order, long orderItem, Long purchase, Long purchaseItem) {}
    private static final String TENANT = "927101";
    private static final long USER = 7101L, WAREHOUSE = 301L;
    private static final LocalDate CUTOFF = LocalDate.of(2024, 3, 10);
    private static final Date NEXT_DAY = Date.valueOf(CUTOFF.plusDays(1));
    private static final Date DUE = Date.valueOf("2024-03-31");
    private static final String DATE_DENIAL = "不能早于或等于已确认期初截止日";
    private static final String OVERLAP_DENIAL = "及之前已有 1 笔已确认业务（含收付款）";
    private static final int MYSQL_LOCK_NOWAIT = 3572;
    private final TradeReturnAuditDatabase db;
    private final JdbcTemplate sql, reader;
    private final Identity identity;
    private final TransactionTemplate repeatableRead;
    private final IErpPurchaseService purchases;
    private final IErpPurchaseReturnService returns;
    private final IFinOpeningService openings;
    private long nextId = 8000;

    static void registerMappers(MybatisConfiguration config) {
        for (Class<?> type : List.of(ErpPurchaseMapper.class, ErpPurchaseItemMapper.class,
            ErpPurchaseOrderMapper.class, ErpPurchaseOrderItemMapper.class,
            ErpPurchaseReturnMapper.class, ErpPurchaseReturnItemMapper.class, ErpProductRecipeMapper.class)) config.addMapper(type);
    }

    PurchaseOpeningMySql(TradeReturnAuditDatabase db, DataSourceTransactionManager tx, SqlSessionTemplate session,
                         Identity identity, IInventoryService inventory, IErpWarehouseService warehouses,
                         IFinArApService arAp, IFinOpeningService openings, IFinOpeningGuardService guard,
                         SequenceService sequence, IErpOrderProgressService progress) {
        this.db = db;
        this.sql = new JdbcTemplate(db.dataSource());
        this.reader = db.reader();
        this.identity = identity;
        this.openings = openings;
        repeatableRead = new TransactionTemplate(tx);
        repeatableRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        repeatableRead.setTimeout(22);
        var product = session.getMapper(ErpProductMapper.class);
        var supplier = session.getMapper(ErpSupplierMapper.class);
        var productService = TradeReturnMySql.txProxy(tx, ErpProductServiceImpl.class,
            new ErpProductServiceImpl(product, session.getMapper(ErpProductRecipeMapper.class)), null);
        purchases = TradeReturnMySql.txProxy(tx, ErpPurchaseServiceImpl.class,
            new ErpPurchaseServiceImpl(session.getMapper(ErpPurchaseMapper.class), session.getMapper(ErpPurchaseReturnMapper.class),
                session.getMapper(ErpPurchaseItemMapper.class), session.getMapper(ErpPurchaseOrderMapper.class),
                session.getMapper(ErpPurchaseOrderItemMapper.class), product, supplier, inventory, productService,
                warehouses, sequence, arAp, progress, guard), this::observeService);
        returns = TradeReturnMySql.txProxy(tx, ErpPurchaseReturnServiceImpl.class,
            new ErpPurchaseReturnServiceImpl(session.getMapper(ErpPurchaseReturnMapper.class),
                session.getMapper(ErpPurchaseReturnItemMapper.class), session.getMapper(ErpPurchaseMapper.class),
                session.getMapper(ErpPurchaseItemMapper.class), product, supplier, inventory, warehouses,
                arAp, sequence, progress, guard), this::observeService);
    }

    private Object observeService(MethodInvocation invocation) throws Throwable {
        if (Set.of("confirm", "insert", "update").contains(invocation.getMethod().getName())) {
            must(TransactionSynchronizationManager.isActualTransactionActive(), "real purchase service lacks Spring transaction");
        }
        return invocation.proceed();
    }

    private <T> T as(Supplier<T> action) { return identity.as(false, action); }
    private void runAs(Runnable action) { as(() -> { action.run(); return null; }); }

    /** No SQL trade confirmations: original purchases, drafts and all tested transitions use production services. */
    private Fixture fixture(Kind kind) {
        long supplier = ++nextId, product = ++nextId, order = ++nextId, item = ++nextId;
        one(sql.update("INSERT INTO md_supplier(id,tenant_id,code,name) VALUES(?,?,?,?)", supplier, TENANT,
            "AP-AUDIT-" + supplier, "AP fixture " + supplier), "supplier seed");
        one(sql.update("INSERT INTO md_product(id,tenant_id,code,name,qty_scale,supplier_id) VALUES(?,?,?,?,0,?)", product,
            TENANT, "AP-PRODUCT-" + product, "AP product " + product, supplier), "product seed");
        one(sql.update("""
            INSERT INTO biz_purchase_order(id,tenant_id,no,supplier_id,biz_date,due_date,status,exec_status,contact_name,contact_phone,
                tax_type,tax_rate,goods_amount)
            VALUES(?,?,?,?,?,?,'CONFIRMED','NOT_STARTED','AP contact','123','NO_TAX',0,100)
            """, order, TENANT, "CGD-AUDIT-" + order, supplier, Date.valueOf(CUTOFF.minusDays(10)), DUE), "order seed");
        one(sql.update("""
            INSERT INTO biz_purchase_order_item(id,tenant_id,order_id,product_id,qty,price,amount,due_date)
            VALUES(?,?,?,?,10,10,100,?)
            """, item, TENANT, order, product, DUE), "order item seed");
        var base = new Fixture(supplier, product, order, item, null, null);
        if (kind == Kind.PURCHASE) return base;
        long original = as(() -> purchases.insert(purchaseBo(base, NEXT_DAY, true, 10)).getId());
        long originalItem = scalar(reader, "SELECT id FROM biz_purchase_item WHERE order_id=? AND tenant_id=? AND del_flag='0'",
            original, TENANT);
        checkConfirmed(reader, Kind.PURCHASE, base, original, NEXT_DAY);
        return new Fixture(supplier, product, order, item, original, originalItem);
    }

    private ErpPurchaseBo purchaseBo(Fixture f, Date date, boolean confirm, int price) {
        var bo = new ErpPurchaseBo();
        bo.setSupplierId(f.supplier);
        bo.setSourceOrderId(f.order);
        bo.setBizDate(date);
        bo.setTaxType("NO_TAX");
        bo.setTaxRate(BigDecimal.ZERO);
        bo.setFreightAmount(bd(20));
        bo.setRemark("AP purchase " + date + "/" + price);
        bo.setConfirm(confirm);
        var item = new ErpPurchaseBo.PurchaseItemBo();
        item.setProductId(f.product);
        item.setSourceOrderItemId(f.orderItem);
        item.setQty(bd(10));
        item.setPrice(bd(price));
        item.setRemark("purchase item " + price);
        bo.setItems(List.of(item));
        return bo;
    }

    private ErpPurchaseReturnBo returnBo(Fixture f, Date date, boolean confirm, int price) {
        var bo = new ErpPurchaseReturnBo();
        bo.setPurchaseId(f.purchase);
        bo.setBizDate(date);
        bo.setConfirm(confirm);
        bo.setRemark("AP return " + date + "/" + price);
        var item = new ErpPurchaseReturnBo.ItemBo();
        item.setPurchaseItemId(f.purchaseItem);
        item.setQty(bd(5));
        item.setPrice(bd(price));
        item.setRemark("return item " + price);
        bo.setItems(List.of(item));
        return bo;
    }

    private long insert(Kind kind, Fixture f, Date date, boolean confirm, int price) {
        return kind == Kind.PURCHASE ? purchases.insert(purchaseBo(f, date, confirm, price)).getId()
            : returns.insert(returnBo(f, date, confirm, price)).getId();
    }

    private void update(Kind kind, Fixture f, long id, Date date, boolean confirm, int price) {
        if (kind == Kind.PURCHASE) purchases.update(id, purchaseBo(f, date, confirm, price));
        else returns.update(id, returnBo(f, date, confirm, price));
    }

    private void confirm(Kind kind, long id) {
        if (kind == Kind.PURCHASE) purchases.confirm(id); else returns.confirm(id);
    }

    private long attempt(Kind kind, Entry entry, Fixture f, Long draft, Date date, int price) {
        if (entry == Entry.INSERT_CONFIRM) return insert(kind, f, date, true, price);
        if (entry == Entry.UPDATE_CONFIRM) update(kind, f, draft, date, true, price);
        else confirm(kind, draft);
        return draft;
    }

    private FinOpeningBo openingBo(Fixture f) {
        var bo = new FinOpeningBo();
        bo.setPartyType("AP");
        bo.setPartyId(f.supplier);
        bo.setOpeningDate(CUTOFF);
        bo.setAmount(bd(200));
        bo.setRemark("AP opening audit");
        return bo;
    }

    private long saveConfirmOpening(Fixture f) {
        long id = openings.save(openingBo(f)).getId();
        openings.confirm(id);
        return id;
    }

    void cutoff(Kind kind, Entry entry, boolean previousDay, Map<String, Object> evidence) {
        Fixture f = fixture(kind);
        long opening = as(() -> saveConfirmOpening(f));
        checkOpening(reader, f, opening);
        Date deniedDate = Date.valueOf(previousDay ? CUTOFF.minusDays(1) : CUTOFF);
        Long draft = entry == Entry.INSERT_CONFIRM ? null : as(() -> insert(kind, f, deniedDate, false, 10));
        var before = db.snapshot();
        var sequenceBefore = sequences();
        // A changed price/remark makes UPDATE rejection prove restoration of actual head/item writes.
        reject(DATE_DENIAL, () -> as(() -> attempt(kind, entry, f, draft, deniedDate,
            entry == Entry.UPDATE_CONFIRM ? 11 : 10)));
        eq(before, db.snapshot(), "denied " + kind + "/" + entry + " changed a full business/soft-delete snapshot");
        checkSequenceDelta(sequenceBefore, kind.sequence, entry == Entry.INSERT_CONFIRM ? 1 : 0);
        if (draft != null) eq("DRAFT", status(reader, kind, draft), "denied draft state");
        var afterDenialSequence = sequences();
        if (entry == Entry.CONFIRM) runAs(() -> update(kind, f, draft, NEXT_DAY, false, 10));
        long id = as(() -> attempt(kind, entry, f, draft, NEXT_DAY, 10));
        checkConfirmed(reader, kind, f, id, NEXT_DAY);
        checkOpening(reader, f, opening);
        checkSequenceDelta(afterDenialSequence, kind.sequence, entry == Entry.INSERT_CONFIRM ? 1 : 0);
        int headDelta = entry == Entry.INSERT_CONFIRM ? 1 : 0;
        int itemDelta = headDelta + (kind == Kind.PURCHASE_RETURN && entry != Entry.INSERT_CONFIRM ? 1 : 0);
        var after = db.snapshot();
        delta(before, after, kind.head, headDelta);
        delta(before, after, kind.item, itemDelta);
        delta(before, after, "fin_ar_ap", 1);
        delta(before, after, "st_stock_log", 1);
        delta(before, after, "st_stock", kind == Kind.PURCHASE ? 1 : 0);
        unchangedExcept(before, after, Set.of(kind.head, kind.item, "fin_ar_ap", "st_stock_log", "st_stock",
            "biz_purchase_order", "biz_purchase_order_item", "md_product"));
        evidence.put("real_service", kind == Kind.PURCHASE ? "ErpPurchaseServiceImpl" : "ErpPurchaseReturnServiceImpl");
        evidence.put("entry", entry.name());
        evidence.put("denied_date", deniedDate.toString());
        evidence.put("opening_cutoff", CUTOFF.toString());
        evidence.put("accepted_date", NEXT_DAY.toString());
        evidence.put("rejection_code", 40902);
        evidence.put("full_all_tenant_and_soft_delete_snapshot_unchanged_on_denial", true);
        evidence.put("independent_requires_new_number_increment_on_denied_insert", entry == Entry.INSERT_CONFIRM ? 1 : 0);
        evidence.put("following_day_real_head_item_inventory_log_ap_product_cost_and_order_chain_verified", true);
    }

    private void checkConfirmed(JdbcTemplate jdbc, Kind kind, Fixture f, long id, Date date) {
        var head = jdbc.queryForMap("SELECT * FROM " + kind.head + " WHERE id=? AND tenant_id=? AND del_flag='0'", id, TENANT);
        eq("CONFIRMED", head.get("status"), "real AP trade confirmed");
        eq(f.supplier, number(head, "supplier_id"), "AP head supplier");
        eq(USER, number(head, "confirmed_by"), "production confirm user");
        must(head.get("confirmed_at") != null, "production confirm timestamp missing");
        eq(date, head.get("biz_date"), "trade business date");
        boolean purchase = kind == Kind.PURCHASE;
        dec(purchase ? "120" : "50", head.get("total_amount"), "head total");
        String no = Objects.toString(head.get("no"));
        must(no.matches(kind.sequence + "[0-9]{12}"), "production document numbering");
        var line = jdbc.queryForMap("SELECT * FROM " + kind.item
            + " WHERE order_id=? AND tenant_id=? AND del_flag='0'", id, TENANT);
        eq(f.product, number(line, "product_id"), "line product");
        dec(purchase ? "10" : "5", line.get("qty"), "line qty");
        dec("10", line.get("price"), "line price");
        dec(purchase ? "100" : "50", line.get("amount"), "line amount");
        dec("12", line.get(purchase ? "cost_price" : "unit_cost"), "real landed cost");
        if (purchase) {
            eq(f.order, number(head, "source_order_id"), "purchase source order");
            eq("CGD-AUDIT-" + f.order, head.get("source_order_no"), "source number snapshot");
            eq("AP contact", head.get("contact_name"), "source contact snapshot");
            eq("123", head.get("contact_phone"), "source phone snapshot");
            eq(f.orderItem, number(line, "source_order_item_id"), "line source order link");
            dec("20", head.get("freight_amount"), "head freight");
            dec("20", line.get("freight_amount"), "line freight");
            dec("2", line.get("freight_per_unit"), "landed freight per unit");
        } else {
            eq(f.purchase, number(head, "purchase_id"), "return original purchase");
            eq(f.purchaseItem, number(line, "purchase_item_id"), "return original item");
            dec("60", line.get("return_cost"), "return line cost");
            dec("60", head.get("return_cost_amount"), "return head cost");
            eq("CONFIRMED", status(jdbc, Kind.PURCHASE, f.purchase), "original purchase retained");
            var originalAp = jdbc.queryForMap("SELECT * FROM fin_ar_ap WHERE tenant_id=? AND source_type='PURCHASE' AND source_id=?",
                TENANT, f.purchase);
            dec("120", originalAp.get("amount"), "original purchase AP retained");
            dec("0", originalAp.get("settled_amount"), "return does not settle original AP");
            eq("OPEN", originalAp.get("status"), "original purchase AP state retained");
        }
        var stock = jdbc.queryForMap("SELECT * FROM st_stock WHERE tenant_id=? AND product_id=? AND warehouse_id=? AND del_flag='0'",
            TENANT, f.product, WAREHOUSE);
        dec(purchase ? "10" : "5", stock.get("qty"), "real stock qty");
        dec("12", stock.get("avg_cost"), "real average landed cost");
        var log = jdbc.queryForMap("SELECT * FROM st_stock_log WHERE tenant_id=? AND biz_type=? AND biz_id=? AND del_flag='0'",
            TENANT, kind.source, id);
        eq(no, log.get("biz_no"), "stock log document number");
        eq(f.product, number(log, "product_id"), "stock log product");
        eq(WAREHOUSE, number(log, "warehouse_id"), "real default warehouse");
        eq("SUPPLIER", log.get("partner_type"), "stock log partner type");
        eq(f.supplier, number(log, "partner_id"), "stock log supplier");
        eq(date, log.get("biz_date"), "stock log business date");
        dec(purchase ? "10" : "-5", log.get("qty"), "stock log signed qty");
        dec("12", log.get("unit_cost"), "stock log landed cost");
        // Production outbound records a negative quantity but a positive cost magnitude.
        dec(purchase ? "120" : "60", log.get("amount"), "stock log movement cost magnitude");
        dec(purchase ? "10" : "5", log.get("balance_after"), "stock balance after");
        var ap = jdbc.queryForMap("SELECT * FROM fin_ar_ap WHERE tenant_id=? AND source_type=? AND source_id=? AND del_flag='0'",
            TENANT, kind.source, id);
        eq("AP", ap.get("party_type"), "trade creates AP not AR");
        eq(f.supplier, number(ap, "party_id"), "AP supplier");
        eq(date, ap.get("biz_date"), "AP business date");
        dec(purchase ? "120" : "-50", ap.get("amount"), "purchase AP includes freight; return AP excludes freight");
        dec("0", ap.get("settled_amount"), "AP unallocated");
        eq("OPEN", ap.get("status"), "AP active state");
        eq(1L, number(ap, "active_flag"), "D15 active AP uniqueness");
        var order = jdbc.queryForMap("SELECT * FROM biz_purchase_order WHERE id=? AND tenant_id=?", f.order, TENANT);
        var orderItem = jdbc.queryForMap("SELECT * FROM biz_purchase_order_item WHERE id=? AND tenant_id=?", f.orderItem, TENANT);
        eq("CONFIRMED", order.get("status"), "source order remains confirmed");
        dec("100", order.get("goods_amount"), "source order goods amount retained");
        dec("10", orderItem.get("qty"), "source order committed quantity retained");
        dec("100", orderItem.get("amount"), "source order item amount retained");
        eq(purchase ? "FULL" : "PARTIAL", order.get("exec_status"), "real derived order execution status");
        eq(DUE, order.get("due_date"), "real derived order due date");
        dec(purchase ? "10" : "5", orderItem.get("executed_qty"), "real derived order net execution");
        dec("0", orderItem.get("closed_qty"), "order closed qty retained");
        dec("12", jdbc.queryForObject("SELECT cost_price FROM md_product WHERE id=? AND tenant_id=?", BigDecimal.class,
            f.product, TENANT), "real ErpProductServiceImpl latest cost");
    }

    private void checkOpening(JdbcTemplate jdbc, Fixture f, long id) {
        var head = jdbc.queryForMap("SELECT * FROM fin_opening WHERE id=? AND tenant_id=?", id, TENANT);
        eq("CONFIRMED", head.get("status"), "real opening confirmed");
        eq("AP", head.get("party_type"), "opening AP direction");
        eq(f.supplier, number(head, "party_id"), "opening supplier");
        eq(Date.valueOf(CUTOFF), head.get("opening_date"), "opening cutoff persisted");
        dec("200", head.get("amount"), "opening amount");
        var ap = jdbc.queryForMap("SELECT * FROM fin_ar_ap WHERE tenant_id=? AND source_type='OPENING' AND source_id=?", TENANT, id);
        eq("AP", ap.get("party_type"), "opening AP ledger direction");
        eq(f.supplier, number(ap, "party_id"), "opening AP supplier");
        eq(Date.valueOf(CUTOFF), ap.get("biz_date"), "opening ledger cutoff date");
        eq("OPEN", ap.get("status"), "opening ledger state");
        dec("200", ap.get("amount"), "opening ledger amount");
        dec("0", ap.get("settled_amount"), "opening ledger untouched");
    }

    void race(Kind kind, boolean openingFirst, Map<String, Object> evidence) throws Exception {
        Fixture f = fixture(kind);
        long draft = as(() -> insert(kind, f, Date.valueOf(CUTOFF), false, 10));
        eq(0L, openingCount(reader, f), "race starts without opening");
        var before = db.snapshot();
        var sequenceBefore = sequences();
        var winnerReady = new CountDownLatch(1);
        var contenderSnapshot = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var winnerId = new AtomicLong();
        var contenderId = new AtomicLong();
        var openingId = new AtomicLong();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var winner = pool.submit(() -> identity.as(false, () -> transaction(() -> {
                winnerId.set(scalar(sql, "SELECT CONNECTION_ID()"));
                if (openingFirst) {
                    openingId.set(saveConfirmOpening(f));
                    checkOpening(sql, f, openingId.get());
                } else {
                    confirm(kind, draft); // Actual ErpPurchase[Return]ServiceImpl.confirm; no SQL status update.
                    checkConfirmed(sql, kind, f, draft, Date.valueOf(CUTOFF));
                }
                var committedExpectation = db.snapshot(sql);
                winnerReady.countDown();
                awaitRelease(release);
                return committedExpectation;
            })));
            reached(winnerReady, winner, "real trade/opening winner did not reach precommit");
            Runnable staleView = () -> {
                eq("DRAFT", status(sql, kind, draft), "RR consistent read must retain old trade draft");
                eq(0L, scalar(sql, "SELECT COUNT(*) FROM fin_ar_ap WHERE tenant_id=? AND source_type=? AND source_id=?",
                    TENANT, kind.source, draft), "RR plain read must retain absent trade AP");
                if (openingFirst) eq(0L, openingCount(sql, f), "RR plain read must retain absent opening");
            };
            var contender = pool.submit(() -> reject(openingFirst ? DATE_DENIAL : OVERLAP_DENIAL,
                () -> identity.as(true, () -> transaction(() -> {
                    contenderId.set(scalar(sql, "SELECT CONNECTION_ID()"));
                    staleView.run(); // Establish old RR consistent view BEFORE the stable party lock waits.
                    contenderSnapshot.countDown();
                    try {
                        if (openingFirst) confirm(kind, draft);
                        else saveConfirmOpening(f); // Both real opening calls in the same outer transaction.
                    } catch (ServiceException expected) {
                        staleView.run(); // Current-read rejection must not refresh the old consistent view.
                        throw expected;
                    }
                    throw new AssertionError("conflicting real-service contender unexpectedly succeeded");
                }))));
            reached(contenderSnapshot, contender, "contender did not establish RR view");
            long observerId = scalar(reader, "SELECT CONNECTION_ID()");
            long nowaitId = provePartyLocked(f);
            must(List.of(winnerId.get(), contenderId.get(), observerId, nowaitId).stream().distinct().count() == 4,
                "winner/contender/observer/NOWAIT must be distinct physical JDBC connections");
            int samples = 0;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (samples < 2 && System.nanoTime() < deadline && !contender.isDone()) {
                if (partyQueryActive(contenderId.get())) samples++;
                if (samples < 2) Thread.sleep(60);
            }
            must(samples == 2 && !contender.isDone(), "server did not observe contender waiting on stable supplier lock");
            eq(before, db.snapshot(), "uncommitted real service winner leaked partial business writes");
            release.countDown();
            var expected = winner.get(15, TimeUnit.SECONDS);
            contender.get(15, TimeUnit.SECONDS); // Only exact ServiceException(40902) passes; deadlock/timeouts fail.
            eq(expected, db.snapshot(), "race loser left head/item/inventory/log/AP/order/opening partial writes");
            checkSequenceDelta(sequenceBefore, kind.sequence, 0);
            if (openingFirst) {
                checkOpening(reader, f, openingId.get());
                eq("DRAFT", status(reader, kind, draft), "losing real trade stays draft");
                // Also prove retry on the exact next day retains the full positive production chain.
                runAs(() -> update(kind, f, draft, NEXT_DAY, true, 10));
                checkConfirmed(reader, kind, f, draft, NEXT_DAY);
                checkOpening(reader, f, openingId.get());
            } else {
                checkConfirmed(reader, kind, f, draft, Date.valueOf(CUTOFF));
                eq(0L, openingCount(reader, f), "losing real opening save must roll back its newly inserted draft");
                eq(0L, scalar(reader, "SELECT COUNT(*) FROM fin_ar_ap WHERE tenant_id=? AND party_type='AP' AND party_id=? AND source_type='OPENING'",
                    TENANT, f.supplier), "losing opening ledger absent");
            }
            evidence.put("mysql_connection_ids", Map.of("winner", winnerId.get(), "contender", contenderId.get(),
                "observer", observerId, "nowait_probe", nowaitId));
            evidence.put("winner", openingFirst ? "real FinOpeningServiceImpl.save+confirm" : kind.source + " real service confirm");
            evidence.put("contender", openingFirst ? kind.source + " real service confirm" : "real FinOpeningServiceImpl.save+confirm");
            evidence.put("locking_table", "md_supplier");
            evidence.put("nowait_vendor_code", MYSQL_LOCK_NOWAIT);
            evidence.put("server_observed_contender_locking_query_samples", samples);
            evidence.put("jdbc_isolation", "REPEATABLE-READ");
            evidence.put("plain_rr_snapshot_remained_old_after_current_read_rejection", true);
            evidence.put("contender_erp_rejection_code", 40902);
            evidence.put("full_final_snapshot_equals_only_real_winner_writes", true);
            evidence.put("deadlock_or_lock_timeout_accepted", false);
        } finally {
            release.countDown();
            pool.shutdown();
            if (!pool.awaitTermination(24, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                must(pool.awaitTermination(20, TimeUnit.SECONDS), "trade race workers still active; cannot accept or clean fixture");
            }
        }
    }

    private long provePartyLocked(Fixture f) {
        return transaction(() -> {
            long connection = scalar(sql, "SELECT CONNECTION_ID()");
            try {
                scalar(sql, "SELECT id FROM md_supplier WHERE id=? AND tenant_id=? AND del_flag='0' FOR UPDATE NOWAIT",
                    f.supplier, TENANT);
            } catch (RuntimeException error) {
                for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                    if (cause instanceof SQLException mysql && mysql.getErrorCode() == MYSQL_LOCK_NOWAIT) return connection;
                }
                throw error;
            }
            throw new AssertionError("real winner did not own the stable supplier row lock");
        });
    }

    private boolean partyQueryActive(long connection) {
        // Dedicated schema privileges suffice to observe this account's own sessions; no global PROCESS grant.
        return Boolean.TRUE.equals(reader.query("SHOW FULL PROCESSLIST", rows -> {
            while (rows.next()) {
                String text = rows.getString("Info");
                if (connection == rows.getLong("Id") && text != null && "Query".equals(rows.getString("Command"))) {
                    String normalized = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
                    return normalized.contains("from md_supplier ") && normalized.contains("for update")
                        && normalized.contains("tenant_id");
                }
            }
            return false;
        }));
    }

    private <T> T transaction(Supplier<T> action) {
        return repeatableRead.execute(status -> {
            must(status.isNewTransaction() && TransactionSynchronizationManager.isActualTransactionActive(),
                "worker/probe must start its own real Spring transaction");
            sql.execute("SET SESSION innodb_lock_wait_timeout=9");
            eq("REPEATABLE-READ", sql.queryForObject("SELECT @@transaction_isolation", String.class), "race JDBC isolation");
            return action.get();
        });
    }

    private static void reached(CountDownLatch event, Future<?> task, String reason) throws Exception {
        if (!event.await(6, TimeUnit.SECONDS)) {
            if (task.isDone()) task.get();
            throw new AssertionError(reason);
        }
    }

    private static void awaitRelease(CountDownLatch event) {
        try { must(event.await(14, TimeUnit.SECONDS), "coordinator did not release real-service race winner"); }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("trade race interrupted", error);
        }
    }

    private Map<String, Long> sequences() {
        var values = new LinkedHashMap<String, Long>();
        for (String type : List.of("CG", "CT")) values.put(type, scalar(reader,
            "SELECT COALESCE(SUM(current_value),0) FROM sys_sequence WHERE tenant_id=? AND biz_type=?", TENANT, type));
        return values;
    }

    private void checkSequenceDelta(Map<String, Long> before, String kind, int delta) {
        var after = sequences();
        for (String type : before.keySet()) eq(before.get(type) + (kind.equals(type) ? delta : 0), after.get(type),
            "independent REQUIRES_NEW " + type + " sequence delta");
    }

    private static long openingCount(JdbcTemplate jdbc, Fixture f) {
        return scalar(jdbc, "SELECT COUNT(*) FROM fin_opening WHERE tenant_id=? AND party_type='AP' AND party_id=?", TENANT, f.supplier);
    }
    private static String status(JdbcTemplate jdbc, Kind kind, long id) {
        return jdbc.queryForObject("SELECT status FROM " + kind.head + " WHERE id=? AND tenant_id=? AND del_flag='0'", String.class, id, TENANT);
    }
    private static void delta(Map<String, List<Map<String, Object>>> before, Map<String, List<Map<String, Object>>> after,
                              String table, int count) {
        eq(before.get(table).size() + count, after.get(table).size(), "real row delta " + table);
    }
    private static void unchangedExcept(Map<String, List<Map<String, Object>>> before,
                                        Map<String, List<Map<String, Object>>> after, Set<String> changed) {
        for (String table : before.keySet()) if (!changed.contains(table)) eq(before.get(table), after.get(table), "untouched table " + table);
    }
    private static BigDecimal bd(int n) { return BigDecimal.valueOf(n); }
    private static long number(Map<String, Object> row, String key) { return ((Number) Objects.requireNonNull(row.get(key), key)).longValue(); }
    private static long scalar(JdbcTemplate jdbc, String query, Object... args) { return Objects.requireNonNull(jdbc.queryForObject(query, Long.class, args)); }
    private static void dec(String expected, Object actual, String label) {
        must(actual instanceof BigDecimal value && value.compareTo(new BigDecimal(expected)) == 0, label + ": " + actual);
    }
    private static void eq(Object expected, Object actual, String label) { must(Objects.equals(expected, actual), label); }
    private static void one(int count, String label) { eq(1, count, label); }
    private static void must(boolean value, String label) { if (!value) throw new AssertionError(label); }
    private static void reject(String text, Runnable action) {
        try { action.run(); }
        catch (ServiceException error) {
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                must(!(cause instanceof SQLException), "SQL errors (including deadlock/lock timeout) are not business rejection");
                must(!(cause instanceof java.util.concurrent.TimeoutException), "timeout is not business rejection");
            }
            eq(40902, error.getCode(), "exact ERP ServiceException rejection code");
            must(error.getMessage().contains(text), "wrong business rejection: " + error.getMessage());
            return;
        }
        throw new AssertionError("expected ServiceException(40902): " + text);
    }

    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void caseResult(Map<String, Object> cases, String name, Map<String, Object> evidence, Checked action) {
        var row = new LinkedHashMap<String, Object>();
        row.put("status", "failed");
        try { action.run(); row.put("status", "passed"); }
        catch (Throwable error) {
            row.put("error_class", error.getClass().getSimpleName());
            row.put("harness_frames", java.util.Arrays.stream(error.getStackTrace())
                .filter(frame -> frame.getClassName().startsWith("PurchaseOpening") || frame.getClassName().startsWith("TradeReturn"))
                .limit(6).map(StackTraceElement::toString).toList());
            if (error.getMessage() != null) row.put("message", error.getMessage().substring(0, Math.min(800, error.getMessage().length())));
        }
        cases.put(name, row);
        if ("passed".equals(row.get("status"))) cases.put(name + "_evidence", evidence);
    }

    static List<String> requiredCases() {
        var names = new java.util.ArrayList<String>();
        for (Kind kind : Kind.values()) {
            for (Entry entry : Entry.values()) for (boolean previous : List.of(false, true)) names.add(cutoffName(kind, entry, previous));
            for (boolean openingFirst : List.of(true, false)) names.add(raceName(kind, openingFirst));
        }
        return List.copyOf(names);
    }
    private static String cutoffName(Kind kind, Entry entry, boolean previous) {
        return kind.name().toLowerCase(Locale.ROOT) + "_" + entry.name().toLowerCase(Locale.ROOT)
            + (previous ? "_before_cutoff_denied_next_day_chain" : "_on_cutoff_denied_next_day_chain");
    }
    private static String raceName(Kind kind, boolean openingFirst) {
        return kind.name().toLowerCase(Locale.ROOT) + (openingFirst ? "_opening_first_rr_real_confirm" : "_trade_first_rr_opening_save_confirm");
    }
    void runCases(Map<String, Object> cases) {
        for (Kind kind : Kind.values()) {
            for (Entry entry : Entry.values()) for (boolean previous : List.of(false, true)) {
                var evidence = new LinkedHashMap<String, Object>();
                caseResult(cases, cutoffName(kind, entry, previous), evidence, () -> cutoff(kind, entry, previous, evidence));
            }
            for (boolean openingFirst : List.of(true, false)) {
                var evidence = new LinkedHashMap<String, Object>();
                caseResult(cases, raceName(kind, openingFirst), evidence, () -> race(kind, openingFirst, evidence));
            }
        }
    }
}
