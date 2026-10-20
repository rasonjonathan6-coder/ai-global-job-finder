import {
  AnswerConfidence,
  InterviewCategory,
  LANGUAGE_LEVEL_META,
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
 * Builds the rehearsal set for one posting from that posting and the candidate's CV. Mirrors
 * the Kotlin `InterviewQuestionBank`.
 *
 * Every question is grounded: it either names a real detail of the posting (a required skill,
 * the destination country, the visa posture) or a real detail of the CV (a matched skill, a
 * certification, a language). Questions the CV cannot support are still asked — an interviewer
 * will ask them — but the missing facts are recorded so the user sees the gap instead of an
 * answer the app invented.
 *
 * The bank is a pure function of its inputs, so it is covered by tests without a device.
 */
export class InterviewQuestionBank {
  /** The full rehearsal set for one posting, in display order. */
  questions(job: Job, profile: CandidateProfile): InterviewQuestion[] {
    return [
      this.opening(job, profile),
      ...this.experience(job, profile),
      ...this.technical(job, profile),
      ...this.behavioural(job),
      this.relocation(job),
      ...(this.languageQuestion(job, profile) ? [this.languageQuestion(job, profile)!] : []),
      ...this.salary(job, profile),
      this.askThem(job),
    ];
  }

  /** The one-line pointers the coach prioritises, derived from the match gaps. */
  focusAreas(job: Job, profile: CandidateProfile): string[] {
    const areas: string[] = [];
    if (job.missingSkills.length > 0) {
      areas.push(
        `Prepare a short story for each skill the posting asks for but your CV does not show yet: ${job.missingSkills.join(", ")}.`,
      );
    }
    if (job.visaSupport === VisaSupport.NOT_MENTIONED) {
      areas.push(
        "The posting does not state visa support, so be ready to ask how they handle sponsorship for a Madagascar-based hire.",
      );
    }
    if (job.languages.length > 0) {
      areas.push(
        `The posting names ${job.languages.join(", ")}. Be ready to hold a short conversation in the language they will interview you in.`,
      );
    }
    if (profile.certifications.length === 0) {
      areas.push(
        "You list no certifications. Interviewers in this market often ask which trade certificates you hold — gather the documents first.",
      );
    }
    if (profile.yearsOfExperience == null) {
      areas.push(
        'Your CV does not state your years of experience. Add it, so you can answer "how many years?" without hesitating.',
      );
    }
    if (job.experienceYears > 0) {
      areas.push(`The posting asks for ${job.experienceYears}+ years. Map your work history onto that number out loud.`);
    }
    if (areas.length === 0) {
      areas.push("Your CV covers the posting well. Rehearse the STAR structure so your answers stay tight.");
    }
    return areas;
  }

  private opening(job: Job, profile: CandidateProfile): InterviewQuestion {
    return {
      id: `opening-${job.id}`,
      category: InterviewCategory.OPENING,
      prompt: `Tell me about yourself and why you are applying for ${job.title} at ${job.company}.`,
      whyItMatters:
        "This is almost always the first question. A rehearsed 60–90 second answer sets the tone for the whole interview.",
      structure: [
        "Who you are today, in one sentence.",
        `Your most relevant experience for ${job.title}.`,
        "One concrete result you are proud of.",
        "Why this role and this country, in one sentence.",
      ],
      grounding: [
        ...(profile.currentTitle.trim().length > 0 ? [`Your current title: ${profile.currentTitle}`] : []),
        ...(profile.yearsOfExperience != null ? [`Your experience: ${profile.yearsOfExperience} years`] : []),
        ...(job.matchedSkills.length > 0
          ? [`Skills that match the posting: ${job.matchedSkills.join(", ")}`]
          : []),
      ],
      missingFacts: [
        ...(profile.fullName.trim().length === 0
          ? ["Your CV does not show your name — add it before rehearsing."]
          : []),
        ...(profile.currentTitle.trim().length === 0
          ? ["Your CV does not show a current professional title."]
          : []),
        ...(profile.yearsOfExperience == null ? ["Your CV does not state your years of experience."] : []),
      ],
    };
  }

  private experience(job: Job, profile: CandidateProfile): InterviewQuestion[] {
    const questions: InterviewQuestion[] = [];
    const roleWord = job.title.split(" ").find((w) => w.length > 3)?.toLowerCase() ?? "role";
    questions.push({
      id: `experience-1-${job.id}`,
      category: InterviewCategory.EXPERIENCE,
      prompt: `Walk me through your most relevant work experience for this ${roleWord} position.`,
      whyItMatters:
        "The interviewer is checking whether your day-to-day work actually resembles this job.",
      structure: [
        "Name the employer, the role and the dates.",
        "Describe what you were responsible for.",
        "Give one measurable result.",
        `Connect it to what ${job.company} needs.`,
      ],
      grounding: profile.previousTitles.slice(0, 2).map((title) => `Previous role: ${title}`),
      missingFacts:
        profile.previousTitles.length === 0
          ? ["Your CV does not list previous job titles — add at least one recent role."]
          : [],
    });

    if (job.experienceYears > 0) {
      const years = profile.yearsOfExperience;
      questions.push({
        id: `experience-2-${job.id}`,
        category: InterviewCategory.EXPERIENCE,
        prompt: `The posting asks for ${job.experienceYears}+ years. How does your experience cover that requirement?`,
        whyItMatters:
          "A mismatch here is the fastest way to be filtered out, so answer it directly rather than hoping it is not raised.",
        structure: [
          "State your total years up front.",
          "Break them down by the roles that are most relevant.",
          "Name the parts of the requirement you have already done.",
          "Name the parts you would grow into, honestly.",
        ],
        grounding: years != null ? [`Your CV states ${years} years of experience`] : [],
        missingFacts: years == null ? ["Your CV does not state your total years of experience."] : [],
      });
    }
    return questions;
  }

  private technical(job: Job, profile: CandidateProfile): InterviewQuestion[] {
    void profile;
    const questions: InterviewQuestion[] = [];
    job.matchedSkills.slice(0, 3).forEach((skill, index) => {
      questions.push({
        id: `technical-${index}-${job.id}`,
        category: InterviewCategory.TECHNICAL,
        prompt: `Describe a time you used ${skill} in practice.`,
        whyItMatters: `${skill} is named in the posting and appears in your CV, so the interviewer will test it.`,
        structure: [
          "Set the scene briefly.",
          `Explain exactly what you did with ${skill}.`,
          "Give the result, with a number if you have one.",
          "Say what you would do differently now.",
        ],
        grounding: [`${skill} appears in both the posting and your CV`],
      });
    });
    job.missingSkills.slice(0, 3).forEach((skill, index) => {
      questions.push({
        id: `technical-gap-${index}-${job.id}`,
        category: InterviewCategory.TECHNICAL,
        prompt: `The role mentions ${skill}. How would you get up to speed on it?`,
        whyItMatters: `${skill} is in the posting but not in your CV. Interviewers accept a gap when you show a concrete plan.`,
        structure: [
          "Acknowledge the gap honestly.",
          "Name the closest skill you do have.",
          "Describe how you have learned something similar before.",
          "Give a specific first step you would take.",
        ],
        grounding: [],
        missingFacts: [`${skill} is not shown in your CV yet`],
      });
    });
    if (job.matchedSkills.length === 0 && job.missingSkills.length === 0) {
      questions.push({
        id: `technical-generic-${job.id}`,
        category: InterviewCategory.TECHNICAL,
        prompt: "What are the core technical skills this role needs, and how strong are you in each?",
        whyItMatters:
          "When the posting is light on detail, the interviewer still wants a clear self-assessment.",
        structure: [
          "List the three skills the role needs most.",
          "Rate yourself honestly on each.",
          "Give one piece of evidence per skill.",
          "Name the one you are actively improving.",
        ],
        grounding: [],
      });
    }
    return questions;
  }

  private behavioural(job: Job): InterviewQuestion[] {
    return [
      {
        id: `behavioural-1-${job.id}`,
        category: InterviewCategory.BEHAVIOURAL,
        prompt: "Tell me about a time you solved a problem under pressure.",
        whyItMatters:
          "This tests how you think, not just what you know — and it is asked in almost every international interview.",
        structure: [
          "Situation — set the scene in two sentences.",
          "Task — what you specifically had to deliver.",
          "Action — what you actually did, step by step.",
          "Result — the outcome, with a number if possible.",
        ],
        grounding: [],
      },
      {
        id: `behavioural-2-${job.id}`,
        category: InterviewCategory.BEHAVIOURAL,
        prompt: "Describe a disagreement with a colleague or manager and how you handled it.",
        whyItMatters:
          "Working abroad means working across cultures. Interviewers look for calm, direct conflict handling.",
        structure: [
          "The situation, without blaming anyone.",
          "What you wanted and what they wanted.",
          "How you listened and found common ground.",
          "What the outcome was and what you learned.",
        ],
        grounding: [],
      },
    ];
  }

  private relocation(job: Job): InterviewQuestion {
    const country = job.country.trim().length > 0 ? job.country : "the destination country";
    return {
      id: `relocation-${job.id}`,
      category: InterviewCategory.RELOCATION,
      prompt: `You are applying from Madagascar. Are you willing and able to relocate to ${country}?`,
      whyItMatters: "Employers ask this first because relocation is a real cost and a real risk for them.",
      structure: [
        "Answer yes or no clearly, in the first sentence.",
        "State when you could start.",
        `Say what you have already researched about living in ${country}.`,
        "Ask what support they provide for the move.",
      ],
      grounding: [
        `The posting is based in ${country}`,
        ...(job.visaSupport === VisaSupport.FULL_SPONSORSHIP
          ? ["The posting states full visa sponsorship"]
          : job.visaSupport === VisaSupport.RELOCATION_PACKAGE
            ? ["The posting offers a relocation package"]
            : job.visaSupport === VisaSupport.SPONSORSHIP_AVAILABLE
              ? ["The posting says sponsorship is available"]
              : []),
      ],
      missingFacts:
        job.visaSupport === VisaSupport.NOT_MENTIONED
          ? ["The posting does not state visa support — ask about it explicitly."]
          : [],
    };
  }

  private languageQuestion(job: Job, profile: CandidateProfile): InterviewQuestion | null {
    const required = job.languages;
    if (required.length === 0 && profile.languages.length === 0) return null;
    const language = required[0] ?? profile.languages[0].name;
    const skill = profile.languages.find((s) => s.name.toLowerCase() === language.toLowerCase());
    const level = skill?.level ?? null;
    return {
      id: `language-${job.id}`,
      category: InterviewCategory.LANGUAGE,
      prompt: `This role is conducted in ${language}. Tell me about your level and give an example of using it at work.`,
      whyItMatters: "A language gap is a common reason offers are withdrawn after the interview.",
      structure: [
        "State your level plainly.",
        `Give one work example where you used ${language}.`,
        "Name the setting you are weakest in (phone, writing, technical terms).",
        "Say how you are improving it.",
      ],
      grounding:
        level != null
          ? [`Your CV lists ${language} — ${LANGUAGE_LEVEL_META[level].label}`]
          : profile.languages.length > 0
            ? [`Your CV lists: ${profile.languages.map((s) => s.name).join(", ")}`]
            : [],
      missingFacts: level == null ? [`Your CV does not state a level for ${language}.`] : [],
    };
  }

  private salary(job: Job, profile: CandidateProfile): InterviewQuestion[] {
    return [
      {
        id: `salary-${job.id}`,
        category: InterviewCategory.SALARY,
        prompt: "What salary are you expecting for this role?",
        whyItMatters:
          "Answering too low costs you money; answering too high ends the process. Have a range ready.",
        structure: [
          "Give a researched range, not a single number.",
          `Base it on the local market for ${job.country}, not on Madagascar salaries.`,
          "Mention that you are flexible on the package as a whole.",
          "Ask what range is budgeted for the role.",
        ],
        grounding:
          job.salaryMin > 0
            ? [
                `The posting advertises ${job.currencyCode} ${job.salaryMin}–${job.salaryMax} per ${job.salaryPeriod.toLowerCase()}`,
              ]
            : ["The posting does not state a salary"],
        missingFacts:
          profile.salaryExpectationMin == null
            ? ["You have not recorded a salary expectation in your profile."]
            : [],
      },
      {
        id: `contract-${job.id}`,
        category: InterviewCategory.SALARY,
        prompt: "What do you need in the offer to accept it — beyond salary?",
        whyItMatters:
          "Visa costs, housing and flights are negotiable and are often easier for the employer to give than a higher salary.",
        structure: [
          "Separate what you need from what you would like.",
          "Name the relocation costs you cannot cover yourself.",
          "Ask about the visa route and who pays for it.",
          "Ask about the probation period and notice.",
        ],
        grounding: [`Destination: ${job.country}`],
      },
    ];
  }

  private askThem(job: Job): InterviewQuestion {
    return {
      id: `ask-${job.id}`,
      category: InterviewCategory.ASK_THEM,
      prompt: `What questions should you ask ${job.company} at the end of the interview?`,
      whyItMatters:
        "The questions you ask show whether you understand the move. They are also the moment to clarify visa reality.",
      structure: [
        "Ask how the visa and work permit process works for a Madagascar-based hire.",
        "Ask what the first three months in the role look like.",
        "Ask how the team is structured and who you would report to.",
        "Ask what would make someone succeed in this role.",
      ],
      grounding: [`Employer: ${job.company}`, ...(job.source.trim().length > 0 ? [`Source: ${job.source}`] : [])],
    };
  }
}

export interface InterviewCoachOptions {
  clock?: () => number;
  bank?: InterviewQuestionBank;
}

const NUMBER = /\d/;
const EVIDENCE_WORDS = [
  "used", "built", "led", "managed", "installed", "repaired", "tested", "delivered", "team", "project",
];
const STRUCTURE_HINTS = [
  "situation", "task", "action", "result", "because", "so", "then", "after", "first", "finally", "when", "while",
];
const RESULT_WORDS = [
  "result", "outcome", "increased", "reduced", "saved", "improved", "delivered", "achieved", "so we", "which meant",
];

/**
 * The interview coach. Mirrors the Kotlin `InterviewCoach`: it builds the rehearsal set for a
 * posting and grades a written answer against the same structure the question asked for.
 *
 * Grading is deliberately about shape, not content: the app cannot know whether an anecdote is
 * true, so it only checks whether the answer is long enough to be credible, whether it names
 * evidence, and whether it reaches a result. It never rewrites the candidate's story.
 */
export class InterviewCoach {
  private readonly clock: () => number;
  private readonly bank: InterviewQuestionBank;

  constructor(options?: InterviewCoachOptions) {
    this.clock = options?.clock ?? Date.now;
    this.bank = options?.bank ?? new InterviewQuestionBank();
  }

  prep(job: Job, profile: CandidateProfile): InterviewPrep {
    return {
      jobId: job.id,
      jobTitle: job.title,
      company: job.company,
      country: job.country,
      countryFlag: job.countryFlag,
      questions: this.bank.questions(job, profile),
      focusAreas: this.bank.focusAreas(job, profile),
      generatedAtEpochMillis: this.clock(),
    };
  }

  /** Grades one written answer. The score is 0–100 and the notes are all actionable. */
  feedback(question: InterviewQuestion, answer: string): AnswerFeedback {
    const text = answer.trim();
    const words = text.split(/\s+/).filter((w) => w.length > 0);
    const wordCount = words.length;

    const strengths: string[] = [];
    const improvements: string[] = [];
    let score = 0;

    // Length: enough to be credible, not so long it rambles.
    if (wordCount >= 60) {
      score += 35;
      strengths.push(`Your answer is detailed enough to be credible (${wordCount} words).`);
    } else if (wordCount >= 30) {
      score += 25;
      strengths.push(`A solid length (${wordCount} words); expand it with one more detail to be safe.`);
    } else if (wordCount >= 12) {
      score += 12;
      improvements.push(`Only ${wordCount} words. Aim for 60–120 words so the interviewer gets a full picture.`);
    } else {
      improvements.push("This is too short to answer the question. Write at least a few sentences.");
    }

    // Evidence: numbers and named tools/employers.
    const hasNumbers = NUMBER.test(text);
    const hasEvidenceWords = EVIDENCE_WORDS.some((w) => text.toLowerCase().includes(w));
    if (hasNumbers && hasEvidenceWords) {
      score += 25;
      strengths.push("You back the answer with specifics and figures.");
    } else if (hasNumbers || hasEvidenceWords) {
      score += 15;
      improvements.push("Add one more concrete detail — a number, a tool or an employer name.");
    } else {
      improvements.push("No specifics detected. Name the tools you used and add one measurable result.");
    }

    // Structure: the STAR pieces the question asked for.
    const matchedStructure = question.structure.filter(
      (step) => step.trim().length > 0 && STRUCTURE_HINTS.some((hint) => text.toLowerCase().includes(hint)),
    ).length;
    if (question.structure.length > 0) {
      if (matchedStructure >= 3) {
        score += 25;
        strengths.push("You cover the situation, the action and the result.");
      } else if (matchedStructure === 2) {
        score += 14;
        improvements.push("You cover two of the four parts. Add the missing result or situation.");
      } else {
        improvements.push(`Follow the ${question.structure.length}-part structure listed for this question.`);
      }
    } else {
      score += 15;
    }

    // Outcome: does the answer actually land on a result?
    if (RESULT_WORDS.some((w) => text.toLowerCase().includes(w))) {
      score += 15;
      strengths.push("You state an outcome, which is what interviewers remember.");
    } else {
      improvements.push("Finish with the result: what changed because of what you did?");
    }

    const finalScore = Math.max(0, Math.min(100, score));
    const headline =
      finalScore >= 80
        ? "Strong answer — ready to say out loud."
        : finalScore >= 60
          ? "Good base — tighten the evidence and the ending."
          : finalScore >= 35
            ? "Workable, but thin. Add structure and specifics."
            : "Needs a rewrite. Follow the structure below step by step.";
    if (improvements.length === 0) {
      improvements.push("Nothing critical missing — rehearse it out loud and time yourself.");
    }

    return { score: finalScore, headline, strengths, improvements };
  }

  /** Derives progress from the saved attempts, so readiness is never guessed. */
  readiness(questions: InterviewQuestion[], attempts: InterviewAttempt[]): InterviewReadiness {
    const questionIds = new Set(questions.map((q) => q.id));
    const attempted = new Set(attempts.map((a) => a.questionId).filter((id) => questionIds.has(id))).size;
    const confident = attempts.filter((a) => a.confidence === AnswerConfidence.CONFIDENT).length;
    const total = questions.length;
    const percent = total === 0 ? 0 : Math.trunc((attempted * 100) / total);
    const isComplete = total > 0 && attempted >= total;
    const label =
      total === 0
        ? "Not started"
        : percent >= 100 && confident === total
          ? "Interview ready"
          : percent >= 100
            ? "Rehearsed — polish the weak answers"
            : percent >= 50
              ? "Half rehearsed"
              : "Just started";
    return { attempted, total, confident, percent, isComplete, label };
  }
}
