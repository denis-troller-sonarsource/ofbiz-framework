package com.company.erp.accounting;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class InvoiceRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcTemplate plainJdbc;

    InvoiceRepository(NamedParameterJdbcTemplate jdbc, JdbcTemplate plainJdbc) {
        this.jdbc = jdbc;
        this.plainJdbc = plainJdbc;
    }

    Map<String, Object> list(Map<String, String> filters, int limit, int offset) {
        var where = new StringBuilder(" where 1=1");
        var params = new MapSqlParameterSource().addValue("limit", limit).addValue("offset", offset);
        addFilter(where, params, filters, "invoiceId", "i.invoice_id");
        addFilter(where, params, filters, "invoiceTypeId", "i.invoice_type_id");
        addFilter(where, params, filters, "statusId", "i.status_id");
        addFilter(where, params, filters, "partyIdFrom", "i.party_id_from");
        addFilter(where, params, filters, "partyId", "i.party_id");
        String totals = """
            left join lateral (select count(*) item_count,
              coalesce(sum(coalesce(quantity,1) * coalesce(amount,0)),0) invoice_total
              from invoice_item x where x.invoice_id=i.invoice_id) items on true
            left join lateral (select coalesce(sum(coalesce(amount_applied,0)),0) applied_total
              from payment_application p where p.invoice_id=i.invoice_id) payments on true
            """;
        String from = " from invoice i left join invoice_type it on it.invoice_type_id=i.invoice_type_id "
            + "left join status_item si on si.status_id=i.status_id " + totals + where;
        String select = """
            select i.invoice_id invoice_id, i.invoice_type_id invoice_type_id,
              it.description type_description, i.status_id status_id, si.description status_description,
              i.party_id_from party_id_from, i.party_id party_id, i.description description, i.invoice_date invoice_date,
              i.due_date due_date, i.currency_uom_id currency_uom_id, items.item_count,
              items.invoice_total, payments.applied_total,
              items.invoice_total-payments.applied_total outstanding_total
            """;
        List<Map<String, Object>> rows = jdbc.queryForList(select + from + " order by i.invoice_date desc nulls last, i.invoice_id limit :limit offset :offset", params);
        Long total = jdbc.queryForObject("select count(*) from invoice i" + where, params, Long.class);
        return Map.of("items", rows, "pagination", Map.of("limit", limit, "offset", offset, "total", total),
            "source", "legacy-ofbiz-postgres", "notes", List.of("Totals are SQL-derived and must be reconciled with OFBiz InvoiceWorker before write cutover."));
    }

    Map<String, Object> detail(String id) {
        Map<String, Object> result = new LinkedHashMap<>(list(Map.of("invoiceId", id), 1, 0));
        @SuppressWarnings("unchecked") List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("items");
        if (rows.isEmpty()) return Map.of();
        Map<String, Object> detail = new LinkedHashMap<>(rows.get(0));
        detail.put("lineItems", plainJdbc.queryForList("select invoice_item_seq_id, invoice_item_type_id, description, quantity, amount, product_id from invoice_item where invoice_id=? order by invoice_item_seq_id", id));
        detail.put("paymentApplications", plainJdbc.queryForList("select payment_application_id, payment_id, billing_account_id, amount_applied from payment_application where invoice_id=? order by payment_application_id", id));
        detail.put("statusHistory", plainJdbc.queryForList("select status_id, status_date, change_by_user_login_id from invoice_status where invoice_id=? order by status_date", id));
        detail.put("source", "legacy-ofbiz-postgres");
        detail.put("notes", List.of("Read projection only: totals are SQL-derived; OFBiz remains authoritative for lines, payments, posting, tax, promotions, PDF, and accounting side effects."));
        return detail;
    }

    Map<String, Object> create(Map<String, Object> body) {
        String id = text(body, "invoiceId");
        if (id == null || id.isBlank()) id = "MSVC" + Instant.now().toEpochMilli();
        Timestamp now = Timestamp.from(Instant.now());
        try {
            plainJdbc.update("""
                insert into invoice (invoice_id, invoice_type_id, party_id_from, party_id, status_id,
                  invoice_date, due_date, currency_uom_id, description,
                  last_updated_stamp, last_updated_tx_stamp, created_stamp, created_tx_stamp)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, required(body, "invoiceTypeId"), required(body, "partyIdFrom"), required(body, "partyId"),
                required(body, "statusId"), timestamp(body.get("invoiceDate"), now), timestamp(body.get("dueDate"), null),
                body.getOrDefault("currencyUomId", "USD"), body.get("description"), now, now, now, now);
        } catch (DuplicateKeyException ex) {
            throw new IllegalStateException("Invoice already exists: " + id, ex);
        }
        return detail(id);
    }

    Map<String, Object> update(String id, Map<String, Object> body) {
        int changed = plainJdbc.update("""
            update invoice set invoice_type_id=coalesce(?,invoice_type_id), party_id_from=coalesce(?,party_id_from),
              party_id=coalesce(?,party_id), status_id=coalesce(?,status_id), due_date=coalesce(?,due_date),
              currency_uom_id=coalesce(?,currency_uom_id), description=coalesce(?,description), last_updated_stamp=?
            where invoice_id=?
            """, body.get("invoiceTypeId"), body.get("partyIdFrom"), body.get("partyId"), body.get("statusId"),
            timestamp(body.get("dueDate"), null), body.get("currencyUomId"), body.get("description"), Timestamp.from(Instant.now()), id);
        return changed == 0 ? Map.of() : detail(id);
    }

    boolean delete(String id) {
        Integer dependencies = plainJdbc.queryForObject("select (select count(*) from invoice_item where invoice_id=?) + (select count(*) from payment_application where invoice_id=?)", Integer.class, id, id);
        if (dependencies != null && dependencies > 0) throw new InvoiceHasDependenciesException();
        return plainJdbc.update("delete from invoice where invoice_id=?", id) > 0;
    }

    private static void addFilter(StringBuilder sql, MapSqlParameterSource params, Map<String, String> filters, String name, String column) {
        String value = filters.get(name);
        if (value != null && !value.isBlank()) { sql.append(" and ").append(column).append(" = :").append(name); params.addValue(name, value); }
    }
    private static String text(Map<String, Object> body, String key) { return body.get(key) == null ? null : body.get(key).toString(); }
    private static String required(Map<String, Object> body, String key) { String value = text(body, key); if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required"); return value; }
    private static Timestamp timestamp(Object value, Timestamp fallback) { return value == null ? fallback : Timestamp.from(Instant.parse(value.toString())); }
    static final class InvoiceHasDependenciesException extends RuntimeException { }
}
