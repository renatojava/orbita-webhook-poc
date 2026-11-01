#!/usr/bin/env bash
# Exercises the running system the way Meta and an operator would, through real HTTP.
# Requires: docker compose, curl, openssl, xargs. Usage: ./scripts/demo.sh
set -euo pipefail
cd "$(dirname "$0")/.."

BASE=http://localhost:8080
export SECRET="${WHATSAPP_APP_SECRET:-dev-only-secret}" BASE
export PHONE_A=100200300400500 PHONE_B=100200300400600
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
export WORK

title() { printf '\n=== %s\n' "$1"; }
sql() { docker compose exec -T postgres psql -U orbita -d orbita -Atc "$1"; }
wait_for() { # description, sql condition returning t
  local i; for i in $(seq 1 120); do [ "$(sql "SELECT $2")" = t ] && return 0; sleep 0.5; done
  echo "TIMEOUT waiting for: $1"; return 1
}
wait_up() { until curl -fsS -o /dev/null -X PUT "$BASE/internal/tenants/tenant-a" -H 'Content-Type: application/json' \
  -d "{\"phoneNumberId\":\"$PHONE_A\",\"sendRatePerSecond\":1000,\"sendBurst\":1000}" 2>/dev/null; do sleep 1; done; }

body_for() { # wamid, phone
  local from="${3:-5511$(printf '%s' "$1" | cksum | cut -d' ' -f1)}"
  printf '{"object":"whatsapp_business_account","entry":[{"id":"108800000000001","changes":[{"field":"messages","value":{"messaging_product":"whatsapp","metadata":{"display_phone_number":"5511999990000","phone_number_id":"%s"},"contacts":[{"profile":{"name":"Ana Souza"},"wa_id":"%s"}],"messages":[{"from":"%s","id":"%s","timestamp":"1760364000","type":"text","text":{"body":"Oi, quero saber da formacao de closer"}}]}}]}]}' "$2" "$from" "$from" "$1"
}
post_message() { # wamid, phone, optional contact -> prints http status
  local body sig; body=$(body_for "$1" "$2" "${3:-}")
  sig=$(printf '%s' "$body" | openssl dgst -sha256 -hmac "$SECRET" -r | cut -d' ' -f1)
  curl -s -m 5 -o /dev/null -w '%{http_code}' -X POST "$BASE/webhooks/whatsapp" \
    -H 'Content-Type: application/json' -H "X-Hub-Signature-256: sha256=$sig" -d "$body" || true
}
export -f body_for post_message

title "Subindo Postgres e aplicacao"
docker compose down -v --remove-orphans >/dev/null 2>&1 || true
docker compose up -d --build >/dev/null 2>&1
wait_up
curl -fsS -X PUT "$BASE/internal/tenants/tenant-b" -H 'Content-Type: application/json' \
  -d "{\"phoneNumberId\":\"$PHONE_B\",\"sendRatePerSecond\":50,\"sendBurst\":50}"
echo "pronto"

title "1. Duplicidade: 50 reenvios identicos em paralelo"
seq 50 | xargs -P 50 -I{} bash -c 'printf "%s\n" "$(post_message wamid.ORBITA-TESTE-0001 "$PHONE_A")"' | sort | uniq -c
wait_for "fila vazia" "count(*) FILTER (WHERE status <> 'DONE') = 0 FROM webhook_deliveries"
wait_for "resposta enviada" "count(*) = 1 FROM outbound_messages WHERE status = 'SENT'"
echo "registros no historico: $(sql "SELECT count(*) FROM messages WHERE wamid = 'wamid.ORBITA-TESTE-0001'")"
echo "respostas automaticas:  $(sql "SELECT count(*) FROM outbound_messages")"
echo "duplicatas descartadas: $(sql "SELECT sum(duplicate_messages) FROM webhook_deliveries")"

title "2. Assinatura invalida"
echo "HTTP $(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/webhooks/whatsapp" -H 'X-Hub-Signature-256: sha256=00' -d '{}')"

title "2b. Mesma pessoa manda 3 mensagens seguidas, com pausas de 500 ms"
for i in 1 2 3; do post_message "wamid.PICADA-$i" "$PHONE_A" 5511977776666 >/dev/null; sleep 0.5; done
wait_for "janela fechada" "count(*) = 0 FROM reply_windows"
wait_for "resposta enviada" "count(*) = 1 FROM outbound_messages WHERE to_phone = '5511977776666' AND status = 'SENT'"
echo "mensagens recebidas: $(sql "SELECT count(*) FROM messages WHERE from_phone = '5511977776666'")"
echo "respostas enviadas:  $(sql "SELECT count(*) FROM outbound_messages WHERE to_phone = '5511977776666'")"

title "3. Campanha de 20 mil do cliente A (limite 50/s) contra uma mensagem do cliente B"
curl -fsS -X PUT "$BASE/internal/tenants/tenant-a" -H 'Content-Type: application/json' \
  -d "{\"phoneNumberId\":\"$PHONE_A\",\"sendRatePerSecond\":50,\"sendBurst\":50}"
curl -fsS -X POST "$BASE/internal/tenants/tenant-a/campaigns" -H 'Content-Type: application/json' \
  -d '{"campaignId":"demo","count":20000,"body":"Promocao"}'; echo
sleep 2
start=$(date +%s%N)
post_message wamid.CLIENTE-B "$PHONE_B" >/dev/null
wait_for "resposta do cliente B" "count(*) = 1 FROM outbound_messages WHERE tenant_id = 'tenant-b' AND status = 'SENT'"
echo "resposta do cliente B enviada em $(( ($(date +%s%N) - start) / 1000000 )) ms (inclui a janela de agrupamento de 2 s)"
echo "campanha do cliente A ainda pendente: $(sql "SELECT count(*) FROM outbound_messages WHERE tenant_id = 'tenant-a' AND status = 'PENDING'")"

title "4. kill -9 no meio do trafego"
sql "DELETE FROM outbound_messages WHERE idempotency_key LIKE 'campaign:%'" >/dev/null
( seq 3000 | xargs -P 20 -I{} bash -c 's=$(post_message wamid.CRASH-{} "$PHONE_A"); [ "$s" = 200 ] && echo wamid.CRASH-{}' > "$WORK/accepted.txt" ) &
sender=$!
sleep 1.5
docker compose kill -s SIGKILL app >/dev/null 2>&1
wait "$sender" || true
docker compose up -d app >/dev/null 2>&1
wait_up
wait_for "fila vazia" "count(*) FILTER (WHERE status NOT IN ('DONE', 'DEAD')) = 0 FROM webhook_deliveries"
wait_for "janelas fechadas" "count(*) = 0 FROM reply_windows"
wait_for "saida resolvida" "count(*) FILTER (WHERE status IN ('PENDING', 'SENDING')) = 0 FROM outbound_messages"
sql "SELECT wamid FROM messages" | sort > "$WORK/recorded.txt"
sort "$WORK/accepted.txt" > "$WORK/accepted.sorted"
echo "enviadas: 3000; aceitas com 200 antes da queda: $(wc -l < "$WORK/accepted.sorted") (as recusadas a Meta reenvia)"
echo "aceitas e NAO gravadas:         $(comm -23 "$WORK/accepted.sorted" "$WORK/recorded.txt" | wc -l)"
echo "envios incertos sinalizados:    $(sql "SELECT count(*) FROM outbound_messages WHERE status = 'UNCERTAIN'")"

title "5. Banco fora do ar: responde 503 e a Meta reenvia"
docker compose stop postgres >/dev/null 2>&1
echo "com banco parado:  HTTP $(post_message wamid.DB-DOWN "$PHONE_A")"
docker compose start postgres >/dev/null 2>&1
wait_up
echo "reenvio da Meta:   HTTP $(post_message wamid.DB-DOWN "$PHONE_A")"
wait_for "reenvio gravado" "count(*) = 1 FROM messages WHERE wamid = 'wamid.DB-DOWN'"
echo "registros:         $(sql "SELECT count(*) FROM messages WHERE wamid = 'wamid.DB-DOWN'")"

title "6. Reconciliacao"
curl -fsS "$BASE/internal/reconciliation"; echo
