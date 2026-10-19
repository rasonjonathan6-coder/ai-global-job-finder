import { BottomNav } from "./components/BottomNav";
import { AiScreen } from "./screens/AiScreen";
import { ApplicationsScreen } from "./screens/ApplicationsScreen";
import { HomeScreen } from "./screens/HomeScreen";
import { JobsScreen } from "./screens/JobsScreen";
import { ProfileScreen } from "./screens/ProfileScreen";
import { useHashRoute } from "./navigation/useHashRoute";

/**
 * Application shell. The chrome — theme, typography, bottom navigation and page transitions
 * — is unchanged; the destinations now read from the live `AppStoreProvider` (job pipeline,
 * application tracker and alerts) instead of the stage-one placeholders.
 */
export function App() {
  const [route, navigate] = useHashRoute();

  return (
    <div className="min-h-dvh bg-background">
      <main key={route} className="animate-fade-in">
        {route === "jobs" ? (
          <JobsScreen onNavigate={navigate} />
        ) : route === "applications" ? (
          <ApplicationsScreen onNavigate={navigate} />
        ) : route === "ai" ? (
          <AiScreen />
        ) : route === "profile" ? (
          <ProfileScreen />
        ) : (
          <HomeScreen onNavigate={navigate} />
        )}
      </main>
      <BottomNav current={route} onNavigate={navigate} />
    </div>
  );
}
