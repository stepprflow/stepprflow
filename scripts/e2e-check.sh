#!/usr/bin/env bash
# Drives the docker-compose.e2e.yml stack: waits for the monitor and the sample,
# POSTs orders to the sample, then asserts the monitor ingested at least one
# COMPLETED execution (full chain: workflow -> Kafka -> monitor -> Mongo -> API).
# Assumes the stack is already up (`docker compose -f docker-compose.e2e.yml up`).
set -uo pipefail

MON=http://localhost:8090
SAMPLE=http://localhost:8010
AUTH="admin:admin"
log(){ echo "[$(date +%H:%M:%S)] $*"; }
dump(){ echo "::group::monitor logs"; docker compose -f docker-compose.e2e.yml logs monitor | tail -80; echo "::endgroup::";
        echo "::group::sample logs";  docker compose -f docker-compose.e2e.yml logs sample  | tail -80; echo "::endgroup::"; }

# 1) monitor health UP (HTTP 200)
log "waiting for monitor health"
ok=""
for i in $(seq 1 40); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' $MON/actuator/health)" = "200" ] && { ok=1; log "monitor UP"; break; }
  sleep 5
done
[ -z "$ok" ] && { echo "::error::monitor never healthy"; dump; exit 1; }

# 2) sample started — probe the HTTP port (robust; no dependency on a log line).
# Any HTTP response (even 404 on /) means the web server is accepting requests;
# connection-refused curls return 000.
log "waiting for sample HTTP port"
ok=""
for i in $(seq 1 40); do
  code=$(curl -s -o /dev/null -w '%{http_code}' $SAMPLE/ || true)
  [ "$code" != "000" ] && { ok=1; log "sample UP (HTTP $code on /)"; break; }
  sleep 5
done
[ -z "$ok" ] && { echo "::error::sample never accepted HTTP"; dump; exit 1; }

# 3) POST orders. The monitor subscribes to workflow topics by pattern and only
# sees order-workflow after a Kafka metadata refresh, so keep posting while we
# poll — later orders are guaranteed to land after the subscription is live.
post_order(){
  curl -s -o /dev/null -w '%{http_code}' -X POST $SAMPLE/api/orders \
    -H 'Content-Type: application/json' -d '{
      "customerId":"CI-'"$1"'","customerEmail":"'"$1"'@example.com",
      "items":[{"productId":"PROD-001","productName":"Widget","quantity":1,"price":49.99}],
      "payment":{"cardLast4":"4242","cardType":"VISA"},
      "shipping":{"street":"1 Main","city":"Paris","state":"IDF","zipCode":"75001","country":"FR"}
    }'
}
# The monitor subscribes to order-workflow only after a Kafka metadata refresh
# (topic discovery), which can take a couple of minutes, so poll generously.
log "posting orders + polling for a COMPLETED execution"
completed=0
for i in $(seq 1 80); do
  [ $((i % 5)) -eq 1 ] && log "POST order $i -> HTTP $(post_order "$i")"
  resp=$(curl -s -u "$AUTH" "$MON/api/workflows?page=0&size=50")
  read -r total comp <<<"$(echo "$resp" | python3 -c "
import sys,json
d=json.load(sys.stdin)
c=sum(1 for e in d.get('content',[]) if e.get('status')=='COMPLETED')
print(d.get('totalElements',0), c)" 2>/dev/null || echo '0 0')"
  echo "  t=$((i*3))s totalElements=$total completed=$comp"
  if [ "${comp:-0}" -ge 1 ] 2>/dev/null; then completed=1; break; fi
  sleep 3
done

if [ "$completed" = 1 ]; then
  echo "E2E PASS — monitor ingested a COMPLETED workflow execution end-to-end."
  curl -s -u "$AUTH" "$MON/api/workflows/stats"; echo
  exit 0
fi
echo "::error::no COMPLETED execution ingested by the monitor"
dump
exit 1
