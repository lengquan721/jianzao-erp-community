import org.dromara.common.core.exception.ServiceException;
import org.erp.fin.domain.bo.FinOpeningBo;
import org.erp.fin.domain.bo.FinPaymentBo;
import org.erp.fin.domain.bo.FinReceiptBo;
import org.erp.fin.service.IFinArApService;
import org.erp.fin.service.IFinOpeningService;
import org.erp.fin.service.IFinPaymentService;
import org.erp.fin.service.IFinReceiptService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Real InnoDB opening cases, executed only inside RefundBusinessMySql's guarded runner. */
final class FinOpeningMySql {
    private static final LocalDate CUTOFF = LocalDate.of(2024, 1, 10);
    private static final LocalDate NEXT_DAY = CUTOFF.plusDays(1);
    private static final String DATE_DENIAL = "不能早于或等于已确认期初截止日";
    private static final int MYSQL_LOCK_NOWAIT = 3572;
    private static final RefundBusinessMySql.Actor A = RefundBusinessMySql.A;
    private static final RefundBusinessMySql.Actor A2 = RefundBusinessMySql.A2;
    private static final RefundBusinessMySql.Actor B = RefundBusinessMySql.B;

    private enum Direction {
        AR("md_customer"), AP("md_supplier");
        private final String table;
        Direction(String table) { this.table = table; }
    }

    private record Party(Direction direction, long id) {
        String type() { return direction.name(); }
    }

    private static final class InjectedAfterWithdrawal extends RuntimeException {}

    private final RefundBusinessMySql owner;
    private final JdbcTemplate sql, reader;
    private final TransactionTemplate repeatableRead;
    private final IFinOpeningService openings;
    private final IFinArApService arAp;
    private final IFinReceiptService receipts;
    private final IFinPaymentService payments;

    FinOpeningMySql(RefundBusinessMySql owner, JdbcTemplate sql, JdbcTemplate reader,
                   DataSourceTransactionManager manager, IFinOpeningService openings, IFinArApService arAp,
                   IFinReceiptService receipts, IFinPaymentService payments) {
        this.owner = owner;
        this.sql = sql;
        this.reader = reader;
        this.openings = openings;
        this.arAp = arAp;
        this.receipts = receipts;
        this.payments = payments;
        repeatableRead = new TransactionTemplate(manager);
        repeatableRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        repeatableRead.setTimeout(22);
    }

    void openingFirst(Map<String, Object> evidence) throws Exception {
        Party party = seedParty(Direction.AR, 8001L);
        long sale = 9101L;
        long opening = contend(party, () -> {
            long id = confirmOpening(party, CUTOFF);
            checkHead(sql, party, id, "CONFIRMED", CUTOFF);
            openingLedger(sql, party, id, "OPEN", CUTOFF, 0);
            return id;
        }, () -> eq(0L, openingCount(sql, party), "RR consistent view must still see no opening"),
            () -> arAp.createBySale(sale, "XS-OPENING-RACE", party.id(), money(60), Date.valueOf(CUTOFF)),
            DATE_DENIAL, evidence);

        checkHead(reader, party, opening, "CONFIRMED", CUTOFF);
        openingLedger(reader, party, opening, "OPEN", CUTOFF, 0);
        eq(1L, openingCount(reader, party), "exactly one committed opening");
        eq(0L, scalar(reader, "SELECT COUNT(*) FROM fin_ar_ap WHERE tenant_id=? AND source_type='SALE' AND source_id=?",
            A.tenant(), sale), "cutoff sale ledger leaked after failed contender");
        runAs(A, () -> arAp.createBySale(sale, "XS-OPENING-RACE", party.id(), money(60), Date.valueOf(NEXT_DAY)));
        eq(Date.valueOf(NEXT_DAY), reader.queryForObject("""
            SELECT biz_date FROM fin_ar_ap WHERE tenant_id=? AND party_type='AR' AND party_id=?
              AND source_type='SALE' AND source_id=? AND status='OPEN'
            """, Date.class, A.tenant(), party.id(), sale), "same source confirms next day");
        evidence.put("winner", "AR opening save + confirm");
        evidence.put("contender", "real createBySale on cutoff; next-day retry commits");
    }

    void businessFirst(Map<String, Object> evidence) throws Exception {
        Party party = seedParty(Direction.AP, 8002L);
        long purchase = 9102L;
        eq(1, sql.update("""
            INSERT INTO biz_purchase (id,tenant_id,no,supplier_id,biz_date,status) VALUES (?,?,?,?,?,'CONFIRMED')
            """, purchase, A.tenant(), "CG-OPENING-RACE", party.id(), Date.valueOf(NEXT_DAY)), "purchase source fixture");
        long ledger = owner.as(A, () -> {
            // The purchase ledger is strictly after the cutoff; only the payment can overlap it.
            arAp.createByPurchase(purchase, "CG-OPENING-RACE", party.id(), money(60), Date.valueOf(NEXT_DAY));
            return Objects.requireNonNull(arAp.getBySource("PURCHASE", purchase)).getId();
        });
        FinPaymentBo bo = new FinPaymentBo();
        bo.setSupplierId(party.id());
        bo.setBizDate(Date.valueOf(CUTOFF));
        bo.setAccountId(A.account());
        bo.setAmount(money(20));
        bo.setConfirm(false);
        var allocation = new FinPaymentBo.AllocationBo();
        allocation.setArApId(ledger);
        allocation.setAmount(money(20));
        bo.setAllocations(List.of(allocation));
        var draftPayment = owner.as(A, () -> payments.insert(bo));
        long payment = draftPayment.getId();
        eq("CG-OPENING-RACE", draftPayment.getAllocations().get(0).getSourceNo(), "scoped AP source projection");
        eq("DRAFT", paymentStatus(reader, payment), "payment must start as a draft");

        contend(party, () -> {
            payments.confirm(payment);
            eq("CONFIRMED", paymentStatus(sql, payment), "real payment confirmed inside winner transaction");
            checkSettlement(sql, ledger, "PARTIAL", 20);
            return payment;
        }, () -> eq("DRAFT", paymentStatus(sql, payment), "RR consistent view must still see the old payment draft"),
            () -> {
                long draft = openings.save(openingBo(party, CUTOFF)).getId();
                checkHead(sql, party, draft, "DRAFT", CUTOFF);
                openings.confirm(draft);
            }, "及之前已有 1 笔已确认业务（含收付款）", evidence);

        eq("CONFIRMED", paymentStatus(reader, payment), "winning payment remains CONFIRMED");
        checkSettlement(reader, ledger, "PARTIAL", 20);
        eq(0L, openingCount(reader, party), "loser's real draft insert must roll back");
        eq(0L, scalar(reader, """
            SELECT COUNT(*) FROM fin_ar_ap WHERE tenant_id=? AND party_type='AP' AND party_id=? AND source_type='OPENING'
            """, A.tenant(), party.id()), "loser left an opening ledger");
        evidence.put("winner", "real AP payment confirm with allocation");
        evidence.put("contender", "real opening save + confirm rolls back draft on current-read overlap");
    }

    void legacyNullAndExplicitReconfirm(Map<String, Object> evidence) {
        Party party = seedParty(Direction.AR, 8003L);
        var empty = snapshot();
        reject(400, "期初截止日为空", () -> runAs(A, () -> openings.save(openingBo(party, null))));
        reject(400, "期初截止日不能晚于今天",
            () -> runAs(A, () -> openings.save(openingBo(party, LocalDate.now().plusDays(1)))));
        eq(empty, snapshot(), "invalid new date left writes");

        long legacy = 8803L;
        eq(1, sql.update("""
            INSERT INTO fin_opening (id,tenant_id,party_type,party_id,amount,status)
            VALUES (?,?,'AR',?,100,'CONFIRMED')
            """, legacy, A.tenant(), party.id()), "legacy NULL fixture");
        runAs(A, () -> arAp.createOpening(party.type(), party.id(), money(100), legacy, Date.valueOf(CUTOFF)));
        long originalLedger = openingLedger(reader, party, legacy, "OPEN", CUTOFF, 0);
        var legacyRows = snapshot().get("fin_opening");
        runAs(A, () -> arAp.createBySale(9103L, "XS-OPENING-LEGACY", party.id(), money(60), Date.valueOf(CUTOFF)));
        eq(legacyRows, snapshot().get("fin_opening"), "legacy NULL row was inferred or backfilled");
        checkHead(reader, party, legacy, "CONFIRMED", null);

        runAs(A, () -> openings.unconfirm(legacy));
        checkHead(reader, party, legacy, "DRAFT", null);
        checkSettlement(reader, originalLedger, "VOID", 0);
        var beforeReconfirm = snapshot();
        reject(400, "期初截止日为空", () -> runAs(A, () -> openings.confirm(legacy)));
        eq(beforeReconfirm, snapshot(), "NULL reconfirm mutated the draft or old ledger");
        LocalDate explicit = CUTOFF.minusDays(1);
        runAs(A, () -> {
            eq(legacy, openings.save(openingBo(party, explicit)).getId(), "explicit edit must retain the legacy id");
            openings.confirm(legacy);
        });
        checkHead(reader, party, legacy, "CONFIRMED", explicit);
        ensure(originalLedger != openingLedger(reader, party, legacy, "OPEN", explicit, 0), "reconfirm reused the void ledger");
        eq(originalLedger, openingLedger(reader, party, legacy, "VOID", CUTOFF, 0), "old ledger date/history retained");
        evidence.put("legacy_null_unchanged_until_explicit_edit", true);
        evidence.put("explicit_date_used_for_reconfirmed_ledger", explicit.toString());
    }

    void settlementAndControlledWithdrawal(Map<String, Object> evidence) {
        Party party = seedParty(Direction.AR, 8004L);
        long id = owner.as(A, () -> confirmOpening(party, CUTOFF));
        long ledger = openingLedger(reader, party, id, "OPEN", CUTOFF, 0);
        // Same numeric id in AP is deliberately present. Its business at the AR cutoff is allowed.
        runAs(A, () -> arAp.createByPurchase(9104L, "CG-DIRECTION-ISOLATION", party.id(), money(60), Date.valueOf(CUTOFF)));
        var isolated = snapshot();
        reject(40902, "本租户往来单位不存在", () -> runAs(B,
            () -> arAp.createBySale(9104L, "XS-FOREIGN-PARTY", party.id(), money(60), Date.valueOf(NEXT_DAY))));
        eq(isolated, snapshot(), "foreign tenant wrote business against another tenant's party");

        long receipt = owner.as(A, () -> receipts.insert(receiptBo(party, ledger, CUTOFF, false)).getId());
        eq("DRAFT", receiptStatus(reader, receipt), "receipt exactly on the cutoff can save as draft");
        var beforeConfirm = snapshot();
        reject(40902, DATE_DENIAL, () -> runAs(A, () -> receipts.confirm(receipt)));
        eq(beforeConfirm, snapshot(), "cutoff receipt confirm wrote head, allocation or ledger changes");
        runAs(A, () -> receipts.update(receipt, receiptBo(party, ledger, NEXT_DAY, true)));
        eq("CONFIRMED", receiptStatus(reader, receipt), "next day receipt confirms");
        checkSettlement(reader, ledger, "PARTIAL", 20);
        var settled = snapshot();
        reject(40904, "先反核销", () -> runAs(A, () -> openings.unconfirm(id)));
        eq(settled, snapshot(), "denied withdrawal must retain settled ledger and opening");

        try {
            runAs(A, () -> transaction(() -> {
                receipts.withdraw(receipt);
                openings.unconfirm(id);
                eq("DRAFT", receiptStatus(sql, receipt), "receipt withdrawn inside faulted transaction");
                checkHead(sql, party, id, "DRAFT", CUTOFF);
                checkSettlement(sql, ledger, "VOID", 0);
                eq(settled, snapshot(), "observer saw transient withdrawal writes");
                throw new InjectedAfterWithdrawal();
            }));
            throw new AssertionError("injected failure not propagated");
        } catch (InjectedAfterWithdrawal expected) {
            eq(settled, snapshot(), "injected failure did not restore opening, receipt and ledger");
        }
        runAs(A, () -> receipts.withdraw(receipt));
        checkSettlement(reader, ledger, "OPEN", 0);
        runAs(A, () -> openings.unconfirm(id));
        checkHead(reader, party, id, "DRAFT", CUTOFF);
        checkSettlement(reader, ledger, "VOID", 0);
        runAs(A, () -> arAp.createBySale(9104L, "XS-AFTER-OPENING-WITHDRAWAL", party.id(), money(60), Date.valueOf(CUTOFF)));
        eq(1L, scalar(reader, """
            SELECT COUNT(*) FROM fin_ar_ap WHERE tenant_id=? AND party_type='AR' AND party_id=?
              AND source_type='SALE' AND source_id=? AND biz_date=? AND status='OPEN'
            """, A.tenant(), party.id(), 9104L, Date.valueOf(CUTOFF)), "successful withdrawal releases the cutoff");
        evidence.put("cutoff_receipt_draft_then_next_day_confirm", true);
        evidence.put("same_id_opposite_direction_allowed_foreign_party_denied", true);
        evidence.put("settled_withdrawal_40904_then_unsettle_and_withdraw", true);
        evidence.put("withdrawal_unblocks_business_on_old_cutoff", true);
        evidence.put("post_dml_failure_restored_full_business_snapshot", true);
    }

    private <T> T contend(Party party, Supplier<T> winningAction, Runnable assertStaleView, Runnable losingAction,
                          String denial, Map<String, Object> evidence) throws Exception {
        eq(0L, openingCount(reader, party), "race must begin with no opening row");
        var before = snapshot();
        long observerId = scalar(reader, "SELECT CONNECTION_ID()");
        var winnerReady = new CountDownLatch(1);
        var contenderSnapshot = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var winnerId = new AtomicLong();
        var contenderId = new AtomicLong();
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<T> winner = pool.submit(() -> owner.as(A, () -> transaction(() -> {
                winnerId.set(scalar(sql, "SELECT CONNECTION_ID()"));
                T result = winningAction.get(); // Real services completed, but their outer transaction is still open.
                winnerReady.countDown();
                awaitRelease(release);
                return result;
            })));
            reached(winnerReady, winner, "winner did not reach pre-commit latch");
            Future<?> contender = pool.submit(() -> reject(40902, denial, () -> owner.as(A2, () -> transaction(() -> {
                contenderId.set(scalar(sql, "SELECT CONNECTION_ID()"));
                assertStaleView.run(); // Plain SELECT before any waiting statement establishes an RR view.
                contenderSnapshot.countDown();
                try {
                    losingAction.run();
                } catch (ServiceException expected) {
                    assertStaleView.run(); // The business decision saw the winner; the plain read still does not.
                    throw expected;
                }
                throw new AssertionError("conflicting contender unexpectedly succeeded");
            }))));
            reached(contenderSnapshot, contender, "contender did not establish RR snapshot");
            long lockProbeId = proveStableRowLocked(party);
            ensure(List.of(winnerId.get(), contenderId.get(), observerId, lockProbeId).stream().distinct().count() == 4,
                "winner, contender, observer and NOWAIT probe must use independent MySQL connections");

            int samples = 0;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (samples < 2 && System.nanoTime() < deadline && !contender.isDone()) {
                if (partyLockQueryActive(contenderId.get(), party.direction())) samples++;
                if (samples < 2) Thread.sleep(60); // Poll server, not a delay-only assertion.
            }
            ensure(samples == 2 && !contender.isDone(), "stable-party locking SELECT not observed while winner held the row");
            eq(before, snapshot(), "uncommitted winner writes leaked to independent observer");
            release.countDown();
            T result = winner.get(15, TimeUnit.SECONDS);
            contender.get(15, TimeUnit.SECONDS); // A lock timeout or deadlock is NOT an accepted result.
            evidence.put("mysql_connection_ids", Map.of("winner", winnerId.get(), "contender", contenderId.get(),
                "observer", observerId, "nowait_probe", lockProbeId));
            evidence.put("locking_table", party.direction().table);
            evidence.put("nowait_vendor_code", MYSQL_LOCK_NOWAIT);
            evidence.put("server_observed_contender_locking_query_samples", samples);
            evidence.put("plain_rr_snapshot_remained_old_after_business_rejection", true);
            evidence.put("contender_erp_rejection_code", 40902);
            return result;
        } finally {
            release.countDown();
            pool.shutdown();
            if (!pool.awaitTermination(24, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                ensure(pool.awaitTermination(20, TimeUnit.SECONDS), "opening race workers still active; not accepting case");
            }
        }
    }

    private long proveStableRowLocked(Party party) {
        // Read-only probe on a separate transaction; ER_LOCK_NOWAIT proves the exact live row is locked.
        return transaction(() -> {
            long connection = scalar(sql, "SELECT CONNECTION_ID()");
            try {
                scalar(sql, "SELECT id FROM " + party.direction().table
                    + " WHERE id=? AND tenant_id=? AND del_flag='0' FOR UPDATE NOWAIT", party.id(), A.tenant());
            } catch (RuntimeException error) {
                for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                    if (cause instanceof SQLException mysql && mysql.getErrorCode() == MYSQL_LOCK_NOWAIT) return connection;
                }
                throw error;
            }
            throw new AssertionError("winner does not own the stable party record lock");
        });
    }

    private boolean partyLockQueryActive(long connection, Direction direction) {
        // A dedicated schema account can see its own connections without granting global PROCESS privileges.
        return Boolean.TRUE.equals(reader.query("SHOW FULL PROCESSLIST", rows -> {
            while (rows.next()) {
                String text = rows.getString("Info");
                if (connection == rows.getLong("Id") && text != null && "Query".equals(rows.getString("Command"))) {
                    String normalized = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
                    return normalized.contains("from " + direction.table + " ") && normalized.contains("for update")
                        && normalized.contains("tenant_id");
                }
            }
            return false;
        }));
    }

    private <T> T transaction(Supplier<T> action) {
        return repeatableRead.execute(status -> {
            ensure(TransactionSynchronizationManager.isActualTransactionActive() && status.isNewTransaction(),
                "each worker/probe needs its own Spring transaction");
            sql.execute("SET SESSION innodb_lock_wait_timeout=9");
            eq("REPEATABLE-READ", sql.queryForObject("SELECT @@transaction_isolation", String.class), "JDBC isolation");
            return action.get();
        });
    }

    private static void reached(CountDownLatch event, Future<?> task, String reason) throws Exception {
        if (!event.await(6, TimeUnit.SECONDS)) {
            if (task.isDone()) task.get(); // Preserve the actual failure instead of reporting a generic race timeout.
            throw new AssertionError(reason);
        }
    }

    private static void awaitRelease(CountDownLatch event) {
        try {
            ensure(event.await(14, TimeUnit.SECONDS), "coordinator did not release opening race winner");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("opening race interrupted", e);
        }
    }

    private Party seedParty(Direction direction, long id) {
        for (Direction table : Direction.values()) {
            eq(1, sql.update("INSERT INTO " + table.table + " (id,tenant_id,code,name) VALUES (?,?,?,?)",
                id, A.tenant(), "OPENING-" + id, "Opening audit " + table.name() + " " + id), "stable party fixture");
        }
        return new Party(direction, id);
    }

    private long confirmOpening(Party party, LocalDate date) {
        long id = openings.save(openingBo(party, date)).getId();
        openings.confirm(id);
        return id;
    }

    private static FinOpeningBo openingBo(Party party, LocalDate date) {
        var bo = new FinOpeningBo();
        bo.setPartyType(party.type());
        bo.setPartyId(party.id());
        bo.setOpeningDate(date);
        bo.setAmount(money(100));
        return bo;
    }

    private static FinReceiptBo receiptBo(Party party, long ledger, LocalDate date, boolean confirm) {
        var bo = new FinReceiptBo();
        bo.setCustomerId(party.id());
        bo.setBizDate(Date.valueOf(date));
        bo.setAccountId(A.account());
        bo.setAmount(money(20));
        bo.setConfirm(confirm);
        var alloc = new FinReceiptBo.AllocationBo();
        alloc.setArApId(ledger);
        alloc.setAmount(money(20));
        bo.setAllocations(List.of(alloc));
        return bo;
    }

    private static long openingCount(JdbcTemplate jdbc, Party party) {
        return scalar(jdbc, "SELECT COUNT(*) FROM fin_opening WHERE tenant_id=? AND party_type=? AND party_id=?",
            A.tenant(), party.type(), party.id());
    }

    private static void checkHead(JdbcTemplate jdbc, Party party, long id, String state, LocalDate date) {
        var row = jdbc.queryForMap("SELECT * FROM fin_opening WHERE id=?", id);
        eq(A.tenant(), row.get("tenant_id"), "opening tenant");
        eq(party.type(), row.get("party_type"), "opening direction");
        eq(party.id(), ((Number) row.get("party_id")).longValue(), "opening party");
        eq(state, row.get("status"), "opening status");
        eq(date == null ? null : Date.valueOf(date), row.get("opening_date"), "persisted opening_date");
        eq(money(100), row.get("amount"), "opening amount");
        eq("0", row.get("del_flag"), "opening row must remain live");
    }

    private static long openingLedger(JdbcTemplate jdbc, Party party, long id, String state, LocalDate date, int settled) {
        var row = jdbc.queryForMap("""
            SELECT * FROM fin_ar_ap WHERE tenant_id=? AND party_type=? AND party_id=?
              AND source_type='OPENING' AND source_id=? AND status=? AND del_flag='0'
            """, A.tenant(), party.type(), party.id(), id, state);
        eq(Date.valueOf(date), row.get("biz_date"), "opening ledger date, not current time");
        eq(money(100), row.get("amount"), "opening ledger face amount");
        eq(money(settled), row.get("settled_amount"), "opening ledger settled amount");
        return ((Number) row.get("id")).longValue();
    }

    private static void checkSettlement(JdbcTemplate jdbc, long ledger, String status, int settled) {
        var row = jdbc.queryForMap("SELECT status,settled_amount FROM fin_ar_ap WHERE id=? AND tenant_id=?", ledger, A.tenant());
        eq(status, row.get("status"), "ledger state after settlement operation");
        eq(money(settled), row.get("settled_amount"), "ledger settlement after operation");
    }

    private static String paymentStatus(JdbcTemplate jdbc, long id) {
        return jdbc.queryForObject("SELECT status FROM fin_payment WHERE id=? AND tenant_id=?", String.class, id, A.tenant());
    }

    private static String receiptStatus(JdbcTemplate jdbc, long id) {
        return jdbc.queryForObject("SELECT status FROM fin_receipt WHERE id=? AND tenant_id=?", String.class, id, A.tenant());
    }

    private Map<String, List<Map<String, Object>>> snapshot() { return RefundAuditDatabase.snapshot(reader); }

    private void runAs(RefundBusinessMySql.Actor actor, Runnable call) {
        owner.as(actor, () -> { call.run(); return null; });
    }

    private static long scalar(JdbcTemplate jdbc, String query, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(query, Long.class, args));
    }

    private static BigDecimal money(int amount) { return BigDecimal.valueOf(amount).setScale(2); }
    private static void ensure(boolean value, String text) { if (!value) throw new AssertionError(text); }
    private static void eq(Object expected, Object actual, String text) { ensure(Objects.equals(expected, actual), text); }

    private static void reject(int code, String text, Runnable call) {
        try {
            call.run();
        } catch (ServiceException error) {
            eq(code, error.getCode(), "unexpected ERP code (expected " + code + ")");
            ensure(error.getMessage().contains(text), "unexpected ERP rejection (expected " + text + ")");
            return;
        }
        throw new AssertionError("expected ERP rejection: " + text);
    }
}
