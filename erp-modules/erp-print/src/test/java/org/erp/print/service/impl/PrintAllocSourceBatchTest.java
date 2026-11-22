package org.erp.print.service.impl;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.dromara.common.tenant.helper.TenantHelper;
import org.erp.fin.domain.FinReceipt;
import org.erp.fin.domain.FinReceiptAlloc;
import org.erp.fin.mapper.FinArApMapper;
import org.erp.fin.mapper.FinReceiptAllocMapper;
import org.erp.fin.mapper.FinReceiptMapper;
import org.erp.master.mapper.ErpCustomerMapper;
import org.erp.master.mapper.ErpProductMapper;
import org.erp.master.mapper.ErpSupplierMapper;
import org.erp.print.domain.PrintTemplate;
import org.erp.print.mapper.PrintFinanceMapper;
import org.erp.print.service.IPrintTemplateService;
import org.erp.trade.mapper.ErpPurchaseItemMapper;
import org.erp.trade.mapper.ErpPurchaseMapper;
import org.erp.trade.mapper.ErpSaleItemMapper;
import org.erp.trade.mapper.ErpSaleMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;

/** Regression coverage for batched allocation-to-source display, without a database. */
@Tag("dev")
class PrintAllocSourceBatchTest {
    private static final String TENANT = "TENANT_A";
    private static final int DISTINCT_LEDGER_COUNT = 202;

    private final IPrintTemplateService templates = mock(IPrintTemplateService.class);
    private final ErpSaleMapper sales = mock(ErpSaleMapper.class);
    private final FinReceiptMapper receipts = mock(FinReceiptMapper.class);
    private final FinReceiptAllocMapper receiptAllocations = mock(FinReceiptAllocMapper.class);
    private final FinArApMapper ledger = mock(FinArApMapper.class);
    private final ErpPurchaseMapper purchases = mock(ErpPurchaseMapper.class);
    private final PrintFinanceMapper finance = mock(PrintFinanceMapper.class);
    private PrintServiceImpl service;

    @BeforeEach
    void setUp() {
        if (TableInfoHelper.getTableInfo(FinReceiptAlloc.class) == null) {
            var assistant = new MapperBuilderAssistant(new Configuration(), "");
            assistant.setCurrentNamespace(FinReceiptAlloc.class.getName());
            TableInfoHelper.initTableInfo(assistant, FinReceiptAlloc.class);
        }
        service = new PrintServiceImpl(templates, sales, receipts, receiptAllocations, null, null,
            ledger, null, mock(ErpCustomerMapper.class), mock(ErpSupplierMapper.class),
            mock(ErpProductMapper.class), mock(ErpSaleItemMapper.class), purchases,
            mock(ErpPurchaseItemMapper.class), finance, new TemplateCompiler(), new ObjectMapper());

        FinReceipt receipt = new FinReceipt();
        receipt.setId(77L);
        receipt.setNo("SK-77");
        receipt.setAmount(new BigDecimal("999.50"));
        when(receipts.selectById(77L)).thenReturn(receipt);

        List<FinReceiptAlloc> allocations = new ArrayList<>();
        for (long id = 1; id <= DISTINCT_LEDGER_COUNT; id++) {
            allocations.add(allocation(id, new BigDecimal(id + ".25")));
        }
        allocations.add(allocation(1L, new BigDecimal("999.50")));
        when(receiptAllocations.selectList(any())).thenReturn(allocations);

        PrintTemplate template = new PrintTemplate();
        template.setContent("""
            <html><body>
              <p class="line" th:each="i : ${items}">
                <span th:text="${i.no}"></span>
                <span th:if="${i.date == null}">NULL</span>
                <span th:if="${i.date != null}" th:text="${#dates.format(i.date, 'yyyy-MM-dd')}"></span>
                <span th:text="${i.amount}"></span>
              </p>
            </body></html>
            """);
        when(templates.resolve(any(), eq("RECEIPT"), any())).thenReturn(template);

        when(finance.selectLedgerSources(eq(TENANT), anyList())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(1);
            return ids.stream().filter(id -> id != 4L).map(this::ledgerSource).toList();
        });
        when(finance.selectSaleNumbers(eq(TENANT), anyList())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(1);
            return ids.contains(99L) ? List.of(documentNo(99L, "SALE-99")) : List.of();
        });
        when(finance.selectPurchaseNumbers(eq(TENANT), anyList())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(1);
            return ids.contains(99L) ? List.of(documentNo(99L, "PURCHASE-99")) : List.of();
        });
    }

    @Test
    void sourceLookupsAreDeduplicatedBatchedAndKeepAllocationRowsOrdered() {
        try (MockedStatic<TenantHelper> tenant = mockStatic(TenantHelper.class)) {
            tenant.when(TenantHelper::getTenantId).thenReturn(TENANT);

            String html = service.renderReceipt(77L, null);
            List<Element> rows = Jsoup.parse(html).select("p.line");

            assertEquals(203, rows.size());
            assertEquals(List.of("SALE-99", dateText(1L), "1.25"), cells(rows.get(0)));
            assertEquals(List.of("PURCHASE-99", dateText(2L), "2.25"), cells(rows.get(1)));
            assertEquals(List.of("OPENING", dateText(3L), "3.25"), cells(rows.get(2)));
            assertEquals(List.of("", "NULL", "4.25"), cells(rows.get(3)), "Missing ledger leaves no/date null");
            assertEquals(List.of("", dateText(5L), "5.25"), cells(rows.get(4)),
                "Missing sale document leaves only no null");
            assertEquals(List.of("SALE-99", dateText(6L), "6.25"), cells(rows.get(5)));
            assertEquals(List.of("SALE-99", dateText(1L), "999.50"), cells(rows.get(202)),
                "Duplicate allocation retains its own amount and original position");

            ArgumentCaptor<List> batches = ArgumentCaptor.forClass(List.class);
            verify(finance, times(2)).selectLedgerSources(eq(TENANT), batches.capture());
            List<List> ledgerBatches = batches.getAllValues();
            assertEquals(List.of(200, 2), ledgerBatches.stream().map(List::size).toList());
            Set<Object> requestedLedgerIds = new HashSet<>();
            ledgerBatches.forEach(requestedLedgerIds::addAll);
            assertEquals(DISTINCT_LEDGER_COUNT, requestedLedgerIds.size());

            ArgumentCaptor<List> saleIds = ArgumentCaptor.forClass(List.class);
            verify(finance).selectSaleNumbers(eq(TENANT), saleIds.capture());
            assertEquals(Set.of(99L, 500L), new HashSet<>(saleIds.getValue()),
                "Sales source IDs are de-duplicated by type; absent document no remains null");
            ArgumentCaptor<List> purchaseIds = ArgumentCaptor.forClass(List.class);
            verify(finance).selectPurchaseNumbers(eq(TENANT), purchaseIds.capture());
            assertEquals(List.of(99L), purchaseIds.getValue(),
                "Purchase IDs are resolved separately even when the numeric ID overlaps sales");
            verifyNoInteractions(ledger, sales, purchases);
        }
    }

    private FinReceiptAlloc allocation(Long ledgerId, BigDecimal amount) {
        FinReceiptAlloc allocation = new FinReceiptAlloc();
        allocation.setReceiptId(77L);
        allocation.setArApId(ledgerId);
        allocation.setAmount(amount);
        return allocation;
    }

    private PrintLedgerSource ledgerSource(Long ledgerId) {
        PrintLedgerSource source = new PrintLedgerSource();
        source.setId(ledgerId);
        source.setBizDate(new Date(1_600_000_000_000L + 86_400_000L * ledgerId));
        if (ledgerId == 1L) {
            source.setSourceType("SALE");
            source.setSourceId(99L);
        } else if (ledgerId == 2L) {
            source.setSourceType("PURCHASE");
            source.setSourceId(99L);
        } else if (ledgerId == 3L) {
            source.setSourceType("OPENING");
            source.setSourceId(ledgerId);
        } else if (ledgerId == 5L) {
            source.setSourceType("SALE");
            source.setSourceId(500L);
        } else {
            source.setSourceType("SALE");
            source.setSourceId(99L);
        }
        return source;
    }

    private PrintDocumentNo documentNo(Long id, String no) {
        PrintDocumentNo result = new PrintDocumentNo();
        result.setId(id);
        result.setDocumentNo(no);
        return result;
    }

    private String dateText(long ledgerId) {
        return new SimpleDateFormat("yyyy-MM-dd").format(new Date(1_600_000_000_000L + 86_400_000L * ledgerId));
    }

    private List<String> cells(Element row) {
        return row.select("span").stream().map(Element::text).toList();
    }
}
