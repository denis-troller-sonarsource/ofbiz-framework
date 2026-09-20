package com.company.erp.accounting;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InvoiceUiController {
    private final InvoiceRepository invoices;
    InvoiceUiController(InvoiceRepository invoices) { this.invoices = invoices; }

    @GetMapping(value = {"/modern/accounting/invoices", "/accounting/control/findInvoices"}, produces = MediaType.TEXT_HTML_VALUE)
    String page(@RequestParam Map<String, String> query) {
        var result = invoices.list(query, 50, 0);
        @SuppressWarnings("unchecked") var rows = (java.util.List<Map<String, Object>>) result.get("items");
        StringBuilder table = new StringBuilder();
        for (var row : rows) {
            String id = esc(row.get("invoice_id"));
            table.append("<tr><td><a href='/api/accounting/invoices/").append(id).append("'>").append(id)
                .append("</a></td><td>").append(esc(row.get("type_description"))).append("</td><td>")
                .append(esc(row.get("status_description"))).append("</td><td>").append(esc(row.get("party_id_from")))
                .append(" → ").append(esc(row.get("party_id"))).append("</td><td>").append(esc(row.get("invoice_total")))
                .append(" ").append(esc(row.get("currency_uom_id"))).append("</td><td><a href='/accounting/control/viewInvoice?invoiceId=")
                .append(id).append("'>Legacy detail</a></td></tr>");
        }
        return """
            <!doctype html><html><head><meta charset="utf-8"><title>OFBiz Accounting - Invoices</title>
            <style>body{margin:0;font:14px Arial;color:#133d3b;background:#f6f8f8}header{height:58px;background:#1BC5BD;color:#133d3b;display:flex;align-items:center;padding:0 22px}header img{width:105px;margin-right:28px}.tabs{background:#133d3b;padding:11px 20px}.tabs a{color:white;margin-right:18px;text-decoration:none}.subnav{background:#dcfffd;padding:12px 20px}.subnav a{color:#133d3b;margin-right:15px}main{padding:22px}form,.card{background:white;padding:18px;margin-bottom:18px;border-radius:4px;box-shadow:0 1px 4px #ccd}label{margin-right:12px}input{padding:6px;width:125px}button{padding:7px 15px;background:#1BC5BD;border:0}table{width:100%%;border-collapse:collapse}th,td{text-align:left;padding:9px;border-bottom:1px solid #dcfffd}.notice{border-left:5px solid #1BC5BD;padding:10px;background:#dcfffd}</style></head><body>
            <header><img src="/images/ofbiz_logo.png" alt="OFBiz logo"><strong>Accounting / Modern Invoice Read Model</strong></header>
            <nav class="tabs"><a href="/webtools">Webtools</a><a href="/ordermgr">Order Manager</a><a href="/accounting">Accounting</a><a href="/partymgr">Party Manager</a><a href="/catalog">Product Catalog</a></nav>
            <nav class="subnav"><a href="/accounting/control/findInvoices">Invoices</a><a href="/accounting/control/findPayments">Payments</a><a href="/accounting/control/findPaymentGroups">Payment Groups</a><a href="/accounting/control/findFinAccountTrans">Transactions</a><a href="/accounting/control/findBillingAccount">Billing Accounts</a><a href="/accounting/control/FindFinAccount">Financial Accounts</a><a href="/accounting/control/FindTaxAuthority">Tax Authorities</a><a href="/accounting/control/FindAgreement">Agreements</a><a href="/accounting/control/FindFixedAsset">Fixed Assets</a><a href="/accounting/control/FindBudget">Budgets</a><a href="/accounting/control/EditGlobalGlAccount">GL Settings</a><a href="/accounting/control/ListCompanies">Companies</a></nav>
            <main><div class="notice">Modern Phase 1 slice. Reads and limited header CRUD use the modern service; OFBiz remains authoritative for invoice lifecycle behavior.</div>
            <form method="get"><label>Invoice ID <input name="invoiceId"></label><label>Type <input name="invoiceTypeId"></label><label>Status <input name="statusId"></label><label>From party <input name="partyIdFrom"></label><label>To party <input name="partyId"></label><button>Search</button></form>
            <div class="card"><table><thead><tr><th>Invoice</th><th>Type</th><th>Status</th><th>Parties</th><th>Total</th><th>Legacy</th></tr></thead><tbody>%s</tbody></table></div></main></body></html>
            """.formatted(table);
    }
    private static String esc(Object value) { return value == null ? "" : value.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
}
