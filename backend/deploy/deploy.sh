#!/usr/bin/env bash
# Деплой Wantly backend на VPS.
#
# Использование: ./backend/deploy/deploy.sh [user@host]
# По умолчанию: root@161.104.45.249
#
# Что делает:
#  1. buildFatJar локально
#  2. scp fat-jar на сервер
#  3. systemctl restart wantly-backend
#  4. Проверка /health и /health/ready

set -euo pipefail

HOST="${1:-root@161.104.45.249}"
JAR_LOCAL="backend/build/libs/wantly-backend-all.jar"
JAR_REMOTE="/opt/wantly-backend/build/libs/wantly-backend-all.jar"

echo "=== Build fat-jar ==="
./gradlew -p backend clean buildFatJar

echo "=== Upload to ${HOST} ==="
scp "$JAR_LOCAL" "${HOST}:${JAR_REMOTE}"

echo "=== Restart backend ==="
ssh "$HOST" "systemctl restart wantly-backend && sleep 5"

echo "=== Health check ==="
ssh "$HOST" "curl -sf http://127.0.0.1:8080/health && echo '' && curl -sf http://127.0.0.1:8080/health/ready && echo ''"
if [ $? -eq 0 ]; then
    echo "✅ Deploy successful"
else
    echo "❌ Health check failed — check journalctl -u wantly-backend"
    exit 1
fi
