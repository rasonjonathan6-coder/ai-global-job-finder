package com.aiglobal.jobfinder.data.jobs.engine

/**
 * A small, explicit skill vocabulary used to pull comparable skills out of a job
 * description. It is intentionally limited: a term only appears here when it is
 * specific enough that finding it in a posting is meaningful evidence. Free-form
 * candidate skills are still matched directly by name; this lexicon covers the
 * phrases boards actually use.
 */
object SkillLexicon {

    /** Canonical skill name → the phrases that indicate it in a posting. */
    private val skills: Map<String, List<String>> = mapOf(
        // Industrial / maintenance
        "Preventive maintenance" to listOf("preventive maintenance", "preventative maintenance", "planned maintenance"),
        "Hydraulics" to listOf("hydraulic", "hydraulics", "pneumatic", "pneumatics"),
        "Electrical diagnostics" to listOf("electrical fault", "electrical diagnostic", "electrical maintenance", "troubleshoot electrical"),
        "Mechanical repair" to listOf("mechanical repair", "mechanical maintenance", "mechanical fitting", "mechanical assembly"),
        "Welding" to listOf("welding", "welder", "mig", "tig"),
        "PLC" to listOf("plc", "programmable logic", "siemens s7", "allen-bradley", "allen bradley"),
        "CMMS" to listOf("cmms", "computerized maintenance", "maintenance management system", "work orders"),
        "Automation" to listOf("automation", "automated line", "robotics"),
        "Machining" to listOf("machining", "lathe", "milling", "cnc"),
        "HVAC" to listOf("hvac", "refrigeration", "air conditioning"),
        "Lockout/tagout" to listOf("lockout", "tagout", "loto"),
        "Forklift" to listOf("forklift", "reach truck", "pallet truck"),
        "Quality control" to listOf("quality control", "qc", "quality assurance", "iso 9001"),
        "Inventory management" to listOf("inventory management", "stock control", "warehouse management"),
        "Heavy equipment" to listOf("heavy equipment", "heavy machinery", "earthmoving"),
        "Instrumentation" to listOf("instrumentation", "calibration", "sensors"),
        "Solar energy" to listOf("solar", "photovoltaic", "renewable energy"),
        "Wind turbines" to listOf("wind turbine", "wind farm"),
        "Diesel engines" to listOf("diesel engine", "diesel generator", "genset"),

        // Safety / compliance
        "Safety compliance" to listOf("safety compliance", "hse", "ehs", "occupational health", "workplace safety"),
        "First aid" to listOf("first aid", "cpr"),
        "Risk assessment" to listOf("risk assessment", "hazard analysis"),

        // Office / tech
        "Customer service" to listOf("customer service", "customer support", "client support"),
        "Microsoft Excel" to listOf("excel", "spreadsheet"),
        "Microsoft Office" to listOf("microsoft office", "ms office", "word", "powerpoint"),
        "Data entry" to listOf("data entry", "data processing"),
        "Bookkeeping" to listOf("bookkeeping", "accounts payable", "accounts receivable", "invoicing"),
        "Project management" to listOf("project management", "pmp", "project coordination"),
        "Team supervision" to listOf("supervise", "supervisor", "team lead", "team leadership", "manage a team"),
        "Training" to listOf("training", "coaching", "mentoring"),
        "Problem solving" to listOf("problem solving", "problem-solving", "analytical"),
        "Communication" to listOf("communication skills", "communicate effectively"),
        "Driving licence" to listOf("driving licence", "driving license", "valid driver"),

        // Software
        "Kotlin" to listOf("kotlin"),
        "Java" to listOf("java "),
        "Python" to listOf("python"),
        "JavaScript" to listOf("javascript", "typescript"),
        "SQL" to listOf("sql", "postgres", "mysql"),
        "Docker" to listOf("docker", "kubernetes"),
        "AWS" to listOf("aws", "amazon web services"),
        "Git" to listOf("git", "github", "gitlab"),
    )

    /** Canonical names in a stable order. */
    val names: List<String> = skills.keys.toList()

    /** Phrases for one canonical skill. */
    fun phrasesFor(skill: String): List<String> = skills[skill].orEmpty()

    /**
     * Canonical skills detected in [text]. Matching is on whole words so "java" does
     * not fire inside "javascript".
     */
    fun detect(text: String): Set<String> {
        if (text.isBlank()) return emptySet()
        val normalized = text.lowercase()
        return skills.entries
            .asSequence()
            .filter { (_, phrases) -> phrases.any { containsPhrase(normalized, it) } }
            .map { it.key }
            .toSet()
    }

    /** Maps a free-form candidate skill onto a canonical name when one is close enough. */
    fun canonicalize(candidateSkill: String): String {
        val normalized = candidateSkill.lowercase().trim()
        skills.forEach { (canonical, phrases) ->
            if (canonical.lowercase() == normalized) return canonical
            if (phrases.any { it.trim() == normalized }) return canonical
        }
        // Fall back to a substring test for short candidate labels such as "Hydraulics".
        return skills.entries
            .firstOrNull { (canonical, phrases) ->
                val key = canonical.lowercase()
                key.contains(normalized) || normalized.contains(key) ||
                    phrases.any { containsPhrase(normalized, it) || it.contains(normalized) }
            }
            ?.key
            ?: candidateSkill.trim()
    }

    private fun containsPhrase(haystack: String, phrase: String): Boolean {
        val needle = phrase.trim().lowercase()
        if (needle.isEmpty()) return false
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            val before = index == 0 || !haystack[index - 1].isLetter()
            val afterIndex = index + needle.length
            val after = afterIndex >= haystack.length || !haystack[afterIndex].isLetter()
            if (before && after) return true
            index = haystack.indexOf(needle, index + 1)
        }
        return false
    }
}
