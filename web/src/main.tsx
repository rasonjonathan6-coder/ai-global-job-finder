import React from "react";
import ReactDOM from "react-dom/client";
import "./index.css";
import { App } from "./ui/App";
import { AppStoreProvider } from "./state/app-store";
import { ThemeProvider } from "./ui/theme/ThemeProvider";

const container = document.getElementById("root");
if (!container) {
  throw new Error("Root element #root was not found in index.html");
}

ReactDOM.createRoot(container).render(
  <React.StrictMode>
    <ThemeProvider>
      <AppStoreProvider>
        <App />
      </AppStoreProvider>
    </ThemeProvider>
  </React.StrictMode>,
);
