# Personal Messenger

Закрытый мессенджер: Android-клиент, backend и веб-панель. Сейчас выполнен каркас этапа 1. Регистрации, переписки, файлов, звонков и сквозного шифрования ещё нет. Экран админки и Android показывает только живой ответ `GET /api/v1/health`.

Документы: [архитектура](docs/ARCHITECTURE.md), [решения](docs/DECISIONS.md), [безопасность](docs/SECURITY.md), [API](docs/API.md), [развёртывание](docs/DEPLOYMENT.md), [план](docs/ROADMAP.md).

## Проверка без Docker

Нужны JDK 17 и Node.js 22.

```powershell
cd backend
.\gradlew.bat test
cd ..\admin-web
npm ci
npm test
npm run build
cd ..\android
.\gradlew.bat :app:testDebugUnitTest
```

Тест backend сам запускает PostgreSQL 16 и Redis, накатывает миграции и проверяет health, отказ неаутентифицированному запросу и запрет повторной вставки того же сообщения.

## Запуск стека

Docker в этой среде разработки не обязателен для тестов, но нужен для полного стенда.

```powershell
pwsh scripts/new-env.ps1
pwsh scripts/up.ps1
pwsh scripts/check-health.ps1
```

Админка для разработки: `npm run dev` в `admin-web`, затем http://localhost:5173 .

Пароль сервера в репозиторий не кладётся. Если он уже попал в переписку, смените его до подключения.
