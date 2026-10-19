import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";

import {
  defaultExpatPreferences,
  type CandidateProfile,
  type ExpatPreferences,
  type Job,
} from "../core/models";
import { JobPipeline, type PipelineResult, type ProviderReport } from "../core/repository/job-pipeline";
import { demoCandidateProfile } from "./demo-profile";
import { SAMPLE_JOBS } from "../ui/lib/sample-jobs";

/** Mirrors the Android `ApplicationStatus`, minus the states the web tracker cannot reach yet. */
export enum ApplicationStatus {
  SAVED = "SAVED",
  PREPARING = "PREPARING",
  APPLIED = "APPLIED",
  INTERVIEW = "INTERVIEW",
  OFFER = "OFFER",
  REJECTED = "REJECTED",
  CLOSED = "CLOSED",
}

/** The columns the tracker shows, in order. */
export const TRACKED_STAGES: ApplicationStatus[] = [
  ApplicationStatus.SAVED,
  ApplicationStatus.PREPARING,
  ApplicationStatus.APPLIED,
  ApplicationStatus.INTERVIEW,
  ApplicationStatus.OFFER,
];

export const APPLICATION_STATUS_LABEL: Record<ApplicationStatus, string> = {
  [ApplicationStatus.SAVED]: "Saved",
  [ApplicationStatus.PREPARING]: "Preparing",
  [ApplicationStatus.APPLIED]: "Applied",
  [ApplicationStatus.INTERVIEW]: "Interview",
  [ApplicationStatus.OFFER]: "Offer",
  [ApplicationStatus.REJECTED]: "Rejected",
  [ApplicationStatus.CLOSED]: "Closed",
};

export interface ApplicationRecord {
  id: string;
  jobId: string;
  jobTitle: string;
  company: string;
  country: string;
  countryFlag: string;
  companyAccent: number;
  status: ApplicationStatus;
  matchScore: number;
  appliedAtEpochMillis: number | null;
  updatedAtEpochMillis: number;
  hasCoverLetter: boolean;
  sourceUrl: string;
  /** Snapshot of the posting, so the tracker works offline and survives a feed refresh. */
  job: Job;
}

export type NotificationTone = "accent" | "success" | "warning" | "info";

export interface AppNotification {
  id: string;
  title: string;
  detail: string;
  tone: NotificationTone;
  createdAtEpochMillis: number;
  read: boolean;
}

export interface RefreshState {
  status: "idle" | "loading" | "ready" | "error";
  lastRefreshedAtEpochMillis: number | null;
  error: string | null;
  reports: ProviderReport[];
}

interface PersistedState {
  version: number;
  profile: CandidateProfile | null;
  expatPreferences: ExpatPreferences;
  applications: ApplicationRecord[];
  notifications: AppNotification[];
  autoApplyEnabled: boolean;
  demoMode: boolean;
}

const STORAGE_KEY = "aigjf.store";
const STORAGE_VERSION = 1;

const EMPTY_REFRESH: RefreshState = {
  status: "idle",
  lastRefreshedAtEpochMillis: null,
  error: null,
  reports: [],
};

function emptyPersisted(): PersistedState {
  return {
    version: STORAGE_VERSION,
    profile: null,
    expatPreferences: defaultExpatPreferences(),
    applications: [],
    notifications: [],
    autoApplyEnabled: false,
    demoMode: false,
  };
}

function readPersisted(): PersistedState {
  if (typeof localStorage === "undefined") return emptyPersisted();
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return emptyPersisted();
    const parsed = JSON.parse(raw) as Partial<PersistedState>;
    if (parsed.version !== STORAGE_VERSION) return emptyPersisted();
    return {
      ...emptyPersisted(),
      ...parsed,
      expatPreferences: parsed.expatPreferences ?? defaultExpatPreferences(),
    };
  } catch {
    return emptyPersisted();
  }
}

interface AppStoreValue {
  profile: CandidateProfile;
  profileIsImported: boolean;
  updateProfile: (profile: CandidateProfile) => void;
  expatPreferences: ExpatPreferences;
  setExpatPreferences: (prefs: ExpatPreferences) => void;

  jobs: Job[];
  refresh: RefreshState;
  /** Refreshes the job list. `demoOverride` forces the fixture path for a just-toggled demo mode. */
  refreshJobs: (demoOverride?: boolean) => Promise<void>;
  /** When true, the curated fixtures are served instead of the live feeds (Android demo mode). */
  demoMode: boolean;
  setDemoMode: (enabled: boolean) => void;

  applications: ApplicationRecord[];
  savedJobs: Job[];
  isSaved: (jobId: string) => boolean;
  toggleSaved: (job: Job) => void;
  saveJob: (job: Job) => void;
  markApplied: (job: Job) => void;
  setApplicationStatus: (applicationId: string, status: ApplicationStatus) => void;
  removeApplication: (applicationId: string) => void;

  notifications: AppNotification[];
  unreadCount: number;
  markNotificationRead: (id: string) => void;
  markAllNotificationsRead: () => void;

  autoApplyEnabled: boolean;
  setAutoApplyEnabled: (enabled: boolean) => void;
}

const AppStoreContext = createContext<AppStoreValue | null>(null);

/**
 * The web edition's single source of truth, mirroring the Android `LiveJobRepository` +
 * `UserPreferencesRepository` pair: it owns the profile, the ranked job list, the
 * application tracker and the in-app alerts, and persists all of them to `localStorage`
 * under versioned keys so a stored schema can be migrated rather than silently mis-read.
 *
 * It is deliberately a small, explicit store rather than a state library: the app has one
 * data shape and one writer, so a reducer-style store would add indirection without benefit.
 */
export function AppStoreProvider({ children }: { children: ReactNode }) {
  const persisted = useRef<PersistedState>(readPersisted());

  const [profile, setProfile] = useState<CandidateProfile>(
    () => persisted.current.profile ?? demoCandidateProfile(),
  );
  const [expatPreferences, setExpatPreferencesState] = useState<ExpatPreferences>(
    () => persisted.current.expatPreferences,
  );
  const [jobs, setJobs] = useState<Job[]>([]);
  const [refresh, setRefresh] = useState<RefreshState>(EMPTY_REFRESH);
  const [applications, setApplications] = useState<ApplicationRecord[]>(
    () => persisted.current.applications,
  );
  const [notifications, setNotifications] = useState<AppNotification[]>(
    () => persisted.current.notifications,
  );
  const [autoApplyEnabled, setAutoApplyState] = useState<boolean>(
    () => persisted.current.autoApplyEnabled,
  );
  const [demoMode, setDemoModeState] = useState<boolean>(() => persisted.current.demoMode);

  const pipelineRef = useRef<JobPipeline | null>(null);
  const inFlight = useRef(false);

  // Persist on every meaningful change. A single effect keeps the write path in one place.
  useEffect(() => {
    const payload: PersistedState = {
      version: STORAGE_VERSION,
      profile,
      expatPreferences,
      applications,
      notifications,
      autoApplyEnabled,
      demoMode,
    };
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(payload));
    } catch {
      // Quota / private-mode failures are non-fatal: the session still works in memory.
    }
  }, [profile, expatPreferences, applications, notifications, autoApplyEnabled, demoMode]);

  const pipeline = useMemo(() => {
    pipelineRef.current ??= new JobPipeline();
    return pipelineRef.current;
  }, []);

  const pushNotifications = useCallback((incoming: AppNotification[]) => {
    if (incoming.length === 0) return;
    setNotifications((current) => [...incoming, ...current].slice(0, 40));
  }, []);

  const refreshJobs = useCallback(
    async (demoOverride?: boolean) => {
      if (inFlight.current) return;
      inFlight.current = true;
      setRefresh((r) => ({ ...r, status: "loading", error: null }));
      const useDemo = demoOverride ?? demoMode;
      // Demo mode serves the curated fixtures instead of the live feeds, mirroring the Android
      // `LiveJobRepository` demo path. It is a deterministic, offline-safe showcase.
      if (useDemo) {
        setJobs(SAMPLE_JOBS);
        setRefresh({
          status: "ready",
          lastRefreshedAtEpochMillis: Date.now(),
          error: null,
          reports: [
            { providerId: "demo", providerName: "Demo fixtures", ok: true, fetched: SAMPLE_JOBS.length, accepted: SAMPLE_JOBS.length, message: null },
          ],
        });
        inFlight.current = false;
        return;
      }
      try {
        const result: PipelineResult = await pipeline.refresh(profile);
        setJobs(result.jobs);
        setRefresh({
          status: result.allFailed ? "error" : "ready",
          lastRefreshedAtEpochMillis: Date.now(),
          error: result.allFailed
            ? "No source could be reached. Check your connection and try again."
            : null,
          reports: result.reports,
        });
        if (!result.allFailed) pushNotifications(newOpportunityAlerts(result.jobs));
      } catch (error) {
        setRefresh({
          status: "error",
          lastRefreshedAtEpochMillis: null,
          error: error instanceof Error ? error.message : "The refresh failed unexpectedly.",
          reports: [],
        });
      } finally {
        inFlight.current = false;
      }
    },
    [pipeline, profile, pushNotifications, demoMode],
  );

  const isSaved = useCallback(
    (jobId: string) => applications.some((a) => a.jobId === jobId),
    [applications],
  );

  const saveJob = useCallback((job: Job) => {
    setApplications((current) => {
      if (current.some((a) => a.jobId === job.id)) return current;
      return [recordFromJob(job, ApplicationStatus.SAVED), ...current];
    });
  }, []);

  const toggleSaved = useCallback((job: Job) => {
    setApplications((current) => {
      const existing = current.find((a) => a.jobId === job.id);
      if (existing) return current.filter((a) => a.jobId !== job.id);
      return [recordFromJob(job, ApplicationStatus.SAVED), ...current];
    });
  }, []);

  const markApplied = useCallback((job: Job) => {
    setApplications((current) => {
      const existing = current.find((a) => a.jobId === job.id);
      const now = Date.now();
      if (existing) {
        return current.map((a) =>
          a.jobId === job.id
            ? { ...a, status: ApplicationStatus.APPLIED, appliedAtEpochMillis: now, updatedAtEpochMillis: now }
            : a,
        );
      }
      return [recordFromJob(job, ApplicationStatus.APPLIED), ...current];
    });
  }, []);

  const setApplicationStatus = useCallback((applicationId: string, status: ApplicationStatus) => {
    setApplications((current) =>
      current.map((a) =>
        a.id === applicationId
          ? {
              ...a,
              status,
              appliedAtEpochMillis:
                status === ApplicationStatus.APPLIED && a.appliedAtEpochMillis == null
                  ? Date.now()
                  : a.appliedAtEpochMillis,
              updatedAtEpochMillis: Date.now(),
            }
          : a,
      ),
    );
  }, []);

  const removeApplication = useCallback((applicationId: string) => {
    setApplications((current) => current.filter((a) => a.id !== applicationId));
  }, []);

  const markNotificationRead = useCallback((id: string) => {
    setNotifications((current) => current.map((n) => (n.id === id ? { ...n, read: true } : n)));
  }, []);

  const markAllNotificationsRead = useCallback(() => {
    setNotifications((current) => current.map((n) => ({ ...n, read: true })));
  }, []);

  const setExpatPreferences = useCallback((prefs: ExpatPreferences) => setExpatPreferencesState(prefs), []);
  const updateProfile = useCallback((next: CandidateProfile) => {
    setProfile(next);
    persisted.current = { ...persisted.current, profile: next };
  }, []);
  const setAutoApplyEnabled = useCallback((enabled: boolean) => setAutoApplyState(enabled), []);
  const setDemoMode = useCallback((enabled: boolean) => setDemoModeState(enabled), []);

  const unreadCount = useMemo(() => notifications.filter((n) => !n.read).length, [notifications]);
  const savedJobs = useMemo(
    () => applications.map((a) => a.job).sort((a, b) => b.matchScore - a.matchScore),
    [applications],
  );

  const value = useMemo<AppStoreValue>(
    () => ({
      profile,
      profileIsImported: persisted.current.profile != null,
      updateProfile,
      expatPreferences,
      setExpatPreferences,
      jobs,
      refresh,
      refreshJobs,
      demoMode,
      setDemoMode,
      applications,
      savedJobs,
      isSaved,
      toggleSaved,
      saveJob,
      markApplied,
      setApplicationStatus,
      removeApplication,
      notifications,
      unreadCount,
      markNotificationRead,
      markAllNotificationsRead,
      autoApplyEnabled,
      setAutoApplyEnabled,
    }),
    [
      profile,
      updateProfile,
      expatPreferences,
      setExpatPreferences,
      jobs,
      refresh,
      refreshJobs,
      demoMode,
      setDemoMode,
      applications,
      savedJobs,
      isSaved,
      toggleSaved,
      saveJob,
      markApplied,
      setApplicationStatus,
      removeApplication,
      notifications,
      unreadCount,
      markNotificationRead,
      markAllNotificationsRead,
      autoApplyEnabled,
      setAutoApplyEnabled,
    ],
  );

  return <AppStoreContext.Provider value={value}>{children}</AppStoreContext.Provider>;
}

export function useAppStore(): AppStoreValue {
  const context = useContext(AppStoreContext);
  if (!context) throw new Error("useAppStore must be used inside an AppStoreProvider");
  return context;
}

function recordFromJob(job: Job, status: ApplicationStatus): ApplicationRecord {
  const now = Date.now();
  return {
    id: `${job.id}:${now}`,
    jobId: job.id,
    jobTitle: job.title,
    company: job.company,
    country: job.country,
    countryFlag: job.countryFlag,
    companyAccent: job.companyAccent,
    status,
    matchScore: job.matchScore,
    appliedAtEpochMillis: status === ApplicationStatus.APPLIED ? now : null,
    updatedAtEpochMillis: now,
    hasCoverLetter: false,
    sourceUrl: job.sourceUrl,
    job,
  };
}

/** A small, honest in-app alert set: only the freshest, strongest new postings, capped. */
function newOpportunityAlerts(jobs: Job[]): AppNotification[] {
  const now = Date.now();
  return jobs
    .filter((job) => job.isNew && job.matchScore >= 70)
    .slice(0, 3)
    .map((job) => ({
      id: `new:${job.id}`,
      title: `New match: ${job.title}`,
      detail: `${job.company} · ${job.country} · ${job.matchScore}% match`,
      tone: "accent" as const,
      createdAtEpochMillis: now,
      read: false,
    }));
}
