import { useEffect, useState } from "react";

/**
 * A tiny hash-router. It keeps the selected destination in the URL (`#/jobs`,
 * `#/shortlist`) so a refresh or a deep link lands on the right screen and the Android-style
 * back button works, without pulling in a routing dependency.
 *
 * The whole remaining path is returned as `fullRoute` so secondary screens (a job detail, a
 * shortlist entry) can read their own argument from the URL, while `route` stays the
 * first segment the bottom navigation compares against.
 */
export function useHashRoute(): [string, (route: string) => void, string] {
  const read = () => {
    const raw = typeof window === "undefined" ? "" : window.location.hash.replace(/^#\/?/, "");
    return raw || "home";
  };
  const [fullRoute, setFullRoute] = useState<string>(read);
  const route = fullRoute.split("/")[0] || "home";

  useEffect(() => {
    const onHashChange = () => setFullRoute(read());
    window.addEventListener("hashchange", onHashChange);
    if (!window.location.hash) window.history.replaceState(null, "", "#/home");
    return () => window.removeEventListener("hashchange", onHashChange);
  }, []);

  const navigate = (next: string) => {
    if (next === fullRoute) return;
    window.location.hash = `#/${next}`;
  };

  return [route, navigate, fullRoute];
}

