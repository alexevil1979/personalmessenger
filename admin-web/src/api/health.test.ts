import { describe, expect, it } from "vitest";
import { parseHealthReport } from "./health";

describe("parseHealthReport", () => {
  it("reads the live health contract", () => {
    const report = parseHealthReport({
      status: "UP",
      version: "0.1.0",
      components: {
        postgres: { status: "UP" },
        redis: { status: "UP" },
        e2ee: { status: "NOT_ENABLED", detail: "End-to-end encryption is not implemented" },
      },
    });
    expect(report.status).toBe("UP");
    expect(report.components.e2ee.status).toBe("NOT_ENABLED");
  });

  it("rejects a payload without components", () => {
    expect(() => parseHealthReport({ status: "UP", version: "0.1.0" })).toThrow(/components/);
  });
});
