import { Alert, Box, Button, Chip, Container, Stack, Typography } from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { AdminAccess } from "./AdminAccess";
import { fetchHealth } from "./api/health";

const labels: Record<string, string> = {
  postgres: "PostgreSQL",
  redis: "Redis",
  minio: "Хранилище файлов",
  websocket: "WebSocket",
  e2ee: "Сквозное шифрование",
};

export function HealthPage({ mode, onToggleMode }: { mode: "light" | "dark"; onToggleMode: () => void }) {
  const health = useQuery({ queryKey: ["health"], queryFn: ({ signal }) => fetchHealth(signal) });

  return (
    <Container maxWidth="sm" sx={{ py: 4 }}>
      <Stack spacing={2}>
        <Stack direction="row" sx={{ justifyContent: "space-between", alignItems: "center" }}>
          <Typography variant="h4" component="h1">
            Панель администратора
          </Typography>
          <Button variant="outlined" onClick={onToggleMode}>
            {mode === "dark" ? "Светлая тема" : "Тёмная тема"}
          </Button>
        </Stack>
        <Alert severity="info">
          Управление пользователями, приглашениями и группами ещё не включено. На этом экране только реальное состояние API.
        </Alert>
        {health.isPending && <Typography>Проверяем сервер…</Typography>}
        {health.isError && (
          <Alert severity="error">
            Сервер не ответил. Запустите backend и откройте панель через тот же адрес, либо через dev-сервер Vite, который проксирует /api.
          </Alert>
        )}
        {health.data && (
          <Box component="section" aria-label="Состояние сервера">
            <Stack spacing={1}>
              <Typography>
                Общий статус: {health.data.status}. Версия backend: {health.data.version}
              </Typography>
              {Object.entries(health.data.components).map(([name, component]) => (
                <Stack key={name} direction="row" spacing={1} sx={{ alignItems: "center" }}>
                  <Chip
                    label={component.status}
                    color={component.status === "UP" ? "success" : component.status === "DOWN" ? "error" : "default"}
                  />
                  <Typography>
                    {labels[name] ?? name}
                    {component.detail ? ` — ${component.detail}` : ""}
                  </Typography>
                </Stack>
              ))}
            </Stack>
          </Box>
        )}
        <Button variant="contained" onClick={() => void health.refetch()} disabled={health.isFetching}>
          Проверить снова
        </Button>
        <AdminAccess />
      </Stack>
    </Container>
  );
}
