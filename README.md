# Wantly 🎁

Wishlist app — добавляйте вещи, которые хотите получить в подарок или купить позже. Вставили ссылку → приложение само вытащило название, фото, цену и магазин.

## Возможности

- 🔗 **Парсинг ссылок** — вставили URL товара → автоматически название, фото, цена, магазин
- 👤 **Гостевой режим** — начните пользоваться без регистрации; данные хранятся локально
- 📝 **Списки желаний** — личные списки с цветовой меткой и статуса желаний (Хочу / Забронировано / Куплено)
- 🔄 **Синхронизация** — local-first: данные хранятся локально (Room), синхронизируются с сервером в фоне. Офлайн работает из коробки.
- 🔐 **JWT-авторизация** — регистрация и вход

## Архитектура

```
Wantly/
├── app/                    # Android-приложение (Kotlin + Jetpack Compose)
│   ├── data/               # Room DB, API клиент, репозитории
│   ├── ui/                 # Compose UI: экраны, темы, компоненты
│   └── navigation/         # Navigation Compose
│
└── backend/                # Сервер (Ktor + PostgreSQL + Playwright)
    └── src/main/kotlin/
        ├── auth/           # JWT-авторизация, bcrypt
        ├── db/             # Exposed ORM таблицы
        ├── wishlist/       # CRUD списков и желаний
        └── preview/        # Парсинг ссылок через Playwright + Chromium
```

### Стек

| Уровень | Технология |
|---------|-----------|
| Android UI | Jetpack Compose, Material 3 |
| Локальное хранилище | Room (SQLite) |
| Сетевой слой | OkHttp + kotlinx.serialization |
| Авторизация | DataStore (JWT), гостевой режим |
| Бэкенд | Ktor 3.5 (Kotlin), JVM 21 |
| База данных | PostgreSQL 16 + Exposed ORM |
| Парсинг ссылок | Playwright + Chromium (headless) |
| Веб-сервер | Nginx (reverse proxy + SSL) |
| SSL | Let's Encrypt (certbot) |

## Разработка

### Android-приложение

Откройте проект в Android Studio и запустите на устройстве/эмуляторе. Минимальные требования: Android 8.0 (API 26).

### Бэкенд

```bash
cd backend
./gradlew buildFatJar
WANTLY_JWT_SECRET=your-secret java -jar build/libs/wantly-backend-all.jar
```

Сервер запускается на порту 8080. База данных PostgreSQL должна быть доступна на localhost:5432.

## Лицензия

MIT
