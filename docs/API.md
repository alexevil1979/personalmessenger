# API `/api/v1`

Базовый URL за Caddy: `https://pm.1tlt.ru`. Локально: `http://127.0.0.1:8080`.

Ошибки:

```json
{"error":{"code":"unauthorized","message":"Authentication is required.","correlationId":"..."}}
```

Коды этапа 1: `unauthorized`, `forbidden`, `internal_error`. Заголовок `X-Correlation-Id` возвращается всегда. Если клиент прислал значение вне `^[A-Za-z0-9_.:-]{8,80}$`, сервер подставляет свой идентификатор.

OpenAPI: `GET /v3/api-docs`, UI: `GET /swagger-ui.html`.

## Реализовано

### `GET /api/v1/health`

Аутентификация не требуется. `200`, если обязательные компоненты доступны, иначе `503`.

```json
{
  "status": "UP",
  "version": "0.1.0",
  "components": {
    "postgres": {"status": "UP"},
    "redis": {"status": "UP"},
    "minio": {"status": "NOT_CONFIGURED", "detail": "Object storage endpoint is not set"},
    "websocket": {"status": "NOT_ENABLED", "detail": "Realtime delivery is planned for stage 3"},
    "e2ee": {"status": "NOT_ENABLED", "detail": "End-to-end encryption is not implemented"}
  }
}
```

`NOT_CONFIGURED` и `NOT_ENABLED` не переводят общий статус в `DOWN`. `DOWN` у PostgreSQL, Redis или настроенного MinIO переводит.

`GET /actuator/health` и `GET /actuator/info` открыты. Детали actuator скрыты. Prometheus наружу не публикуется.

## Правила, уже зафиксированные в схеме

- Идемпотентность сообщения: уникальная пара `(sender_device_id, client_message_id)`.
- Порядок: `messages.seq`, клиент синхронизируется по курсору `seq`.
- Личный чат один на пару пользователей: `conversations.direct_key`.
- Права участника хранятся в `conversation_members`, а не присылаются клиентом как истина.

## Реализовано в этапе 2

Пароль хранится как Argon2id. В ответах и списках приглашений нет пароля, кода приглашения и хеша кода. Код возвращается только из `POST /api/v1/admin/invitations`.

| Метод | Путь | Доступ |
| --- | --- | --- |
| POST | `/api/v1/auth/register` | приглашение, одно использование учитывается в транзакции |
| POST | `/api/v1/auth/login` | после нескольких ошибок с одного IP — `429` |
| POST | `/api/v1/auth/refresh` | старый refresh гасится; повтор уже погашенного гасит все сессии пользователя |
| POST | `/api/v1/auth/logout` | текущая сессия |
| POST | `/api/v1/auth/logout-all` | все сессии |
| GET | `/api/v1/me` | текущий пользователь |
| GET | `/api/v1/sessions` | только свои |
| POST | `/api/v1/sessions/{id}/revoke` | только своя, чужая отвечает `404` |
| GET, POST | `/api/v1/admin/invitations` | роли `owner` и `admin` |
| POST | `/api/v1/admin/invitations/{id}/revoke` | роли `owner` и `admin` |

Первый владелец создаётся при старте, если заданы `BOOTSTRAP_ADMIN_USERNAME` и `BOOTSTRAP_ADMIN_PASSWORD` и роли `owner` ещё нет. Пароль в лог не пишется.

## Реализовано в этапе 3

Личный чат. Текст хранится как `encryption = none`: сервер может его прочитать. Повтор того же `clientMessageId` с того же устройства возвращает уже сохранённое сообщение. Чужой чат отвечает `404`. WebSocket `GET /api/v1/ws` с заголовком `Authorization: Bearer` пушит событие `{"type":"message",...}` участникам. Пропущенное догоняется через `afterSeq`.

| Метод | Путь | Доступ |
| --- | --- | --- |
| POST | `/api/v1/conversations/direct` | участник, тело `{username}` |
| GET | `/api/v1/conversations` | только свои |
| GET | `/api/v1/conversations/{id}/messages?afterSeq=` | только участник |
| POST | `/api/v1/conversations/{id}/messages` | только участник, тело `{clientMessageId, text}` |
| GET | `/api/v1/ws` | WebSocket, bearer-токен |

## Реализовано в этапе 4

Групповой чат. Текст по-прежнему `encryption = none`. Создатель получает роль `owner`. Участника добавляет `owner` или `admin`. Повторное добавление не меняет роль. Обычный участник получает `403`. Чужой чат отвечает `404`. Вложения и голосовые сообщения не входят в этот срез.

| Метод | Путь | Доступ |
| --- | --- | --- |
| POST | `/api/v1/conversations/group` | тело `{title}`, до 80 символов |
| POST | `/api/v1/conversations/{id}/members` | `owner` или `admin`, тело `{username}` |

## Реализовано в этапе 4, вложения

Файл загружается через API и пишется в MinIO. Тип определяется по содержимому: PNG, JPEG, WebP, PDF, Ogg, MP3, M4A, AMR и AMR-WB. Аудио сохраняется как `kind = voice`, остальное как `attachment`. Больше 8 МБ — `413`. Чужой чат — `404`. Временная ссылка живёт 5 минут и после этого отвечает `404`. Сервер читает файл: `client_encrypted = false`. Android пишет голосовое с микрофона в AMR-WB, не дольше 60 секунд.

| Метод | Путь | Доступ |
| --- | --- | --- |
| POST | `/api/v1/conversations/{id}/attachments` | участник, поле `file` |
| POST | `/api/v1/conversations/{id}/messages` | можно передать `attachmentId` |
| POST | `/api/v1/attachments/{id}/link` | участник чата, ссылка на скачивание |
| GET | `/api/v1/attachments/{id}/content?ticket=` | билет из ссылки, без сессии, до `expiresAt` |

## Реализовано в этапе 7, часть

Смена пароля оставляет текущую сессию и гасит остальные. Блокировка запрещает личный чат в обе стороны. Владелец группы не выходит, пока в ней есть другие. Администратор видит людей и может поставить статус `blocked`: сессии гасятся, вход отвечает `403`.

| Метод | Путь | Доступ |
| --- | --- | --- |
| POST | `/api/v1/me/password` | тело `{currentPassword, newPassword}` |
| GET, POST | `/api/v1/blocks` | свои блокировки, тело `{username}` |
| DELETE | `/api/v1/blocks/{username}` | своя блокировка |
| POST | `/api/v1/conversations/{id}/leave` | участник |
| DELETE | `/api/v1/conversations/{id}/members/{username}` | `owner` или `admin` |
| POST | `/api/v1/conversations/{id}/read` | участник, тело `{lastReadSeq}` |
| GET | `/api/v1/admin/users` | `owner` и `admin` |
| POST | `/api/v1/admin/users/{username}/status` | тело `{status}`: `active` или `blocked` |

## Не реализовано

Эти операции появятся в указанных этапах. Сейчас любой другой путь под `/api/v1` отвечает `401` и не имитирует успешный ответ.

| Область | Этап |
| --- | --- |
| Ключи и шифротекст Signal | 5 |
| Сигнализация и история звонков, push | 6 |
| Аудит в интерфейсе, настройки | 7 |

Клиент не передаёт авторитетные `user_id` и `role`. Идентичность определяется сессией на сервере.
