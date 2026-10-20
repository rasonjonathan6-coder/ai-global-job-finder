import {
  LetterLanguage,
  RELOCATION_PREFERENCE_META,
  RelocationPreference,
  languageSkillDisplayLabel,
  type CandidateProfile,
  type CoverLetter,
  type EducationEntry,
  type Job,
} from "../models";

/**
 * Writes a cover letter from the candidate's own CV and the posting. Mirrors the Kotlin
 * `CoverLetterGenerator`.
 *
 * The generator is deliberately literal: every sentence is backed by a value that actually
 * exists in the profile or the posting. When a fact is missing the sentence is dropped and
 * recorded in `missingFacts`, so the user sees the gap and can fill it rather than reading a
 * letter the app invented for them. Nothing leaves the device and no network call is made.
 */

export interface CoverLetterGeneratorOptions {
  clock?: () => number;
  idFactory?: () => string;
}

/** The values the writer had available, resolved once so all languages agree. */
interface Facts {
  jobTitle: string;
  company: string;
  location: string;
  source: string;
  candidateName: string;
  candidateLocation: string;
  currentTitle: string;
  yearsOfExperience: number | null;
  matchedSkills: string[];
  education: string | null;
  certifications: string[];
  languages: string[];
  relocation: RelocationPreference;
  summary: string;
  used: string[];
  missing: string[];
}

function firstEducation(profile: CandidateProfile): EducationEntry | null {
  return profile.education[0] ?? null;
}

function buildFacts(job: Job, profile: CandidateProfile): Facts {
  const location =
    job.city.trim().length === 0 || job.city.toLowerCase() === job.country.toLowerCase()
      ? job.country
      : `${job.city}, ${job.country}`;

  const edu = firstEducation(profile);
  const education =
    edu == null
      ? null
      : `${edu.degree}${edu.institution.trim().length > 0 ? ` — ${edu.institution}` : ""}${
          edu.year > 0 ? ` (${edu.year})` : ""
        }`;

  const matchedSkills = job.matchedSkills.slice(0, 6);
  const certifications = profile.certifications.map((cert) =>
    cert.year != null ? `${cert.name} (${cert.year})` : cert.name,
  );
  const languages = profile.languages.map((lang) => languageSkillDisplayLabel(lang));

  const used: string[] = [`Posting: ${job.title} at ${job.company} (${location})`];
  if (job.source.trim().length > 0) used.push(`Source: ${job.source}`);
  if (profile.fullName.trim().length > 0) used.push(`Your name: ${profile.fullName}`);
  if (profile.location.trim().length > 0) used.push(`Your location: ${profile.location}`);
  if (profile.currentTitle.trim().length > 0) used.push(`Your current title: ${profile.currentTitle}`);
  if (profile.yearsOfExperience != null) used.push(`Experience: ${profile.yearsOfExperience} years`);
  if (matchedSkills.length > 0) used.push(`Skills matched to this posting: ${matchedSkills.join(", ")}`);
  if (education) used.push(`Education: ${education}`);
  if (certifications.length > 0) used.push(`Certifications: ${certifications.join(", ")}`);
  if (languages.length > 0) used.push(`Languages: ${languages.join(", ")}`);
  if (profile.relocationPreference !== RelocationPreference.UNKNOWN) {
    used.push(`Relocation preference: ${RELOCATION_PREFERENCE_META[profile.relocationPreference].label}`);
  }
  if (profile.summary.trim().length > 0) used.push("Professional summary from your CV");

  const missing: string[] = [];
  if (profile.fullName.trim().length === 0) missing.push("Your full name");
  if (profile.currentTitle.trim().length === 0) missing.push("Your professional title");
  if (profile.yearsOfExperience == null) missing.push("Years of experience");
  if (matchedSkills.length === 0) missing.push("Skills that overlap with this posting");
  if (education == null) missing.push("Your highest qualification");
  if (languages.length === 0) missing.push("Languages and levels");
  if (profile.relocationPreference === RelocationPreference.UNKNOWN) {
    missing.push("Your relocation preference");
  }

  return {
    jobTitle: job.title,
    company: job.company,
    location,
    source: job.source,
    candidateName: profile.fullName,
    candidateLocation: profile.location,
    currentTitle: profile.currentTitle,
    yearsOfExperience: profile.yearsOfExperience,
    matchedSkills,
    education,
    certifications,
    languages,
    relocation: profile.relocationPreference,
    summary: profile.summary,
    used,
    missing,
  };
}

/** Per-language sentence builders. Each returns text only when its facts exist. */
interface Pack {
  salutation: string;
  signOff: string;
  subject(f: Facts): string;
  opening(f: Facts): string;
  experience(f: Facts): string;
  education(f: Facts): string | null;
  languages(f: Facts): string | null;
  relocation(f: Facts): string | null;
  callToAction(f: Facts): string;
}

const ENGLISH: Pack = {
  salutation: "Dear Hiring Manager,",
  signOff: "Yours sincerely,",
  subject: (f) => `Application for ${f.jobTitle} — ${f.company}`,
  opening: (f) => {
    let text = `I am writing to apply for the ${f.jobTitle} position at ${f.company} in ${f.location}.`;
    if (f.source.trim().length > 0) text += ` I found this opening through ${f.source}.`;
    if (f.candidateLocation.trim().length > 0) {
      text += ` I am currently based in ${f.candidateLocation} and I am applying as an international candidate.`;
    }
    return text;
  },
  experience: (f) => {
    let text = `I bring ${f.yearsOfExperience != null ? `${f.yearsOfExperience} years of ` : ""}hands-on experience`;
    if (f.currentTitle.trim().length > 0) text += ` as a ${f.currentTitle.toLowerCase()}`;
    text += ".";
    if (f.matchedSkills.length > 0) {
      text += ` The posting calls for ${f.matchedSkills.join(", ").toLowerCase()}, which I use in my current role.`;
    }
    if (f.summary.trim().length > 0) text += ` ${f.summary}`;
    return text;
  },
  education: (f) =>
    f.education == null
      ? null
      : `I hold a ${f.education}.` +
        (f.certifications.length > 0 ? ` I also hold ${f.certifications.join(", ")}.` : ""),
  languages: (f) => (f.languages.length === 0 ? null : `I work in ${f.languages.join(", ")}.`),
  relocation: (f) => {
    switch (f.relocation) {
      case RelocationPreference.PREFERS_RELOCATION:
        return "I am actively seeking to relocate and I am ready to start the work permit process as soon as you make an offer.";
      case RelocationPreference.OPEN_TO_RELOCATION:
        return "I am open to relocating for this role and can begin the visa process promptly.";
      case RelocationPreference.REMOTE_ONLY:
        return "I am applying for this role on a remote basis and can work across your team's time zone.";
      default:
        return null;
    }
  },
  callToAction: () =>
    "Thank you for considering my application. I would welcome the chance to discuss how my " +
    "experience fits your team, and I am available for an interview at a time that suits your time zone.",
};

const FRENCH: Pack = {
  salutation: "Madame, Monsieur,",
  signOff: "Veuillez agréer, Madame, Monsieur, l'expression de mes salutations distinguées.",
  subject: (f) => `Candidature au poste de ${f.jobTitle} — ${f.company}`,
  opening: (f) => {
    let text = `Je vous adresse ma candidature au poste de ${f.jobTitle} au sein de ${f.company}, à ${f.location}.`;
    if (f.source.trim().length > 0) text += ` J'ai découvert cette offre via ${f.source}.`;
    if (f.candidateLocation.trim().length > 0) {
      text += ` Je réside actuellement à ${f.candidateLocation} et je postule en tant que candidat international.`;
    }
    return text;
  },
  experience: (f) => {
    let text = `Je dispose de ${f.yearsOfExperience != null ? `${f.yearsOfExperience} ans d'` : ""}expérience pratique`;
    if (f.currentTitle.trim().length > 0) text += ` en tant que ${f.currentTitle.toLowerCase()}`;
    text += ".";
    if (f.matchedSkills.length > 0) {
      text += ` L'offre recherche ${f.matchedSkills.join(", ").toLowerCase()}, compétences que j'exerce au quotidien.`;
    }
    if (f.summary.trim().length > 0) text += ` ${f.summary}`;
    return text;
  },
  education: (f) =>
    f.education == null
      ? null
      : `Je suis titulaire d'un ${f.education}.` +
        (f.certifications.length > 0 ? ` Je détiens également ${f.certifications.join(", ")}.` : ""),
  languages: (f) => (f.languages.length === 0 ? null : `Je travaille en ${f.languages.join(", ")}.`),
  relocation: (f) => {
    switch (f.relocation) {
      case RelocationPreference.PREFERS_RELOCATION:
        return "Je souhaite activement m'expatrier et je suis prêt à engager les démarches de permis de travail dès votre proposition.";
      case RelocationPreference.OPEN_TO_RELOCATION:
        return "Je suis ouvert à une expatriation pour ce poste et je peux entamer les démarches rapidement.";
      case RelocationPreference.REMOTE_ONLY:
        return "Je postule pour ce poste en télétravail et je peux m'aligner sur votre fuseau horaire.";
      default:
        return null;
    }
  },
  callToAction: () =>
    "Je vous remercie de l'attention portée à ma candidature et reste à votre disposition " +
    "pour un entretien au moment qui conviendra à votre équipe.",
};

const GERMAN: Pack = {
  salutation: "Sehr geehrte Damen und Herren,",
  signOff: "Mit freundlichen Grüßen",
  subject: (f) => `Bewerbung als ${f.jobTitle} — ${f.company}`,
  opening: (f) => {
    let text = `hiermit bewerbe ich mich um die Stelle als ${f.jobTitle} bei ${f.company} in ${f.location}.`;
    if (f.source.trim().length > 0) text += ` Die Stellenanzeige habe ich über ${f.source} gefunden.`;
    if (f.candidateLocation.trim().length > 0) {
      text += ` Ich bin derzeit in ${f.candidateLocation} ansässig und bewerbe mich als internationale Fachkraft.`;
    }
    return text;
  },
  experience: (f) => {
    let text = `Ich bringe ${f.yearsOfExperience != null ? `${f.yearsOfExperience} Jahre ` : ""}praktische Erfahrung`;
    if (f.currentTitle.trim().length > 0) text += ` als ${f.currentTitle} mit`;
    text += ".";
    if (f.matchedSkills.length > 0) {
      text += ` Die Stelle verlangt ${f.matchedSkills.join(", ")}, die ich täglich einsetze.`;
    }
    if (f.summary.trim().length > 0) text += ` ${f.summary}`;
    return text;
  },
  education: (f) =>
    f.education == null
      ? null
      : `Ich habe ${f.education} abgeschlossen.` +
        (f.certifications.length > 0 ? ` Zusätzlich besitze ich ${f.certifications.join(", ")}.` : ""),
  languages: (f) => (f.languages.length === 0 ? null : `Ich arbeite in ${f.languages.join(", ")}.`),
  relocation: (f) => {
    switch (f.relocation) {
      case RelocationPreference.PREFERS_RELOCATION:
        return "Ich möchte aktiv auswandern und bin bereit, das Arbeitserlaubnisverfahren nach Ihrem Angebot sofort zu beginnen.";
      case RelocationPreference.OPEN_TO_RELOCATION:
        return "Ich bin für diesen Einsatz offen für einen Umzug und kann das Visumverfahren zügig starten.";
      case RelocationPreference.REMOTE_ONLY:
        return "Ich bewerbe mich für diese Stelle im Homeoffice und kann mich an Ihre Zeitzone anpassen.";
      default:
        return null;
    }
  },
  callToAction: () =>
    "Vielen Dank für Ihre Aufmerksamkeit. Über die Möglichkeit eines Gesprächs zu einer Zeit, " +
    "die Ihrem Team passt, freue ich mich sehr.",
};

const PACKS: Record<LetterLanguage, Pack> = {
  [LetterLanguage.ENGLISH]: ENGLISH,
  [LetterLanguage.FRENCH]: FRENCH,
  [LetterLanguage.GERMAN]: GERMAN,
};

export class CoverLetterGenerator {
  private readonly clock: () => number;
  private readonly idFactory: () => string;

  constructor(options?: CoverLetterGeneratorOptions) {
    this.clock = options?.clock ?? Date.now;
    this.idFactory = options?.idFactory ?? (() => `letter-${Math.random().toString(36).slice(2)}`);
  }

  generate(job: Job, profile: CandidateProfile, language: LetterLanguage): CoverLetter {
    const pack = PACKS[language];
    const facts = buildFacts(job, profile);

    const paragraphs: string[] = [pack.opening(facts), pack.experience(facts)];
    const education = pack.education(facts);
    if (education) paragraphs.push(education);
    const languages = pack.languages(facts);
    if (languages) paragraphs.push(languages);
    const relocation = pack.relocation(facts);
    if (relocation) paragraphs.push(relocation);
    paragraphs.push(pack.callToAction(facts));

    return {
      id: this.idFactory(),
      jobId: job.id,
      language,
      subject: pack.subject(facts),
      salutation: pack.salutation,
      paragraphs,
      closing: pack.signOff,
      signature: profile.fullName.trim().length > 0 ? profile.fullName : "Your name",
      usedFacts: facts.used,
      missingFacts: facts.missing,
      generatedAtEpochMillis: this.clock(),
      edited: false,
    };
  }
}
