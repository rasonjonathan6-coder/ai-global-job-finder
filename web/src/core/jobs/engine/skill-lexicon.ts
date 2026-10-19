import { containsWord } from "../search/phrases";

/**
 * A small, explicit skill vocabulary used to pull comparable skills out of a job
 * description. Mirrors the Kotlin `SkillLexicon`. It is intentionally limited: a term only
 * appears here when it is specific enough that finding it in a posting is meaningful
 * evidence. Free-form candidate skills are still matched directly by name.
 */

/** Canonical skill name → the phrases that indicate it in a posting. */
const SKILLS: Record<string, string[]> = {
  // Industrial / maintenance
  "Preventive maintenance": ["preventive maintenance", "preventative maintenance", "planned maintenance"],
  Hydraulics: ["hydraulic", "hydraulics", "pneumatic", "pneumatics"],
  "Electrical diagnostics": ["electrical fault", "electrical diagnostic", "electrical maintenance", "troubleshoot electrical"],
  "Mechanical repair": ["mechanical repair", "mechanical maintenance", "mechanical fitting", "mechanical assembly"],
  Welding: ["welding", "welder", "mig", "tig"],
  PLC: ["plc", "programmable logic", "siemens s7", "allen-bradley", "allen bradley"],
  CMMS: ["cmms", "computerized maintenance", "maintenance management system", "work orders"],
  Automation: ["automation", "automated line", "robotics"],
  Machining: ["machining", "lathe", "milling", "cnc"],
  HVAC: ["hvac", "refrigeration", "air conditioning"],
  "Lockout/tagout": ["lockout", "tagout", "loto"],
  Forklift: ["forklift", "reach truck", "pallet truck"],
  "Quality control": ["quality control", "qc", "quality assurance", "iso 9001"],
  "Inventory management": ["inventory management", "stock control", "warehouse management"],
  "Heavy equipment": ["heavy equipment", "heavy machinery", "earthmoving"],
  Instrumentation: ["instrumentation", "calibration", "sensors"],
  "Solar energy": ["solar", "photovoltaic", "renewable energy"],
  "Wind turbines": ["wind turbine", "wind farm"],
  "Diesel engines": ["diesel engine", "diesel generator", "genset"],

  // Safety / compliance
  "Safety compliance": ["safety compliance", "hse", "ehs", "occupational health", "workplace safety"],
  "First aid": ["first aid", "cpr"],
  "Risk assessment": ["risk assessment", "hazard analysis"],

  // Office / tech
  "Customer service": ["customer service", "customer support", "client support"],
  "Microsoft Excel": ["excel", "spreadsheet"],
  "Microsoft Office": ["microsoft office", "ms office", "word", "powerpoint"],
  "Data entry": ["data entry", "data processing"],
  Bookkeeping: ["bookkeeping", "accounts payable", "accounts receivable", "invoicing"],
  "Project management": ["project management", "pmp", "project coordination"],
  "Team supervision": ["supervise", "supervisor", "team lead", "team leadership", "manage a team"],
  Training: ["training", "coaching", "mentoring"],
  "Problem solving": ["problem solving", "problem-solving", "analytical"],
  Communication: ["communication skills", "communicate effectively"],
  "Driving licence": ["driving licence", "driving license", "valid driver"],

  // Software
  Kotlin: ["kotlin"],
  Java: ["java "],
  Python: ["python"],
  JavaScript: ["javascript", "typescript"],
  SQL: ["sql", "postgres", "mysql"],
  Docker: ["docker", "kubernetes"],
  AWS: ["aws", "amazon web services"],
  Git: ["git", "github", "gitlab"],
};

/** Canonical names in a stable order. */
export const skillNames: string[] = Object.keys(SKILLS);

/** Phrases for one canonical skill. */
export function phrasesFor(skill: string): string[] {
  return SKILLS[skill] ?? [];
}

/**
 * Canonical skills detected in `text`. Matching is on whole words so "java" does not fire
 * inside "javascript".
 */
export function detectSkills(text: string): Set<string> {
  if (text.trim().length === 0) return new Set();
  const normalized = text.toLowerCase();
  const found = new Set<string>();
  for (const [canonical, phrases] of Object.entries(SKILLS)) {
    if (phrases.some((phrase) => containsWord(normalized, phrase))) found.add(canonical);
  }
  return found;
}

/** Maps a free-form candidate skill onto a canonical name when one is close enough. */
export function canonicalizeSkill(candidateSkill: string): string {
  const normalized = candidateSkill.toLowerCase().trim();
  for (const [canonical, phrases] of Object.entries(SKILLS)) {
    if (canonical.toLowerCase() === normalized) return canonical;
    if (phrases.some((phrase) => phrase.trim() === normalized)) return canonical;
  }
  // Fall back to a substring test for short candidate labels such as "Hydraulics".
  for (const [canonical, phrases] of Object.entries(SKILLS)) {
    const key = canonical.toLowerCase();
    if (
      key.includes(normalized) ||
      normalized.includes(key) ||
      phrases.some((phrase) => containsWord(normalized, phrase) || phrase.includes(normalized))
    ) {
      return canonical;
    }
  }
  return candidateSkill.trim();
}
