# Развёртывание

Первый поддерживаемый способ — Docker Compose на Ubuntu LTS. На машине, где собирался этот каркас, Docker не установлен, поэтому сам Compose здесь не запускался. Команды ниже нужно выполнить на сервере и проверить health.

Hostname: `pm.1tlt.ru`. Адрес, который был назван для сервера, — `192.168.1.152`. Это частный адрес; с публичного интернета он сам по себе не откроется, пока на роутере нет проброса или пока DNS не указывает на публичный адрес. Пароль администратора сервера в репозиторий не входит. Пароль, отправленный в чат, нужно сменить до входа.

## Что открыто наружу

| Порт | Назначение |
| --- | --- |
| 80, 443 | Caddy: API, OpenAPI и админка |
| 3478 TCP/UDP | STUN/TURN |
| 49160–49200 UDP | реле TURN |

PostgreSQL `5432`, Redis `6379`, MinIO `9000/9001` и backend `8080` привязаны к `127.0.0.1`. Их не пробрасывают в интернет.

## DNS

- `A` или `AAAA` запись `pm.1tlt.ru` на публичный адрес сервера.
- Для TLS по `deploy/Caddyfile.production` запись должна уже резолвиться снаружи.

## Сервер 192.168.1.152

На этой машине уже работают чужие сайты через Apache на портах 80 и 443, и отдельный Redis на `127.0.0.1:6379`. Мессенджер их не занимает.

- Код: `/opt/personalmessenger`
- Стек: `docker compose --env-file .env -f deploy/docker-compose.server.yml up -d --build`
- PostgreSQL, Redis и хранилище доступны только внутри сети Docker.
- Backend слушает `127.0.0.1:8080`, админка — `127.0.0.1:8088`.
- Apache-сайт `pm.1tlt.ru` проксирует `/api`, `/actuator`, `/v3` и `/swagger-ui` на backend, остальное — на админку.
- Секреты лежат только в `/opt/personalmessenger/.env` с правами `600`. В репозитории их нет.
- Публичный адрес сервера: `81.23.181.220`. Запись `pm.1tlt.ru` в DNS отсутствует, поэтому сертификат Let's Encrypt ещё не выпускался. Нужна запись `A pm.1tlt.ru 81.23.181.220`, после неё: `certbot --apache -d pm.1tlt.ru`.
- Coturn на этом хосте не запускался: звонков ещё нет, а широкий диапазон UDP затронул бы общий сервер.

Проверка без DNS: `curl --header Host:pm.1tlt.ru http://81.23.181.220/api/v1/health`

## Первый запуск

На сервере с Docker Engine и плагином Compose:

```bash
git clone https://github.com/alexevil1979/personalmessenger.git
cd personalmessenger
pwsh scripts/new-env.ps1   # на Windows
# на Ubuntu секреты создаются так же, вручную из .env.example
# openssl rand -base64 24 для каждого пароля
docker compose --env-file .env -f deploy/docker-compose.yml up -d --build
curl -fsS http://127.0.0.1:8080/api/v1/health
```

На Windows, если Docker установлен: `pwsh scripts/new-env.ps1`, затем `pwsh scripts/up.ps1`.

`.env.example` содержит только подсказки. `scripts/new-env.ps1` отказывается перезаписывать существующий `.env`.

Миграции применяет Flyway при старте backend. Отдельная ручная команда для первого запуска не нужна. Если миграция падает, контейнер backend не становится здоровым, база остаётся на последней успешной версии Flyway.

## Проверка компонентов

```bash
docker compose --env-file .env -f deploy/docker-compose.yml ps
curl -fsS http://127.0.0.1:8080/api/v1/health
curl -fsS http://127.0.0.1:9000/minio/health/live
docker compose --env-file .env -f deploy/docker-compose.yml exec redis redis-cli -a "$REDIS_PASSWORD" ping
```

Админка: `http://<хост>/`. Она запрашивает `/api/v1/health` у того же origin. Пока backend не запущен, страница показывает ошибку соединения, а не выдуманные числа.

## TLS

Для локальной проверки используется `deploy/Caddyfile` без HTTPS. На сервере замените файл на `deploy/Caddyfile.production`, задайте `PUBLIC_HOST=pm.1tlt.ru` и `ACME_EMAIL`. Caddy получит сертификат сам. Не включайте production-файл, пока DNS не указывает на этот хост.

## TURN

Coturn запущен с `--use-auth-secret`. Временные учётные данные для клиента будут выдавать backend на этапе 6. Секрет — `TURN_SECRET` в `.env`. Реле ограничено портами 49160–49200. На Linux при проблемах с NAT Docker предпочтительнее `network_mode: host`; текущий файл публикует порты, чтобы Compose был переносим. Прямой P2P не гарантируется в каждой мобильной сети.

## Обновление и откат

```bash
git fetch
git checkout <предыдущий-проверенный-коммит>
docker compose --env-file .env -f deploy/docker-compose.yml up -d --build
```

Миграции только вперёд. Откат приложения на код, который не понимает уже применённую миграцию, не поддерживается без восстановления базы из копии. Перед обновлением снимайте копию.

Копии и восстановление — этап 8. Пока процедура ручная:

```bash
docker compose --env-file .env -f deploy/docker-compose.yml exec -T postgres \
  pg_dump -U messenger messenger > messenger.sql
```

Восстановление в пустой том: остановить backend, `psql` из этого файла, затем поднять backend. Проверка восстановления ещё не автоматизирована и здесь не выполнялась.

## Секреты

Смена пароля базы: остановить backend, `ALTER USER`, обновить `.env`, поднять backend. То же для Redis `--requirepass` и MinIO: смена ключа MinIO делает старые ключи недействительными, бакеты сохраняются. `TURN_SECRET` можно сменить перезапуском Coturn; старые временные учётные данные звонков перестанут работать.

## Первый администратор

Не создаётся на этапе 1. На этапе 2 будет отдельная одноразовая команда, которая читает пароль из окружения и пишет только Argon2id-хеш. В миграциях администратора нет.

## Локальная разработка без Docker

```bash
cd backend
./gradlew test
cd ../admin-web
npm ci
npm test
npm run build
cd ../android
./gradlew :app:testDebugUnitTest
```

Тест backend поднимает настоящие PostgreSQL 16 и Redis, применяет Flyway и вызывает HTTP. Это не замена серверного Compose.

Админка в разработке: `npm run dev` в `admin-web`, API проксируется на `127.0.0.1:8080`.
