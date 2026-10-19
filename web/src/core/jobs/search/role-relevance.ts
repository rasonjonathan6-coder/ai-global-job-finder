import { RoleFamily } from "../../models";
import { containsPhrase } from "./phrases";
import {
  descriptionIndustryTerms,
  equipmentTerms,
  industryTerms,
  otherProfessionMarkers,
  roles,
  strongCoreTerms,
  weakCoreTerms,
  type RoleRelevance,
} from "./target-search";

/** How much of the description is read for supporting equipment/industry words. */
const DESCRIPTION_HEAD_CHARS = 400;

/**
 * Decides whether a posting is a maintenance/trade role the candidate should see. Mirrors
 * the Kotlin `RoleClassifier`.
 *
 * This is deliberately title-led. A generic word such as "technician", "engineer" or
 * "maintenance" appearing once in a long description is not evidence that the posting is
 * the candidate's job; only a title that names the trade (or names plant machinery or a
 * specific plant/mining setting alongside a trade word) qualifies.
 */
export function classifyRole(title: string, descriptionHead = ""): RoleRelevance {
  const titleText = title.toLowerCase().trim();
  if (titleText.length === 0) return { family: RoleFamily.UNKNOWN, matchedTarget: null, evidence: [] };

  const head = descriptionHead.toLowerCase().slice(0, DESCRIPTION_HEAD_CHARS);

  // 0. A title that names a different profession is not the candidate's job, even if it
  //    also carries a maintenance word ("Software Engineer, Maintenance Tooling").
  const otherProfession = otherProfessionMarkers.filter((it) => containsPhrase(titleText, it));
  if (otherProfession.length > 0) {
    return { family: RoleFamily.UNRELATED, matchedTarget: null, evidence: otherProfession };
  }

  // 1. A canonical target role in the title always wins.
  const matchedTarget = roles.find((role) => role.titleSignals.some((it) => containsPhrase(titleText, it)));
  if (matchedTarget) {
    const signals = matchedTarget.titleSignals.filter((it) => containsPhrase(titleText, it));
    return { family: RoleFamily.TARGET, matchedTarget, evidence: signals };
  }

  const evidence: string[] = [];
  const strong = strongCoreTerms.filter((it) => containsPhrase(titleText, it));
  const weak = weakCoreTerms.filter((it) => containsPhrase(titleText, it));
  // Equipment and a named plant/mining setting count from the title or the description head.
  // Broad sector words count only from the title.
  const equipment = equipmentTerms.filter((it) => containsPhrase(titleText, it) || containsPhrase(head, it));
  const titleIndustry = industryTerms.filter((it) => containsPhrase(titleText, it));
  const descriptionIndustry = descriptionIndustryTerms.filter((it) => containsPhrase(head, it));
  const industry = Array.from(new Set([...titleIndustry, ...descriptionIndustry]));
  evidence.push(...strong, ...weak, ...equipment, ...industry);

  // 2. A strong trade word in the title is maintenance family on its own.
  if (strong.length > 0) {
    return { family: RoleFamily.MAINTENANCE_FAMILY, matchedTarget: null, evidence: Array.from(new Set(evidence)) };
  }

  // 3. A weak trade word needs a concrete machine, a named plant/mining setting in the title,
  //    or a specific processing setting in the description.
  if (weak.length > 0 && (equipment.length > 0 || titleIndustry.length > 0 || descriptionIndustry.length > 0)) {
    return { family: RoleFamily.MAINTENANCE_FAMILY, matchedTarget: null, evidence: Array.from(new Set(evidence)) };
  }

  // 4. Everything else — including a lone "technician" with no trade context — is unrelated.
  return { family: RoleFamily.UNRELATED, matchedTarget: null, evidence: Array.from(new Set(evidence)) };
}
