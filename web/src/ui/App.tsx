import { BottomNav } from "./components/BottomNav";
import { AiScreen } from "./screens/AiScreen";
import { ApplicationsScreen } from "./screens/ApplicationsScreen";
import { HomeScreen } from "./screens/HomeScreen";
import { JobsScreen } from "./screens/JobsScreen";
import { ProfileScreen } from "./screens/ProfileScreen";
import { useHashRoute } from "./navigation/useHashRoute";

/**
 * Application shell. Stage 1 delivers the premium chrome — theme, typography, bottom
 * navigation and page transitions — with each destination rendered through a shared
 * animated container. Later stages replace the placeholder bodies with live data.
 */
export function App() {
  const [route, navigate] = useHashRoute();

  return (
    <div className="min-h-dvh bg-background">
      <main key={route} className="animate-fade-in">
        {route === "jobs" ? (
          <JobsScreen onNavigate={navigate} />
        ) : route === "applications" ? (
          <ApplicationsScreen />
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
