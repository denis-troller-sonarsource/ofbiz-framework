package com.company.erp.accounting;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounting/invoices")
public class InvoiceController {
    private final InvoiceRepository invoices;
    InvoiceController(InvoiceRepository invoices) { this.invoices = invoices; }

    @GetMapping("/health") Map<String, String> health() { return Map.of("status", "UP", "service", "modern-accounting-invoice-service"); }
    @GetMapping ResponseEntity<?> list(@RequestParam Map<String, String> filters) {
        int limit = bounded(filters.get("limit"), 20, 1, 100);
        int offset = bounded(filters.get("offset"), 0, 0, Integer.MAX_VALUE);
        return ResponseEntity.ok(invoices.list(filters, limit, offset));
    }
    @GetMapping("/{id}") ResponseEntity<?> detail(@PathVariable String id) { return found(invoices.detail(id)); }
    @PostMapping ResponseEntity<?> create(@RequestBody Map<String, Object> body) { return ResponseEntity.status(HttpStatus.CREATED).body(invoices.create(body)); }
    @PutMapping("/{id}") ResponseEntity<?> update(@PathVariable String id, @RequestBody Map<String, Object> body) { return found(invoices.update(id, body)); }
    @DeleteMapping("/{id}") ResponseEntity<?> delete(@PathVariable String id) { return invoices.delete(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build(); }

    private static ResponseEntity<?> found(Map<String, Object> value) { return value.isEmpty() ? ResponseEntity.notFound().build() : ResponseEntity.ok(value); }
    private static int bounded(String raw, int fallback, int min, int max) { if (raw == null) return fallback; return Math.max(min, Math.min(max, Integer.parseInt(raw))); }

    @org.springframework.web.bind.annotation.ExceptionHandler(InvoiceRepository.InvoiceHasDependenciesException.class)
    ResponseEntity<?> dependencies() { return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Invoice has line items or payment applications and cannot be deleted")); }
    @org.springframework.web.bind.annotation.ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    ResponseEntity<?> badRequest(RuntimeException ex) { return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage())); }
}
