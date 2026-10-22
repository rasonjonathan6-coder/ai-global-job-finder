import {
  AvailabilitySignalType,
  AvailabilityStatus,
  availabilityDecisiveSignal,
  type AvailabilityResult,
} from "../../core/models";
import { Badge, type BadgeTone } from "./Badge";
import { BlockIcon, CheckIcon, ClockIcon, ExternalLinkIcon, HelpIcon } from "./icons";

/**
 * Tone for an availability verdict, on the shared success/danger/neutral scale. Mirrors the
 * Kotlin `availabilityStatusColor`. "Open" is success, "Expired" is danger, and "Unconfirmed"
 * is deliberately neutral — the absence of proof is never shown in a warning or danger colour,
 * so a posting the app could not reach is never visually accused of being closed.
 */
export function availabilityStatusTone(status: AvailabilityStatus): BadgeTone {
  switch (status) {
    case AvailabilityStatus.OPEN:
      return "success";
    case AvailabilityStatus.EXPIRED:
      return "danger";
    case AvailabilityStatus.UNCONFIRMED:
    default:
      return "neutral";
  }
}

/** The chip label, worded exactly as the Kotlin `OfferAvailabilityBadge`. */
export function availabilityBadgeLabel(availability: AvailabilityResult): string {
  switch (availability.status) {
    case AvailabilityStatus.OPEN:
      switch (availabilityDecisiveSignal(availability)) {
        case AvailabilitySignalType.SOURCE_FLAG:
          return "Open · verified by source";
        case AvailabilitySignalType.LINK_PROBE:
          return "Open · verified at source";
        default:
          return "Open · verified";
      }
    case AvailabilityStatus.EXPIRED:
      switch (availabilityDecisiveSignal(availability)) {
        case AvailabilitySignalType.EMPLOYER_DEADLINE:
          return "Expired · deadline passed";
        case AvailabilitySignalType.SOURCE_FLAG:
          return "Expired · source reports closed";
        case AvailabilitySignalType.LINK_PROBE:
          return "Expired · page confirms closed";
        default:
          return "Expired";
      }
    case AvailabilityStatus.UNCONFIRMED:
    default:
      return "Availability unconfirmed";
  }
}

function availabilityIcon(status: AvailabilityStatus) {
  switch (status) {
    case AvailabilityStatus.OPEN:
      return <CheckIcon width={12} height={12} />;
    case AvailabilityStatus.EXPIRED:
      return <BlockIcon width={12} height={12} />;
    case AvailabilityStatus.UNCONFIRMED:
    default:
      return <HelpIcon width={12} height={12} />;
  }
}

/**
 * The availability verdict as one chip. Mirrors the Kotlin `OfferAvailabilityBadge`: the
 * single, unambiguous answer to "can I still apply?", kept separate from the multi-axis
 * verification panel so the two are never blended.
 */
export function AvailabilityBadge({
  availability,
  className,
}: {
  availability: AvailabilityResult;
  className?: string;
}) {
  return (
    <Badge tone={availabilityStatusTone(availability.status)} icon={availabilityIcon(availability.status)} className={className}>
      {availabilityBadgeLabel(availability)}
    </Badge>
  );
}

/** "just now", "3 h ago" or a date, for the last-checked stamp on the availability section. */
function relativeCheckedLabel(epochMillis: number): string {
  const minutes = Math.max(0, Math.floor((Date.now() - epochMillis) / 60_000));
  if (minutes < 1) return "just now";
  if (minutes < 60) return `${minutes} min ago`;
  if (minutes < 60 * 24) return `${Math.floor(minutes / 60)} h ago`;
  return `${Math.floor(minutes / (60 * 24))} d ago`;
}

function evidenceIcon(type: AvailabilitySignalType) {
  switch (type) {
    case AvailabilitySignalType.SOURCE_FLAG:
      return <CheckIcon width={14} height={14} />;
    case AvailabilitySignalType.EMPLOYER_DEADLINE:
      return <ClockIcon width={14} height={14} />;
    case AvailabilitySignalType.LINK_PROBE:
      return <ExternalLinkIcon width={14} height={14} />;
    case AvailabilitySignalType.NONE:
    default:
      return <HelpIcon width={14} height={14} />;
  }
}

/**
 * The availability read as a detail section: the verdict, when it was checked, and each reason
 * behind it. Mirrors the Kotlin `OfferAvailabilityDetail`. This is where the app shows its work,
 * so a candidate can see why a posting is called open, expired or unconfirmed — and can open the
 * source to check for themselves.
 */
export function AvailabilityDetail({ availability }: { availability: AvailabilityResult }) {
  const heading =
    availability.status === AvailabilityStatus.OPEN
      ? "Open — verified"
      : availability.status === AvailabilityStatus.EXPIRED
        ? "Expired — confirmed closed"
        : "Availability not confirmed";

  return (
    <div className="space-y-2">
      <div className="flex items-center gap-2">
        <span className="shrink-0">{availabilityIcon(availability.status)}</span>
        <span className="flex-1 text-[13px] font-semibold text-ink-primary">{heading}</span>
        {availability.lastCheckedAtEpochMillis != null && (
          <span className="text-[11px] text-ink-tertiary">
            Checked {relativeCheckedLabel(availability.lastCheckedAtEpochMillis)}
          </span>
        )}
      </div>
      {availability.evidence.length === 0 ? (
        <p className="text-[12px] leading-relaxed text-ink-secondary">
          This app has not confirmed whether the vacancy is still open. Open the posting to check.
        </p>
      ) : (
        availability.evidence.map((fact, i) => (
          <div key={i} className="flex items-start gap-2">
            <span className="mt-0.5 shrink-0 text-ink-tertiary">{evidenceIcon(fact.type)}</span>
            <p className="text-[12px] leading-relaxed text-ink-secondary">{fact.detail}</p>
          </div>
        ))
      )}
    </div>
  );
}
