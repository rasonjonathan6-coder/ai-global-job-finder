import { useEffect, useState } from "react";

/**
 * A tiny hash-router. It keeps the selected tab in the URL (`#/jobs`) so a refresh or a
 * deep link lands on the right destination and the Android-style back button works, without
 * pulling in a routing dependency.
 */
export function useHashRoute(): [string, (route: string) => void] {
  const read = () => {
    const raw = typeof window === "undefined" ? "" : window.location.hash.replace(/^#\/?/, "");
    return raw.split("/")[0] || "home";
  };
  const [route, setRoute] = useState<string>(read);

  useEffect(() => {
    const onHashChange = () => setRoute(read());
    window.addEventListener("hashchange", onHashChange);
    if (!window.location.hash) window.history.replaceState(null, "", "#/home");
    return () => window.removeEventListener("hashchange", onHashChange);
  }, []);

  const navigate = (next: string) => {
    if (next === route) return;
    window.location.hash = `#/${next}`;
  };

  return [route, navigate];
}
