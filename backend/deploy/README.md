# Deploy

## Первичная настройка сервера (Ubuntu 24.04)

### 1. Установить зависимости

```bash
apt update && apt install -y nginx postgresql certbot python3-certbot-nginx openjdk-21-jre-headless
```

### 2. Создать пользователя и директории

```bash
useradd -r -s /usr/sbin/nologin -d /opt/wantly-backend wantly
mkdir -p /opt/wantly-backend/build/libs
mkdir -p /opt/wantly-backend/deploy
mkdir -p /opt/playwright-browsers
chown -R wantly:wantly /opt/wantly-backend /opt/playwright-browsers
```

### 3. Настроить PostgreSQL

```bash
sudo -u postgres psql << SQL
CREATE USER wantly WITH PASSWORD '__GENERATED_PASSWORD__';
CREATE DATABASE wantly OWNER wantly;
GRANT ALL PRIVILEGES ON DATABASE wantly TO wantly;
SQL
```

### 4. Сгенерировать secrets

```bash
mkdir -p /etc/wantly
echo "WANTLY_JWT_SECRET=$(openssl rand -base64 48)" > /etc/wantly/backend.env
echo "WANTLY_DB_PASSWORD=__GENERATED_PASSWORD__" >> /etc/wantly/backend.env
chown wantly:wantly /etc/wantly/backend.env
chmod 0600 /etc/wantly/backend.env
```

### 5. Установить Playwright (Chromium для preview)

```bash
sudo -u wantly PLAYWRIGHT_BROWSERS_PATH=/opt/playwright-browsers \
  npx playwright install chromium --with-deps
```

Или при первом запуске backend'а Java Playwright скачает Chromium автоматически
(нужны системные зависимости: `apt install -y libnss3 libatk1.0-0 libatk-bridge2.0-0
libcups2 libgbm1 libasound2t64`).

### 6. Создать assetlinks.json

```bash
# Получить SHA-256 fingerprint сертификата app:
keytool -list -v -keystore <keystore> -alias <alias> | grep SHA256

# Создать файл:
cat > /opt/wantly-backend/deploy/assetlinks.json << EOF
[{"relation":["delegate_permission/common.handle_all_urls"],
  "target":{"namespace":"android_app","package_name":"com.nervs.wantly",
  "sha256_cert_fingerprints":["YOUR_SHA256"]}}]
EOF
```

### 7. Установить systemd unit

```bash
cp wantly-backend.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable wantly-backend
```

### 8. Установить nginx config + TLS

```bash
cp wantlyapp.ru.nginx /etc/nginx/sites-available/wantlyapp.ru
ln -sf /etc/nginx/sites-available/wantlyapp.ru /etc/nginx/sites-enabled/
nginx -t && systemctl reload nginx

# Получить TLS-сертификат (auto-configures nginx for 443):
certbot --nginx -d wantlyapp.ru -d www.wantlyapp.ru --non-interactive --agree-tos --email your@email.com
```

### 9. Деплой backend

```bash
# С локальной машины:
./backend/deploy/deploy.sh root@YOUR_SERVER_IP

# Или вручную:
./gradlew -p backend buildFatJar
scp backend/build/libs/wantly-backend-all.jar root@YOUR_SERVER:/opt/wantly-backend/build/libs/
ssh root@YOUR_SERVER "systemctl restart wantly-backend"
```

### 10. Проверка

```bash
curl https://wantlyapp.ru/health       # OK
curl https://wantlyapp.ru/health/ready # OK (DB ping)
curl https://wantlyapp.ru/.well-known/assetlinks.json  # Android App Links
```

## Обновление деплоя

```bash
./backend/deploy/deploy.sh
```

Соберёт fat-jar, загрузит, перезапустит, проверит health.

## TLS renewal

Certbot автоматически продлевает сертификаты через systemd timer:
```bash
certbot renew --dry-run  # проверить
```
