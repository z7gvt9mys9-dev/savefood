# SaveFood — ревью логики волны Java-миграции (вопросы для гриллинга)

Дата ревью: 2026-07-02. Скоуп: волна `077c871..7572dd5` (clamp → Java-миграция
`c6518e5` → юнит-тесты/CI → Geo-рефакторинг), сверка с аудированным Python-состоянием
`c6518e5^` и спецификацией savefood.md §56–59. Планка — заявленная в §60 цель
«wire-совместимо, 1:1»: любое смысловое расхождение = либо баг порта, либо
недокументированное улучшение, требующее фиксации.
(Прошлое ревью 2026-06-18 закрыто и заархивировано в savefood.md §59.)

**Как пользоваться:** под каждым вопросом есть строка `Ответ:` — пиши туда.
Вопросы отсортированы по серьёзности.

---

## Что проверено и признано верным портом (НЕ требует обсуждения)

- `RouteRevertService` = `background.revert_route_lot` дословно: лот ПЕРВЫМ,
  ветка по rowcount, пересчёт quantity из `initial_quantity − SUM(open/assigned/fulfilled)`,
  cancel+notify при мёртвом лоте (Q4/Q12).
- `MaintenanceTasks`: reassign 90/240 мин, per-route транзакция = SAVEPOINT-изоляция,
  antifraud (15/15/300 м), reservation-TTL, expire (Q5: cancel open-броней), KYC-retry/retention.
  В docker-compose `BACKGROUND_TASKS_JAVA=embedded` установлен — планировщик реально включён.
- `NeedyService.createTicket`: недельный лимит, one-active-ticket (+гонка через
  `uq_tickets_one_active_per_needy`), атомарный резерв `quantity>=1`, TTL 2ч/48ч.
  `cancelTicket`: guarded-возврат юнита, снятие точки с маршрута.
- `startRoute`: §59/Q1-A `windowOpenWithin(120)`, §59/Q2 default=`ROUTE_HARD_CAP=20`,
  §59/Q3 веса score (1.5/1/2/3/3), displaced_count +1 при вытеснении, отмена
  self-pickup и overflow-тикетов с guarded-возвратом, KYC-гейт §58 и
  проверка активного маршрута — в контроллере.
- `complete_point`/`attempt_delivery`/`finish_route`/`confirm_lot_transfer`/`delete_lot`/
  `confirm_self_pickup` — семантика и guard'ы совпадают (кроме Вопроса 1).
- `Geo.haversineMeters` (7572dd5): формула = utils.py, единицы согласованы
  (VolunteerService — км, MaintenanceTasks/NeedsMatch — метры, как в Python).
- CI path-filter: skip засчитывается как success для required checks (документированный
  паттерн dorny), каждый фильтр включает сам workflow-файл, main не отменяется.

---

## Вопрос 1 — displaced_count не сбрасывается при доставке волонтёром; недельный лимит дырявый без profile-строки
**Где:** `backend-java/.../volunteer/VolunteerService.java:365` (completePoint) против
`NeedyService.setProfileLastReceived` (:385, корректный порт) и Python
`needy_db.set_profile_last_received` (использовался в `complete_point:815`).

**Дыра:** порт заинлайнил голый `UPDATE needy_profile SET last_received_at = ?` вместо
вызова хелпера. Потеряно ДВА свойства:
1. **§59/Q1-C сломан наполовину:** `displaced_count = 0` при выдаче не выполняется на
   пути «доставка волонтёром» (основной путь!). Счётчик вытеснений растёт монотонно,
   бонус +3.0×N становится пожизненным — однажды вытесненный получатель навсегда
   поднимается над очередью. Q1-C задумывался как разовая компенсация, не вечная рента.
   (Self-pickup путь через `ShopService:132` — корректен.)
2. **§3.2 недельный лимит обходим:** без INSERT-fallback у получателя БЕЗ строки
   `needy_profile` `last_received_at` вообще не записывается → лимит «раз в 7 дней»
   (читается из needy_profile в `createTicket`) для него не действует после доставок
   волонтёром.

**Моя рекомендация:** одна строка — в `completePoint` вызывать
`needyService.setProfileLastReceived(needyId, now)` (сохранив best-effort try/catch,
как в Python). Плюс интеграционный тест на «fulfil сбрасывает displaced_count»
(он был в удалённом `tests/test_orphan_and_ttl.py`).

**Согласен чинить так? Или у отказа от сброса была причина, которую я не вижу?**

Ответ: **Подтверждено — баг порта, не решение. Починено 2026-07-03:** `completePoint`
теперь вызывает `needyService.setProfileLastReceived(...)` (инъекция `NeedyService` в
`VolunteerService`, конструкторы тестов обновлены). `./mvnw clean test` — 79/79 зелёные.
Интеграционный тест на «fulfil сбрасывает displaced_count» — в рамках Вопроса 4.

---

## Вопрос 2 — Telegram/Web-Push/FCM для доменных событий молча выключены удалением Python
**Где:** `VolunteerService`/`NeedyService`/`ShopService`/`NeedsMatchService`/`KycService`/чат —
ни один не вызывает `TelegramService`; вызывают только `MaintenanceTasks` и OAuth.
Javadoc'и и README честно писали «fan-out остаётся на Python-нотификаторе на время
миграции» — но тот же коммит `c6518e5` удалил Python целиком.

**Дыра:** конфликт нового решения со старым в чистом виде: осознанный компромисс
(«внешние каналы временно на Python») превращён в молчаливую потерю функциональности.
Пропали: пинг магазину «волонтёр забрал лот», `volunteer_assigned`/`en_route`
получателю (§12), рассылка «карты потребностей» о новом подходящем лоте (§35.1) вместе
с гео-подпиской Web Push (§48 — фича теперь мертва), уведомления о вердикте KYC, пинги
чата. In-app-строки и WS-фанаут (geows) живы, но получатель, не открывший приложение,
не узнаёт ничего — а это целевая аудитория Telegram-канала.
Инфраструктура при этом ПОЛНОСТЬЮ портирована: `TelegramService.notify*` шлёт Telegram
и зеркалит в `PushDispatchService` (VAPID + FCM, честный порт). Не хватает только
вызовов из доменных сервисов.

**Моя рекомендация:** восстановить все Python-точки вызова (`git grep telegram_service
c6518e5^` даёт список: volunteer/routes, needy/routes, needs_match, kyc_service,
chat_routes), в семантике Python: best-effort, вне транзакции / после коммита
(в Spring — из контроллера после сервиса, как уже сделано с webhook `lot.taken`).
Проверить §48: `geo_push_enabled` должен снова фильтровать push-цели в NeedsMatch.

**Подтверждаешь восстановление? Или внешние каналы сознательно выключены (тогда это надо записать в savefood.md и выпилить мёртвый §48-тумблер)?**

Ответ: **Подтверждено — восстанавливаем. Сделано 2026-07-03**, все точки из Python-инвентаря:
`start_route` (магазину + каждому назначенному, через контроллер после коммита, HTML-escape
имён — новый `util/Html`), `complete_point` en-route, `attempt_delivery`, `cancel_ticket`
(сервис возвращает volId, контроллер шлёт после транзакции), `NeedsMatchService`
(Telegram всем совпавшим + §48-фильтрованный Web Push по `geo_push_enabled` — колонка
снова читается), `KycService` (4 вердикта ✅/⚠️), чат (Telegram + push обеим сторонам).
Все javadoc'и «stays with the Python notifier» переписаны на фактическое поведение.
`./mvnw clean test` — 79/79. Замечание для протокола: как и в Python,
`TelegramService.notify*` зеркалит КАЖДОЕ сообщение в Web Push независимо от §48-тумблера
(тумблер гейтит только дополнительный таргетированный push в needs_match) — это
унаследованное поведение Python, сохранено 1:1.

---

## Вопрос 3 — у Java-стека нет механизма схемы БД: fresh deploy и будущие миграции сломаны
**Где:** корневые `alembic.ini` + `migrations/versions/0001–0014` (Python/alembic —
удалены из рантайма), Python `database.py`/`*_db.py` c `CREATE TABLE`/`ALTER TABLE IF
NOT EXISTS` при старте — удалены; в `backend-java` ни одного DDL-оператора, в
docker-compose нет migration-шага.

**Дыра:** существующая БД работает (схема уже накачена), поэтому дыра невидима до
первого fresh environment (dev-машина коллеги, staging, DR-восстановление) — там
Java-бэкенд упадёт на первом запросе к несуществующим таблицам. Хуже: СЛЕДУЮЩАЯ фича
с новой колонкой (аналог `0014_needy_displaced_count`) не имеет механизма доставки
схемы вообще. Ревью затевалось ради «новые решения не конфликтуют со старыми» — это
решение конфликтует со ВСЕМИ будущими.

**Моя рекомендация:** Flyway в backend-java (стандарт Spring Boot, нулевой код):
`V1__baseline.sql` = дамп текущей схемы (`pg_dump --schema-only`), помеченный
baseline'ом для существующих БД (`flyway.baseline-on-migrate=true`), дальше
V2, V3… вместо alembic. Каталог `migrations/` и `alembic.ini` — в архив/удалить
(история остаётся в git). Альтернатива-минимум: один init.sql в
`docker-entrypoint-initdb.d` Postgres-контейнера — закрывает fresh deploy, но не
даёт эволюции схемы; не рекомендую.

**Flyway? И когда — до следующей фичи с миграцией или прямо в этой волне фиксов?**

Ответ: **Flyway, конфиг сейчас, baseline с живой БД. Сделано 2026-07-03:**
`flyway-core` + `flyway-database-postgresql` в pom.xml; в application.yml
`baseline-on-migrate: true`, `baseline-version: 1` (прод при первом старте помечается,
V1 не выполняется; пустая БД выполняет V1). Инструкция генерации
`V1__baseline.sql` (`pg_dump --schema-only …`) — в
`backend-java/src/main/resources/db/migration/README.md`. 79/79 зелёные.
**Открытый шаг на деплой-хосте:** выполнить pg_dump-команду из README против живой БД
и закоммитить `V1__baseline.sql` — до этого fresh-окружения всё ещё не поднимаются.
`migrations/` + `alembic.ini` удаляем при закрытии ревью (Вопрос 5, вместе с docs).

---

## Вопрос 4 — покрытие аудированных потоков упало со 184 до 79 тестов; корень tests/ — мёртвый груз
**Где:** удалённые `tests/test_background.py` (Q4/Q9), `tests/test_orphan_and_ttl.py`
(Q5/Q12/Q1-C/Q3), `tests/test_routing.py` (Q1-A) и весь Python-суит; Java-суит —
14 файлов / 79 чистых юнит-тестов (без БД и Spring-контекста, CI сам это декларирует:
«pure logic only»). Корневой каталог `tests/` при этом остался в репо и импортирует
несуществующий `backend.*` — не запускается ничем.

**Дыра:** все гарантии §56–59 (ветвление revert, отмена осиротевших броней, сброс
displaced, TTL, таймауты) сейчас НЕ проверяются ни одним исполняемым тестом — а это
ровно та логика, где три аудита находили дыры. Вопрос 1 — прямое следствие: тест
`test_orphan_and_ttl` его бы поймал. Юнит-тесты волны 7572dd5 хороши, но покрывают
хелперы, не транзакционные цепочки.

**Моя рекомендация:** портировать три аудит-файла как интеграционные тесты на
Testcontainers-Postgres (отдельный maven-профиль/CI-job, чтобы не терять «без БД»
скорость юнит-джоба); удалить корневой `tests/` (история в git). Порядок: сначала
фикс Вопроса 1, тесты — следом в той же волне.

**ОК? Или интеграционный слой отложим (тогда фиксируем осознанный риск в savefood.md)?**

Ответ: **Согласовано 2026-07-03: Testcontainers-порт трёх аудит-файлов — следующий шаг
(отдельная сессия; заведена задача-чип), корневой `tests/` удалён сейчас** (29 файлов,
история в git на `c6518e5^`). Обязательное покрытие в порте: «fulfil сбрасывает
displaced_count» (регресс Вопроса 1), ветвление revert, TTL, orphan-cancel, окно Q1-A.

---

## Вопрос 5 — документация противоречит репозиторию: §60 описывает несуществующую реальность
**Где:** savefood.md §60 («переключение НЕ выполнено намеренно… Python остаётся
источником истины», описаны только шаги 1–3 admin/shop/needy); CLAUDE.md («Backend:
FastAPI»); backend-java/README + javadoc'и («на время миграции fan-out/worker на
Python»); комментарий compose обещает «нотификатор/пуш порт» (неправда — см. Вопрос 2).

**Дыра:** CLAUDE.md обязывает сверяться с savefood.md перед любой фичей — а savefood.md
врёт о самом фундаментальном факте (какой бэкенд живой). Следующая сессия/разработчик
примет решения на ложной базе (например, «Python источник истины — правлю его»).
Волонтёрский/auth/chat/partner/push/impact-модули портированы вообще без записи в роадмапе.

**Моя рекомендация:** дописать в §60 «Шаг 4 — полная замена» (все 7 модулей, ticks,
нотификатор, удаление Python, compose/nginx перенаправлены, дата 2026-06-21) +
честно записать текущие пробелы (Вопросы 1–4) как открытые пункты; CLAUDE.md →
«Backend: Java 21 / Spring Boot 3.3»; вычистить «during the migration» из README/javadoc.
Обновление — вместе с закрытием этого ревью (как §59 сделал с прошлым).

**Так?**

Ответ:

---

## Мелочи (фиксирую, отдельного вопроса не требуют — поправлю вместе с волной фиксов, если не возразишь)

- `isAvailableNow` (Java) сравнивает минуты, Python сравнивал с секундами: окно
  «…-17:00» в Java открыто до 17:00:59. Расхождение ≤1 мин, в пользу получателя.
- `daysSince`: наивные timestamp'ы (`LocalDateTime`) трактуются как UTC — совпадает
  с `ensure_aware_utc`; колонки timestamptz, путь мёртвый. Не трогаю.
- `attemptDelivery` без `@Transactional` — это паритет (Python писал отдельными
  курсорами), не баг.
