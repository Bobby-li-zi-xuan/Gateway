#!/usr/bin/env bash
# 冒烟测试：启动 2 个 mock + 网关，验证基础端点。
set -e
cd "$(dirname "$0")/.."

echo "[smoke] starting mock 8001 (qwen-large)..."
SERVER_PORT=8001 MOCK_MODEL=qwen-large nohup mvn -q -pl mock-model-server spring-boot:run > /tmp/mock1.log 2>&1 &

echo "[smoke] starting mock 8002 (qwen-small)..."
SERVER_PORT=8002 MOCK_MODEL=qwen-small nohup mvn -q -pl mock-model-server spring-boot:run > /tmp/mock2.log 2>&1 &

echo "[smoke] starting gateway 8080..."
nohup mvn -q -pl ai-gateway spring-boot:run > /tmp/gw.log 2>&1 &

echo "[smoke] waiting for gateway..."
for i in $(seq 1 40); do
  if curl -s -o /dev/null http://localhost:8080/actuator/health; then
    echo "[smoke] gateway is up"
    break
  fi
  sleep 2
done

echo "--- GET /v1/models ---"
curl -s http://localhost:8080/v1/models
echo

echo "--- POST /v1/chat/completions (non-stream; H2 桩未实现时预期 500) ---"
curl -s -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"qwen","messages":[{"role":"user","content":"hi"}]}'
echo

echo "--- gateway log tail ---"
tail -8 /tmp/gw.log

echo "[smoke] cleaning up processes..."
pkill -f 'spring-boot:run' || true
echo "[smoke] done"
