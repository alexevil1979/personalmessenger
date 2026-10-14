import { Alert, Button, Stack, TextField, Typography } from "@mui/material";
import { FormEvent, useState } from "react";

type Invitation = {
  id: string;
  expiresAt: string;
  maxUses: number;
  useCount: number;
  revokedAt: string | null;
  createdAt: string;
  note: string | null;
  createdBy: string | null;
};

type Session = { accessToken: string; refreshToken: string; role: string | null; username: string };
type Person = { id: string; username: string; displayName: string; status: string; role: string | null };

async function api(path: string, method: string, token: string | null, body?: unknown) {
  const response = await fetch(path, {
    method,
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  const payload = text ? JSON.parse(text) : null;
  if (!response.ok) {
    const message = payload?.error?.message ?? `Ошибка ${response.status}`;
    throw new Error(message);
  }
  return payload;
}

export function AdminAccess() {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [session, setSession] = useState<Session | null>(null);
  const [error, setError] = useState("");
  const [invitations, setInvitations] = useState<Invitation[]>([]);
  const [freshCode, setFreshCode] = useState("");
  const [hours, setHours] = useState("24");
  const [uses, setUses] = useState("1");
  const [people, setPeople] = useState<Person[]>([]);

  async function login(event: FormEvent) {
    event.preventDefault();
    setError("");
    setFreshCode("");
    try {
      const result = await api("/api/v1/auth/login", "POST", null, {
        username,
        password,
        deviceName: "admin-web",
        platform: "admin",
      });
      const next = {
        accessToken: result.accessToken as string,
        refreshToken: result.refreshToken as string,
        role: (result.user.role as string | null) ?? null,
        username: result.user.username as string,
      };
      setSession(next);
      setPassword("");
      if (next.role === "owner" || next.role === "admin") {
        setInvitations(await api("/api/v1/admin/invitations", "GET", next.accessToken));
        setPeople(await api("/api/v1/admin/users", "GET", next.accessToken));
      }
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Не удалось войти");
    }
  }

  async function createInvitation(event: FormEvent) {
    event.preventDefault();
    if (!session) return;
    setError("");
    try {
      const created = await api("/api/v1/admin/invitations", "POST", session.accessToken, {
        expiresInHours: Number(hours),
        maxUses: Number(uses),
        note: null,
      });
      setFreshCode(created.code as string);
      setInvitations(await api("/api/v1/admin/invitations", "GET", session.accessToken));
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Не удалось создать приглашение");
    }
  }

  async function setStatus(username: string, status: string) {
    if (!session) return;
    setError("");
    try {
      await api(`/api/v1/admin/users/${username}/status`, "POST", session.accessToken, { status });
      setPeople(await api("/api/v1/admin/users", "GET", session.accessToken));
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Не удалось изменить статус");
    }
  }

  async function revoke(id: string) {
    if (!session) return;
    await api(`/api/v1/admin/invitations/${id}/revoke`, "POST", session.accessToken);
    setInvitations(await api("/api/v1/admin/invitations", "GET", session.accessToken));
  }

  return (
    <Stack spacing={2} component="section" aria-label="Вход администратора">
      <Typography variant="h5" component="h2">
        Вход
      </Typography>
      {!session && (
        <Stack component="form" spacing={1} onSubmit={login}>
          <TextField label="Имя" value={username} onChange={(event) => setUsername(event.target.value)} required />
          <TextField
            label="Пароль"
            type="password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            required
          />
          <Button type="submit" variant="contained">
            Войти
          </Button>
        </Stack>
      )}
      {session && (
        <Stack spacing={1}>
          <Typography>
            Вы вошли как {session.username}. Роль: {session.role ?? "нет"}
          </Typography>
          <Button
            variant="outlined"
            onClick={() => {
              void api("/api/v1/auth/logout", "POST", session.accessToken).finally(() => {
                setSession(null);
                setInvitations([]);
                setFreshCode("");
              });
            }}
          >
            Выйти
          </Button>
        </Stack>
      )}
      {session && (session.role === "owner" || session.role === "admin") && (
        <Stack spacing={1}>
          <Typography variant="h6" component="h3">
            Приглашения
          </Typography>
          <Stack component="form" direction="row" spacing={1} onSubmit={createInvitation}>
            <TextField label="Часы" value={hours} onChange={(event) => setHours(event.target.value)} />
            <TextField label="Использований" value={uses} onChange={(event) => setUses(event.target.value)} />
            <Button type="submit" variant="contained">
              Создать
            </Button>
          </Stack>
          {freshCode && (
            <Alert severity="warning">Код показывается один раз: {freshCode}</Alert>
          )}
          <Typography variant="h6" component="h3">
            Люди
          </Typography>
          {people.map((person) => (
            <Stack key={person.id} direction="row" spacing={1} sx={{ alignItems: "center" }}>
              <Typography>
                {person.username} ({person.displayName}) — {person.status}
                {person.role ? `, ${person.role}` : ""}
              </Typography>
              {person.username !== session.username && person.role !== "owner" && (
                <Button
                  onClick={() =>
                    void setStatus(person.username, person.status === "blocked" ? "active" : "blocked")
                  }
                >
                  {person.status === "blocked" ? "Разблокировать" : "Заблокировать"}
                </Button>
              )}
            </Stack>
          ))}
          {invitations.map((invitation) => (
            <Stack key={invitation.id} direction="row" spacing={1} sx={{ alignItems: "center" }}>
              <Typography>
                {invitation.useCount}/{invitation.maxUses}, до {new Date(invitation.expiresAt).toLocaleString("ru-RU")}
                {invitation.revokedAt ? ", отозвано" : ""}
                {invitation.note ? `, ${invitation.note}` : ""}
              </Typography>
              {!invitation.revokedAt && (
                <Button onClick={() => void revoke(invitation.id)}>Отозвать</Button>
              )}
            </Stack>
          ))}
        </Stack>
      )}
      {error && <Alert severity="error">{error}</Alert>}
    </Stack>
  );
}
