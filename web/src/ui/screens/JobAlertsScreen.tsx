import { useEffect, useMemo, useState } from "react";
import {
  ALERT_FREQUENCY_LABEL,
  ALERT_SCOPE_LABEL,
  AlertFrequency,
  AlertScope,
  defaultJobAlertRule,
  type JobAlertRule,
} from "../../core/models";
import { useAppStore } from "../../state/app-store";
import { Badge } from "../components/Badge";
import { Button } from "../components/Button";
import { Chip } from "../components/Chip";
import { EmptyState } from "../components/EmptyState";
import { HeaderIconButton, SecondaryHeader } from "../components/SecondaryHeader";
import { Skeleton } from "../components/Skeleton";
import { cx } from "../lib/cx";
import {
  BellIcon,
  CloseIcon,
  PlusIcon,
  RefreshIcon,
  SearchIcon,
  TrashIcon,
} from "../components/icons";

const MIN_MATCH_OPTIONS = [50, 60, 70, 80, 90];
const SCOPES = [AlertScope.ANY_MATCH, AlertScope.STRONG_ONLY, AlertScope.VISA_FRIENDLY];
const FREQUENCIES = [AlertFrequency.DAILY, AlertFrequency.WEEKLY, AlertFrequency.MONTHLY];

/**
 * Saved searches and the postings they catch, evaluated against the live feed. Mirrors the
 * Android `JobAlertsScreen`: rules are stored, the alerts are recomputed from the current
 * feed, and a caught posting opens the same detail the rest of the app uses.
 */
export function JobAlertsScreen({ onNavigate }: { onNavigate: (route: string) => void }) {
  const {
    refresh,
    refreshJobs,
    alertRules,
    alerts,
    alertsUnreadCount,
    alertsRefreshing,
    refreshAlerts,
    addAlertRule,
    removeAlertRule,
    markAlertRead,
    clearAlerts,
  } = useAppStore();
  const [showEditor, setShowEditor] = useState(false);

  // Deep-linking straight to `#/alerts` must still evaluate against a real feed.
  useEffect(() => {
    if (refresh.status === "idle") void refreshJobs();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const activeRuleCount = alertRules.filter((r) => r.enabled).length;

  return (
    <>
      <SecondaryHeader
        title="Job alerts"
        subtitle={`${activeRuleCount} saved searches · ${alertsUnreadCount} new`}
        onBack={() => onNavigate("ai")}
        action={
          <HeaderIconButton label="Refresh alerts" onClick={() => void refreshAlerts()}>
            <RefreshIcon width={20} height={20} />
          </HeaderIconButton>
        }
      />

      <div className="app-shell space-y-4">
        <section className="card-surface flex items-center gap-3 p-4">
          <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-accent-500/[0.14] text-accent-ink">
            <BellIcon width={22} height={22} />
          </span>
          <div className="min-w-0 flex-1">
            <p className="text-[14px] font-bold text-ink-primary">
              {alertsUnreadCount > 0 ? `${alertsUnreadCount} new matches` : "You are up to date"}
            </p>
            <p className="text-[12.5px] leading-relaxed text-ink-secondary">
              Rules are re-checked every time your feed refreshes.
            </p>
          </div>
        </section>

        <div className="flex items-center gap-3">
          <div className="min-w-0 flex-1">
            <h2 className="text-[15px] font-bold tracking-tight text-ink-primary">Saved searches</h2>
            <p className="text-[12px] text-ink-secondary">What to watch for</p>
          </div>
          <Button
            variant="secondary"
            size="sm"
            leadingIcon={showEditor ? <CloseIcon width={15} height={15} /> : <PlusIcon width={15} height={15} />}
            onClick={() => setShowEditor((v) => !v)}
          >
            {showEditor ? "Close" : "New"}
          </Button>
        </div>

        {showEditor && (
          <RuleEditor
            onSave={(rule) => {
              addAlertRule(rule);
              setShowEditor(false);
            }}
          />
        )}

        {alertRules.length === 0 ? (
          <p className="px-1 text-[12.5px] leading-relaxed text-ink-secondary">
            No saved searches yet. Add one to be told when a matching posting appears.
          </p>
        ) : (
          <div className="space-y-2.5">
            {alertRules.map((rule) => (
              <RuleRow key={rule.id} rule={rule} onRemove={() => removeAlertRule(rule.id)} />
            ))}
          </div>
        )}

        <div className="flex items-center gap-3 pt-1">
          <div className="min-w-0 flex-1">
            <h2 className="text-[15px] font-bold tracking-tight text-ink-primary">Matched postings</h2>
            <p className="text-[12px] text-ink-secondary">
              {alerts.length === 0 ? "Nothing caught yet" : `${alerts.length} caught`}
            </p>
          </div>
          {alerts.length > 0 && (
            <button
              type="button"
              onClick={clearAlerts}
              className="text-[12.5px] font-semibold text-accent-ink transition-colors hover:opacity-80"
            >
              Clear
            </button>
          )}
        </div>

        {alertsRefreshing && alerts.length === 0 ? (
          <div className="space-y-2.5" role="status" aria-label="Refreshing alerts">
            <Skeleton className="h-[84px] rounded-2xl" />
            <Skeleton className="h-[84px] rounded-2xl" />
          </div>
        ) : alerts.length === 0 ? (
          <EmptyState
            icon={<BellIcon width={26} height={26} />}
            title="No alerts yet"
            message="When a posting matches one of your saved searches, it will appear here."
          />
        ) : (
          <div className="space-y-2.5">
            {alerts.map((alert) => (
              <AlertRow
                key={`${alert.ruleId}:${alert.jobId}`}
                alert={alert}
                onClick={() => {
                  markAlertRead(alert.jobId);
                  onNavigate(`job/${alert.jobId}`);
                }}
              />
            ))}
          </div>
        )}
      </div>
    </>
  );
}

function RuleRow({ rule, onRemove }: { rule: JobAlertRule; onRemove: () => void }) {
  const detail = [
    ALERT_SCOPE_LABEL[rule.scope],
    rule.minMatchScore > 0 ? `${rule.minMatchScore}%+` : null,
    rule.countries.length > 0 ? rule.countries.join(", ") : null,
    rule.keywords.length > 0 ? rule.keywords.join(", ") : null,
  ]
    .filter(Boolean)
    .join(" · ");

  return (
    <div className="card-surface flex items-center gap-3 p-4">
      <div className="min-w-0 flex-1">
        <p className="truncate text-[13.5px] font-semibold text-ink-primary">{rule.name}</p>
        <p className="mt-0.5 line-clamp-2 text-[12px] leading-relaxed text-ink-secondary">{detail}</p>
      </div>
      <button
        type="button"
        onClick={onRemove}
        aria-label="Delete saved search"
        className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full text-danger transition-colors hover:bg-danger-container active:scale-95"
      >
        <TrashIcon width={19} height={19} />
      </button>
    </div>
  );
}

function AlertRow({
  alert,
  onClick,
}: {
  alert: ReturnType<typeof useAppStore>["alerts"][number];
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="card-surface flex w-full items-center gap-3 p-4 text-left transition-all hover:-translate-y-0.5 hover:shadow-card-hover active:scale-[0.99]"
    >
      <span className="text-[26px] leading-none" aria-hidden="true">
        {alert.countryFlag || "🌍"}
      </span>
      <div className="min-w-0 flex-1">
        <p className="truncate text-[13.5px] font-semibold text-ink-primary">{alert.jobTitle}</p>
        <p className="truncate text-[12px] text-ink-secondary">
          {alert.company} · {alert.country}
        </p>
        <div className="mt-1.5 flex flex-wrap gap-1.5">
          <Badge tone="accent">{alert.matchScore}% Match</Badge>
          {alert.isVisaFriendly && <Badge tone="success">Visa support</Badge>}
          {!alert.read && <Badge tone="info">New</Badge>}
        </div>
      </div>
    </button>
  );
}

function RuleEditor({ onSave }: { onSave: (rule: JobAlertRule) => void }) {
  const [name, setName] = useState("");
  const [keywords, setKeywords] = useState("");
  const [countries, setCountries] = useState("");
  const [scope, setScope] = useState(AlertScope.ANY_MATCH);
  const [frequency, setFrequency] = useState(AlertFrequency.DAILY);
  const [score, setScore] = useState(70);

  const parsed = useMemo(
    () => ({
      keywords: splitList(keywords),
      countries: splitList(countries),
    }),
    [keywords, countries],
  );

  return (
    <section className="card-surface space-y-3 p-4 animate-fade-up">
      <div>
        <h3 className="text-[14px] font-bold text-ink-primary">New saved search</h3>
        <p className="text-[12px] text-ink-secondary">
          Keywords, countries and how strong a match must be
        </p>
      </div>

      <EditorField label="Name" value={name} onChange={setName} placeholder="e.g. Welding jobs in Canada" />
      <EditorField
        label="Keywords"
        value={keywords}
        onChange={setKeywords}
        placeholder="welder, maintenance, HVAC"
      />
      <EditorField
        label="Countries"
        value={countries}
        onChange={setCountries}
        placeholder="Canada, Germany"
      />

      <div>
        <p className="text-[11.5px] font-medium text-ink-primary">Scope</p>
        <div className="mt-1.5 flex flex-wrap gap-1.5">
          {SCOPES.map((option) => (
            <Chip
              key={option}
              label={ALERT_SCOPE_LABEL[option]}
              selected={scope === option}
              onClick={() => setScope(option)}
            />
          ))}
        </div>
      </div>

      <div>
        <p className="text-[11.5px] font-medium text-ink-primary">Frequency</p>
        <div className="mt-1.5 flex flex-wrap gap-1.5">
          {FREQUENCIES.map((option) => (
            <Chip
              key={option}
              label={ALERT_FREQUENCY_LABEL[option]}
              selected={frequency === option}
              onClick={() => setFrequency(option)}
            />
          ))}
        </div>
      </div>

      <div>
        <p className="text-[11.5px] font-medium text-ink-primary">Minimum match: {score}%</p>
        <div className="mt-1.5 flex flex-wrap gap-1.5">
          {MIN_MATCH_OPTIONS.map((value) => (
            <Chip
              key={value}
              label={`${value}%`}
              selected={score === value}
              onClick={() => setScore(value)}
            />
          ))}
        </div>
      </div>

      <Button
        fullWidth
        leadingIcon={<SearchIcon width={16} height={16} />}
        onClick={() =>
          onSave(
            defaultJobAlertRule({
              id: `rule-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
              name: name.trim() || "Saved search",
              keywords: parsed.keywords,
              countries: parsed.countries,
              scope,
              frequency,
              minMatchScore: score,
              createdAtEpochMillis: Date.now(),
            }),
          )
        }
      >
        Save search
      </Button>
    </section>
  );
}

function EditorField({
  label,
  value,
  onChange,
  placeholder,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  placeholder: string;
}) {
  return (
    <label className="block">
      <span className="text-[11.5px] font-medium text-ink-primary">{label}</span>
      <input
        type="text"
        value={value}
        placeholder={placeholder}
        onChange={(e) => onChange(e.target.value)}
        className={cx(
          "mt-1 w-full rounded-2xl border border-outline bg-surface-variant px-3.5 py-3",
          "text-[13.5px] text-ink-primary placeholder:text-ink-tertiary",
          "outline-none transition-colors focus:border-accent-500 focus:ring-2 focus:ring-accent-500/25",
        )}
      />
    </label>
  );
}

function splitList(value: string): string[] {
  return value
    .split(",")
    .map((part) => part.trim())
    .filter((part) => part.length > 0);
}
