# SaveFood

Платформа спасения еды — соединяет магазины с излишками, волонтёров и нуждающихся людей в единую систему распределения продуктов.

---

## Как это работает

```
Магазин публикует лот → Волонтёр берёт маршрут → Нуждающийся получает продукты
```

1. Магазин добавляет лот вручную или **фотографирует чек списания** — OCR сам распознаёт позиции, категории и готовит лоты (тариф «Профи»)
2. Нуждающийся выбирает лот на карте и оформляет заявку (доставка или самовывоз); документы при регистрации предпроверяет **Auto-KYC** (ИИ-вердикт для модератора)
3. Волонтёр берёт лот — система отбирает заявки по приоритету и строит оптимальный маршрут (2-opt)
4. Получатель подтверждает доставку QR-кодом + GPS-проверка (≤ 100 м); самовывоз закрывает магазин по тому же QR

## Монетизация (B2B SaaS)

Помощь и работа волонтёров бесплатны — платит ритейл. Тариф хранится в `shops.plan`, фичи гейтятся на сервере (HTTP 402 с подсказкой об апгрейде):

| | Базовый | Профи | Enterprise |
|---|---|---|---|
| Создание лотов | вручную, 20/мес | без лимита | без лимита |
| OCR чеков (фото → лоты) | — | ✓ | ✓ |
| ESG-отчёт (кг, CO₂, приёмы пищи) | — | ✓ | ✓ |

Чеки проходят **антифрод** (дата, дубликаты по sha256/fingerprint, ИИ-оценка подлинности → `fraud_score`). Смена тарифа — админом (вкладка «Тарифы», пишется в audit log); биллинг-шлюз — в планах.

---

## Стек

| Слой | Технология |
|---|---|
| Backend | Python 3.11, FastAPI, PostgreSQL 15 |
| Микросервис горячих путей | Go 1.24 (`go-services/geows`): WebSocket-фанаут + геокоординаты |
| Frontend | React 18, Vite, react-router-dom v7 |
| Карты | Yandex Maps + Geosuggest/Геокодер (подсказки адресов) |
| i18n | react-i18next (ru / kk / en) |
| Мобильное приложение | Capacitor 8 (Android / iOS) |
| Уведомления | Telegram Bot (aiogram 3.x), WebSocket |
| ИИ-помощник поддержки | Google Gemini (`backend/ai_service.py`), эскалация на админа |
| ИИ: OCR чеков + антифрод | Gemini Vision (`backend/receipt_service.py`): позиции, категории, подлинность |
| ИИ: Auto-KYC документов | Gemini Vision (`backend/kyc_service.py`): вердикт для очереди модерации |
| SaaS-тарифы / ESG | `backend/billing.py` (гейтинг, квоты), `backend/esg.py` (CO₂-методология v1) |
| Авторизация | JWT HS256, bcrypt |
| Деплой | Docker Compose, Nginx, Cloudflare Tunnel |

---

## Быстрый старт (Docker)

### Требования

- Docker и Docker Compose
- Yandex Maps API Key
- Telegram Bot Token (опционально)
- Gemini API Key (опционально — для ИИ-помощника в боте, https://aistudio.google.com/apikey)

### Запуск

```bash
git clone https://github.com/your-username/savefood.git
cd savefood

cp .env.example .env
# Заполнить .env (см. ниже)

docker compose up -d --build
```

Приложение будет доступно на `http://localhost` (порт меняется через `APP_PORT`).

### Переменные окружения (.env)

```env
# База данных
POSTGRES_DB=savefood
POSTGRES_USER=postgres
POSTGRES_PASSWORD=strong-password

# Backend (обязательные)
SECRET_KEY=random-string-64-chars        # python -c "import secrets; print(secrets.token_urlsafe(64))"
CORS_ORIGIN=https://yourdomain.com       # список origins через запятую, без wildcard

# Frontend (Vite, передаются при сборке)
VITE_YANDEX_MAPS_API_KEY=your-yandex-maps-key
VITE_YANDEX_SUGGEST_API_KEY=your-geosuggest-key

# Telegram-бот (опционально)
TELEGRAM_BOT_TOKEN=...
TELEGRAM_BOT_NAME=your_bot_username
TELEGRAM_POLLING=true                    # true = long-polling, false = webhook
TELEGRAM_WEBHOOK_SECRET=...              # для webhook-режима
SITE_URL=https://yourdomain.com
SUPPORT_CHAT_ID=...                      # chat id админа: алерты + эскалации ИИ

# ИИ: помощник в боте, OCR чеков, Auto-KYC (опционально — без ключа платформа
# деградирует в ручной режим: OCR отвечает 503, KYC помечает заявки «unchecked»)
GEMINI_API_KEY=...
AI_MODEL=gemini-2.5-flash
OCR_MODEL=                               # vision-модель для чеков (по умолчанию = AI_MODEL)
KYC_MODEL=                               # vision-модель для документов (по умолчанию = AI_MODEL)
RECEIPT_MAX_AGE_HOURS=48                 # максимальный возраст чека для антифрода
KYC_AUTO_APPROVE=false                   # Auto-KYC v2: авто-одобрение уверенных likely_ok
KYC_AUTO_APPROVE_SCORE=0.85              # порог скора для авто-одобрения

# Мониторинг (опционально)
SENTRY_DSN=                              # ошибки в Sentry; пусто = выключено
SENTRY_ENV=production
METRICS_TOKEN=                           # если задан, GET /metrics требует Bearer-токен

# Web Push / VAPID (опционально; без ключей кнопка подписки скрыта)
# Генерация пары:
#   python -c "from cryptography.hazmat.primitives.asymmetric import ec; \
# from cryptography.hazmat.primitives import serialization; import base64; \
# k = ec.generate_private_key(ec.SECP256R1()); \
# priv = k.private_numbers().private_value.to_bytes(32,'big'); \
# pub = k.public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint); \
# print('VAPID_PRIVATE_KEY=' + base64.urlsafe_b64encode(priv).decode().rstrip('=')); \
# print('VAPID_PUBLIC_KEY=' + base64.urlsafe_b64encode(pub).decode().rstrip('='))"
VAPID_PUBLIC_KEY=
VAPID_PRIVATE_KEY=
VAPID_SUBJECT=mailto:admin@example.com

# Соц-вход (опционально; кнопка появляется, только если задана пара ключей)
GOOGLE_CLIENT_ID=...
GOOGLE_CLIENT_SECRET=...
YANDEX_CLIENT_ID=...
YANDEX_CLIENT_SECRET=...
OAUTH_PUBLIC_URL=                        # база для redirect_uri (по умолчанию SITE_URL)

# Прочее
LOCAL_TZ=Asia/Almaty                     # часовой пояс окон available_time
APP_PORT=80
VLESS_URL=                               # опциональный прокси для Telegram API
```

---

## Локальная разработка

### Backend

```bash
# из корня репозитория
python -m venv venv
source venv/bin/activate
pip install -r backend/requirements.txt

# база данных
docker run -d --name savefood-pg -v savefood_pgdata:/var/lib/postgresql/data \
  -e POSTGRES_DB=savefood -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 postgres:15-alpine

# сервер (SECRET_KEY и CORS_ORIGIN обязательны)
SECRET_KEY=$(python -c "import secrets; print(secrets.token_urlsafe(64))") \
CORS_ORIGIN=http://localhost:3000 \
DB_HOST=localhost DB_USER=postgres DB_PASS=postgres DB_NAME=savefood \
uvicorn backend.main:app --host 127.0.0.1 --port 8000 --reload
```

API документация: `http://localhost:8000/docs`

### Frontend

```bash
cd savefood
npm install
npm run dev        # Vite dev-сервер на http://localhost:3000
```

В dev-режиме Vite проксирует все API-пути на `http://127.0.0.1:8000` (настраивается через `VITE_API_URL` в `savefood/.env`; пустое значение = относительные пути через прокси).

### Go-микросервис (geows)

Горячие пути — `/ws/needy/{id}` (WebSocket-уведомления) и `/volunteers/{id}/location` — вынесены в Go-сервис. В Docker-развёртке он собирается и маршрутизируется автоматически (nginx). Локально:

```bash
cd go-services/geows
SECRET_KEY=<тот же, что у backend> DB_HOST=localhost DB_USER=postgres \
DB_PASS=postgres DB_NAME=savefood PORT=8001 go run .
```

Чтобы dev-прокси Vite направлял горячие пути в Go, добавьте в `savefood/.env`:
`VITE_GO_URL=http://127.0.0.1:8001` (пусто → эти пути обслуживает Python, как раньше).

### Доступ с другого устройства (Cloudflare Tunnel)

```bash
cloudflared tunnel --url http://localhost:3000   # dev-сервер
# или ./cloudflare-tunnel.sh                     # прод (nginx на :80), URL пишется в ~/savefood-url.txt
```

Случайный хост `*.trycloudflare.com` уже разрешён в `vite.config.js` (`server.allowedHosts`). Quick-туннель живёт, пока работает процесс `cloudflared`; при перезапуске URL меняется.

### Миграции (опционально)

```bash
alembic upgrade head
```

> База данных также инициализируется автоматически при первом запуске через `CREATE TABLE IF NOT EXISTS`.

---

## Мобильное приложение (Android APK)

```bash
cd savefood
npm run build
npx cap sync android

cd android
./gradlew assembleDebug
```

APK: `android/app/build/outputs/apk/debug/app-debug.apk`

**Требования:** Java 21, Android SDK 36, Gradle 8.14+

### SSL pinning перед релизом

Перед сборкой release APK замените плейсхолдеры в `savefood/android/app/src/main/res/xml/network_security_config.xml` реальными SHA-256-пинами:

```bash
# Получить primary pin (запускать с доступом к прод-серверу)
openssl s_client -connect api.yourdomain.com:443 -servername api.yourdomain.com \
  < /dev/null 2>/dev/null \
  | openssl x509 -pubkey -noout \
  | openssl pkey -pubin -outform der \
  | openssl dgst -sha256 -binary \
  | openssl enc -base64
```

Обязательно указать два пина (primary + backup): если сертификат ротируется, а backup отсутствует — приложение перестанет работать. Обновляйте пины и выпускайте новую версию **до** истечения `expiration` в pin-set.

---

## Роли

| Роль | Возможности |
|---|---|
| **Магазин** | Создание лотов вручную или сканом чека (OCR), подтверждение передачи, закрытие самовывоза по QR-коду, ESG-отчёт, тариф с квотой в сайдбаре, история, Telegram-уведомления |
| **Волонтёр** | Карта лотов, маршруты с 2-opt-оптимизацией и навигацией, GPS-верификация, QR-сканер, статистика и достижения (бейджи) |
| **Получатель** | Просмотр лотов на карте, заявки (доставка/самовывоз), трекинг волонтёра, оценка доставки 1–5 ★ |
| **Администратор** | Модерация заявок с ИИ-вердиктом Auto-KYC, диспетчерская, управление пользователями и тарифами магазинов, аналитика + ESG платформы, audit log |

---

## Структура проекта

```
savefood/
├── backend/                 # FastAPI приложение
│   ├── main.py              # Точка входа, CORS, фоновые циклы (expire / reassign / antifraud)
│   ├── auth.py              # JWT, ensure_owner_or_admin
│   ├── auth_routes.py       # /auth/login, /auth/refresh
│   ├── limiter.py           # Rate limiting (slowapi, CF-Connecting-IP)
│   ├── ai_service.py        # ИИ-помощник поддержки (Google Gemini API)
│   ├── receipt_service.py   # OCR чеков + классификация + антифрод (Gemini Vision)
│   ├── kyc_service.py       # Auto-KYC: ИИ-предпроверка документов нуждающихся
│   ├── billing.py           # SaaS-тарифы: гейтинг OCR/ESG, квоты лотов
│   ├── esg.py               # ESG-отчёты: CO₂-методология v1, разбивки
│   ├── telegram_routes.py   # Бот: команды, relay-чат, ИИ-фоллбэк, webhook
│   ├── telegram_service.py  # Отправка уведомлений в Telegram
│   ├── proxy_service.py     # Опциональный VLESS-прокси для Telegram API
│   ├── shop/                # Роуты, БД, схемы магазинов
│   ├── volunteer/           # Роуты, БД, схемы волонтёров (+ маршрутизация)
│   ├── needy/               # Роуты, БД, схемы получателей (+ WebSocket)
│   ├── admin/               # Роуты администратора
│   └── requirements.txt
│
├── savefood/                # React приложение (Vite)
│   ├── src/
│   │   ├── Pages/           # Shop, Volunteer, Needy, Admin, Auth, About
│   │   ├── components/      # EmptyState, ProtectedRoute
│   │   ├── context/         # AuthContext
│   │   ├── i18n/            # Переводы ru/kk/en
│   │   └── api.js           # API_URL (VITE_API_URL)
│   ├── android/             # Capacitor Android проект
│   ├── nginx.conf           # Прод-прокси (rate limit, WS, security headers)
│   ├── vite.config.js       # Dev-прокси, allowedHosts для туннеля
│   └── capacitor.config.ts
│
├── go-services/
│   └── geows/               # Go-микросервис: WS-фанаут уведомлений, геокоординаты
│       ├── main.go          # hub с общим поллером БД, JWT (общий SECRET_KEY)
│       └── Dockerfile       # multi-stage, ~10 MB образ
│
├── migrations/              # Alembic миграции (raw SQL)
├── docker-compose.yml
├── Dockerfile.backend
├── cloudflare-tunnel.sh     # Quick tunnel для внешнего доступа
└── .env.example
```

---

## API

Основные эндпоинты:

| Метод | Путь | Описание |
|---|---|---|
| `POST` | `/auth/login` | Вход (OAuth2 PasswordFlow), 5 req/min |
| `GET` | `/auth/oauth/{google\|yandex}/start` | Соц-вход / привязка (authorization code) |
| `POST` | `/auth/telegram/login/start` + `/poll` | Вход через Telegram (deep-link бота) |
| `GET` | `/auth/links` | Статус привязок Telegram / Google / Yandex |
| `POST` | `/shops/register` | Регистрация магазина (атомарно с учёткой) |
| `POST` | `/volunteers/register` | Регистрация волонтёра |
| `POST` | `/needy/register` | Регистрация получателя |
| `GET` | `/lots` | Активные лоты (фильтры: категория, поиск) |
| `POST` | `/shops/{id}/lots/upload` | Создать лот (multipart с фото) |
| `POST` | `/shops/{id}/receipts` | OCR: фото чека → позиции + антифрод-вердикт (Профи+) |
| `POST` | `/shops/{id}/receipts/{rid}/confirm` | Создать лоты из проверенных черновиков чека |
| `GET` | `/shops/{id}/esg` | ESG-отчёт магазина: кг, CO₂, приёмы пищи (Профи+) |
| `GET` | `/shops/{id}/plan` | Текущий тариф и использование квоты |
| `POST` | `/shops/{id}/self_pickup/confirm` | Закрыть самовывоз по коду `SF-<id>` |
| `POST` | `/volunteers/{id}/start_route` | Взять лот: отбор заявок по приоритету + 2-opt маршрут |
| `POST` | `/volunteers/route/{id}/complete_point` | Точка маршрута (QR + GPS проверяются на сервере) |
| `POST` | `/volunteers/route/{id}/finish` | Завершить маршрут (незакрытые тикеты → open) |
| `GET` | `/volunteers/{id}/stats` | Статистика + достижения |
| `PATCH` | `/volunteers/{id}/location` | Пуш координат (каждые 20 с, питает антифрод) |
| `POST` | `/needy/{id}/ticket` | Создать заявку (доставка / самовывоз) |
| `PATCH` | `/needy/{id}/moderation` | Одобрить/отклонить (admin) |
| `GET` | `/admin/stats` | Статистика платформы |
| `GET` | `/admin/esg` | ESG платформы + топ-10 магазинов |
| `PATCH` | `/admin/shops/{id}/plan` | Сменить тариф магазина (audit log) |
| `WS` | `/ws/needy/{id}` | WebSocket уведомлений (auth первым сообщением) |

Полная документация: `/docs` (Swagger UI)

---

## Социальный вход и привязка аккаунтов

- **Профиль** каждой роли содержит блок «Привязанные аккаунты»: Telegram, Google, Яндекс (привязать/отвязать)
- **Вход**: кнопки на странице логина. Google/Яндекс — стандартный OAuth (authorization code, серверный обмен); redirect URI `<OAUTH_PUBLIC_URL>/auth/oauth/<provider>/callback` нужно зарегистрировать в консоли провайдера
- **Telegram** — без Login Widget (он требует фиксированный домен в BotFather, что несовместимо с туннелями): вход идёт через deep-link бота `t.me/<bot>?start=login_<token>` + поллинг; работает с любым доменом
- Вход возможен только в уже существующий аккаунт: сначала регистрация по паролю, затем привязка в профиле
- Токен после OAuth-входа возвращается во **fragment** URL (`#oauth_token=…`) — не попадает в логи серверов

---

## Telegram-бот

Бот выполняет три функции:

1. **Уведомления** — заявка принята волонтёром, волонтёр в пути (с ETA), попытка доставки, антифрод-пинг
2. **Чат** — сообщения между волонтёром и нуждающимся в активном маршруте
3. **ИИ-помощник** — если активной доставки нет, на вопрос отвечает Gemini (FAQ по платформе); когда модель не уверена — вопрос автоматически пересылается администратору в `SUPPORT_CHAT_ID`

Команды: `/start`, `/help`, `/status`, `/chat`, `/unlink`.

### Привязка аккаунта

```
Профиль → «Подключить Telegram» → deep-link → /start link_<token>
```

### Режимы получения апдейтов

- **Long-polling** (по умолчанию, `TELEGRAM_POLLING=true`) — ничего настраивать не нужно
- **Webhook** (`TELEGRAM_POLLING=false`):

```bash
curl "https://api.telegram.org/bot<TOKEN>/setWebhook?url=https://yourdomain.com/telegram/webhook&secret_token=<TELEGRAM_WEBHOOK_SECRET>"
```

---

## Особенности безопасности

- Документы нуждающихся скачиваются только владельцем/админом и удаляются сразу после модерации
- Пароли хранятся только в виде bcrypt-хеша
- JWT содержит `role` и `related_id` — каждый пользователь видит только свои данные (`ensure_owner_or_admin` на всех приватных эндпоинтах)
- Подтверждение доставки: QR-код и GPS-радиус 100 м проверяются **на сервере**
- Rate limiting на `/auth/login` и `/register`: 5 запросов в минуту с одного IP (за Cloudflare Tunnel клиент определяется по `CF-Connecting-IP`)
- **Антифрод (волонтёры)**: если волонтёр взял лот и удаляется от магазина — авто-пинг «Всё в порядке?», при отсутствии реакции маршрут снимается и еда возвращается на витрину
- **Антифрод (чеки)**: дубликаты по sha256 и fingerprint (магазин+дата+сумма), проверка свежести даты, ИИ-оценка подлинности фото; `fraud_score ≥ 0.7` блокирует создание лотов
- **Auto-KYC**: ИИ предпроверяет документы нуждающихся (тип, ФИО, следы редактирования), но финальное решение принимает модератор; фото чеков и документы недоступны по публичным URL
- Заблокированный админом пользователь теряет доступ немедленно (проверка на каждом запросе)
- **SSL pinning (mobile):** Android — `network_security_config.xml` с SHA-256 SPKI pin-set, cleartext-трафик заблокирован; iOS — `NSAppTransportSecurity` отключает `NSAllowsArbitraryLoads`, Certificate Transparency включена для прод-домена

---

## Тестирование

Юнит-тесты: `tests/` (pytest, без БД — чистая логика: 2-opt-маршрутизация, окна `available_time`, приоритетный score, ESG-математика, антифрод чеков, биллинг-гейтинг, уровни геймификации, прогноз списаний, матчинг предпочтений, JWT/пароли, API-ключи и HMAC-подпись вебхуков, решение Auto-KYC v2, коды команд).

```bash
pip install -r requirements-dev.txt
SECRET_KEY=<32+ символов> CORS_ORIGIN=http://localhost:3000 pytest tests/ -v
```

CI: GitHub Actions (`.github/workflows/ci.yml`) — pytest + production-сборка фронтенда + `go build`/`go vet` микросервиса geows на каждый push/PR.

Дополнительно — сквозной runtime-смоук против живого сервера: регистрации (включая негативные 401/403/409/422), модерация, заявки, маршрут с QR/GPS-проверками, самовывоз, рейтинг, ачивки, антифрод-цикл. Сценарий описан в `savefood.md` §21.

---

## Устранение неполадок

**Ошибка подключения к БД**
- Убедитесь, что контейнер PostgreSQL запущен: `docker ps`
- Проверьте `POSTGRES_*` / `DB_*` переменные

**Карта не загружается**
- Проверьте `VITE_YANDEX_MAPS_API_KEY` (для Docker-сборки передаётся как build ARG)
- API ключ должен быть активным в консоли Yandex Cloud

**Vite-прокси отдаёт 502 в dev**
- Бэкенд должен слушать `127.0.0.1:8000` (Node может резолвить `localhost` в IPv6)
- Проверьте `VITE_API_URL` в `savefood/.env` — устаревший URL туннеля ломает прокси

**Telegram-бот не отвечает**
- Проверьте `TELEGRAM_BOT_TOKEN`
- По умолчанию бот работает в режиме long-polling; webhook нужен только при `TELEGRAM_POLLING=false`
- ИИ-ответы требуют `GEMINI_API_KEY`; без него вопросы эскалируются в `SUPPORT_CHAT_ID`

**OCR чеков возвращает 503 / KYC-вердикт «unchecked»**
- Проверьте `GEMINI_API_KEY` — без него ИИ-функции отключены (лоты создаются вручную, модерация полностью ручная)
- Кнопка OCR требует тариф «Профи»+: ответ 402 означает, что магазину нужно сменить тариф (админ → вкладка «Тарифы»)

**Проблемы при сборке APK**
- Обновите Java: `java -version` (требуется Java 21+)
- Очистите кеш: `./gradlew clean`

---

## Вклад

Если вы хотите помочь развитию проекта:

1. Fork репозитория
2. Создайте ветку для вашей функции (`git checkout -b feature/new-feature`)
3. Делайте коммиты с чёткими сообщениями
4. Push в вашу ветку
5. Откройте Pull Request с описанием изменений

Перед отправкой убедитесь:
- Код работает локально
- Переводы добавлены для всех трёх языков (ru/kk/en)
- При изменении схемы БД добавлена/обновлена соответствующая Alembic-миграция (`0001`–`0003`, см. `savefood.md` §18)

---

## Контакты и поддержка

- **Email**: igel2020i@gmail.com
- **Telegram**: @savefood_bot
- **Issues**: [GitHub Issues](https://github.com/your-username/savefood/issues)

---

## Лицензия

MIT
