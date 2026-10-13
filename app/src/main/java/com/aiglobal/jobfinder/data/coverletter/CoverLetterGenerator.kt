package com.aiglobal.jobfinder.data.coverletter

import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.CoverLetter
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.LetterLanguage
import com.aiglobal.jobfinder.data.model.RelocationPreference
import java.util.UUID

/**
 * Writes a cover letter from the candidate's own CV and the posting.
 *
 * The generator is deliberately literal: every sentence is backed by a value that
 * actually exists in the profile or the posting. When a fact is missing the sentence
 * is dropped and recorded in [CoverLetter.missingFacts], so the user sees the gap and
 * can fill it rather than reading a letter the app invented for them.
 *
 * Nothing leaves the device and no network call is made.
 */
class CoverLetterGenerator(
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { "letter-${UUID.randomUUID()}" },
) {

    fun generate(job: Job, profile: CandidateProfile, language: LetterLanguage): CoverLetter {
        val pack = packs.getValue(language)
        val facts = Facts.of(job, profile)

        val paragraphs = buildList {
            add(pack.opening(facts))
            add(pack.experience(facts))
            pack.education(facts)?.let(::add)
            pack.languages(facts)?.let(::add)
            pack.relocation(facts)?.let(::add)
            add(pack.callToAction(facts))
        }

        return CoverLetter(
            id = idFactory(),
            jobId = job.id,
            language = language,
            subject = pack.subject(facts),
            salutation = pack.salutation,
            paragraphs = paragraphs,
            closing = pack.signOff,
            signature = profile.fullName.ifBlank { "Your name" },
            usedFacts = facts.used,
            missingFacts = facts.missing,
            generatedAtEpochMillis = clock(),
        )
    }

    /** The values the writer had available, resolved once so all languages agree. */
    private data class Facts(
        val jobTitle: String,
        val company: String,
        val location: String,
        val source: String,
        val candidateName: String,
        val candidateLocation: String,
        val currentTitle: String,
        val yearsOfExperience: Int?,
        val matchedSkills: List<String>,
        val education: String?,
        val certifications: List<String>,
        val languages: List<String>,
        val relocation: RelocationPreference,
        val summary: String,
    ) {
        val used: List<String> = buildList {
            add("Posting: $jobTitle at $company ($location)")
            if (source.isNotBlank()) add("Source: $source")
            if (candidateName.isNotBlank()) add("Your name: $candidateName")
            if (candidateLocation.isNotBlank()) add("Your location: $candidateLocation")
            if (currentTitle.isNotBlank()) add("Your current title: $currentTitle")
            yearsOfExperience?.let { add("Experience: $it years") }
            if (matchedSkills.isNotEmpty()) add("Skills matched to this posting: ${matchedSkills.joinToString(", ")}")
            education?.let { add("Education: $it") }
            if (certifications.isNotEmpty()) add("Certifications: ${certifications.joinToString(", ")}")
            if (languages.isNotEmpty()) add("Languages: ${languages.joinToString(", ")}")
            if (relocation != RelocationPreference.UNKNOWN) add("Relocation preference: ${relocation.label}")
            if (summary.isNotBlank()) add("Professional summary from your CV")
        }

        val missing: List<String> = buildList {
            if (candidateName.isBlank()) add("Your full name")
            if (currentTitle.isBlank()) add("Your professional title")
            if (yearsOfExperience == null) add("Years of experience")
            if (matchedSkills.isEmpty()) add("Skills that overlap with this posting")
            if (education == null) add("Your highest qualification")
            if (languages.isEmpty()) add("Languages and levels")
            if (relocation == RelocationPreference.UNKNOWN) add("Your relocation preference")
        }

        companion object {
            fun of(job: Job, profile: CandidateProfile): Facts {
                val location = if (job.city.isBlank() || job.city.equals(job.country, ignoreCase = true)) {
                    job.country
                } else {
                    "${job.city}, ${job.country}"
                }
                val education = profile.education.firstOrNull()?.let { entry ->
                    buildString {
                        append(entry.degree)
                        if (entry.institution.isNotBlank()) append(" — ${entry.institution}")
                        if (entry.year > 0) append(" (${entry.year})")
                    }
                }
                return Facts(
                    jobTitle = job.title,
                    company = job.company,
                    location = location,
                    source = job.source,
                    candidateName = profile.fullName,
                    candidateLocation = profile.location,
                    currentTitle = profile.currentTitle,
                    yearsOfExperience = profile.yearsOfExperience,
                    matchedSkills = job.matchedSkills.take(6),
                    education = education,
                    certifications = profile.certifications.map { cert ->
                        if (cert.year != null) "${cert.name} (${cert.year})" else cert.name
                    },
                    languages = profile.languages.map { it.displayLabel },
                    relocation = profile.relocationPreference,
                    summary = profile.summary,
                )
            }
        }
    }

    /** Per-language sentence builders. Each returns text only when its facts exist. */
    private interface Pack {
        val salutation: String
        val signOff: String
        fun subject(f: Facts): String
        fun opening(f: Facts): String
        fun experience(f: Facts): String
        fun education(f: Facts): String?
        fun languages(f: Facts): String?
        fun relocation(f: Facts): String?
        fun callToAction(f: Facts): String
    }

    private val packs: Map<LetterLanguage, Pack> = mapOf(
        LetterLanguage.ENGLISH to EnglishPack,
        LetterLanguage.FRENCH to FrenchPack,
        LetterLanguage.GERMAN to GermanPack,
    )

    private object EnglishPack : Pack {
        override val salutation = "Dear Hiring Manager,"
        override val signOff = "Yours sincerely,"
        override fun subject(f: Facts) = "Application for ${f.jobTitle} — ${f.company}"

        override fun opening(f: Facts) = buildString {
            append("I am writing to apply for the ${f.jobTitle} position at ${f.company} in ${f.location}.")
            if (f.source.isNotBlank()) append(" I found this opening through ${f.source}.")
            if (f.candidateLocation.isNotBlank()) {
                append(" I am currently based in ${f.candidateLocation} and I am applying as an international candidate.")
            }
        }

        override fun experience(f: Facts) = buildString {
            append("I bring ")
            append(f.yearsOfExperience?.let { "$it years of " } ?: "")
            append("hands-on experience")
            if (f.currentTitle.isNotBlank()) append(" as a ${f.currentTitle.lowercase()}")
            append(".")
            if (f.matchedSkills.isNotEmpty()) {
                append(" The posting calls for ${f.matchedSkills.joinToString(", ").lowercase()}, which I use in my current role.")
            }
            if (f.summary.isNotBlank()) append(" ${f.summary}")
        }

        override fun education(f: Facts): String? = f.education?.let {
            "I hold a $it." + if (f.certifications.isNotEmpty()) {
                " I also hold ${f.certifications.joinToString(", ")}."
            } else {
                ""
            }
        }

        override fun languages(f: Facts): String? =
            if (f.languages.isEmpty()) null else "I work in ${f.languages.joinToString(", ")}."

        override fun relocation(f: Facts): String? = when (f.relocation) {
            RelocationPreference.PREFERS_RELOCATION ->
                "I am actively seeking to relocate and I am ready to start the work permit process as soon as you make an offer."
            RelocationPreference.OPEN_TO_RELOCATION ->
                "I am open to relocating for this role and can begin the visa process promptly."
            RelocationPreference.REMOTE_ONLY ->
                "I am applying for this role on a remote basis and can work across your team's time zone."
            RelocationPreference.NO_RELOCATION -> null
            RelocationPreference.UNKNOWN -> null
        }

        override fun callToAction(f: Facts) =
            "Thank you for considering my application. I would welcome the chance to discuss how my " +
                "experience fits your team, and I am available for an interview at a time that suits your time zone."
    }

    private object FrenchPack : Pack {
        override val salutation = "Madame, Monsieur,"
        override val signOff = "Veuillez agréer, Madame, Monsieur, l'expression de mes salutations distinguées."
        override fun subject(f: Facts) = "Candidature au poste de ${f.jobTitle} — ${f.company}"

        override fun opening(f: Facts) = buildString {
            append("Je vous adresse ma candidature au poste de ${f.jobTitle} au sein de ${f.company}, à ${f.location}.")
            if (f.source.isNotBlank()) append(" J'ai découvert cette offre via ${f.source}.")
            if (f.candidateLocation.isNotBlank()) {
                append(" Je réside actuellement à ${f.candidateLocation} et je postule en tant que candidat international.")
            }
        }

        override fun experience(f: Facts) = buildString {
            append("Je dispose de ")
            append(f.yearsOfExperience?.let { "$it ans d'" } ?: "")
            append("expérience pratique")
            if (f.currentTitle.isNotBlank()) append(" en tant que ${f.currentTitle.lowercase()}")
            append(".")
            if (f.matchedSkills.isNotEmpty()) {
                append(" L'offre recherche ${f.matchedSkills.joinToString(", ").lowercase()}, compétences que j'exerce au quotidien.")
            }
            if (f.summary.isNotBlank()) append(" ${f.summary}")
        }

        override fun education(f: Facts): String? = f.education?.let {
            "Je suis titulaire d'un $it." + if (f.certifications.isNotEmpty()) {
                " Je détiens également ${f.certifications.joinToString(", ")}."
            } else {
                ""
            }
        }

        override fun languages(f: Facts): String? =
            if (f.languages.isEmpty()) null else "Je travaille en ${f.languages.joinToString(", ")}."

        override fun relocation(f: Facts): String? = when (f.relocation) {
            RelocationPreference.PREFERS_RELOCATION ->
                "Je souhaite activement m'expatrier et je suis prêt à engager les démarches de permis de travail dès votre proposition."
            RelocationPreference.OPEN_TO_RELOCATION ->
                "Je suis ouvert à une expatriation pour ce poste et je peux entamer les démarches rapidement."
            RelocationPreference.REMOTE_ONLY ->
                "Je postule pour ce poste en télétravail et je peux m'aligner sur votre fuseau horaire."
            RelocationPreference.NO_RELOCATION -> null
            RelocationPreference.UNKNOWN -> null
        }

        override fun callToAction(f: Facts) =
            "Je vous remercie de l'attention portée à ma candidature et reste à votre disposition " +
                "pour un entretien au moment qui conviendra à votre équipe."
    }

    private object GermanPack : Pack {
        override val salutation = "Sehr geehrte Damen und Herren,"
        override val signOff = "Mit freundlichen Grüßen"
        override fun subject(f: Facts) = "Bewerbung als ${f.jobTitle} — ${f.company}"

        override fun opening(f: Facts) = buildString {
            append("hiermit bewerbe ich mich um die Stelle als ${f.jobTitle} bei ${f.company} in ${f.location}.")
            if (f.source.isNotBlank()) append(" Die Stellenanzeige habe ich über ${f.source} gefunden.")
            if (f.candidateLocation.isNotBlank()) {
                append(" Ich bin derzeit in ${f.candidateLocation} ansässig und bewerbe mich als internationale Fachkraft.")
            }
        }

        override fun experience(f: Facts) = buildString {
            append("Ich bringe ")
            append(f.yearsOfExperience?.let { "$it Jahre " } ?: "")
            append("praktische Erfahrung")
            if (f.currentTitle.isNotBlank()) append(" als ${f.currentTitle} mit")
            append(".")
            if (f.matchedSkills.isNotEmpty()) {
                append(" Die Stelle verlangt ${f.matchedSkills.joinToString(", ")}, die ich täglich einsetze.")
            }
            if (f.summary.isNotBlank()) append(" ${f.summary}")
        }

        override fun education(f: Facts): String? = f.education?.let {
            "Ich habe $it abgeschlossen." + if (f.certifications.isNotEmpty()) {
                " Zusätzlich besitze ich ${f.certifications.joinToString(", ")}."
            } else {
                ""
            }
        }

        override fun languages(f: Facts): String? =
            if (f.languages.isEmpty()) null else "Ich arbeite in ${f.languages.joinToString(", ")}."

        override fun relocation(f: Facts): String? = when (f.relocation) {
            RelocationPreference.PREFERS_RELOCATION ->
                "Ich möchte aktiv auswandern und bin bereit, das Arbeitserlaubnisverfahren nach Ihrem Angebot sofort zu beginnen."
            RelocationPreference.OPEN_TO_RELOCATION ->
                "Ich bin für diesen Einsatz offen für einen Umzug und kann das Visumverfahren zügig starten."
            RelocationPreference.REMOTE_ONLY ->
                "Ich bewerbe mich für diese Stelle im Homeoffice und kann mich an Ihre Zeitzone anpassen."
            RelocationPreference.NO_RELOCATION -> null
            RelocationPreference.UNKNOWN -> null
        }

        override fun callToAction(f: Facts) =
            "Vielen Dank für Ihre Aufmerksamkeit. Über die Möglichkeit eines Gesprächs zu einer Zeit, " +
                "die Ihrem Team passt, freue ich mich sehr."
    }
}
