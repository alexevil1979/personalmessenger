import { CssBaseline, ThemeProvider, createTheme, useMediaQuery } from "@mui/material";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { HealthPage } from "./HealthPage";

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
});

export function App() {
  const prefersDark = useMediaQuery("(prefers-color-scheme: dark)");
  const [mode, setMode] = useState<"light" | "dark">(prefersDark ? "dark" : "light");
  const theme = useMemo(
    () =>
      createTheme({
        palette: {
          mode,
          primary: { main: mode === "dark" ? "#7dcea0" : "#1f6f5b" },
          background: { default: mode === "dark" ? "#121816" : "#f4f7f5" },
        },
      }),
    [mode],
  );

  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider theme={theme}>
        <CssBaseline />
        <HealthPage mode={mode} onToggleMode={() => setMode((current) => (current === "dark" ? "light" : "dark"))} />
      </ThemeProvider>
    </QueryClientProvider>
  );
}
