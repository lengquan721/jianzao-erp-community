import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Closed-form synthetic history; deliberately NOT a copy of the production replay algorithm. */
final class StockHotspotFixture {
    static final String A = "929101", B = "929102";
    static final long PRODUCT = 88091, WAREHOUSE = 77091;
    static final int MAX_PER_TENANT = 10000, MAX_TOTAL = 20000;
    record Row(long id, String tenant, String type, int qty, int cost, int balance) {}

    static List<Row> rows(String tenant, int count) {
        check(List.of(A, B).contains(tenant), "unknown synthetic tenant");
        check(count >= 2 && count <= MAX_PER_TENANT && count * 2 <= MAX_TOTAL, "fixture bound exceeded");
        int base = tenant.equals(A) ? 2 : 17, tail = tenant.equals(A) ? 6 : 25;
        long offset = tenant.equals(A) ? 1000000L : 2000000L;
        var rows = new ArrayList<Row>(count);
        for (int i = 1; i <= count; i++) rows.add(new Row(offset + i, tenant, i == 1 ? "OPENING" : "PURCHASE",
            i == count ? count - 1 : 1, i == count ? tail : base, i == count ? 2 * (count - 1) : i));
        return rows;
    }

    static BigDecimal quantity(int count) { return BigDecimal.valueOf(2L * (count - 1)).setScale(3); }
    static BigDecimal average(String tenant) { return new BigDecimal(tenant.equals(A) ? "4.0000" : "21.0000"); }
    static void exactIds(List<Long> actual, List<Row> expected) {
        check(actual.equals(expected.stream().map(Row::id).toList()), "replay omitted/duplicated/reordered/foreign IDs");
    }
    static void selfTest() {
        for (int count : List.of(1000, 10000)) for (String tenant : List.of(A, B)) {
            var rows = rows(tenant, count);
            long qty = rows.stream().mapToLong(Row::qty).sum();
            long amount = rows.stream().mapToLong(r -> (long) r.qty * r.cost).sum();
            check(quantity(count).compareTo(BigDecimal.valueOf(qty)) == 0, "closed-form qty failed");
            check(average(tenant).multiply(BigDecimal.valueOf(qty)).compareTo(BigDecimal.valueOf(amount)) == 0,
                "closed-form weighted average failed");
            check(rows.stream().allMatch(r -> r.qty > 0 && r.cost > 0 && r.balance > 0), "illegal history");
            var ids = rows.stream().map(Row::id).toList(); exactIds(ids, rows);
            refused(() -> exactIds(ids.subList(0, ids.size() - 1), rows));
            var duplicate = new ArrayList<>(ids); duplicate.set(duplicate.size() - 1, duplicate.get(0));
            refused(() -> exactIds(duplicate, rows));
            refused(() -> exactIds(rows(tenant.equals(A) ? B : A, count).stream().map(Row::id).toList(), rows));
            // Omitting the last legal receipt changes average from 4/21 to 2/17: truncation cannot accidentally pass.
            check(rows.get(count - 2).cost != average(tenant).intValueExact(), "tail sentinel ineffective");
        }
        refused(() -> rows(A, 10001)); refused(() -> rows(A, 1)); refused(() -> rows("other", 1000));
    }
    private static void refused(Runnable action) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalStateException expected) { rejected = true; }
        check(rejected, "mutation/bound must be refused");
    }
    static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
