package com.aiglobal.jobfinder.data.interview

import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.InterviewCategory
import com.aiglobal.jobfinder.data.model.InterviewQuestion
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.VisaSupport

/**
 * Builds the rehearsal set for one posting from that posting and the candidate's CV.
 *
 * Every question is grounded: it either names a real detail of the posting (a required
 * skill, the destination country, the visa posture) or a real detail of the CV (a
 * matched skill, a certification, a language). Questions the CV cannot support are
 * still asked — an interviewer will ask them — but the missing facts are recorded so the
 * user sees the gap instead of an answer the app invented.
 *
 * The bank is a pure function of its inputs, so it runs on the JVM and is covered by
 * tests without Robolectric.
 */
class InterviewQuestionBank {

    fun questions(job: Job, profile: CandidateProfile): List<InterviewQuestion> = buildList {
        add(opening(job, profile))
        addAll(experience(job, profile))
        addAll(technical(job, profile))
        addAll(behavioural(job))
        add(relocation(job))
        languageQuestion(job, profile)?.let(::add)
        addAll(salary(job, profile))
        add(askThem(job))
    }

    /** The one-line pointers the coach prioritises, derived from the match gaps. */
    fun focusAreas(job: Job, profile: CandidateProfile): List<String> = buildList {
        if (job.missingSkills.isNotEmpty()) {
            add("Prepare a short story for each skill the posting asks for but your CV does not show yet: ${job.missingSkills.joinToString(", ")}.")
        }
        if (job.visaSupport == VisaSupport.NOT_MENTIONED || job.eligibility == MadagascarEligibility.UNCLEAR) {
            add("The posting does not state visa support, so be ready to ask how they handle sponsorship for a Madagascar-based hire.")
        }
        if (job.languages.isNotEmpty()) {
            add("The posting names ${job.languages.joinToString(", ")}. Be ready to hold a short conversation in the language they will interview you in.")
        }
        if (profile.certifications.isEmpty()) {
            add("You list no certifications. Interviewers in this market often ask which trade certificates you hold — gather the documents first.")
        }
        if (profile.yearsOfExperience == null) {
            add("Your CV does not state your years of experience. Add it, so you can answer \"how many years?\" without hesitating.")
        }
        if (job.experienceYears > 0) {
            add("The posting asks for ${job.experienceYears}+ years. Map your work history onto that number out loud.")
        }
        if (isEmpty()) {
            add("Your CV covers the posting well. Rehearse the STAR structure so your answers stay tight.")
        }
    }

    private fun opening(job: Job, profile: CandidateProfile): InterviewQuestion {
        return InterviewQuestion(
            id = "opening-${job.id}",
            category = InterviewCategory.OPENING,
            prompt = "Tell me about yourself and why you are applying for ${job.title} at ${job.company}.",
            whyItMatters = "This is almost always the first question. A rehearsed 60–90 second answer sets the tone for the whole interview.",
            structure = listOf(
                "Who you are today, in one sentence.",
                "Your most relevant experience for ${job.title}.",
                "One concrete result you are proud of.",
                "Why this role and this country, in one sentence.",
            ),
            grounding = buildList {
                if (profile.currentTitle.isNotBlank()) add("Your current title: ${profile.currentTitle}")
                profile.yearsOfExperience?.let { add("Your experience: $it years") }
                if (job.matchedSkills.isNotEmpty()) add("Skills that match the posting: ${job.matchedSkills.joinToString(", ")}")
            },
            missingFacts = buildList {
                if (profile.fullName.isBlank()) add("Your CV does not show your name — add it before rehearsing.")
                if (profile.currentTitle.isBlank()) add("Your CV does not show a current professional title.")
                if (profile.yearsOfExperience == null) add("Your CV does not state your years of experience.")
            },
        )
    }

    private fun experience(job: Job, profile: CandidateProfile): List<InterviewQuestion> = buildList {
        val roleWord = job.title.split(" ").firstOrNull { it.length > 3 }?.lowercase() ?: "role"
        add(
            InterviewQuestion(
                id = "experience-1-${job.id}",
                category = InterviewCategory.EXPERIENCE,
                prompt = "Walk me through your most relevant work experience for this $roleWord position.",
                whyItMatters = "The interviewer is checking whether your day-to-day work actually resembles this job.",
                structure = listOf(
                    "Name the employer, the role and the dates.",
                    "Describe what you were responsible for.",
                    "Give one measurable result.",
                    "Connect it to what ${job.company} needs.",
                ),
                grounding = profile.previousTitles.take(2).map { "Previous role: $it" },
                missingFacts = if (profile.previousTitles.isEmpty()) {
                    listOf("Your CV does not list previous job titles — add at least one recent role.")
                } else emptyList(),
            ),
        )

        if (job.experienceYears > 0) {
            val years = profile.yearsOfExperience
            add(
                InterviewQuestion(
                    id = "experience-2-${job.id}",
                    category = InterviewCategory.EXPERIENCE,
                    prompt = "The posting asks for ${job.experienceYears}+ years. How does your experience cover that requirement?",
                    whyItMatters = "A mismatch here is the fastest way to be filtered out, so answer it directly rather than hoping it is not raised.",
                    structure = listOf(
                        "State your total years up front.",
                        "Break them down by the roles that are most relevant.",
                        "Name the parts of the requirement you have already done.",
                        "Name the parts you would grow into, honestly.",
                    ),
                    grounding = if (years != null) listOf("Your CV states $years years of experience") else emptyList(),
                    missingFacts = if (years == null) {
                        listOf("Your CV does not state your total years of experience.")
                    } else emptyList(),
                ),
            )
        }
    }

    private fun technical(job: Job, profile: CandidateProfile): List<InterviewQuestion> = buildList {
        job.matchedSkills.take(3).forEachIndexed { index, skill ->
            add(
                InterviewQuestion(
                    id = "technical-$index-${job.id}",
                    category = InterviewCategory.TECHNICAL,
                    prompt = "Describe a time you used $skill in practice.",
                    whyItMatters = "$skill is named in the posting and appears in your CV, so the interviewer will test it.",
                    structure = listOf(
                        "Set the scene briefly.",
                        "Explain exactly what you did with $skill.",
                        "Give the result, with a number if you have one.",
                        "Say what you would do differently now.",
                    ),
                    grounding = listOf("$skill appears in both the posting and your CV"),
                ),
            )
        }
        job.missingSkills.take(3).forEachIndexed { index, skill ->
            add(
                InterviewQuestion(
                    id = "technical-gap-$index-${job.id}",
                    category = InterviewCategory.TECHNICAL,
                    prompt = "The role mentions $skill. How would you get up to speed on it?",
                    whyItMatters = "$skill is in the posting but not in your CV. Interviewers accept a gap when you show a concrete plan.",
                    structure = listOf(
                        "Acknowledge the gap honestly.",
                        "Name the closest skill you do have.",
                        "Describe how you have learned something similar before.",
                        "Give a specific first step you would take.",
                    ),
                    missingFacts = listOf("$skill is not shown in your CV yet"),
                ),
            )
        }
        if (job.matchedSkills.isEmpty() && job.missingSkills.isEmpty()) {
            add(
                InterviewQuestion(
                    id = "technical-generic-${job.id}",
                    category = InterviewCategory.TECHNICAL,
                    prompt = "What are the core technical skills this role needs, and how strong are you in each?",
                    whyItMatters = "When the posting is light on detail, the interviewer still wants a clear self-assessment.",
                    structure = listOf(
                        "List the three skills the role needs most.",
                        "Rate yourself honestly on each.",
                        "Give one piece of evidence per skill.",
                        "Name the one you are actively improving.",
                    ),
                ),
            )
        }
    }

    private fun behavioural(job: Job): List<InterviewQuestion> = listOf(
        InterviewQuestion(
            id = "behavioural-1-${job.id}",
            category = InterviewCategory.BEHAVIOURAL,
            prompt = "Tell me about a time you solved a problem under pressure.",
            whyItMatters = "This tests how you think, not just what you know — and it is asked in almost every international interview.",
            structure = listOf(
                "Situation — set the scene in two sentences.",
                "Task — what you specifically had to deliver.",
                "Action — what you actually did, step by step.",
                "Result — the outcome, with a number if possible.",
            ),
        ),
        InterviewQuestion(
            id = "behavioural-2-${job.id}",
            category = InterviewCategory.BEHAVIOURAL,
            prompt = "Describe a disagreement with a colleague or manager and how you handled it.",
            whyItMatters = "Working abroad means working across cultures. Interviewers look for calm, direct conflict handling.",
            structure = listOf(
                "The situation, without blaming anyone.",
                "What you wanted and what they wanted.",
                "How you listened and found common ground.",
                "What the outcome was and what you learned.",
            ),
        ),
    )

    private fun relocation(job: Job): InterviewQuestion {
        val country = job.country.ifBlank { "the destination country" }
        return InterviewQuestion(
            id = "relocation-${job.id}",
            category = InterviewCategory.RELOCATION,
            prompt = "You are applying from Madagascar. Are you willing and able to relocate to $country?",
            whyItMatters = "Employers ask this first because relocation is a real cost and a real risk for them.",
            structure = listOf(
                "Answer yes or no clearly, in the first sentence.",
                "State when you could start.",
                "Say what you have already researched about living in $country.",
                "Ask what support they provide for the move.",
            ),
            grounding = buildList {
                add("The posting is based in $country")
                when (job.visaSupport) {
                    VisaSupport.FULL_SPONSORSHIP -> add("The posting states full visa sponsorship")
                    VisaSupport.RELOCATION_PACKAGE -> add("The posting offers a relocation package")
                    VisaSupport.SPONSORSHIP_AVAILABLE -> add("The posting says sponsorship is available")
                    else -> Unit
                }
            },
            missingFacts = if (job.visaSupport == VisaSupport.NOT_MENTIONED) {
                listOf("The posting does not state visa support — ask about it explicitly.")
            } else emptyList(),
        )
    }

    private fun languageQuestion(job: Job, profile: CandidateProfile): InterviewQuestion? {
        val required = job.languages
        if (required.isEmpty() && profile.languages.isEmpty()) return null
        val language = required.firstOrNull() ?: profile.languages.first().name
        val level = profile.languages.firstOrNull { it.name.equals(language, ignoreCase = true) }?.level
        return InterviewQuestion(
            id = "language-${job.id}",
            category = InterviewCategory.LANGUAGE,
            prompt = "This role is conducted in $language. Tell me about your level and give an example of using it at work.",
            whyItMatters = "A language gap is a common reason offers are withdrawn after the interview.",
            structure = listOf(
                "State your level plainly.",
                "Give one work example where you used $language.",
                "Name the setting you are weakest in (phone, writing, technical terms).",
                "Say how you are improving it.",
            ),
            grounding = when {
                level != null -> listOf("Your CV lists $language — ${level.label}")
                profile.languages.isNotEmpty() -> listOf("Your CV lists: ${profile.languages.joinToString(", ") { it.name }}")
                else -> emptyList()
            },
            missingFacts = if (level == null) {
                listOf("Your CV does not state a level for $language.")
            } else emptyList(),
        )
    }

    private fun salary(job: Job, profile: CandidateProfile): List<InterviewQuestion> = listOf(
        InterviewQuestion(
            id = "salary-${job.id}",
            category = InterviewCategory.SALARY,
            prompt = "What salary are you expecting for this role?",
            whyItMatters = "Answering too low costs you money; answering too high ends the process. Have a range ready.",
            structure = listOf(
                "Give a researched range, not a single number.",
                "Base it on the local market for ${job.country}, not on Madagascar salaries.",
                "Mention that you are flexible on the package as a whole.",
                "Ask what range is budgeted for the role.",
            ),
            grounding = if (job.salaryMin > 0) {
                listOf("The posting advertises ${job.currencyCode} ${job.salaryMin}–${job.salaryMax} per ${job.salaryPeriod.name.lowercase()}")
            } else {
                listOf("The posting does not state a salary")
            },
            missingFacts = if (profile.salaryExpectationMin == null) {
                listOf("You have not recorded a salary expectation in your profile.")
            } else emptyList(),
        ),
        InterviewQuestion(
            id = "contract-${job.id}",
            category = InterviewCategory.SALARY,
            prompt = "What do you need in the offer to accept it — beyond salary?",
            whyItMatters = "Visa costs, housing and flights are negotiable and are often easier for the employer to give than a higher salary.",
            structure = listOf(
                "Separate what you need from what you would like.",
                "Name the relocation costs you cannot cover yourself.",
                "Ask about the visa route and who pays for it.",
                "Ask about the probation period and notice.",
            ),
            grounding = listOf("Destination: ${job.country}"),
        ),
    )

    private fun askThem(job: Job): InterviewQuestion = InterviewQuestion(
        id = "ask-${job.id}",
        category = InterviewCategory.ASK_THEM,
        prompt = "What questions should you ask ${job.company} at the end of the interview?",
        whyItMatters = "The questions you ask show whether you understand the move. They are also the moment to clarify visa reality.",
        structure = listOf(
            "Ask how the visa and work permit process works for a Madagascar-based hire.",
            "Ask what the first three months in the role look like.",
            "Ask how the team is structured and who you would report to.",
            "Ask what would make someone succeed in this role.",
        ),
        grounding = buildList {
            add("Employer: ${job.company}")
            if (job.source.isNotBlank()) add("Source: ${job.source}")
        },
    )
}
