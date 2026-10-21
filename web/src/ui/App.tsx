import { BottomNav } from "./components/BottomNav";
import { AiScreen } from "./screens/AiScreen";
import { ApplicationsScreen } from "./screens/ApplicationsScreen";
import { AutoApplyScreen } from "./screens/AutoApplyScreen";
import { CoverLetterScreen } from "./screens/CoverLetterScreen";
import { HomeScreen } from "./screens/HomeScreen";
import { InterviewScreen } from "./screens/InterviewScreen";
import { JobAlertsScreen } from "./screens/JobAlertsScreen";
import { JobsScreen } from "./screens/JobsScreen";
import { ProfileScreen } from "./screens/ProfileScreen";
import { ScamShieldScreen } from "./screens/ScamShieldScreen";
import { ShortlistScreen } from "./screens/ShortlistScreen";
import { useHashRoute } from "./navigation/useHashRoute";

/** Secondary screens render full-page, without the bottom bar, matching the Android graph. */
const SECONDARY_ROUTES = new Set(["shortlist", "alerts", "autoapply", "coverletter", "scam", "interview"]);

/**
 * Application shell. The chrome — theme, typography, bottom navigation and page transitions
 * — is unchanged; the destinations read from the live `AppStoreProvider` (job pipeline,
 * application tracker, alerts and the ported AI engines) instead of stage-one placeholders.
 * Job-scoped screens (cover letter, scam shield, interview coach) read the posting id from the
 * route's second segment and render without the bottom bar, matching the Android graph.
 */
export function App() {
  const [route, navigate, fullRoute] = useHashRoute();
  const jobId = fullRoute.split("/").slice(1).join("/");

  if (SECONDARY_ROUTES.has(route)) {
    return (
      <div className="min-h-dvh bg-background">
        <main key={fullRoute} className="animate-fade-in">
          {route === "shortlist" ? (
            <ShortlistScreen onNavigate={navigate} />
          ) : route === "alerts" ? (
            <JobAlertsScreen onNavigate={navigate} />
          ) : route === "autoapply" ? (
            <AutoApplyScreen onNavigate={navigate} />
          ) : route === "coverletter" ? (
            <CoverLetterScreen jobId={jobId} onBack={() => navigate("jobs")} />
          ) : route === "scam" ? (
            <ScamShieldScreen jobId={jobId} onBack={() => navigate("jobs")} />
          ) : (
            <InterviewScreen jobId={jobId} onBack={() => navigate("jobs")} />
          )}
        </main>
      </div>
    );
  }

  return (
    <div className="min-h-dvh bg-background">
      <main key={route} className="animate-fade-in">
        {route === "jobs" ? (
          <JobsScreen onNavigate={navigate} />
        ) : route === "applications" ? (
          <ApplicationsScreen onNavigate={navigate} />
        ) : route === "ai" ? (
          <AiScreen onNavigate={navigate} />
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
