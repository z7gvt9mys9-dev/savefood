# Схема БД: Flyway-миграции

Этот каталог — единственный источник схемы для Java-бэкенда (заменяет удалённую
alembic-цепочку `migrations/versions/0001–0014` и стартовый DDL Python `init_db`;
их история осталась в git до коммита `c6518e5^`).

## Как сгенерировать baseline (одноразовый шаг, выполняется на хосте с живой БД)

```bash
pg_dump --schema-only --no-owner --no-privileges \
  --exclude-table=flyway_schema_history \
  -h <DB_HOST> -U <DB_USER> -d savefood > V1__baseline.sql
```

Положи результат сюда как `V1__baseline.sql` и закоммить.

## Как это работает

- **Существующая БД** (прод, где схема уже накачена alembic'ом): при первом старте
  Flyway видит непустую схему без `flyway_schema_history` и, благодаря
  `baseline-on-migrate: true` + `baseline-version: 1` (application.yml),
  ПОМЕЧАЕТ версию 1 как применённую — `V1__baseline.sql` не выполняется.
- **Пустая БД** (fresh dev/staging/DR): Flyway выполняет `V1__baseline.sql`
  и получает ту же схему.
- **Новые изменения схемы** (аналог alembic-ревизий): кладём рядом как
  `V2__<описание>.sql`, `V3__…` — применятся при старте на всех окружениях.

Никогда не редактируй уже применённый V-файл — Flyway сверяет чек-суммы;
любое изменение схемы = новый файл.
