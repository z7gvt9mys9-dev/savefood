# SaveFood

Платформа спасения еды — соединяет магазины с излишками, волонтёров и нуждающихся людей в единую систему распределения продуктов.

**[Презентация проекта](./SaveFood_Presentation.pptx)** · **[Архитектура](#архитектура-и-структура-проекта)** · **[Быстрый старт](#быстрый-старт-docker)** · **[API](#api)**

В системе четыре роли: магазин или частный донор публикует излишки, волонтёр
доставляет, получатель бронирует помощь, администратор следит за волонтёрской KYC-модерацией и
операциями. Web-клиент работает как PWA; для полевых сценариев есть нативный
Android-клиент (вход и кабинеты трёх пользовательских ролей, без админки).

---

## Как это работает

```
Магазин публикует лот → Волонтёр берёт маршрут → Нуждающийся получает продукты
```

1. Магазин добавляет лот вручную или **фотографирует чек списания** — OCR сам распознаёт позиции, категории и готовит лоты (тариф «Профи»)
2. Нуждающийся после обычной регистрации сразу выбирает лот на карте и оформляет заявку (доставка или самовывоз), без документов и модерации
3. Волонтёр берёт лот — система отбирает заявки по приоритету и строит оптимальный маршрут (2-opt)
4. Получатель подтверждает доставку QR-кодом + GPS-проверка (≤ 100 м); самовывоз закрывает магазин по тому же QR

## Монетизация (B2B SaaS)

Помощь и работа волонтёров бесплатны — платит ритейл. Частный донор работает в
базовой модели. Тариф хранится в `shops.plan`, фичи гейтятся на сервере
(HTTP 402 с подсказкой об апгрейде):

| | Базовый | Профи | Enterprise |
|---|---|---|---|
| Создание лотов | вручную, 20/мес | без лимита | без лимита |
| OCR чеков (фото → лоты) | — | ✓ | ✓ |
| ESG-отчёт (кг, CO₂, приёмы пищи) | — | ✓ | ✓ |
| Партнёрский API и исходящие вебхуки | — | — | ✓ |

Чеки проходят **антифрод** (дата, дубликаты по sha256/fingerprint, ИИ-оценка подлинности → `fraud_score`). Смена тарифа — админом (вкладка «Тарифы», пишется в audit log); биллинг-шлюз — в планах.

---

## Стек

| Слой | Технология |
|---|---|
| Backend | Java 21, Spring Boot 3.3, JdbcTemplate + рукописный SQL, PostgreSQL 15 |
| Схема БД | Flyway (`backend-java/src/main/resources/db/migration/`), baseline-on-migrate |
| Микросервис горячих путей | Go 1.26.4 (`go-services/geows`): WebSocket-фанаут + геокоординаты |
| Frontend | React 18, Vite, react-router-dom v7 |
| Web-режим | PWA: manifest + service worker |
| Карты | Yandex Maps + Geosuggest/Геокодер (подсказки адресов) |
| i18n | react-i18next (ru / en) |
| Мобильное приложение | Нативный Android: Kotlin, Jetpack Compose, Hilt (`android-app/`) |
| Уведомления | Telegram Bot (HTTP Bot API, входящий вебхук), Web Push, FCM, WebSocket |
| ИИ-помощник поддержки | Google Gemini (`ai/AiService`), эскалация на админа |
| ИИ: OCR чеков + антифрод | Gemini Vision (`receipt/ReceiptService`): позиции, категории, подлинность |
| ИИ: KYC волонтёров | Gemini Vision (`kyc/KycService`): проверка удостоверений волонтёров |
| SaaS-тарифы / ESG | `billing/BillingService` (гейтинг, квоты), `esg/EsgService` (CO₂-методология v1) |
| Кэш | Caffeine, in-process (`cache/CacheService`) |
| Авторизация | JWT HS256, bcrypt |
| Деплой | Docker Compose, Nginx, Cloudflare Tunnel |

> Python/FastAPI-бэкенд удалён 21.06.2026 и заменён на Java. Если в каком-то
> документе ещё встречается `backend/*.py`, `uvicorn` или `alembic` — он устарел.

---

## Быстрый старт (Docker)

### Требования

- Docker и Docker Compose
- Сильные значения `POSTGRES_PASSWORD`, `SECRET_KEY` и `KYC_ENCRYPTION_KEY`
- Yandex Maps API Key (опционально: без него карты отключены)
- Telegram Bot Token и Gemini API Key — опционально, для бота и ИИ-функций

### Запуск

```bash
git clone https://github.com/z7gvt9mys9-dev/savefood.git
cd savefood

$EDITOR .env

docker compose up -d --build
```

Создайте `.env` в корне по сводному блоку ниже и заполните обязательные
значения. Не добавляйте этот файл с секретами в Git.

Приложение будет доступно на `http://localhost` (порт меняется через `APP_PORT`).

Схему БД накатит Flyway при старте бэкенда, отдельного шага миграций не нужно.

### Переменные окружения (.env)

Ниже — сводный справочник для Docker и локального запуска компонентов.
`savefood/.env.example` — отслеживаемый Git шаблон только для Vite; корневой
`.env` для Compose создаётся вручную и хранится локально.

```env
# База данных
POSTGRES_DB=savefood
POSTGRES_USER=postgres
POSTGRES_PASSWORD=strong-password

# Backend (обязательные в Docker Compose)
SECRET_KEY=random-string-64-chars        # openssl rand -base64 64 | tr -d '\n'
KYC_ENCRYPTION_KEY=...                   # openssl rand -base64 32 | tr '+/' '-_' | tr -d '\n'

# Frontend (Vite, передаются при сборке)
VITE_YANDEX_MAPS_API_KEY=your-yandex-maps-key
VITE_YANDEX_SUGGEST_API_KEY=your-geosuggest-key

# Telegram-бот (опционально)
TELEGRAM_BOT_TOKEN=...
TELEGRAM_BOT_NAME=your_bot_username
TELEGRAM_WEBHOOK_SECRET=...              # обязателен: без него вебхук игнорирует апдейты
SITE_URL=https://yourdomain.com
SUPPORT_CHAT_ID=...                      # chat id админа: алерты + эскалации ИИ

# ИИ: помощник в боте, OCR чеков, KYC волонтёров (опционально)
GEMINI_API_KEY=...
AI_MODEL=gemini-2.5-flash
OCR_MODEL=                               # vision-модель для чеков (по умолчанию = AI_MODEL)
KYC_MODEL=                               # vision-модель для удостоверений волонтёров
PHOTO_MODEL=                             # vision-модель для модерации фото доставок (по умолчанию = AI_MODEL)
RECEIPT_MAX_AGE_HOURS=48                 # максимальный возраст чека для антифрода
PHOTO_AUTO_MODERATE=false                # авто-модерация фото ленты: одобрять уверенную еду, отклонять недопустимое
PHOTO_AUTO_APPROVE_SCORE=0.85            # порог скора для авто-одобрения фото

# KYC волонтёров (§58/§58.1)
KYC_OK_THRESHOLD=0.7                     # score ≥ порога → likely_ok → авто-одобрение
KYC_FRAUD_THRESHOLD=0.3                  # score ≤ порога → likely_fraud; решение принимает модератор
                                         # между порогами → review; обе зоны остаются pending
KYC_RETRY_BATCH=100                      # размер пачки для kycRetryTick
KYC_DOC_RETENTION_HOURS=0                # 0 = фоновая очистка ожидающих документов отключена
VOLUNTEER_KYC_REQUIRED=true              # гейт: волонтёр без approved не берёт маршрут

# Мониторинг (опционально)
SENTRY_DSN=                              # ошибки в Sentry; пусто = выключено
SENTRY_ENV=production
SENTRY_TRACES_SAMPLE_RATE=0.05
METRICS_TOKEN=                           # обязателен: GET /metrics требует Authorization: Bearer <token>

# Каталоги загрузок. docker-compose задаёт их явно (тома), но дефолты в
# application.yml всё ещё указывают на `../backend/...` — layout удалённого
# Python-бэкенда. Для локального запуска вне Docker задайте пути сами.
SHOP_UPLOAD_DIR=
RECEIPT_UPLOAD_DIR=
NEEDY_UPLOAD_DIR=                        # только удаление legacy KYC-файлов получателей (V5)
VOLUNTEER_UPLOAD_DIR=
VOLUNTEER_KYC_UPLOAD_DIR=
DELIVERY_PHOTO_UPLOAD_DIR=

# Web Push / VAPID (опционально; без ключей кнопка подписки скрыта)
# Генерация пары: npx web-push generate-vapid-keys
VAPID_PUBLIC_KEY=
VAPID_PRIVATE_KEY=
VAPID_SUBJECT=mailto:admin@example.com

# Push в Android-приложение через FCM (опционально)
FCM_ENABLED=false
FCM_PROJECT_ID=
FCM_CREDENTIALS_FILE=                    # путь к service-account JSON …
FCM_CREDENTIALS_JSON=                    # … либо сам JSON строкой

# Соц-вход (опционально; кнопка появляется, только если задана пара ключей)
GOOGLE_CLIENT_ID=...
GOOGLE_CLIENT_SECRET=...
YANDEX_CLIENT_ID=...
YANDEX_CLIENT_SECRET=...
OAUTH_PUBLIC_URL=                        # база для redirect_uri (по умолчанию SITE_URL)

# Фоновые задачи
# embedded = тики в API-процессе; off = выключено. Ровно один процесс в кластере
# должен быть embedded, иначе тики задвоятся. В docker-compose это backend.
BACKGROUND_TASKS_JAVA=embedded

# Прочее
LOCAL_TZ=Europe/Moscow                   # часовой пояс окон available_time
APP_PORT=80                              # порт nginx на 127.0.0.1 (внешний доступ через Cloudflare Tunnel)
SERVER_PORT=8000                         # порт Java-бэкенда
VLESS_URL=                               # опциональный прокси для Telegram API
XRAY_BINARY=./vendor/xray                # бинарник xray для VLESS-прокси
```

> `docker-compose.yml` передаёт контейнерам только используемые в нём переменные
> и сам задаёт внутренние адреса сервисов, каталоги загрузок и порт бэкенда `8000`
> и `BACKGROUND_TASKS_JAVA=embedded`. Остальные настройки из блока нужны при
> локальном запуске Java-бэкенда либо требуют явного добавления в compose.

Для серверного FCM в Compose проще передать `FCM_CREDENTIALS_JSON`. Значение
`FCM_CREDENTIALS_FILE` должно указывать на файл, который вы отдельно смонтировали
в контейнер. Android-клиент получает push только при наличии
`android-app/app/google-services.json`; без него приложение собирается, но FCM
не инициализируется.

---

## Локальная разработка

### Backend

Требуется JDK 21. Maven ставить не нужно — в репозитории лежит wrapper.

```bash
# база данных
docker run -d --name savefood-pg -v savefood_pgdata:/var/lib/postgresql/data \
  -e POSTGRES_DB=savefood -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 postgres:15-alpine

# сервер (SECRET_KEY обязателен)
cd backend-java
SECRET_KEY=$(openssl rand -base64 64 | tr -d '\n') \
DB_HOST=localhost DB_USER=postgres DB_PASS=postgres DB_NAME=savefood \
./mvnw spring-boot:run
```

Бэкенд использует порт `8000` (`SERVER_PORT`); dev-прокси обращается к нему по
`127.0.0.1:8000`. Flyway накатывает схему на старте сам; на непустой базе
срабатывает baseline-on-migrate и `V1__baseline.sql` не выполняется.

CORS не настраивается: фронт и API ходят через один origin (в dev — прокси Vite,
в проде — nginx). Отдельного `CORS_ORIGIN` в Java-бэкенде нет.

Swagger UI отсутствует — springdoc не подключён. Список эндпоинтов см. ниже
в разделе [API](#api) и в контроллерах `backend-java/src/main/java/ru/savefood/*/`.

### Frontend

```bash
cd savefood
npm ci
npm run dev        # Vite dev-сервер на http://localhost:3000
```

Нужен Node.js `^20.19` или `>=22.12` (требование Vite 8).

В dev-режиме Vite проксирует все API-пути на `http://127.0.0.1:8000` (настраивается через `VITE_API_URL` в `savefood/.env`; пустое значение = относительные пути через прокси).

### Go-микросервис (geows)

Горячие пути — `/ws/needy/{id}` (WebSocket-уведомления) и `/volunteers/{id}/location` — вынесены в Go-сервис. В Docker-развёртке он собирается и маршрутизируется автоматически (nginx). Локально:

```bash
cd go-services/geows
SECRET_KEY=<тот же, что у backend> DB_HOST=localhost DB_USER=postgres \
DB_PASS=postgres DB_NAME=savefood PORT=8001 go run .
```

Inbound WebSocket messages are capped at 4096 bytes by default; set
`WS_MAX_MESSAGE_BYTES` to override the limit when running `geows`.

Чтобы dev-прокси Vite направлял горячие пути в Go, добавьте в `savefood/.env`:
`VITE_GO_URL=http://127.0.0.1:8001` (пусто → эти пути обслуживает Java-бэкенд).

> **Контракт:** nginx направляет GET и PATCH координат в `geows`; оба метода
> читают и пишут общую PostgreSQL, поэтому маршрут не следует расщеплять между
> Java и Go.

### Доступ с другого устройства (Cloudflare Tunnel)

```bash
cloudflared tunnel --url http://localhost:3000   # dev-сервер
# или ./cloudflare-tunnel.sh                     # прод (nginx на :80), URL пишется в ~/savefood-url.txt
```

Случайный хост `*.trycloudflare.com` уже разрешён в `vite.config.js` (`server.allowedHosts`). Quick-туннель живёт, пока работает процесс `cloudflared`; при перезапуске URL меняется.
Скрипт `cloudflare-tunnel.sh` всегда проксирует `localhost:80`, поэтому при
изменённом `APP_PORT` запускайте `cloudflared` вручную с фактическим портом.
Туннель не делает `/metrics` приватным: каждый scrape, включая локальный или
туннельный, обязан передать `Authorization: Bearer <METRICS_TOKEN>`; токен не
передавайте в query-параметрах и не публикуйте.

### Миграции (Flyway)

Схема живёт в `backend-java/src/main/resources/db/migration/` и накатывается
**автоматически при старте бэкенда** (`flyway-core` как зависимость). Отдельных
maven-целей нет — `flyway-maven-plugin` не подключён, так что `./mvnw flyway:*`
не сработает. Что уже применено, смотрите в самой базе:

```bash
psql -d savefood -c \
  'SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank'
```

Изменение схемы = **новый** файл `V{N}__описание.sql`. Уже применённые файлы не
редактировать: Flyway сверяет контрольные суммы и упадёт на расхождении.

> Номер версии должен быть уникален. Две миграции с одним `V{N}` git сливает
> молча (имена файлов разные → конфликта нет), а Flyway затем падает на старте
> с `Found more than one migration with version N`. При слиянии веток проверяйте
> `ls backend-java/src/main/resources/db/migration/` глазами.

---

## Мобильное приложение (Android APK)

Клиент нативный (Kotlin + Jetpack Compose + Hilt), лежит в `android-app/` и
собирается независимо от React-фронтенда.

```bash
cd android-app
./gradlew :app:assembleDevDebug -x lint -PYANDEX_MAPKIT_API_KEY=your-key
```

APK: `android-app/app/build/outputs/apk/dev/debug/app-dev-debug.apk`

Два флейвора по измерению `env` — они отличаются только базовым URL API:

| Флейвор | API по умолчанию | Переопределение |
|---|---|---|
| `dev` | `http://10.0.2.2:8000` (хост из эмулятора) | `-PdevApiBaseUrl=https://…` |
| `prod` | `https://api.savefood.kz` | `-PprodApiBaseUrl=https://…` |

Например, для сборки на физическое устройство через туннель:

```bash
./gradlew :app:assembleDevDebug -PdevApiBaseUrl=https://your-tunnel.trycloudflare.com
```

**Требования:** JDK 21, Android SDK 36 (`compileSdk`/`targetSdk` = 36, `minSdk` = 26).
Без `YANDEX_MAPKIT_API_KEY` приложение собирается, но карты отключаются. Dev URL
`:8000` рассчитан на локальный Spring Boot; в Docker Compose наружу опубликован
nginx на `127.0.0.1:${APP_PORT:-80}`.
Для FCM положите `google-services.json` в `android-app/app/`; без файла сборка
остаётся рабочей, но Firebase и push-уведомления не активируются.
Если системного JDK нет, подойдёт тот, что идёт с Android Studio:
`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.

### SSL pinning перед релизом

Сейчас `android-app/app/src/main/res/xml/network_security_config.xml` **запрещает
cleartext, но пин-сета ещё не содержит** — его добавляют, когда известны боевой
домен и сертификат. У флейвора `dev` свой оверрайд в `android-app/app/src/dev/res/xml/`,
разрешающий cleartext на `10.0.2.2` и `localhost`; в `prod`-сборку он не попадает.

Перед релизом добавьте в `main`-конфиг `<pin-set>` с реальными SHA-256 SPKI-пинами:

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
| **Частный донор** | Публикация лота в базовой модели; фотография продуктов обязательна |
| **Волонтёр** | Карта лотов, маршруты с 2-opt-оптимизацией и навигацией, GPS-верификация, QR-сканер, статистика и достижения (бейджи) |
| **Получатель** | Просмотр лотов на карте, заявки (доставка/самовывоз), трекинг волонтёра, оценка доставки 1–5 ★ |
| **Администратор** | KYC-модерация волонтёров, диспетчерская, управление пользователями и тарифами магазинов, аналитика + ESG платформы, audit log |

Публичной регистрации администратора и seed-аккаунта в миграциях нет. Первого
администратора нужно создать доверенным операционным способом, сохранив в БД
только bcrypt-хеш пароля; дальше вход выполняется через обычную страницу логина.

---

## Регистрация получателя

Регистрация получателя — восстанавливаемый сценарий из двух шагов:

1. Фронтенд создаёт учётную запись через `POST /needy/register` и автоматически входит.
2. Пользователь сразу заполняет профиль через `POST /needy/{id}/profile` и переходит
   к функциям получателя. Документы, KYC-статусы и ожидание модератора отсутствуют.

Сценарий и защита от дублей покрыты тестами `savefood/src/Pages/Auth/AuthPage.test.jsx`.

---

## Архитектура и структура проекта

```
savefood/
├── backend-java/                    # Spring Boot приложение (Java 21)
│   ├── src/main/java/ru/savefood/
│   │   ├── SaveFoodApplication.java # Точка входа
│   │   ├── auth/                    # Логин, OAuth, привязка аккаунтов
│   │   ├── security/                # JWT, фильтры, @Admin, резолверы
│   │   ├── web/                     # ApiException, ClientIp, обработчик ошибок
│   │   ├── shop/                    # Магазины: лоты, чеки, самовывоз
│   │   ├── volunteer/               # Волонтёры: маршруты, 2-opt, GPS, доступность
│   │   ├── needy/                   # Получатели: заявки, профиль, WebSocket
│   │   ├── admin/                   # Админка: модерация, диспетчер, тарифы
│   │   ├── match/                   # Отбор заявок по приоритетному score
│   │   ├── background/              # Фоновые тики (expire / reassign / antifraud / TTL)
│   │   ├── ai/ receipt/ kyc/ photo/ # Gemini: помощник, OCR, Auto-KYC, модерация фото
│   │   ├── billing/ esg/            # Тарифы и квоты; CO₂-методология
│   │   ├── telegram/                # Вебхук бота + исходящие уведомления
│   │   ├── push/ webhook/           # Web Push, FCM; исходящие вебхуки партнёров
│   │   ├── chat/                    # Чат волонтёр ↔ получатель (§53)
│   │   ├── cache/ audit/ monitoring/# Caffeine-кэш, audit log, /metrics
│   │   └── util/                    # Geo, Clamp, Html, FoodCategories
│   ├── src/main/resources/
│   │   ├── application.yml          # Вся конфигурация и ENV-плейсхолдеры
│   │   └── db/migration/            # Flyway: V1__baseline → V2 → V3 …
│   ├── src/test/java/ru/savefood/   # JUnit: юниты + it/ (Testcontainers)
│   └── Dockerfile
│
├── savefood/                        # React приложение (Vite)
│   ├── src/
│   │   ├── Pages/                   # Shop, Volunteer, Needy, Admin, Auth, About
│   │   ├── components/              # EmptyState, ProtectedRoute
│   │   ├── context/                 # AuthContext
│   │   ├── i18n/                    # Переводы ru/en
│   │   ├── utils/                   # geo.js (haversine) и прочее
│   │   └── api.js                   # API_URL (VITE_API_URL)
│   ├── nginx.conf                   # Прод-прокси (rate limit, WS, security headers)
│   ├── vite.config.js               # Dev-прокси, allowedHosts для туннеля
│   └── Dockerfile
│
├── android-app/                     # Нативный Android (Kotlin + Compose + Hilt)
│   └── app/src/main/java/ru/savefood/app/
│       ├── core/                    # designsystem, datastore, device (камера, QR, GPS)
│       └── feature/                 # Экраны по ролям
│
├── go-services/
│   └── geows/                       # Go-микросервис: WS-фанаут, геокоординаты
│       ├── main.go                  # hub с общим поллером БД, JWT (общий SECRET_KEY)
│       └── Dockerfile               # multi-stage, ~10 MB образ
│
├── scripts/                         # db_backup.sh / db_restore.sh / DEPLOY.md
├── .github/workflows/ci.yml
├── docker-compose.yml
└── cloudflare-tunnel.sh             # Quick tunnel для внешнего доступа
```

---

## API

Основные эндпоинты:

| Метод | Путь | Описание |
|---|---|---|
| `POST` | `/auth/login` | Вход (OAuth2 PasswordFlow), 5 req/min |
| `GET` | `/auth/oauth/{google\|yandex}/start` | Соц-вход / привязка (authorization code) |
| `POST` | `/auth/telegram/login/start`, `/poll`, `/complete`, `/cancel` | Вход через Telegram (deep-link + личная одноразовая ссылка) |
| `GET` | `/auth/links` | Статус привязок Telegram / Google / Yandex |
| `POST` | `/shops/register` | Регистрация магазина (атомарно с учёткой) |
| `POST` | `/volunteers/register` | Регистрация волонтёра |
| `POST` | `/needy/register` | Регистрация получателя |
| `GET` | `/needy/{id}` | Учётная запись получателя (owner/admin) |
| `POST` | `/needy/{id}/profile` | Создать профиль получателя (owner/admin) |
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
| `GET` | `/tickets/{id}/messages` | Чат волонтёр ↔ получатель (§53) |
| `POST` | `/tickets/{id}/messages` | Отправить сообщение в чат |
| `GET` | `/admin/volunteers?status=pending` | Очередь модерации волонтёров |
| `PATCH` | `/admin/volunteers/{id}/moderation` | Ручное решение по KYC волонтёра |
| `GET` | `/admin/stats` | Статистика платформы |
| `GET` | `/admin/esg` | ESG платформы + топ-10 магазинов |
| `PATCH` | `/admin/shops/{id}/plan` | Сменить тариф магазина (audit log) |
| `POST` | `/telegram/webhook` | Входящие апдейты бота (сверяет secret-токен) |
| `WS` | `/ws/needy/{id}` | WebSocket уведомлений (auth первым сообщением) |
| `GET` | `/healthz`, `/readyz` | Liveness / readiness |
| `GET` | `/metrics` | Prometheus; обязателен `Authorization: Bearer <METRICS_TOKEN>` |

Swagger UI нет (springdoc не подключён) — источник правды по контракту это
контроллеры и DTO в `backend-java/src/main/java/ru/savefood/*/`.

---

## Социальный вход и привязка аккаунтов

- **Профиль** каждой роли содержит блок «Привязанные аккаунты»: Telegram, Google, Яндекс (привязать/отвязать)
- **Вход**: кнопки на странице логина. Google/Яндекс — стандартный OAuth (authorization code, серверный обмен); redirect URI `<OAUTH_PUBLIC_URL>/auth/oauth/<provider>/callback` нужно зарегистрировать в консоли провайдера
- **Telegram** — без Login Widget: браузер создаёт status-only deep-link, а после подтверждения бот присылает в личный чат отдельную короткоживущую одноразовую ссылку завершения входа; исходный браузерный токен JWT не выдаёт
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

### Получение апдейтов

Бот принимает апдейты вебхуком на `POST /telegram/webhook`. Эндпоинт сверяет
`X-Telegram-Bot-Api-Secret-Token` с `TELEGRAM_WEBHOOK_SECRET`; пока секрет не
задан, все апдейты игнорируются. Зарегистрировать вебхук:

```bash
curl "https://api.telegram.org/bot<TOKEN>/setWebhook?url=https://yourdomain.com/telegram/webhook&secret_token=<TELEGRAM_WEBHOOK_SECRET>"
```

---

## Особенности безопасности

- **KYC применяется только к волонтёрам**: удостоверения приватны и шифруются at-rest (`KYC_ENCRYPTION_KEY`); получатели документы не загружают и модерацию не проходят. Legacy-файлы получателей ставятся V5 в durable-очередь удаления и повторяются до успеха
- Пароли хранятся только в виде bcrypt-хеша
- JWT содержит `role` и `related_id` — каждый пользователь видит только свои данные (`ensure_owner_or_admin` на всех приватных эндпоинтах)
- Подтверждение доставки: QR-код и GPS-радиус 100 м проверяются **на сервере**
- Rate limiting на `/auth/login` и `/register`: 5 запросов в минуту с одного IP (за Cloudflare Tunnel клиент определяется по `CF-Connecting-IP`)
- **Антифрод (волонтёры)**: если волонтёр взял лот и удаляется от магазина — авто-пинг «Всё в порядке?», при отсутствии реакции маршрут снимается и еда возвращается на витрину
- **Антифрод (чеки)**: дубликаты по sha256 и fingerprint (магазин+дата+сумма), проверка свежести даты, ИИ-оценка подлинности фото; `fraud_score ≥ 0.7` блокирует создание лотов
- **Auto-KYC волонтёров**: ИИ предпроверяет удостоверения (тип, ФИО, следы редактирования). Уверенный `likely_ok` может быть одобрен автоматически; остальные результаты остаются в волонтёрской очереди
- Заблокированный админом пользователь теряет доступ немедленно (проверка на каждом запросе)
- **Транспорт (Android):** cleartext заблокирован в `main`-конфиге; исключение для `10.0.2.2`/`localhost` живёт только во флейворе `dev` и в `prod`-сборку не попадает. SSL pinning **ещё не настроен** — `<pin-set>` добавляется, когда будут известны боевой домен и сертификат (см. раздел выше)

---

## Тестирование

**Бэкенд** — JUnit, без БД (чистая логика: 2-opt-маршрутизация, окна `available_time`,
приоритетный score, ESG-математика, антифрод чеков, биллинг-гейтинг, уровни
геймификации, JWT/пароли, KYC волонтёров, QR, грузоподъёмность):

```bash
cd backend-java
./mvnw -B clean test
```

Интеграционные тесты (Testcontainers, нужен запущенный Docker) — отдельный профиль:

```bash
./mvnw -B -Pintegration verify
```

Если Testcontainers не поднимается (Docker Desktop отвергает запрошенную
API-версию, ошибка маскируется под «Could not find a valid Docker environment»),
можно подсунуть внешнюю базу: `SAVEFOOD_IT_JDBC_URL=jdbc:postgresql://localhost:5432/savefood_it`.
Логин и пароль задаются через `SAVEFOOD_IT_DB_USER` и `SAVEFOOD_IT_DB_PASS`.

> **Внимание:** интеграционный набор перед каждым тестом выполняет
> `DROP SCHEMA public CASCADE`. Указывайте только отдельную одноразовую БД —
> никогда не dev- или production-базу с нужными данными.

**Фронтенд** — Vitest: регистрация получателя без KYC, адресные сценарии,
валидация геолокации доставки, персонализация лендинга, локализация и геоутилиты:

```bash
cd savefood && npm test
```

**Go-сервис**:

```bash
cd go-services/geows && go test ./...
```

В CI для `geows` сейчас выполняются `go build` и `go vet`; тесты запускайте
локально до отправки изменений.

**CI** — GitHub Actions (`.github/workflows/ci.yml`), джобы запускаются по путям
изменений: `backend-tests`, `backend-integration`, `frontend-build` (тесты +
production-сборка), `android-build` (`:app:assembleDevDebug`), `geows-build`
(`go build` / `go vet`).

Дополнительно проект предусматривает сквозной runtime-смоук против живого
сервера: регистрации (включая негативные 401/403/409/422), модерация, заявки,
маршрут с QR/GPS-проверками, самовывоз, рейтинг, ачивки и антифрод-цикл.

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
- Задан ли `TELEGRAM_WEBHOOK_SECRET` и зарегистрирован ли вебхук через `setWebhook` — без секрета `POST /telegram/webhook` молча отбрасывает все апдейты
- ИИ-ответы требуют `GEMINI_API_KEY`; без него вопросы эскалируются в `SUPPORT_CHAT_ID`

**OCR чеков возвращает 503 / KYC волонтёра остаётся «unchecked»**
- Проверьте `GEMINI_API_KEY` — без него OCR отключён, а удостоверения волонтёров требуют ручной модерации
- Кнопка OCR требует тариф «Профи»+: ответ 402 означает, что магазину нужно сменить тариф (админ → вкладка «Тарифы»)

**Проблемы при сборке APK**
- Требуется JDK 21: `java -version`. Если системного нет — `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`
- После переименования пакетов или смены JDK чистите кеш: `./gradlew clean` — Hilt и KSP кешируют сгенерированный код и продолжают ссылаться на старые классы
- Собирайте конкретный вариант: `./gradlew :app:assembleDevDebug -x lint`

**`./mvnw` или `./gradlew`: permission denied**
- У враппера потерян бит исполнения: `git update-index --chmod=+x backend-java/mvnw android-app/gradlew`

---

## Эксплуатация

```bash
docker compose ps
docker compose logs -f backend
curl -fsS http://localhost/healthz
curl -fsS http://localhost/readyz
```

Резервное копирование PostgreSQL запускается через `scripts/db_backup.sh`.
Скрипт не сохраняет Docker-тома с загруженными фото и документами — нужные тома
архивируйте отдельно. Восстановление защищено явным подтверждением `CONFIRM=yes`;
перед ним остановите запись данных и проверьте выбранный архив. Текущий
deploy-процесс ручной и не обещает zero-downtime.

---

## Вклад

Если вы хотите помочь развитию проекта:

1. Fork репозитория
2. Создайте ветку для вашей функции (`git checkout -b feature/new-feature`)
3. Делайте коммиты с чёткими сообщениями
4. Push в вашу ветку
5. Откройте Pull Request с описанием изменений

Перед отправкой убедитесь:
- Тесты проходят: `./mvnw -B clean test` в `backend-java` и `npm test` в `savefood`
- Переводы добавлены для **обоих** языков (ru/en) — паритет ключей проверяется тестом
- При изменении схемы БД добавлен **новый** файл `V{N}__описание.sql` в
  `backend-java/src/main/resources/db/migration/` с уникальным номером версии.
  Уже применённые миграции не редактировать — Flyway сверяет контрольные суммы

---

## Контакты и поддержка

- **Email**: igel2020i@gmail.com
- **Telegram**: @savefood_bot
- **Issues**: [GitHub Issues](https://github.com/z7gvt9mys9-dev/savefood/issues)

---

## Лицензия

Лицензия пока не опубликована отдельным файлом. До её добавления используйте
код только с разрешения владельца репозитория.
