# SaveFood

Платформа спасения еды — соединяет магазины с излишками, волонтёров и нуждающихся людей в единую систему распределения продуктов.

---

## Как это работает

```
Магазин публикует лот → Волонтёр берёт маршрут → Нуждающийся получает продукты
```

1. Магазин добавляет лот с продуктами, у которых истекает срок годности
2. Волонтёр видит лоты на карте, берёт маршрут
3. Едет в магазин, забирает еду, доставляет получателю
4. Получатель подтверждает получение QR-кодом

---

## Стек

| Слой | Технология |
|---|---|
| Backend | Python 3.11, FastAPI, PostgreSQL 15 |
| Frontend | React 18, react-router-dom v7 |
| Карты | Yandex Maps + Geosuggest/Геокодер (подсказки адресов) |
| i18n | react-i18next (ru / kk / en) |
| Мобильное приложение | Capacitor 8 (Android / iOS) |
| Уведомления | Telegram Bot (aiogram 3.x), WebSocket |
| Авторизация | JWT HS256, bcrypt |
| Деплой | Docker Compose, Nginx |

---

## Быстрый старт

### Требования

- Docker и Docker Compose
- Yandex Maps API Key
- Telegram Bot Token (опционально)

### Запуск

```bash
git clone https://github.com/your-username/savefood.git
cd savefood

cp .env.example .env
# Заполнить .env (см. ниже)

docker compose up -d --build
```

Приложение будет доступно на `http://localhost`.

### Переменные окружения (.env)

```env
SECRET_KEY=your-secret-key-here
DATABASE_URL=postgresql://savefood:savefood@postgres:5432/savefood

REACT_APP_YANDEX_MAPS_API_KEY=your-yandex-maps-key
REACT_APP_API_URL=

TELEGRAM_BOT_TOKEN=your-telegram-bot-token
TELEGRAM_BOT_NAME=your_bot_username
SITE_URL=https://yourdomain.com

SUPPORT_CHAT_ID=your-telegram-chat-id
CORS_ORIGINS=http://localhost,https://yourdomain.com
```

---

## Локальная разработка

### Backend

```bash
cd backend
python -m venv venv
source venv/bin/activate
pip install -r requirements.txt

# Запустить базу данных
docker compose up postgres -d

# Запустить сервер
uvicorn main:app --reload --port 8000
```

API документация: `http://localhost:8000/docs`

### Frontend

```bash
cd savefood
npm install
npm start
```

Приложение: `http://localhost:3000`

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

---

## Роли

| Роль | Возможности |
|---|---|
| **Магазин** | Создание лотов с фото и категорией, подтверждение передачи, история, Telegram-уведомления |
| **Волонтёр** | Карта лотов, маршруты с навигацией, GPS-верификация, QR-сканер, статистика |
| **Получатель** | Просмотр лотов на карте, заявки, трекинг волонтёра, оценка доставки |
| **Администратор** | Модерация заявок, диспетчерская, управление пользователями, аналитика, audit log |

---

## Структура проекта

```
savefood/
├── backend/                 # FastAPI приложение
│   ├── main.py              # Точка входа, lifespan, middleware
│   ├── auth.py              # JWT, авторизация
│   ├── auth_routes.py       # /auth/login, /auth/telegram
│   ├── shop/                # Роуты, БД, схемы магазинов
│   ├── volunteer/           # Роуты, БД, схемы волонтёров
│   ├── needy/               # Роуты, БД, схемы получателей
│   ├── admin/               # Роуты администратора
│   ├── telegram_routes.py   # Webhook бота
│   ├── telegram_service.py  # Отправка уведомлений
│   └── requirements.txt
│
├── savefood/                # React приложение
│   ├── src/
│   │   ├── Pages/           # Shop, Volunteer, Needy, Admin, Auth, About
│   │   ├── components/      # EmptyState и другие общие компоненты
│   │   ├── context/         # AuthContext
│   │   ├── i18n/            # Переводы ru/kk/en
│   │   └── api.js           # API_URL
│   ├── android/             # Capacitor Android проект
│   ├── capacitor.config.ts
│   └── package.json
│
├── migrations/              # Alembic миграции
├── docker-compose.yml
├── Dockerfile.backend
└── .env.example
```

---

## API

Основные эндпоинты:

| Метод | Путь | Описание |
|---|---|---|
| `POST` | `/auth/login` | Вход (OAuth2 PasswordFlow) |
| `POST` | `/shops/register` | Регистрация магазина |
| `POST` | `/volunteers/register` | Регистрация волонтёра |
| `POST` | `/needy/register` | Регистрация получателя |
| `GET` | `/lots` | Список активных лотов (с фильтром по категории, городу, поиску) |
| `POST` | `/shops/{id}/lots` | Создать лот (multipart с фото) |
| `POST` | `/volunteers/{id}/start_route` | Взять маршрут |
| `POST` | `/volunteers/route/{id}/complete_point` | Отметить точку выполненной |
| `POST` | `/volunteers/route/{id}/finish` | Завершить маршрут |
| `GET` | `/volunteers/map` | Карта лотов для волонтёра (с геофенсом) |
| `PATCH` | `/volunteers/{id}/location` | Обновить локацию волонтёра |
| `POST` | `/needy/{id}/ticket` | Создать заявку |
| `PATCH` | `/needy/{id}/moderation` | Одобрить/отклонить (admin) |
| `GET` | `/admin/stats` | Статистика платформы |
| `WS` | `/ws/needy/{id}` | WebSocket уведомлений |

Полная документация: `/docs` (Swagger UI)

---

## Telegram-бот

Бот выполняет две функции:

1. **Уведомления** — волонтёр взял лот, волонтёр в пути (с ETA), попытка доставки
2. **Чат** — сообщения между волонтёром и нуждающимся в активном маршруте

### Привязка аккаунта

```
Профиль → «Подключить Telegram» → deep-link → /start link_<token>
```

### Установка webhook

```bash
curl "https://api.telegram.org/bot<TOKEN>/setWebhook?url=https://yourdomain.com/telegram/webhook"
```

---

## Особенности безопасности

- Документы нуждающихся удаляются сразу после модерации
- Пароли хранятся только в виде bcrypt-хеша
- JWT содержит `role` и `related_id` — каждый пользователь видит только свои данные
- Rate limiting на `/auth/login`: 5 запросов в минуту с одного IP
- Все admin-эндпоинты защищены проверкой роли

---

---

## Тестирование

### Backend

```bash
cd backend
pytest tests/ -v
```

### Frontend

```bash
cd savefood
npm test
```

---

## Устранение неполадок

**Ошибка подключения к БД**
- Убедитесь, что контейнер PostgreSQL запущен: `docker ps`
- Проверьте переменные в `.env`

**Карта не загружается**
- Проверьте `REACT_APP_YANDEX_MAPS_API_KEY` в `.env`
- API ключ должен быть активным в консоли Yandex Cloud

**Telegram-бот не отправляет сообщения**
- Убедитесь, что `TELEGRAM_BOT_TOKEN` заполнен
- Webhook настроен правильно (см. раздел "Telegram-бот")
- Проверьте CORS и порт в конфиге Nginx

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
- Код работает локально и тесты проходят
- Переводы добавлены для всех трёх языков (ru/kk/en)
- API документация обновлена (если нужно)

---

## Контакты и поддержка

- **Email**: igel2020i@gmail.com
- **Telegram**: @savefood_bot
- **Issues**: [GitHub Issues](https://github.com/your-username/savefood/issues)

---

## Лицензия

MIT
