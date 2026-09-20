#!/bin/sh
set -eu
gateway=https://localhost:18080
legacy=https://localhost:18443
curl_args='-ksS --fail'

curl $curl_args "$gateway/api/notifications/health" | grep 'modern-notification-service'
curl $curl_args "$gateway/api/accounting/invoices/health" | grep 'modern-accounting-invoice-service'
curl $curl_args "$gateway/api/accounting/invoices?limit=2" | grep 'legacy-ofbiz-postgres'
curl $curl_args "$gateway/api/accounting/invoices/8009" | grep 'lineItems'
curl $curl_args "$gateway/accounting/control/findInvoices?invoiceId=8009" | grep 'Legacy detail'
curl $curl_args "$gateway/accounting/control/findInvoices?invoiceId=8009" | grep 'OFBiz logo'
# Unauthenticated legacy protected routes normally return the OFBiz login response (401).
curl -ksS "$legacy/accounting/control/findInvoices" | grep -E 'OFBiz|login|Login' >/dev/null
curl -ksS "$gateway/accounting/control/findPayments" | grep -E 'OFBiz|login|Login' >/dev/null

for path in /helveticus/HELVETICUS_EMERALD.less /helveticus/style.css /common/js/node_modules/jquery-ui-dist/jquery-ui.min.js; do
  curl $curl_args "$gateway$path" >/dev/null
  curl $curl_args "$legacy$path" >/dev/null
done

test_id="MSVCTEST$(date +%s)"
cleanup() { curl -ksS -X DELETE "$gateway/api/accounting/invoices/$test_id" >/dev/null 2>&1 || true; }
trap cleanup EXIT
payload="{\"invoiceId\":\"$test_id\",\"invoiceTypeId\":\"SALES_INVOICE\",\"partyIdFrom\":\"Company\",\"partyId\":\"DemoCustomer\",\"statusId\":\"INVOICE_IN_PROCESS\",\"currencyUomId\":\"USD\"}"
curl $curl_args -H 'Content-Type: application/json' -d "$payload" "$gateway/api/accounting/invoices" | grep "$test_id"
curl $curl_args -X PUT -H 'Content-Type: application/json' -d '{"description":"Phase 1 smoke test"}' "$gateway/api/accounting/invoices/$test_id" | grep 'Phase 1 smoke test'
curl $curl_args -X DELETE "$gateway/api/accounting/invoices/$test_id" >/dev/null
trap - EXIT
echo 'Phase 1 hybrid smoke test passed.'
