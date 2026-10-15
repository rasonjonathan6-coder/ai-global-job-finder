package com.aiglobal.jobfinder.data.jobs.search

import androidx.compose.runtime.Immutable

/**
 * One role the candidate is actually looking for, with the phrases a job board will
 * recognise and the words that mark it in a posting title.
 *
 * [searchPhrases] are sent to providers that support server-side keyword search.
 * [titleSignals] are matched locally against a posting title to decide whether the
 * role belongs to this family — that check is what stops a generic remote feed from
 * being presented as a targeted maintenance search.
 */
@Immutable
data class TargetRole(
    /** Canonical display title, e.g. "Mechanical Fitter". */
    val canonical: String,
    /** Phrases a board accepts for this role, most specific first. */
    val searchPhrases: List<String>,
    /** Whole words that mark this role in a posting title. */
    val titleSignals: List<String>,
)

/**
 * The candidate's target-role taxonomy: an industrial-maintenance tradesperson with a
 * juice-production, nickel/cobalt pump and automotive background. This is static data,
 * not a model, and it is the single source of truth for what "relevant" means.
 *
 * Everything here is a phrase the candidate would genuinely apply for; nothing is a
 * keyword chosen to inflate a result count.
 */
object TargetSearch {

    /** The roles the candidate targets, in priority order. */
    val roles: List<TargetRole> = listOf(
        TargetRole(
            canonical = "Industrial Maintenance Technician",
            searchPhrases = listOf("industrial maintenance technician", "industrial maintenance", "maintenance technician"),
            titleSignals = listOf("industrial maintenance", "maintenance technician"),
        ),
        TargetRole(
            canonical = "Mechanical Maintenance Technician",
            searchPhrases = listOf("mechanical maintenance technician", "mechanical maintenance", "mechanical technician"),
            titleSignals = listOf("mechanical maintenance", "mechanical technician"),
        ),
        TargetRole(
            canonical = "Pump Technician",
            searchPhrases = listOf("pump technician", "pump maintenance", "pump fitter"),
            titleSignals = listOf("pump"),
        ),
        TargetRole(
            canonical = "Mechanical Fitter",
            searchPhrases = listOf("mechanical fitter", "maintenance fitter", "fitter"),
            titleSignals = listOf("fitter", "fitting"),
        ),
        TargetRole(
            canonical = "Rotating Equipment Technician",
            searchPhrases = listOf("rotating equipment technician", "rotating equipment", "rotating equipment mechanic"),
            titleSignals = listOf("rotating equipment", "rotating machinery"),
        ),
        TargetRole(
            canonical = "Mining Maintenance Technician",
            searchPhrases = listOf("mining maintenance technician", "mine maintenance", "mining maintenance"),
            titleSignals = listOf("mine maintenance", "mining maintenance", "mining technician"),
        ),
        TargetRole(
            canonical = "Plant Maintenance Technician",
            searchPhrases = listOf("plant maintenance technician", "plant maintenance", "plant mechanic"),
            titleSignals = listOf("plant maintenance", "plant mechanic"),
        ),
        TargetRole(
            canonical = "Equipment Service Technician",
            searchPhrases = listOf("equipment service technician", "field service technician", "service technician"),
            titleSignals = listOf("service technician", "field service"),
        ),
        TargetRole(
            canonical = "Production Maintenance Technician",
            searchPhrases = listOf("production maintenance technician", "production maintenance", "production technician"),
            titleSignals = listOf("production maintenance"),
        ),
    )

    /**
     * Title words that on their own mark a role as maintenance/trade family. "Maintenance
     * Technician", "Diesel Mechanic" and "Mechanical Fitter" all belong here.
     */
    val strongCoreTerms: List<String> = listOf(
        "maintenance", "mechanic", "mechanical", "fitter", "millwright", "mechatronics",
    )

    /**
     * Title words that are only meaningful with a supporting equipment or industry word.
     * "Technician" alone could be a lab technician, so it is not treated as maintenance
     * unless the posting also names plant machinery or an industrial setting.
     */
    val weakCoreTerms: List<String> = listOf(
        "technician", "electrician", "welder", "machinist", "artisan", "instrumentation",
        "repairer", "assembler",
    )

    /**
     * Equipment/industry nouns that add relevance when they appear in a title or the
     * opening of a description (pumps, conveyors, rotating equipment, plant machinery).
     *
     * These are deliberately specific: a word that merely names the sector a company works
     * in ("manufacturing", "industrial") is not enough to claim a lone "technician" title,
     * because almost every posting at an industrial employer opens by describing that
     * employer. A concrete machine or a named plant setting is.
     */
    val equipmentTerms: List<String> = listOf(
        "pump", "pumps", "conveyor", "conveyors", "rotating equipment", "rotating machinery",
        "compressor", "gearbox", "bearing", "hydraulic", "hydraulics", "pneumatic",
        "pneumatics", "turbine", "diesel", "generator", "motor", "valve", "pipeline",
        "crusher", "mill", "kiln", "boiler", "chiller", "hvac",
        "drill rig", "drilling", "excavator", "haul truck", "loader", "shovel",
        "smelter", "refinery", "blast furnace", "coke oven", "flotation", "thickener",
        "screening plant", "processing plant", "pump station", "plant machinery",
    )

    /**
     * Industry context words that support a maintenance reading of a posting when they
     * appear in the title. A title that names the plant/production/mining setting is the
     * employer saying the role is on the shop floor, so a weak trade word there counts.
     */
    val industryTerms: List<String> = listOf(
        "industrial", "plant", "production", "manufacturing", "mining", "mine", "smelter",
        "refinery", "processing", "factory", "mill", "juice", "beverage", "food processing",
    )

    /**
     * The subset of [industryTerms] specific enough to support a weak trade word from the
     * *description* as well as the title. The broad ones ("industrial", "plant",
     * "production", "manufacturing", "processing", "factory") are excluded: a long
     * employer blurb about its factory would otherwise turn every "Lab Technician" or
     * "Process Technician" into a maintenance role, which is exactly the false positive
     * this gate exists to stop.
     */
    val descriptionIndustryTerms: List<String> = listOf(
        "mining", "mine", "smelter", "refinery", "juice", "beverage", "food processing",
    )

    /**
     * Titles for clearly different professions. A posting whose title names one of these
     * is not the candidate's job even if it also carries a maintenance word — a software
     * engineer on a "maintenance tooling" team, or an account manager for industrial
     * accounts, is not a maintenance technician.
     */
    val otherProfessionMarkers: List<String> = listOf(
        "social worker", "account executive", "account manager", "sales representative",
        "sales executive", "sales manager", "marketing", "graphic design", "product design",
        "product manager", "ux designer", "ui designer", "nurse", "caregiver", "teacher",
        "tutor", "professor", "accountant", "bookkeeper", "financial analyst", "recruiter",
        "talent acquisition", "human resources", "customer support", "customer service",
        "data entry", "content writer", "copywriter", "translator", "lawyer", "paralegal",
        "software engineer", "software developer", "frontend developer", "backend developer",
        "full stack", "data scientist", "devops", "business analyst", "project manager",
        "chef", "cook", "waiter", "barista", "housekeeper", "cleaner", "security guard",
        "truck driver", "delivery driver", "courier", "warehouse picker",
    )

    /**
     * The keyword sets to send, most valuable first: the canonical target titles, then
     * the candidate's own titles. Capped so a keyword-search provider is not asked for
     * an unreasonable number of requests in one refresh.
     */
    fun keywordPlan(profileTitles: List<String>, max: Int = 6): List<String> {
        val canonical = roles.map { it.canonical }
        val merged = (canonical + profileTitles)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
        return merged.take(max.coerceAtLeast(1))
    }
}
