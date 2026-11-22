package org.erp.print.service.impl;

import org.erp.print.domain.PrintTemplate;

/**
 * 内置兜底模板（DOC/14 §2 解析顺序第 3 级：无可用模板时代码内置）
 *
 * 4 套：送货单 / 对账单 / 收款单 / 付款单（收付款共用一套，doc.type 切换标题）。
 */
public final class BuiltinTemplates {

    private BuiltinTemplates() {
    }

    public static PrintTemplate saleDelivery() {
        return of("SALE_DELIVERY", "内置送货单", "A4", """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
              <meta charset="UTF-8">
              <style>
                /* @page 由 PageSetup 按模板 paper 注入 */
                body { font-family: "Microsoft YaHei", "PingFang SC", sans-serif; font-size: 12px; color: #000; }
                h1 { text-align: center; font-size: 20px; letter-spacing: 4px; margin: 0 0 8px; }
                .meta { display: flex; justify-content: space-between; margin-bottom: 10px; }
                table { width: 100%; border-collapse: collapse; }
                th, td { border: 1px solid #333; padding: 5px 6px; }
                th { background: #f0f0f0; }
                .num { text-align: right; }
                .sign { display: flex; justify-content: space-between; margin-top: 30px; }
              </style>
            </head>
            <body>
              <h1>送 货 单</h1>
              <div class="meta">
                <span th:text="${tenant.name}">公司名称</span>
                <span>单号：<b th:text="${doc.no}">XS202610310001</b></span>
              </div>
              <div class="meta">
                <span>客户：<b th:text="${partner.name}">客户</b></span>
                <span>日期：<span th:text="${#dates.format(doc.date, 'yyyy-MM-dd')}">2026-10-31</span></span>
              </div>
              <table>
                <thead>
                  <tr>
                    <th style="width:6%">序号</th>
                    <th>产品名称</th>
                    <th style="width:16%">规格</th>
                    <th style="width:8%">单位</th>
                    <th style="width:10%" class="num">数量</th>
                    <th style="width:10%" class="num">单价</th>
                    <th style="width:12%" class="num">金额</th>
                  </tr>
                </thead>
                <tbody>
                  <tr th:each="item, stat : ${items}">
                    <td th:text="${stat.index + 1}">1</td>
                    <td th:text="${item.name}">产品</td>
                    <td th:text="${item.spec}">规格</td>
                    <td th:text="${item.unit}">支</td>
                    <td class="num" th:text="${item.qty}">0</td>
                    <td class="num" th:text="${item.price}">0.00</td>
                    <td class="num" th:text="${item.amount}">0.00</td>
                  </tr>
                </tbody>
              </table>
              <div style="text-align:right; margin-top:8px;">
                合计：<b th:text="${totals.amountTotal}">0.00</b> 元
                （大写：<span th:text="${totals.amountUpper}">零元整</span>）
              </div>
              <div th:if="${showProfit}" style="margin-top:8px;border-top:1px dashed #999;padding-top:6px;">
                成本：<span th:text="${profit.costAmount}">0.00</span> 元；
                毛利：<span th:text="${profit.grossProfit}">0.00</span> 元；
                毛利率：<span th:text="${profit.grossMargin}">0</span>%
              </div>
              <div class="sign">
                <span>制单：<span th:text="${signatures.maker}"></span></span>
                <span>仓管：<span th:text="${signatures.warehouse}"></span></span>
                <span>客户签收：______________</span>
              </div>
            </body>
            </html>
            """);
    }

    public static PrintTemplate statement() {
        return of("STATEMENT", "内置对账单", "A4", """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
              <meta charset="UTF-8">
              <style>
                /* @page 由 PageSetup 按模板 paper 注入 */
                body { font-family: "Microsoft YaHei", "PingFang SC", sans-serif; font-size: 12px; }
                h1 { text-align: center; font-size: 20px; letter-spacing: 4px; margin: 0 0 4px; }
                .sub { text-align: center; color: #666; margin-bottom: 14px; }
                .meta { display: flex; justify-content: space-between; margin-bottom: 10px; }
                table { width: 100%; border-collapse: collapse; }
                th, td { border: 1px solid #333; padding: 5px 6px; }
                th { background: #f0f0f0; }
                .num { text-align: right; }
                .sign { display: flex; justify-content: space-between; margin-top: 30px; }
              </style>
            </head>
            <body>
              <h1>对 账 单</h1>
              <div class="sub" th:text="${statement.month} + ' 月度'">2026-10 月度</div>
              <div class="meta">
                <span>客户：<b th:text="${partner.name}">客户</b></span>
                <span>制单日期：<span th:text="${printDate}">2026-10-31</span></span>
              </div>
              <table>
                <thead>
                  <tr>
                    <th style="width:11%">日期</th>
                    <th style="width:16%">单号</th>
                    <th style="width:12%">摘要</th>
                    <th>商品名称</th>
                    <th style="width:9%" class="num">数量</th>
                    <th style="width:9%" class="num">单价</th>
                    <th style="width:14%" class="num">发生额</th>
                  </tr>
                </thead>
                <tbody>
                  <tr th:each="item : ${statement.items}">
                    <td th:text="${#dates.format(item.date, 'yyyy-MM-dd')}">2026-10-05</td>
                    <td th:text="${item.no}">XS202610050001</td>
                    <td th:text="${item.content}">销售</td>
                    <td th:text="${item.productName} ?: '-'">料管</td>
                    <td class="num" th:text="${item.qtyText != null} ? ${item.qtyText} : '-'">10</td>
                    <td class="num" th:text="${item.price != null} ? ${#numbers.formatDecimal(item.price,1,2)} : '-'">0.00</td>
                    <td class="num" th:text="${item.amount}">0.00</td>
                  </tr>
                </tbody>
              </table>
              <div style="margin-top:10px;">
                <div>期初应收：<span th:text="${statement.opening}">0.00</span></div>
                <div>本期建账期初：<span th:text="${statement.openingEntryAmount}">0.00</span></div>
                <div>本期销售：<span th:text="${statement.salesAmount}">0.00</span></div>
                <div>本期退货：<span th:text="${statement.returnAmount}">0.00</span></div>
                <div>本期收款：<span th:text="${statement.receiptAmount}">0.00</span></div>
                <div>本期退款：<span th:text="${statement.refundAmount}">0.00</span></div>
                <div>本期净发生额：<span th:text="${statement.monthlyUnpaid}">0.00</span></div>
                <div style="text-align:right; font-size:14px;">
                  期末应收：<b th:text="${statement.closing}">0.00</b> 元
                </div>
              </div>
              <div style="margin-top:6px;">
                <div>累计销售净额：<span th:text="${statement.cumulativeSales}">0.00</span></div>
                <div>累计净收款：<span th:text="${statement.cumulativeReceipts}">0.00</span></div>
                <div>累计未付金额：<b th:text="${statement.cumulativeUnpaid}">0.00</b> 元</div>
              </div>
              <div class="sign">
                <span>制单：<span th:text="${tenant.name}">公司名称</span></span>
                <span>客户确认：______________</span>
              </div>
            </body>
            </html>
            """);
    }

    public static PrintTemplate receiptPayment() {
        return of("RECEIPT", "内置收款/付款单", "A5", """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
              <meta charset="UTF-8">
              <style>
                /* @page 由 PageSetup 按模板 paper 注入 */
                body { font-family: "Microsoft YaHei", "PingFang SC", sans-serif; font-size: 12px; }
                h1 { text-align: center; font-size: 18px; letter-spacing: 4px; }
                .meta { display: flex; justify-content: space-between; margin: 10px 0; }
                table { width: 100%; border-collapse: collapse; }
                th, td { border: 1px solid #333; padding: 5px 6px; }
                th { background: #f0f0f0; }
                .num { text-align: right; }
                .sign { display: flex; justify-content: space-between; margin-top: 30px; }
              </style>
            </head>
            <body>
              <h1 th:text="${doc.type} == 'RECEIPT' ? '收 款 单' : '付 款 单'">收 款 单</h1>
              <div class="meta">
                <span>单号：<b th:text="${doc.no}"></b></span>
                <span>日期：<span th:text="${#dates.format(doc.date, 'yyyy-MM-dd')}"></span></span>
              </div>
              <div class="meta">
                <span th:text="${doc.type} == 'RECEIPT' ? '客户' : '供应商'">客户</span>
                ：<b th:text="${partner.name}"></b>
                <span>账户：<span th:text="${doc.account}"></span></span>
              </div>
              <table>
                <thead>
                  <tr><th>关联单据</th><th>日期</th><th class="num">金额</th></tr>
                </thead>
                <tbody>
                  <tr th:each="item : ${items}">
                    <td th:text="${item.no}"></td>
                    <td th:text="${#dates.format(item.date, 'yyyy-MM-dd')}"></td>
                    <td class="num" th:text="${item.amount}"></td>
                  </tr>
                </tbody>
              </table>
              <div style="text-align:right; margin-top:8px;">
                合计：<b th:text="${totals.amountTotal}"></b> 元
                （大写：<span th:text="${totals.amountUpper}"></span>）
              </div>
              <div class="sign">
                <span>制单：______________</span>
                <span>客户/供应商确认：______________</span>
              </div>
            </body>
            </html>
            """);
    }

    public static PrintTemplate purchaseIn() {
        return of("PURCHASE_IN", "内置采购入库单", "A4", """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
              <meta charset="UTF-8">
              <style>
                /* @page 由 PageSetup 按模板 paper 注入 */
                body { font-family: "Microsoft YaHei", "PingFang SC", sans-serif; font-size: 12px; color: #000; }
                h1 { text-align: center; font-size: 20px; letter-spacing: 4px; margin: 0 0 8px; }
                .meta { display: flex; justify-content: space-between; margin-bottom: 10px; }
                table { width: 100%; border-collapse: collapse; }
                th, td { border: 1px solid #333; padding: 5px 6px; }
                th { background: #f0f0f0; }
                .num { text-align: right; }
                .sign { display: flex; justify-content: space-between; margin-top: 30px; }
              </style>
            </head>
            <body>
              <h1>采 购 入 库 单</h1>
              <div class="meta">
                <span th:text="${tenant.name}">公司名称</span>
                <span>单号：<b th:text="${doc.no}">CR202610310001</b></span>
              </div>
              <div class="meta">
                <span>供应商：<b th:text="${partner.name}">供应商</b></span>
                <span>日期：<span th:text="${#dates.format(doc.date, 'yyyy-MM-dd')}">2026-10-31</span></span>
              </div>
              <table>
                <thead>
                  <tr>
                    <th style="width:6%">序号</th>
                    <th>产品名称</th>
                    <th style="width:16%">规格</th>
                    <th style="width:8%">单位</th>
                    <th style="width:10%" class="num">数量</th>
                    <th style="width:12%" class="num">单价</th>
                    <th style="width:12%" class="num">金额</th>
                  </tr>
                </thead>
                <tbody>
                  <tr th:each="item : ${items}">
                    <td th:text="${item.index}">1</td>
                    <td th:text="${item.name}">料管</td>
                    <td th:text="${item.spec}">Φ25×2m</td>
                    <td th:text="${item.unit}">支</td>
                    <td class="num" th:text="${item.qty}">10</td>
                    <td class="num" th:text="${item.price}">50.00</td>
                    <td class="num" th:text="${item.amount}">500.00</td>
                  </tr>
                </tbody>
              </table>
              <div style="text-align:right; margin-top:8px;">
                合计：<b th:text="${totals.amountTotal}"></b> 元
                （大写：<span th:text="${totals.amountUpper}"></span>）
              </div>
              <div class="sign">
                <span>制单：______________</span>
                <span>仓管：______________</span>
                <span>供应商确认：______________</span>
              </div>
            </body>
            </html>
            """);
    }

    /** Built-in custom-range statement; business totals remain independent from line presentation. */
    public static PrintTemplate statementRange() {
        return of("STATEMENT_RANGE", "内置对账单（区间）", "A4", """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
              <meta charset="UTF-8">
              <style>
                /* @page 由 PageSetup 按模板 paper 注入 */
                body { font-family: "Microsoft YaHei", "PingFang SC", sans-serif; font-size: 12px; }
                h1 { text-align: center; font-size: 20px; letter-spacing: 4px; margin: 0 0 4px; }
                .sub { text-align: center; color: #666; margin-bottom: 14px; }
                .meta { display: flex; justify-content: space-between; margin-bottom: 10px; }
                table { width: 100%; border-collapse: collapse; }
                th, td { border: 1px solid #333; padding: 5px 6px; }
                th { background: #f0f0f0; }
                .num { text-align: right; }
                .summary { display: flex; justify-content: space-between; gap: 24px; margin-top: 10px; line-height: 1.8; }
                .sign { display: flex; justify-content: space-between; margin-top: 30px; }
              </style>
            </head>
            <body>
              <h1>对 账 单</h1>
              <div class="sub">对账期间：<span th:text="${statement.periodLabel}">2026-09-01 至 2026-09-30</span></div>
              <div class="meta">
                <span>客户：<b th:text="${partner.name}">客户</b></span>
                <span>制单日期：<span th:text="${printDate}">2026-10-31</span></span>
              </div>
              <table>
                <thead><tr>
                  <th style="width:12%">日期</th><th style="width:18%">单号</th><th style="width:14%">摘要</th>
                  <th>商品名称</th><th style="width:9%" class="num">数量</th>
                  <th style="width:10%" class="num">单价</th><th style="width:15%" class="num">发生额</th>
                </tr></thead>
                <tbody>
                  <tr th:each="item : ${statement.items}">
                    <td th:text="${#dates.format(item.date, 'yyyy-MM-dd')}">2026-09-05</td>
                    <td th:text="${item.no}">XS202609050001</td>
                    <td th:text="${item.content}">销售</td>
                    <td th:text="${item.productName} ?: '-'"></td>
                    <td class="num" th:text="${item.qtyText != null} ? ${item.qtyText} : '-'"></td>
                    <td class="num" th:text="${item.price != null} ? ${#numbers.formatDecimal(item.price,1,2)} : '-'"></td>
                    <td class="num" th:text="${item.amount}">0.00</td>
                  </tr>
                </tbody>
              </table>
              <div class="summary">
                <div>
                  <div>期初应收：<span th:text="${statement.opening}">0.00</span></div>
                  <div>本期销售金额：<span th:text="${statement.netSalesAmount}">0.00</span></div>
                  <div>本期收款金额：<span th:text="${statement.receiptAmount}">0.00</span></div>
                  <div>本期退款：<span th:text="${statement.refundAmount}">0.00</span></div>
                  <div>本期未付金额：<span th:text="${statement.monthlyUnpaid}">0.00</span></div>
                </div>
                <div>
                  <div>累计销售净额：<span th:text="${statement.cumulativeSales}">0.00</span></div>
                  <div>累计净收款：<span th:text="${statement.cumulativeReceipts}">0.00</span></div>
                  <div>期末应收：<b th:text="${statement.closing}">0.00</b> 元</div>
                  <div>当前实时未核销余额：<span th:text="${totals.debtTotal}">0.00</span> 元</div>
                </div>
              </div>
              <div class="sign">
                <span>制单：<span th:text="${tenant.name}">公司名称</span></span>
                <span>客户确认：______________</span>
              </div>
            </body>
            </html>
            """);
    }

    private static PrintTemplate of(String scene, String name, String paper, String content) {
        PrintTemplate t = new PrintTemplate();
        t.setCode("BUILTIN_" + scene);
        t.setName(name);
        t.setScene(scene);
        t.setPaper(paper);
        t.setVersion(1);
        t.setIsDefault(0);
        t.setStatus(1);
        t.setContent(content);
        return t;
    }
}
