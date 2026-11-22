package org.erp.print.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.tenant.helper.TenantHelper;
import org.erp.fin.domain.FinPayment;
import org.erp.fin.domain.FinPaymentAlloc;
import org.erp.fin.domain.FinReceipt;
import org.erp.fin.domain.FinReceiptAlloc;
import org.erp.fin.mapper.FinArApMapper;
import org.erp.fin.mapper.FinOpeningMapper;
import org.erp.fin.mapper.FinPaymentAllocMapper;
import org.erp.fin.mapper.FinPaymentMapper;
import org.erp.fin.mapper.FinReceiptAllocMapper;
import org.erp.fin.mapper.FinReceiptMapper;
import org.erp.master.domain.ErpCustomer;
import org.erp.master.domain.ErpProduct;
import org.erp.master.domain.ErpSupplier;
import org.erp.master.mapper.ErpCustomerMapper;
import org.erp.master.mapper.ErpProductMapper;
import org.erp.master.mapper.ErpSupplierMapper;
import org.erp.print.domain.PrintTemplate;
import org.erp.print.domain.design.DesignSchema;
import org.erp.print.mapper.PrintFinanceMapper;
import org.erp.print.service.IPrintService;
import org.erp.print.service.IPrintTemplateService;
import org.erp.trade.domain.ErpPurchase;
import org.erp.trade.domain.ErpPurchaseItem;
import org.erp.trade.domain.ErpSale;
import org.erp.trade.domain.ErpSaleItem;
import org.erp.trade.mapper.ErpPurchaseItemMapper;
import org.erp.trade.mapper.ErpPurchaseMapper;
import org.erp.trade.mapper.ErpSaleItemMapper;
import org.erp.trade.mapper.ErpSaleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 打印渲染服务实现
 *
 * 场景（DOC/12 §9、DOC/14）：送货单 / 对账单 / 收款单 / 付款单。
 * 模板解析：指定 templateId → 客户专属 → 租户默认 → 内置兜底。
 * 对账按业务日期重述当前有效单据：期初 + 本期建账 + 销售 − 退货 − 收款 + 退款 = 期末。
 */
@RequiredArgsConstructor
@Service
public class PrintServiceImpl implements IPrintService {

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_INPUT_FMT = DateTimeFormatter.ofPattern("uuuu-MM-dd")
        .withResolverStyle(ResolverStyle.STRICT);
    private static final int MAX_STATEMENT_ROWS = 5000;
    private static final int MAX_RANGE_DAYS = 366;
    private static final int MAX_COPIES = 10;
    private static final int MAX_BATCH_IDS = 200;
    private static final String PROFIT_PERMISSION = "erp:print:profit";

    private final IPrintTemplateService templateService;
    private final ErpSaleMapper saleMapper;
    private final FinReceiptMapper receiptMapper;
    private final FinReceiptAllocMapper receiptAllocMapper;
    private final FinPaymentMapper paymentMapper;
    private final FinPaymentAllocMapper paymentAllocMapper;
    private final FinArApMapper arApMapper;
    private final FinOpeningMapper openingMapper;
    private final ErpCustomerMapper customerMapper;
    private final ErpSupplierMapper supplierMapper;
    private final ErpProductMapper productMapper;
    private final ErpSaleItemMapper saleItemMapper;
    private final ErpPurchaseMapper purchaseMapper;
    private final ErpPurchaseItemMapper purchaseItemMapper;
    private final PrintFinanceMapper financeMapper;
    private final TemplateSanitizer sanitizer = new TemplateSanitizer();
    private final TemplateCompiler compiler;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public String renderSaleDelivery(Long saleId, Long templateId, boolean showProfit, int copies) {
        checkProfitPermission(showProfit);
        // Ordinary printing never fetches real cost/profit snapshots, even for a custom template.
        ErpSale sale = saleMapper.selectOne(new LambdaQueryWrapper<ErpSale>()
            .select(ErpSale::getId, ErpSale::getCustomerId, ErpSale::getNo, ErpSale::getBizDate,
                ErpSale::getStatus, ErpSale::getRemark, ErpSale::getGoodsAmount,
                ErpSale::getFreightAmount, ErpSale::getTotalAmount)
            .eq(ErpSale::getId, saleId));
        if (sale == null) {
            throw new ServiceException("销售单不存在：" + saleId);
        }
        PrintContext ctx = new PrintContext();
        ctx.setCopies(normalizeCopies(copies));
        fillPartner(ctx, "AR", sale.getCustomerId());
        ctx.getDoc().setNo(sale.getNo());
        ctx.getDoc().setDate(sale.getBizDate());
        ctx.getDoc().setType("SALE_DELIVERY");
        ctx.getDoc().setRemark(sale.getRemark());
        ctx.getDoc().setStatus(sale.getStatus());

        BigDecimal qtyTotal = BigDecimal.ZERO;
        List<PrintContext.Item> items = new ArrayList<>();
        for (ErpSaleVoRow row : loadSaleItems(saleId)) {
            PrintContext.Item item = new PrintContext.Item();
            item.setIndex(items.size() + 1);
            item.setName(row.name);
            item.setSpec(row.spec);
            item.setUnit(row.unit);
            item.setQty(row.qty);
            item.setPrice(row.price);
            item.setAmount(row.amount);
            item.setTaxType(row.taxType);
            item.setRemark(row.remark);
            items.add(item);
            qtyTotal = qtyTotal.add(row.qty == null ? BigDecimal.ZERO : row.qty);
        }
        ctx.setItems(items);
        ctx.getTotals().setQtyTotal(qtyTotal);
        ctx.getTotals().setAmountTotal(sale.getTotalAmount());
        ctx.getTotals().setAmountUpper(PrintFormatUtils.upper(sale.getTotalAmount()));
        fillTotalsExtras(ctx, "AR", sale.getCustomerId(), sale.getGoodsAmount(), sale.getFreightAmount());
        fillSourceUnsettled(ctx, "AR", sale.getCustomerId(), "SALE", saleId, sale.getStatus());
        if (showProfit) {
            ctx.setProfit(financeMapper.selectSaleProfit(currentTenantId(), saleId));
        }
        ctx.setShowProfit(showProfit);
        return render(ctx, templateId, "SALE_DELIVERY", sale.getCustomerId());
    }

    @Override
    @Transactional(readOnly = true)
    public String renderPurchaseIn(Long purchaseId, Long templateId) {
        ErpPurchase purchase = purchaseMapper.selectById(purchaseId);
        if (purchase == null) {
            throw new ServiceException("采购入库单不存在：" + purchaseId);
        }
        PrintContext ctx = new PrintContext();
        fillPartner(ctx, "AP", purchase.getSupplierId());
        ctx.getDoc().setNo(purchase.getNo());
        ctx.getDoc().setDate(purchase.getBizDate());
        ctx.getDoc().setType("PURCHASE_IN");
        ctx.getDoc().setRemark(purchase.getRemark());
        ctx.getDoc().setStatus(purchase.getStatus());

        BigDecimal qtyTotal = BigDecimal.ZERO;
        List<PrintContext.Item> items = new ArrayList<>();
        for (ErpPurchaseItem row : purchaseItemMapper.selectList(
                new LambdaQueryWrapper<ErpPurchaseItem>()
                    .eq(ErpPurchaseItem::getOrderId, purchaseId)
                    .orderByAsc(ErpPurchaseItem::getSeq))) {
            PrintContext.Item item = new PrintContext.Item();
            item.setIndex(items.size() + 1);
            ErpProduct product = loadPrintProduct(row.getProductId());
            item.setName(product == null ? null : product.getName());
            item.setSpec(product == null ? null : product.getSpec());
            item.setUnit(product == null ? null : product.getUnit());
            item.setQty(row.getQty());
            item.setPrice(row.getPrice());
            item.setAmount(row.getAmount());
            item.setTaxType(row.getTaxType());
            item.setRemark(row.getRemark());
            items.add(item);
            qtyTotal = qtyTotal.add(row.getQty() == null ? BigDecimal.ZERO : row.getQty());
        }
        ctx.setItems(items);
        ctx.getTotals().setQtyTotal(qtyTotal);
        BigDecimal total = purchase.getTotalAmount() == null ? BigDecimal.ZERO : purchase.getTotalAmount();
        ctx.getTotals().setAmountTotal(total);
        ctx.getTotals().setAmountUpper(PrintFormatUtils.upper(total));
        fillTotalsExtras(ctx, "AP", purchase.getSupplierId(), purchase.getGoodsAmount(), purchase.getFreightAmount());
        fillSourceUnsettled(ctx, "AP", purchase.getSupplierId(), "PURCHASE", purchaseId, purchase.getStatus());
        return render(ctx, templateId, "PURCHASE_IN", null);
    }

    /** Legacy month-only API: callers still cross the Spring proxy and retain a real RR transaction. */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public String renderStatement(Long customerId, String month, Long templateId) {
        return renderStatementInternal(customerId, month, null, null, templateId);
    }

    /** Month and range requests both enter this public method through the Spring transaction proxy. */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public String renderStatement(Long customerId, String month, String startDate, String endDate, Long templateId) {
        return renderStatementInternal(customerId, month, startDate, endDate, templateId);
    }

    private String renderStatementInternal(Long customerId, String month, String startDate, String endDate,
                                           Long templateId) {
        if (customerId == null || customerId <= 0) {
            throw new ServiceException("请选择对账客户");
        }
        boolean rangeMode = (startDate != null && !startDate.isBlank())
            || (endDate != null && !endDate.isBlank());
        LocalDate from;
        LocalDate to;
        String periodLabel;
        if (rangeMode) {
            if (startDate == null || startDate.isBlank() || endDate == null || endDate.isBlank()) {
                throw new ServiceException("自定义对账区间需同时选择开始日期和结束日期");
            }
            LocalDate end;
            try {
                from = LocalDate.parse(startDate, DATE_INPUT_FMT);
                end = LocalDate.parse(endDate, DATE_INPUT_FMT);
                to = end.plusDays(1);
            } catch (DateTimeException e) {
                throw new ServiceException("对账日期格式必须是 yyyy-MM-dd");
            }
            if (from.isAfter(end)) {
                throw new ServiceException("对账开始日期不能晚于结束日期");
            }
            long inclusiveDays = java.time.temporal.ChronoUnit.DAYS.between(from, end) + 1;
            if (inclusiveDays > MAX_RANGE_DAYS) {
                throw new ServiceException("对账区间不能超过 " + MAX_RANGE_DAYS + " 天");
            }
            periodLabel = from.format(DATE_FMT) + " 至 " + end.format(DATE_FMT);
            month = null;
        } else {
            if (month == null || month.isBlank()) {
                month = LocalDate.now().format(MONTH_FMT);
            }
            try {
                if (!month.matches("[0-9]{4}-[0-9]{2}")) {
                    throw new DateTimeParseException("invalid month", month, 0);
                }
                from = YearMonth.parse(month).atDay(1);
            } catch (DateTimeParseException e) {
                throw new ServiceException("账期格式必须是 yyyy-MM");
            }
            to = from.plusMonths(1);
            periodLabel = month + " 月度";
        }
        if (from.getYear() < 1000 || from.getYear() > 9999 || to.getYear() > 9999) {
            throw new ServiceException("对账日期超出支持范围");
        }

        // Validate period and tenant before any financial query. The mapper returns only narrow projections;
        // statement values below are calculated from document headers, not by summing item rows.
        String tenantId = currentTenantId();
        List<PrintStatementLine> rows = financeMapper.selectStatementLines(tenantId, customerId, from, to);
        if (rows == null) {
            rows = List.of();
        }
        if (rows.size() > MAX_STATEMENT_ROWS) {
            throw new ServiceException("对账单明细超过 " + MAX_STATEMENT_ROWS + " 行，未生成对账单，请联系管理员");
        }
        List<PrintContext.StatementItem> statementItems = new ArrayList<>(rows.size());
        for (PrintStatementLine row : rows) {
            PrintContext.StatementItem item = statementItem(row.getEventDate(), row.getDocumentNo(), row.getContent(),
                row.getProductName(), nvl(row.getSignedAmount()), row.getQty());
            item.setPrice(row.getPrice());
            if (row.getQty() != null) {
                int qtyScale = row.getQtyScale() == null ? 3 : Math.min(3, Math.max(0, row.getQtyScale()));
                item.setQtyText(row.getQty().setScale(qtyScale, RoundingMode.HALF_UP).toPlainString());
            }
            statementItems.add(item);
        }

        Map<String, BigDecimal> movements = new HashMap<>();
        BigDecimal opening = BigDecimal.ZERO;
        List<PrintLedgerMovement> movementRows = financeMapper.selectStatementMovements(tenantId, customerId, from, to);
        if (movementRows != null) {
            for (PrintLedgerMovement row : movementRows) {
                opening = opening.add(nvl(row.getPriorAmount()));
                movements.put(row.getSourceType(), nvl(row.getPeriodAmount()));
            }
        }
        BigDecimal openingEntry = movements.getOrDefault("OPENING", BigDecimal.ZERO);
        BigDecimal sales = movements.getOrDefault("SALE", BigDecimal.ZERO);
        BigDecimal returnMovement = movements.getOrDefault("SALE_RETURN", BigDecimal.ZERO); // source is signed negative
        BigDecimal returns = returnMovement.negate();
        BigDecimal receipts = movements.getOrDefault("RECEIPT", BigDecimal.ZERO).negate();
        BigDecimal refunds = movements.getOrDefault("REFUND", BigDecimal.ZERO);
        BigDecimal netSales = sales.add(returnMovement);
        BigDecimal netReceipts = receipts.subtract(refunds);
        BigDecimal closing = opening.add(openingEntry).add(sales).subtract(returns).subtract(receipts).add(refunds);

        PrintContext ctx = new PrintContext();
        fillPartner(ctx, "AR", customerId);
        ctx.getDoc().setType("STATEMENT");
        PrintContext.Statement statement = new PrintContext.Statement();
        statement.setMonth(month);
        statement.setPeriodStart(from.format(DATE_FMT));
        statement.setPeriodEnd(to.minusDays(1).format(DATE_FMT));
        statement.setPeriodLabel(periodLabel);
        statement.setOpening(scale2(opening));
        statement.setPreviousReceivable(scale2(opening));
        statement.setOpeningEntryAmount(scale2(openingEntry));
        statement.setSalesAmount(scale2(sales));
        statement.setReturnAmount(scale2(returns));
        statement.setNetSalesAmount(scale2(netSales));
        statement.setReceiptAmount(scale2(receipts));
        statement.setRefundAmount(scale2(refunds));
        statement.setNetReceiptAmount(scale2(netReceipts));
        statement.setMonthlyUnpaid(scale2(netSales.subtract(netReceipts)));
        statement.setCumulativeSales(scale2(
            movements.getOrDefault("SALE", BigDecimal.ZERO).add(
                movements.getOrDefault("SALE_RETURN", BigDecimal.ZERO))
                .add(priorMovement(movementRows, "SALE"))
                .add(priorMovement(movementRows, "SALE_RETURN"))));
        statement.setCumulativeReceipts(scale2(cumulativeNetReceipts(movementRows)));
        statement.setCumulativeUnpaid(scale2(closing));
        statement.setTotalReceivable(scale2(closing));
        statement.setClosing(scale2(closing));
        statement.setItems(statementItems);
        ctx.setStatement(statement);
        // This is intentionally live, current unsettled debt; it is not used to reconstruct period-end closing.
        fillTotalsExtras(ctx, "AR", customerId, null, null);
        return render(ctx, templateId, rangeMode ? "STATEMENT_RANGE" : "STATEMENT", customerId);
    }

    private BigDecimal priorMovement(List<PrintLedgerMovement> rows, String sourceType) {
        if (rows == null) {
            return BigDecimal.ZERO;
        }
        return rows.stream().filter(row -> sourceType.equals(row.getSourceType()))
            .map(PrintLedgerMovement::getPriorAmount).map(this::nvl).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal cumulativeNetReceipts(List<PrintLedgerMovement> rows) {
        if (rows == null) {
            return BigDecimal.ZERO;
        }
        return rows.stream().filter(row -> "RECEIPT".equals(row.getSourceType()) || "REFUND".equals(row.getSourceType()))
            .map(row -> nvl(row.getPriorAmount()).add(nvl(row.getPeriodAmount())))
            .reduce(BigDecimal.ZERO, BigDecimal::add).negate();
    }

    @Override
    @Transactional(readOnly = true)
    public String renderReceipt(Long receiptId, Long templateId) {
        FinReceipt receipt = receiptMapper.selectById(receiptId);
        if (receipt == null) {
            throw new ServiceException("收款单不存在：" + receiptId);
        }
        return renderReceiptPayment(receipt.getNo(), "RECEIPT", receipt.getCustomerId(),
            receipt.getAmount(), receipt.getBizDate(), receipt.getAccount(), receipt.getRemark(),
            loadAllocSourceNos(receiptAllocMapper.selectList(new LambdaQueryWrapper<FinReceiptAlloc>()
                .eq(FinReceiptAlloc::getReceiptId, receiptId))),
            templateId, receipt.getCustomerId());
    }

    @Override
    @Transactional(readOnly = true)
    public String renderPayment(Long paymentId, Long templateId) {
        FinPayment payment = paymentMapper.selectById(paymentId);
        if (payment == null) {
            throw new ServiceException("付款单不存在：" + paymentId);
        }
        return renderReceiptPayment(payment.getNo(), "PAYMENT", payment.getSupplierId(),
            payment.getAmount(), payment.getBizDate(), payment.getAccount(), payment.getRemark(),
            loadAllocSourceNos(paymentAllocMapper.selectList(new LambdaQueryWrapper<FinPaymentAlloc>()
                .eq(FinPaymentAlloc::getPaymentId, paymentId))),
            templateId, null);
    }

    @Override
    public String preview(String scene, String content, Long customerId, boolean showProfit) {
        return preview(scene, content, customerId, showProfit, null);
    }

    @Override
    public String preview(String scene, String content, Long customerId, boolean showProfit, String paper) {
        checkProfitPermission(showProfit);
        validateScene(scene);
        String cleaned = sanitizer.clean(content, scene);
        PrintContext ctx = sampleContext(scene, showProfit);
        String rendered = ThymeleafRenderer.render(cleaned, toModel(ctx));
        return PageSetup.injectIntoHead(rendered, PageSetup.pageStyle(paper, cleaned));
    }

    @Override
    @Transactional(readOnly = true)
    public String previewDesign(String scene, String schemaJson, boolean showProfit) {
        checkProfitPermission(showProfit);
        validateScene(scene);
        if (schemaJson == null || schemaJson.length() > TemplateSanitizer.MAX_TEMPLATE_CHARS) {
            throw new ServiceException("设计器请求体为空或超过允许大小");
        }
        DesignSchema schema;
        try {
            schema = objectMapper.readValue(schemaJson, DesignSchema.class);
        } catch (JsonProcessingException e) {
            throw new ServiceException("设计器内容格式错误：" + e.getOriginalMessage());
        }
        String html;
        try {
            html = sanitizer.clean(compiler.compile(schema, scene), scene);
        } catch (IllegalArgumentException e) {
            throw new ServiceException("设计器内容无法编译：" + e.getMessage());
        }
        PrintContext ctx = sampleContext(scene, showProfit);
        String rendered = ThymeleafRenderer.render(html, toModel(ctx));
        return PageSetup.injectIntoHead(rendered, PageSetup.pageStyle(schema.getPaper(), html));
    }

    private String renderReceiptPayment(String no, String type, Long partyId, BigDecimal amount,
                                        Date bizDate, String account, String remark,
                                        List<PrintContext.Item> items, Long templateId, Long customerId) {
        PrintContext ctx = new PrintContext();
        fillPartner(ctx, "PAYMENT".equals(type) ? "AP" : "AR", partyId);
        ctx.getDoc().setNo(no);
        ctx.getDoc().setType(type);
        ctx.getDoc().setDate(bizDate);
        ctx.getDoc().setAccount(account);
        ctx.getDoc().setRemark(remark);
        ctx.setItems(items);
        ctx.getTotals().setAmountTotal(amount);
        ctx.getTotals().setAmountUpper(PrintFormatUtils.upper(amount));
        // 收付款单不推算来源单未结，只补累计欠款。
        fillTotalsExtras(ctx, "PAYMENT".equals(type) ? "AP" : "AR", partyId, null, null);
        return render(ctx, templateId, type, customerId);
    }

    /**
     * 补齐合计扩展字段：小计（货品金额）、运杂费、累计欠款；本单未结单独按来源台账读取。
     *
     * <p>累计欠款取自应收应付台账 {@code fin_ar_ap} 的未核销余额
     * （Σ(amount − settled_amount)，status ≠ VOID），是对该往来单位的权威欠款口径。
     *
     * @param partyType     AR=应收 AP=应付，null 表示无往来单位（不下发欠款）
     * @param goodsAmount   货品金额，null 表示由明细行金额累加
     * @param freightAmount 运杂费，null 视为 0
     */
    private void fillTotalsExtras(PrintContext ctx, String partyType, Long partyId,
                                  BigDecimal goodsAmount, BigDecimal freightAmount) {
        PrintContext.Totals totals = ctx.getTotals();
        if (goodsAmount != null) {
            totals.setGoodsAmount(scale2(goodsAmount));
        }
        if (freightAmount != null) {
            totals.setFreightAmount(scale2(freightAmount));
        }
        if (partyType == null || partyId == null) {
            return;
        }
        BigDecimal debt = arApMapper.sumUnsettledByParty(partyType, partyId);
        totals.setDebtTotal(scale2(debt == null ? BigDecimal.ZERO : debt));
    }

    /** Only confirmed documents have an effective SALE/PURCHASE ledger balance. */
    private void fillSourceUnsettled(PrintContext ctx, String partyType, Long partyId,
                                     String sourceType, Long sourceId, String status) {
        if (!"CONFIRMED".equals(status)) {
            return; // Draft/withdrawn/void documents must not acquire a fabricated balance.
        }
        BigDecimal unsettled = financeMapper.sumUnsettledBySource(currentTenantId(), partyType, partyId,
            sourceType, sourceId);
        if (unsettled == null) {
            throw new ServiceException("已确认单据缺少有效来源台账：" + sourceType + "/" + sourceId, 40901);
        }
        ctx.getTotals().setUnsettledAmount(scale2(unsettled));
    }

    private void checkProfitPermission(boolean showProfit) {
        if (showProfit) {
            StpUtil.checkPermission(PROFIT_PERMISSION);
        }
    }

    private void validateScene(String scene) {
        if (scene == null || !List.of("SALE_DELIVERY", "STATEMENT", "STATEMENT_RANGE", "PURCHASE_IN",
            "RECEIPT", "PAYMENT").contains(scene)) {
            throw new ServiceException("不支持的场景：" + scene);
        }
    }

    private String currentTenantId() {
        String tenantId = TenantHelper.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new ServiceException("缺少租户上下文，拒绝生成打印数据", 40901);
        }
        return tenantId;
    }

    private BigDecimal scale2(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private String render(PrintContext ctx, Long templateId, String scene, Long customerId) {
        PrintTemplate template = templateService.resolve(templateId, scene, customerId);
        if (template == null) {
            template = builtin(scene);
        } else if (templateId != null && !scene.equals(template.getScene())) {
            throw new ServiceException("打印模板场景不匹配：当前场景为 " + scene);
        }
        String cleaned = sanitizer.clean(template.getContent(), scene);
        ctx.setPrintDate(LocalDate.now().format(DATE_FMT));
        String rendered = ThymeleafRenderer.render(cleaned, toModel(ctx));
        rendered = PageSetup.injectIntoHead(rendered, PageSetup.pageStyle(template.getPaper(), cleaned));
        return ThymeleafRenderer.renderWithCopies(rendered, ctx.getCopies() == null ? 1 : ctx.getCopies());
    }

    /**
     * 份数钳制在 1..MAX_COPIES，防止放大渲染资源
     */
    private int normalizeCopies(Integer copies) {
        return copies == null ? 1 : Math.max(1, Math.min(copies, MAX_COPIES));
    }

    private PrintTemplate builtin(String scene) {
        return switch (scene) {
            case "SALE_DELIVERY" -> BuiltinTemplates.saleDelivery();
            case "STATEMENT" -> BuiltinTemplates.statement();
            case "PURCHASE_IN" -> BuiltinTemplates.purchaseIn();
            case "RECEIPT", "PAYMENT" -> BuiltinTemplates.receiptPayment();
            default -> throw new ServiceException("该场景暂不支持打印：" + scene);
        };
    }

    private Map<String, Object> toModel(PrintContext ctx) {
        Map<String, Object> model = new HashMap<>();
        model.put("tenant", ctx.getTenant());
        model.put("doc", ctx.getDoc());
        model.put("partner", ctx.getPartner());
        model.put("items", ctx.getItems());
        model.put("totals", ctx.getTotals());
        model.put("statement", ctx.getStatement());
        model.put("profit", ctx.getProfit());
        model.put("signatures", ctx.getSignatures());
        model.put("printDate", ctx.getPrintDate());
        model.put("copies", ctx.getCopies());
        model.put("showProfit", ctx.getShowProfit());
        return model;
    }

    private PrintContext sampleContext(String scene, boolean showProfit) {
        PrintContext ctx = new PrintContext();
        ctx.getTenant().setName("示例加工厂");
        ctx.getPartner().setName("示例机械有限公司");
        ctx.getPartner().setContact("张三");
        ctx.getPartner().setPhone("13800000000");
        ctx.getPartner().setAddress("示例市示例区示例路 1 号");
        ctx.getDoc().setNo("XS202610310001");
        ctx.getDoc().setDate(new Date());
        ctx.getDoc().setAccount("微信");
        ctx.getDoc().setRemark("示例备注");
        ctx.getDoc().setType("SALE_DELIVERY".equals(scene) ? "SALE_DELIVERY"
            : "RECEIPT".equals(scene) ? "RECEIPT" : "PAYMENT".equals(scene) ? "PAYMENT" : scene);
        PrintContext.Item item = new PrintContext.Item();
        item.setIndex(1);
        item.setName("料管");
        item.setSpec("Φ25×2m");
        item.setUnit("支");
        item.setQty(BigDecimal.TEN);
        item.setPrice(new BigDecimal("50.00"));
        item.setAmount(new BigDecimal("500.00"));
        item.setNo("XS202610050001");
        item.setDate(new Date());
        ctx.setItems(List.of(item));
        ctx.getTotals().setAmountTotal(new BigDecimal("500.00"));
        ctx.getTotals().setAmountUpper(PrintFormatUtils.upper(new BigDecimal("500.00")));
        ctx.getTotals().setGoodsAmount(new BigDecimal("500.00"));
        ctx.getTotals().setFreightAmount(BigDecimal.ZERO);
        ctx.getTotals().setDebtTotal(new BigDecimal("2200.00"));
        ctx.getTotals().setUnsettledAmount(new BigDecimal("500.00"));
        // 预览示例数据无条件填充全部变量：模板场景与内容可能不一致（如送货单场景套用对账单模板），
        // 若按场景只给对应变量，模板引用未填充对象会抛 OGNL null 异常
        PrintContext.Statement statement = new PrintContext.Statement();
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        statement.setMonth(today.format(MONTH_FMT));
        statement.setPeriodStart(monthStart.format(DATE_FMT));
        statement.setPeriodEnd(today.withDayOfMonth(today.lengthOfMonth()).format(DATE_FMT));
        statement.setPeriodLabel("STATEMENT_RANGE".equals(scene)
            ? monthStart.format(DATE_FMT) + " 至 " + today.format(DATE_FMT)
            : today.format(MONTH_FMT) + " 月度");
        statement.setOpening(new BigDecimal("2000.00"));
        statement.setPreviousReceivable(new BigDecimal("2000.00"));
        statement.setOpeningEntryAmount(new BigDecimal("0.00"));
        statement.setSalesAmount(new BigDecimal("500.00"));
        statement.setReturnAmount(new BigDecimal("0.00"));
        statement.setNetSalesAmount(new BigDecimal("500.00"));
        statement.setReceiptAmount(new BigDecimal("300.00"));
        statement.setRefundAmount(new BigDecimal("0.00"));
        statement.setNetReceiptAmount(new BigDecimal("300.00"));
        statement.setMonthlyUnpaid(new BigDecimal("200.00"));
        statement.setCumulativeSales(new BigDecimal("2500.00"));
        statement.setCumulativeReceipts(new BigDecimal("300.00"));
        statement.setCumulativeUnpaid(new BigDecimal("2200.00"));
        statement.setTotalReceivable(new BigDecimal("2200.00"));
        statement.setClosing(new BigDecimal("2200.00"));
        PrintContext.StatementItem si = new PrintContext.StatementItem();
        si.setDate(new Date());
        si.setNo("XS202610050001");
        si.setContent("销售");
        si.setProductName("料管");
        si.setQty(BigDecimal.TEN);
        si.setQtyText("10");
        si.setPrice(new BigDecimal("50.00"));
        si.setAmount(new BigDecimal("500.00"));
        statement.setItems(List.of(si));
        ctx.setStatement(statement);
        // 成本利润示例同样无条件填充，避免模板直接引用 ${profit.*} 时预览报 null
        PrintContext.Profit profit = new PrintContext.Profit();
        profit.setCostAmount(new BigDecimal("158.80"));
        profit.setGrossProfit(new BigDecimal("341.20"));
        profit.setGrossMargin(new BigDecimal("68.24"));
        ctx.setProfit(profit);
        ctx.setShowProfit(showProfit);
        ctx.setPrintDate(LocalDate.now().format(DATE_FMT));
        return ctx;
    }

    private void fillPartner(PrintContext ctx, String partyType, Long partyId) {
        if (partyId == null) {
            return;
        }
        if ("AR".equals(partyType)) {
            ErpCustomer c = customerMapper.selectById(partyId);
            if (c != null) {
                ctx.getPartner().setName(c.getName());
                ctx.getPartner().setContact(c.getContact());
                ctx.getPartner().setPhone(c.getPhone());
                ctx.getPartner().setAddress(c.getAddress());
            }
        } else {
            ErpSupplier s = supplierMapper.selectById(partyId);
            if (s != null) {
                ctx.getPartner().setName(s.getName());
                ctx.getPartner().setContact(s.getContact());
                ctx.getPartner().setPhone(s.getPhone());
                ctx.getPartner().setAddress(s.getAddress());
            }
        }
    }

    private List<PrintContext.Item> loadAllocSourceNos(List<?> allocs) {
        List<AllocationSource> allocations = new ArrayList<>(allocs.size());
        Set<Long> ledgerIds = new LinkedHashSet<>();
        for (Object row : allocs) {
            AllocationSource allocation = null;
            if (row instanceof FinReceiptAlloc receiptAlloc) {
                allocation = new AllocationSource(receiptAlloc.getArApId(), receiptAlloc.getAmount());
            } else if (row instanceof FinPaymentAlloc paymentAlloc) {
                allocation = new AllocationSource(paymentAlloc.getArApId(), paymentAlloc.getAmount());
            }
            if (allocation != null) {
                allocations.add(allocation);
                if (allocation.arApId != null) {
                    ledgerIds.add(allocation.arApId);
                }
            }
        }
        if (allocations.isEmpty()) {
            return List.of();
        }

        Map<Long, PrintLedgerSource> ledgersById = new HashMap<>();
        String tenantId = ledgerIds.isEmpty() ? null : currentTenantId();
        if (!ledgerIds.isEmpty()) {
            for (List<Long> batch : batches(ledgerIds)) {
                List<PrintLedgerSource> rows = financeMapper.selectLedgerSources(tenantId, batch);
                if (rows != null) {
                    for (PrintLedgerSource ledger : rows) {
                        ledgersById.put(ledger.getId(), ledger);
                    }
                }
            }
        }

        Set<Long> saleIds = new LinkedHashSet<>();
        Set<Long> purchaseIds = new LinkedHashSet<>();
        for (PrintLedgerSource ledger : ledgersById.values()) {
            if (ledger.getSourceId() == null) {
                continue;
            }
            if ("SALE".equals(ledger.getSourceType())) {
                saleIds.add(ledger.getSourceId());
            } else if ("PURCHASE".equals(ledger.getSourceType())) {
                purchaseIds.add(ledger.getSourceId());
            }
        }
        Map<Long, String> saleNos = loadDocumentNos(saleIds,
            batch -> financeMapper.selectSaleNumbers(tenantId, batch));
        Map<Long, String> purchaseNos = loadDocumentNos(purchaseIds,
            batch -> financeMapper.selectPurchaseNumbers(tenantId, batch));

        List<PrintContext.Item> items = new ArrayList<>(allocations.size());
        for (AllocationSource allocation : allocations) {
            PrintLedgerSource ledger = allocation.arApId == null ? null : ledgersById.get(allocation.arApId);
            PrintContext.Item item = new PrintContext.Item();
            item.setNo(ledger == null ? null : resolveSourceNo(ledger, saleNos, purchaseNos));
            item.setDate(ledger == null ? null : ledger.getBizDate());
            item.setAmount(allocation.amount);
            items.add(item);
        }
        return items;
    }

    private Map<Long, String> loadDocumentNos(Set<Long> sourceIds,
                                               Function<List<Long>, List<PrintDocumentNo>> loader) {
        Map<Long, String> numbersById = new HashMap<>();
        for (List<Long> batch : batches(sourceIds)) {
            List<PrintDocumentNo> rows = loader.apply(batch);
            if (rows != null) {
                for (PrintDocumentNo row : rows) {
                    numbersById.put(row.getId(), row.getDocumentNo());
                }
            }
        }
        return numbersById;
    }

    private List<List<Long>> batches(Set<Long> ids) {
        List<Long> values = new ArrayList<>(ids);
        List<List<Long>> batches = new ArrayList<>((values.size() + MAX_BATCH_IDS - 1) / MAX_BATCH_IDS);
        for (int start = 0; start < values.size(); start += MAX_BATCH_IDS) {
            batches.add(values.subList(start, Math.min(start + MAX_BATCH_IDS, values.size())));
        }
        return batches;
    }

    private String resolveSourceNo(PrintLedgerSource ledger, Map<Long, String> saleNos,
                                   Map<Long, String> purchaseNos) {
        return switch (ledger.getSourceType() == null ? "" : ledger.getSourceType()) {
            case "SALE" -> saleNos.get(ledger.getSourceId());
            case "PURCHASE" -> purchaseNos.get(ledger.getSourceId());
            default -> ledger.getSourceType();
        };
    }

    private static class AllocationSource {
        private final Long arApId;
        private final BigDecimal amount;

        private AllocationSource(Long arApId, BigDecimal amount) {
            this.arApId = arApId;
            this.amount = amount;
        }
    }

    private PrintContext.StatementItem statementItem(Date date, String no, String content,
                                                     String productName, BigDecimal amount, BigDecimal qty) {
        PrintContext.StatementItem si = new PrintContext.StatementItem();
        si.setDate(date);
        si.setNo(no);
        si.setContent(content);
        si.setProductName(productName);
        si.setQty(qty);
        // 行仅展示发生额；不能用当前核销金额重建历史逐行未结。
        si.setAmount(scale2(amount));
        return si;
    }

    private BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private ErpProduct loadPrintProduct(Long productId) {
        return productId == null ? null : productMapper.selectOne(new LambdaQueryWrapper<ErpProduct>()
            .select(ErpProduct::getName, ErpProduct::getSpec, ErpProduct::getUnit, ErpProduct::getQtyScale)
            .eq(ErpProduct::getId, productId));
    }

    private List<ErpSaleVoRow> loadSaleItems(Long saleId) {
        List<ErpSaleVoRow> rows = new ArrayList<>();
        for (ErpSaleItem item : saleItemMapper.selectList(
                new LambdaQueryWrapper<ErpSaleItem>()
                    .select(ErpSaleItem::getProductId, ErpSaleItem::getQty, ErpSaleItem::getPrice,
                        ErpSaleItem::getAmount, ErpSaleItem::getTaxType, ErpSaleItem::getRemark)
                    .eq(ErpSaleItem::getOrderId, saleId)
                    .orderByAsc(ErpSaleItem::getSeq))) {
            ErpSaleVoRow row = new ErpSaleVoRow();
            ErpProduct product = loadPrintProduct(item.getProductId());
            row.name = product == null ? null : product.getName();
            row.spec = product == null ? null : product.getSpec();
            row.unit = product == null ? null : product.getUnit();
            row.qty = item.getQty();
            row.price = item.getPrice();
            row.amount = item.getAmount();
            row.taxType = item.getTaxType();
            row.remark = item.getRemark();
            rows.add(row);
        }
        return rows;
    }

    private static class ErpSaleVoRow {
        String name;
        String spec;
        String unit;
        BigDecimal qty;
        BigDecimal price;
        BigDecimal amount;
        String taxType;
        String remark;
    }
}
