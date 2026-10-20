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
  AlertFrequency,
  AlertScope,
  AutoApplyStatus,
  defaultExpatPreferences,
  letterLanguageForCountry,
  shortlistSourceNote,
  type AutoApplyItem,
  type CandidateProfile,
  type CoverLetter,
  type ExpatPreferences,
  type Job,
  type JobAlert,
  type JobAlertRule,
  type ShortlistState,
} from "../core/models";
import { JobPipeline, type PipelineResult, type ProviderReport } from "../core/repository/job-pipeline";
import type { SearchCoverage } from "../core/jobs/search/search-strategy";
import { ShortlistEngine } from "../core/shortlist/engine/shortlist-engine";
import { JobAlertEngine } from "../core/alerts/alert-engine";
import { AutoApplyEngine } from "../core/autoapply/auto-apply-engine";
import { CoverLetterGenerator } from "../core/coverletter/cover-letter-generator";
import { profileCompleteness } from "../core/cv/completeness";
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
  /**
   * An honest account of the last search: which sources narrowed by keyword, which only
   * returned a broad sample, and how many postings matched the trade. Mirrors the Android
   * `LiveJobRepository.coverage` flow.
   */
  coverage: SearchCoverage;
}

interface PersistedState {
  version: number;
  profile: CandidateProfile | null;
  expatPreferences: ExpatPreferences;
  applications: ApplicationRecord[];
  notifications: AppNotification[];
  autoApplyEnabled: boolean;
  demoMode: boolean;
  alertRules: JobAlertRule[];
  autoApplyItems: AutoApplyItem[];
  coverLetters: CoverLetter[];
}

const STORAGE_KEY = "aigjf.store";
const STORAGE_VERSION = 1;

const EMPTY_REFRESH: RefreshState = {
  status: "idle",
  lastRefreshedAtEpochMillis: null,
  error: null,
  reports: [],
  coverage: { sources: [], totalRelevant: 0, totalRetrieved: 0 },
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
    alertRules: [],
    autoApplyItems: [],
    coverLetters: [],
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

  /** The expat shortlist built from the current ranked feed and the user's profile. */
  shortlist: ShortlistState;
  /** Rebuilds the shortlist from the current jobs, profile and expat preferences. */
  buildShortlist: () => void;

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

  /** Saved searches and the postings they catch, re-evaluated against the live feed. */
  alertRules: JobAlertRule[];
  alerts: JobAlert[];
  alertsUnreadCount: number;
  alertsRefreshing: boolean;
  refreshAlerts: () => Promise<void>;
  addAlertRule: (rule: JobAlertRule) => void;
  removeAlertRule: (id: string) => void;
  markAlertRead: (jobId: string) => void;
  clearAlerts: () => void;

  /** Safe auto-apply: prepared drafts that always stop at the user's approval. */
  autoApplyItems: AutoApplyItem[];
  autoApplyPreparing: boolean;
  autoApplyThreshold: number;
  setAutoApplyThreshold: (value: number) => void;
  prepareAutoApply: () => void;
  approveAutoApply: (jobId: string) => void;
  skipAutoApply: (jobId: string) => void;
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
  const [alertRules, setAlertRules] = useState<JobAlertRule[]>(() => persisted.current.alertRules);
  const [alerts, setAlerts] = useState<JobAlert[]>([]);
  const [alertsRefreshing, setAlertsRefreshing] = useState(false);
  const [autoApplyItems, setAutoApplyItems] = useState<AutoApplyItem[]>(
    () => persisted.current.autoApplyItems,
  );
  const [autoApplyPreparing, setAutoApplyPreparing] = useState(false);
  const [autoApplyThreshold, setAutoApplyThresholdState] = useState(AutoApplyEngine.DEFAULT_THRESHOLD);
  const [coverLetters, setCoverLetters] = useState<CoverLetter[]>(() => persisted.current.coverLetters);
  const [shortlist, setShortlist] = useState<ShortlistState>(() => ({
    isLoading: true,
    entries: [],
    totalJobs: 0,
    isLive: false,
    sourceNote: "",
    generatedAtEpochMillis: 0,
  }));

  const pipelineRef = useRef<JobPipeline | null>(null);
  const shortlistEngineRef = useRef<ShortlistEngine | null>(null);
  const alertEngineRef = useRef<JobAlertEngine | null>(null);
  const coverLetterGeneratorRef = useRef<CoverLetterGenerator | null>(null);
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
      alertRules,
      autoApplyItems,
      coverLetters,
    };
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(payload));
    } catch {
      // Quota / private-mode failures are non-fatal: the session still works in memory.
    }
  }, [
    profile,
    expatPreferences,
    applications,
    notifications,
    autoApplyEnabled,
    demoMode,
    alertRules,
    autoApplyItems,
    coverLetters,
  ]);

  const pipeline = useMemo(() => {
    pipelineRef.current ??= new JobPipeline();
    return pipelineRef.current;
  }, []);

  const shortlistEngine = useMemo(() => {
    shortlistEngineRef.current ??= new ShortlistEngine();
    return shortlistEngineRef.current;
  }, []);

  const alertEngine = useMemo(() => {
    alertEngineRef.current ??= new JobAlertEngine();
    return alertEngineRef.current;
  }, []);

  const coverLetterGenerator = useMemo(() => {
    coverLetterGeneratorRef.current ??= new CoverLetterGenerator();
    return coverLetterGeneratorRef.current;
  }, []);

  /**
   * Rebuilds the shortlist from the current feed. The readiness score weighs the employer's
   * stated route, the CV match, the documented benefits and how complete the profile is — so
   * the same guards the mapper applied to salary are re-applied here through the pipeline.
   */
  const buildShortlist = useCallback(() => {
    const state = shortlistEngine.build(
      jobs,
      profile,
      expatPreferences,
      profileCompleteness(profile).percent,
      (job) => pipeline.eurPerYear(job),
    );
    // Mirror the Android `ShortlistViewModel`: the live/cached note is derived from the last
    // refresh's per-source outcomes, not stored in the engine. Demo mode reports a single
    // "Demo fixtures" source, so it reads as live — exactly as the fixture repository does.
    const reports = refresh.reports;
    const successCount = reports.filter((r) => r.ok).length;
    const errorCount = reports.length - successCount;
    setShortlist({
      ...state,
      isLoading: false,
      isLive: successCount > 0,
      sourceNote: shortlistSourceNote(successCount, errorCount, jobs.length),
    });
  }, [shortlistEngine, pipeline, jobs, profile, expatPreferences, refresh.reports]);

  // Keep the shortlist in step with the feed, profile and priorities. The engine is pure and
  // cheap, so recomputing on change is simpler and less error-prone than a manual sync step.
  useEffect(() => {
    if (jobs.length === 0) return;
    buildShortlist();
  }, [jobs, buildShortlist]);

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
            {
              providerId: "demo",
              providerName: "Demo fixtures",
              ok: true,
              fetched: SAMPLE_JOBS.length,
              accepted: SAMPLE_JOBS.length,
              message: null,
              serverSideSearch: false,
            },
          ],
          coverage: {
            sources: [
              {
                providerId: "demo",
                providerName: "Demo fixtures",
                targeted: false,
                relevantCount: SAMPLE_JOBS.length,
                totalCount: SAMPLE_JOBS.length,
              },
            ],
            totalRelevant: SAMPLE_JOBS.length,
            totalRetrieved: SAMPLE_JOBS.length,
          },
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
          coverage: result.coverage,
        });
        if (!result.allFailed) pushNotifications(newOpportunityAlerts(result.jobs));
      } catch (error) {
        setRefresh({
          status: "error",
          lastRefreshedAtEpochMillis: null,
          error: error instanceof Error ? error.message : "The refresh failed unexpectedly.",
          reports: [],
          coverage: { sources: [], totalRelevant: 0, totalRetrieved: 0 },
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

  // ---- Job alerts -------------------------------------------------------
  // Rules are stored; the alerts they produce are recomputed from the live feed on every
  // change, so a new posting is surfaced without a background service and a deleted rule
  // never leaves an orphan alert behind. Mirrors the Android `JobAlertsCenter`.
  useEffect(() => {
    if (alertRules.length === 0) {
      setAlerts([]);
      return;
    }
    const now = Date.now();
    const produced = alertEngine.alertsFor(alertRules, jobs, now);
    setAlerts((previous) => {
      const readJobIds = new Set(previous.filter((a) => a.read).map((a) => a.jobId));
      return produced
        .map((alert) => (readJobIds.has(alert.jobId) ? { ...alert, read: true } : alert))
        .sort((a, b) => b.detectedAtEpochMillis - a.detectedAtEpochMillis);
    });
  }, [alertRules, jobs, alertEngine]);

  const refreshAlerts = useCallback(async () => {
    setAlertsRefreshing(true);
    try {
      // A refresh re-scores the feed; the effect above then re-evaluates every saved search.
      await refreshJobs();
    } finally {
      setAlertsRefreshing(false);
    }
  }, [refreshJobs]);

  const addAlertRule = useCallback((rule: JobAlertRule) => {
    setAlertRules((current) => [...current, rule]);
  }, []);

  const removeAlertRule = useCallback((id: string) => {
    setAlertRules((current) => current.filter((rule) => rule.id !== id));
  }, []);

  const markAlertRead = useCallback((jobId: string) => {
    setAlerts((current) => current.map((a) => (a.jobId === jobId ? { ...a, read: true } : a)));
  }, []);

  const clearAlerts = useCallback(() => setAlerts([]), []);

  // ---- Safe auto-apply --------------------------------------------------
  // Prepare drafts only. Approving a draft never sends anything: it marks the item ready
  // and the send happens on the posting itself, exactly as the Android centre documents.
  const prepareAutoApply = useCallback(
    (threshold: number = autoApplyThreshold) => {
      setAutoApplyPreparing(true);
      try {
        const engine = new AutoApplyEngine(threshold);
        const queue = engine.buildQueue(jobs, autoApplyItems, AutoApplyEngine.DEFAULT_LIMIT);
        const generated: CoverLetter[] = [];
        const prepared = queue.map((item) => {
          if (item.status !== AutoApplyStatus.DRAFT_READY) return item;
          const job = jobs.find((candidate) => candidate.id === item.jobId);
          if (!job) return item;
          if (profile.fullName.trim() === "" && profile.skills.length === 0) {
            return {
              ...item,
              reason: "Import and analyse your CV first, so the letter can use your real details.",
            };
          }
          const letter = coverLetterGenerator.generate(job, profile, letterLanguageForCountry(job.country));
          generated.push(letter);
          return { ...item, coverLetterId: letter.id };
        });
        setAutoApplyItems(prepared);
        if (generated.length > 0) {
          setCoverLetters((current) => [...generated, ...current].slice(0, 50));
        }
      } finally {
        setAutoApplyPreparing(false);
      }
    },
    [autoApplyThreshold, jobs, autoApplyItems, profile, coverLetterGenerator],
  );

  // Mirror the Android `AutoApplyCenter`: when the mode is on, the queue is rebuilt from the
  // live feed, so a persisted "on" state prepares on launch and a refresh never leaves a
  // draft pointing at a posting that has since disappeared.
  useEffect(() => {
    if (autoApplyEnabled && jobs.length > 0) prepareAutoApply();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [autoApplyEnabled, jobs]);

  const setAutoApplyThreshold = useCallback(
    (value: number) => {
      setAutoApplyThresholdState(value);
      if (autoApplyEnabled) prepareAutoApply(value);
    },
    [autoApplyEnabled, prepareAutoApply],
  );

  const approveAutoApply = useCallback((jobId: string) => {
    setAutoApplyItems((current) =>
      current.map((item) =>
        item.jobId === jobId
          ? {
              ...item,
              status: AutoApplyStatus.APPROVED,
              reason: "Approved. Open the posting to send it yourself.",
            }
          : item,
      ),
    );
  }, []);

  const skipAutoApply = useCallback((jobId: string) => {
    setAutoApplyItems((current) =>
      current.map((item) =>
        item.jobId === jobId
          ? { ...item, status: AutoApplyStatus.SKIPPED, reason: "You skipped this one." }
          : item,
      ),
    );
  }, []);

  const setExpatPreferences = useCallback((prefs: ExpatPreferences) => setExpatPreferencesState(prefs), []);
  const updateProfile = useCallback((next: CandidateProfile) => {
    setProfile(next);
    persisted.current = { ...persisted.current, profile: next };
  }, []);
  const setAutoApplyEnabled = useCallback(
    (enabled: boolean) => {
      setAutoApplyState(enabled);
      // Turning the mode on prepares the first queue straight away; turning it off clears
      // the queue so a stale draft can never be approved later.
      if (enabled) prepareAutoApply();
      else setAutoApplyItems([]);
    },
    [prepareAutoApply],
  );
  const setDemoMode = useCallback(
    (enabled: boolean) => {
      setDemoModeState(enabled);
      // Mirror the Android `JobAlertsCenter`: demo mode seeds two curated example searches
      // so the alerts screen has something honest to show. Never seeds on the user's behalf
      // outside demo mode — the alert list is their own.
      if (enabled) {
        setAlertRules((current) => (current.length > 0 ? current : demoAlertRules()));
      }
    },
    [],
  );

  const unreadCount = useMemo(() => notifications.filter((n) => !n.read).length, [notifications]);
  const alertsUnreadCount = useMemo(() => alerts.filter((a) => !a.read).length, [alerts]);
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
      shortlist,
      buildShortlist,
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
      alertRules,
      alerts,
      alertsUnreadCount,
      alertsRefreshing,
      refreshAlerts,
      addAlertRule,
      removeAlertRule,
      markAlertRead,
      clearAlerts,
      autoApplyItems,
      autoApplyPreparing,
      autoApplyThreshold,
      setAutoApplyThreshold,
      prepareAutoApply,
      approveAutoApply,
      skipAutoApply,
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
      shortlist,
      buildShortlist,
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
      alertRules,
      alerts,
      alertsUnreadCount,
      alertsRefreshing,
      refreshAlerts,
      addAlertRule,
      removeAlertRule,
      markAlertRead,
      clearAlerts,
      autoApplyItems,
      autoApplyPreparing,
      autoApplyThreshold,
      setAutoApplyThreshold,
      prepareAutoApply,
      approveAutoApply,
      skipAutoApply,
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

/**
 * The two curated example searches demo mode seeds, mirroring the Android
 * `JobAlertsCenter.seedDefaultRules`. Only ever used in demo mode.
 */
function demoAlertRules(): JobAlertRule[] {
  const now = Date.now();
  return [
    {
      id: `rule-demo-${now}-visa`,
      name: "Visa-sponsored roles",
      keywords: [],
      countries: [],
      frequency: AlertFrequency.DAILY,
      scope: AlertScope.VISA_FRIENDLY,
      minMatchScore: 60,
      createdAtEpochMillis: now,
      enabled: true,
    },
    {
      id: `rule-demo-${now}-strong`,
      name: "Strong matches anywhere",
      keywords: [],
      countries: [],
      frequency: AlertFrequency.DAILY,
      scope: AlertScope.STRONG_ONLY,
      minMatchScore: 75,
      createdAtEpochMillis: now,
      enabled: true,
    },
  ];
}
