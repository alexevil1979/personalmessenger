export type ComponentStatus = {
  status: string;
  detail?: string | null;
};

export type HealthReport = {
  status: string;
  version: string;
  components: Record<string, ComponentStatus>;
};

export function parseHealthReport(payload: unknown): HealthReport {
  if (!payload || typeof payload !== "object") {
    throw new Error("Health response is not an object");
  }
  const body = payload as Record<string, unknown>;
  if (typeof body.status !== "string" || typeof body.version !== "string") {
    throw new Error("Health response is missing status or version");
  }
  if (!body.components || typeof body.components !== "object") {
    throw new Error("Health response is missing components");
  }
  return {
    status: body.status,
    version: body.version,
    components: body.components as Record<string, ComponentStatus>,
  };
}

export async function fetchHealth(signal?: AbortSignal): Promise<HealthReport> {
  const response = await fetch("/api/v1/health", { signal, headers: { Accept: "application/json" } });
  const payload: unknown = await response.json();
  const report = parseHealthReport(payload);
  if (!response.ok && report.status !== "DOWN") {
    throw new Error(`Health request failed with ${response.status}`);
  }
  return report;
}
