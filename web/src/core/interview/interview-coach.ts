import {
  AnswerConfidence,
  InterviewCategory,
  INTERVIEW_CATEGORY_ORDER,
  MadagascarEligibility,
  VisaSupport,
  type AnswerFeedback,
  type CandidateProfile,
  type InterviewAttempt,
  type InterviewPrep,
  type InterviewQuestion,
  type InterviewReadiness,
  type Job,
} from "../models";

/**
 * Builds the rehearsal question set for one posting. Mirrors the Kotlin `InterviewQuestionBank`.
 *
 * Questions are not generic. Each one is anchored to a fact from the posting or the CV, and
 * carries the STAR-style skeleton the answer should follow plus the facts the candidate can
 * lean on. Where the CV is silent the question says so in `missingFacts` rather than inviting
 * the candidate to invent an answer.
 */

export interface InterviewQuestionBankOptions {
  clock?: () => number;
}

function normalize(text: string): string {
  return text.trim().toLowerCase();
}

export class InterviewQuestionBank {
  build(job: Job, profile: CandidateProfile): InterviewQuestion[] {
    const questions: InterviewQuestion[] = [];
    const hasName = profile.fullName.trim().length > 0;
    const hasTitle = profile.currentTitle.trim().length > 0;
    const skills = job.matchedSkills.slice(0, 5);
    const missingSkills = job.missingSkills.slice(0, 5);

    questions.push({
      id: `${job.id}-opening`,
      category: InterviewCategory.OPENING,
      prompt: `Tell me about yourself and why you are applying for the ${job.title} role at ${job.company}.`,
      whyItMatters: "The opening answer sets the tone; a tight two-minute story beats a life history.",
      structure: [
        "One line on who you are today.",
        "Two or three sentences on the experience that fits this posting.",
        "One line on why this role, at this company, in this country.",
      ],
      grounding: [
        ...(hasTitle ? [`Your current title: ${profile.currentTitle}`] : []),
        ...(profile.yearsOfExperience != null ? [`Your experience: ${profile.yearsOfExperience} years`] : []),
        ...(skills.length > 0 ? [`Skills that match: ${skills.join(", ")}`] : []),
      ],
      missingFacts: [
        ...(hasName ? [] : ["Your name"]),
        ...(hasTitle ? [] : ["Your professional title"]),
      ],
    });

    questions.push({
      id: `${job.id}-experience`,
      category: InterviewCategory.EXPERIENCE,
      prompt: `Walk me through the experience that makes you ready for a ${job.title} role.`,
      whyItMatters: "Interviewers hire on evidence, not adjectives. Numbers and outcomes carry the answer.",
      structure: [
        "Name the role and the company where you did this work.",
        "State the problem you were given.",
        "State what you did and the result, with a number if you have one.",
      ],
      grounding: [
        ...(profile.summary.trim().length > 0 ? [profile.summary] : []),
        ...(skills.length > 0 ? [`Core skills to evidence: ${skills.join(", ")}`] : []),
      ],
      missingFacts: skills.length === 0 ? ["Skills that overlap with this posting"] : [],
    });

    if (skills.length > 0 || missingSkills.length > 0) {
      questions.push({
        id: `${job.id}-technical`,
        category: InterviewCategory.TECHNICAL,
        prompt: `The posting asks for ${skills.length > 0 ? skills.join(", ") : "specific trade skills"}. How do you handle these day to day?`,
        whyItMatters: "This is where the panel checks your hands-on depth, not your vocabulary.",
        structure: [
          "Pick one skill and give a concrete example of using it.",
          "Explain how you would diagnose a fault or a failure in it.",
          "Say what safety or quality step you never skip.",
        ],
        grounding: skills.length > 0 ? [`Skills this posting lists: ${skills.join(", ")}`] : [],
        missingFacts: [
          ...(skills.length === 0 ? ["Skills that overlap with this posting"] : []),
          ...(missingSkills.length > 0 ? [`Skills to prepare a story for: ${missingSkills.join(", ")}`] : []),
        ],
      });
    }

    questions.push({
      id: `${job.id}-behavioural`,
      category: InterviewCategory.BEHAVIOURAL,
      prompt: "Describe a time you disagreed with a supervisor or a colleague. How did you handle it?",
      whyItMatters: "Behavioural questions predict how you will behave on a team you have not met.",
      structure: [
        "Set the scene in one sentence.",
        "Say what you did and why.",
        "Give the outcome, and what you learned.",
      ],
      grounding: hasTitle ? [`Frame it from your role as ${profile.currentTitle}`] : [],
      missingFacts: [],
    });

    questions.push({
      id: `${job.id}-relocation`,
      category: InterviewCategory.RELOCATION,
      prompt: `This role is in ${job.country}. Are you ready to relocate, and what do you need from us to make it work?`,
      whyItMatters: "Employers ask because relocation logistics decide whether an offer is real.",
      structure: [
        "State your position on relocating plainly.",
        "Name the documents and timelines you can control.",
        "Ask what support they provide for the visa and the move.",
      ],
      grounding: [`Destination: ${job.country}`, `Visa support on the posting: ${job.visaSupport}`],
      missingFacts: [],
    });

    if (job.languages.length > 0) {
      questions.push({
        id: `${job.id}-language`,
        category: InterviewCategory.LANGUAGE,
        prompt: `The role works in ${job.languages.join(", ")}. Can you describe your level and give an example?`,
        whyItMatters: "Language is often the deciding factor for an international hire, above the trade skills.",
        structure: [
          "State your level in each language honestly.",
          "Give one example of using it at work.",
          "Say what you are doing to improve.",
        ],
        grounding: job.languages.map((lang) => `Posting language: ${lang}`),
        missingFacts: profile.languages.length === 0 ? ["Languages and levels on your CV"] : [],
      });
    }

    questions.push({
      id: `${job.id}-salary`,
      category: InterviewCategory.SALARY,
      prompt: "What salary are you expecting, and how did you arrive at that figure?",
      whyItMatters: "A number you can justify protects you from being anchored too low.",
      structure: [
        "Give a range, not a single figure.",
        "Say what you based it on (market, cost of living, your experience).",
        "State that you are open to discussing the full package.",
      ],
      grounding:
        job.salaryMax > 0
          ? [`The posting advertises ${job.currencyCode} ${job.salaryMin}–${job.salaryMax}`]
          : ["The posting does not advertise a salary — ask for the band first."],
      missingFacts: profile.salaryExpectationMin == null ? ["Your salary expectation"] : [],
    });

    questions.push({
      id: `${job.id}-ask-them`,
      category: InterviewCategory.ASK_THEM,
      prompt: "What questions will you ask them?",
      whyItMatters: "Good questions show you are evaluating them too, and they surface the real terms.",
      structure: [
        "Ask about the visa and permit process and who handles it.",
        "Ask about relocation support: housing, flights, settling-in.",
        "Ask what success looks like in the first six months.",
      ],
      grounding: [`Visa support stated: ${job.visaSupport}`, `Benefits listed: ${job.benefits.length}`],
      missingFacts: [],
    });

    return questions.sort(
      (a, b) => INTERVIEW_CATEGORY_ORDER.indexOf(a.category) - INTERVIEW_CATEGORY_ORDER.indexOf(b.category),
    );
  }
}

export interface InterviewCoachOptions {
  clock?: () => number;
  bank?: InterviewQuestionBank;
}

/**
 * The interview coach. Mirrors the Kotlin `InterviewCoach`: it assembles the prep set, scores
 * a written answer against the posting's own vocabulary, and derives readiness from the saved
 * rehearsal attempts so the progress number is measured, never guessed.
 */
export class InterviewCoach {
  private readonly clock: () => number;
  private readonly bank: InterviewQuestionBank;

  constructor(options?: InterviewCoachOptions) {
    this.clock = options?.clock ?? Date.now;
    this.bank = options?.bank ?? new InterviewQuestionBank();
  }

  prep(job: Job, profile: CandidateProfile): InterviewPrep {
    const questions = this.bank.build(job, profile);
    const focusAreas: string[] = [];
    if (job.missingSkills.length > 0) {
      focusAreas.push(`Close the gaps on ${job.missingSkills.slice(0, 3).join(", ")}`);
    }
    if (job.languages.length > 0) focusAreas.push(`Practise speaking ${job.languages.join(", ")}`);
    if (
      job.visaSupport === VisaSupport.FULL_SPONSORSHIP ||
      job.visaSupport === VisaSupport.RELOCATION_PACKAGE ||
      job.visaSupport === VisaSupport.SPONSORSHIP_AVAILABLE
    ) {
      focusAreas.push("Be ready to explain your visa situation clearly");
    }
    if (job.eligibility === MadagascarEligibility.ELIGIBLE || job.eligibility === MadagascarEligibility.VISA_SPONSORED) {
      focusAreas.push(`Show you understand working in ${job.country}`);
    }

    return {
      jobId: job.id,
      jobTitle: job.title,
      company: job.company,
      country: job.country,
      countryFlag: job.countryFlag,
      questions,
      focusAreas,
      generatedAtEpochMillis: this.clock(),
    };
  }

  /** Deterministic feedback on a written answer, checked against the question's own grounding. */
  feedback(question: InterviewQuestion, answer: string): AnswerFeedback {
    const words = answer.split(/\s+/).filter((w) => w.length > 0);
    const strengths: string[] = [];
    const improvements: string[] = [];
    let score = 0;

    if (words.length >= 60) {
      strengths.push("The answer is developed, not a one-liner.");
      score += 30;
    } else if (words.length >= 25) {
      improvements.push("Add more detail — aim for 60 words or more.");
      score += 15;
    } else {
      improvements.push("This is too short to judge. Expand it with a concrete example.");
    }

    const lower = normalize(answer);
    const grounded = question.grounding.filter((g) => {
      const tokens = normalize(g).split(/\W+/).filter((t) => t.length > 3);
      return tokens.some((t) => lower.includes(t));
    });
    if (grounded.length > 0) {
      strengths.push("You used facts from your CV and the posting.");
      score += 30;
    } else if (question.grounding.length > 0) {
      improvements.push("Reference a concrete fact: a skill, a role, or the country.");
    }

    const hasNumber = /\d/.test(answer);
    if (hasNumber) {
      strengths.push("You included a number, which makes the answer credible.");
      score += 20;
    } else {
      improvements.push("Add a figure — a year, a team size, a result.");
    }

    if (words.length >= 60 && grounded.length > 0 && hasNumber) score += 20;

    const bounded = Math.min(100, score);
    const headline =
      bounded >= 80
        ? "Strong answer"
        : bounded >= 50
          ? "Solid — tighten the detail"
          : "Needs more substance";

    return { score: bounded, headline, strengths, improvements };
  }

  /** Turns saved attempts into a measured readiness figure for one prep set. */
  readiness(prep: InterviewPrep, attempts: InterviewAttempt[]): InterviewReadiness {
    const byQuestion = new Map(attempts.map((a) => [a.questionId, a]));
    let attempted = 0;
    let confident = 0;
    let points = 0;
    const total = prep.questions.length;

    for (const question of prep.questions) {
      const attempt = byQuestion.get(question.id);
      if (!attempt) continue;
      attempted += 1;
      switch (attempt.confidence) {
        case AnswerConfidence.CONFIDENT:
          confident += 1;
          points += 100;
          break;
        case AnswerConfidence.GETTING_THERE:
          points += 60;
          break;
        default:
          points += 20;
          break;
      }
    }

    const percent = total === 0 ? 0 : Math.round(points / total);
    const isComplete = total > 0 && attempted === total;
    const label =
      isComplete && percent >= 80
        ? "You are ready"
        : isComplete
          ? "Covered — keep sharpening"
          : attempted === 0
            ? "Not started"
            : `${attempted} of ${total} rehearsed`;

    return { attempted, total, confident, percent, isComplete, label };
  }
}
