package com.aiglobal.jobfinder.data.expat

import com.aiglobal.jobfinder.data.model.BenefitCategory
import com.aiglobal.jobfinder.data.model.BenefitItem
import com.aiglobal.jobfinder.data.model.ExpatGuide

/**
 * Settlement guide per destination country.
 *
 * Every entry names the government department that administers the rule and links to
 * its own page, because relocation facts change and the user must be able to check the
 * current position. Where a country's rules are conditional, the item says so instead
 * of stating a flat guarantee.
 *
 * This is guidance, not legal advice, and the guide says as much on screen.
 */
object ExpatBenefitsCatalog {

    /** Canonical country name → guide. Keys match [com.aiglobal.jobfinder.data.geo.CountryCatalog]. */
    private val guides: Map<String, ExpatGuide> = listOf(
        canada(),
        germany(),
        unitedArabEmirates(),
        qatar(),
        unitedKingdom(),
        australia(),
        netherlands(),
        newZealand(),
        remoteWorldwide(),
    ).associateBy { it.country }

    /** Guides offered when the destination is not one of the detailed entries. */
    val highlightedCountries: List<String> = listOf(
        "Canada", "Germany", "United Arab Emirates", "Qatar",
        "United Kingdom", "Australia", "Netherlands", "New Zealand", "Worldwide",
    )

    fun forCountry(country: String): ExpatGuide =
        guides[country] ?: generic(country)

    fun hasDetailedGuide(country: String): Boolean = guides.containsKey(country)

    // ------------------------------------------------------------------ Canada

    private fun canada() = ExpatGuide(
        country = "Canada",
        countryFlag = "🇨🇦",
        summary = "Employer-driven route. A Canadian employer usually needs an approved LMIA before " +
            "you can be issued a work permit, then your spouse may qualify for an open permit.",
        benefits = listOf(
            BenefitItem(
                category = BenefitCategory.WORK_VISA,
                title = "Employer-specific work permit",
                detail = "Most temporary foreign workers arrive on a closed work permit tied to one employer. " +
                    "The employer normally applies for a Labour Market Impact Assessment (LMIA) first.",
                sourceName = "IRCC — Temporary foreign workers",
                sourceUrl = "https://www.canada.ca/en/immigration-refugees-citizenship/services/work-canada.html",
            ),
            BenefitItem(
                category = BenefitCategory.WORK_VISA,
                title = "LMIA-exempt pathways",
                detail = "Some roles — including certain francophone mobility and intra-company transfers — " +
                    "are exempt from the LMIA. Ask the employer which category the job falls under.",
                sourceName = "IRCC — LMIA exemptions",
                sourceUrl = "https://www.canada.ca/en/employment-social-development/services/foreign-workers/median-wage.html",
            ),
            BenefitItem(
                category = BenefitCategory.FAMILY,
                title = "Spouse open work permit",
                detail = "Spouses of some work permit holders in higher-skilled occupations can apply for an " +
                    "open work permit, which lets them work for almost any employer in Canada.",
                sourceName = "IRCC — Spouses of workers",
                sourceUrl = "https://www.canada.ca/en/immigration-refugees-citizenship/services/work-canada/permit/temporary/work-permit.html",
            ),
            BenefitItem(
                category = BenefitCategory.HEALTH,
                title = "Provincial health coverage",
                detail = "Public health insurance is administered by each province. Most provinces have a " +
                    "waiting period, so arrange private cover for the first months.",
                sourceName = "Government of Canada — Health care",
                sourceUrl = "https://www.canada.ca/en/health-canada/services/health-care-system/canada.html",
            ),
            BenefitItem(
                category = BenefitCategory.CITIZENSHIP,
                title = "Path to permanent residence",
                detail = "Canadian work experience counts towards economic immigration programmes such as " +
                    "Express Entry. Keep payslips and contracts to prove your hours.",
                sourceName = "IRCC — Express Entry",
                sourceUrl = "https://www.canada.ca/en/immigration-refugees-citizenship/services/immigrate-canada/express-entry.html",
            ),
        ),
        practicalNotes = listOf(
            "Never pay a fee to a recruiter for a Canadian job. Legitimate employers and LMIA processing do not charge the worker.",
            "Confirm the job offer in writing and check the employer on the federal Job Bank before you sign.",
            "Your permit is tied to the employer named on it, so a new job needs a new permit.",
        ),
    )

    // ------------------------------------------------------------------ Germany

    private fun germany() = ExpatGuide(
        country = "Germany",
        countryFlag = "🇩🇪",
        summary = "Skills-led route. Germany actively recruits qualified workers and offers a residence " +
            "permit for skilled workers, with a fast-track procedure employers can request.",
        benefits = listOf(
            BenefitItem(
                category = BenefitCategory.WORK_VISA,
                title = "Residence permit for skilled workers",
                detail = "With a recognised qualification and a concrete job offer you can apply for a " +
                    "residence permit under the Skilled Immigration Act.",
                sourceName = "Make it in Germany — Visa",
                sourceUrl = "https://www.make-it-in-germany.com/en/visa-residence/types",
            ),
            BenefitItem(
                category = BenefitCategory.WORK_VISA,
                title = "Fast-track skilled worker procedure",
                detail = "Your employer can start a fast-track procedure with the local foreigners authority " +
                    "before you arrive, which shortens the wait at the embassy.",
                sourceName = "Make it in Germany — Fast-track procedure",
                sourceUrl = "https://www.make-it-in-germany.com/en/visa-residence/accelerated-skilled-worker-procedure",
            ),
            BenefitItem(
                category = BenefitCategory.SETTLEMENT,
                title = "Qualification recognition",
                detail = "Check whether your diploma or certificate is recognised in Germany. Recognition " +
                    "is often the deciding factor for the visa.",
                sourceName = "BQ-Portal — Recognition",
                sourceUrl = "https://www.bq-portal.de/en",
            ),
            BenefitItem(
                category = BenefitCategory.FAMILY,
                title = "Family reunification",
                detail = "Skilled workers can usually bring a spouse and children. Spouses may work without " +
                    "a separate permit in many cases.",
                sourceName = "Make it in Germany — Family",
                sourceUrl = "https://www.make-it-in-germany.com/en/living-in-germany/family",
            ),
            BenefitItem(
                category = BenefitCategory.TAX,
                title = "Social security and health insurance",
                detail = "Employment comes with statutory health, pension and unemployment insurance. " +
                    "Contributions are shared with the employer.",
                sourceName = "Make it in Germany — Social security",
                sourceUrl = "https://www.make-it-in-germany.com/en/living-in-germany/insurance",
            ),
            BenefitItem(
                category = BenefitCategory.CITIZENSHIP,
                title = "Permanent residence",
                detail = "Skilled workers can typically apply for a settlement permit after a few years of " +
                    "employment, depending on the permit type and your language level.",
                sourceName = "Federal Office for Migration (BAMF)",
                sourceUrl = "https://www.bamf.de/EN/Home/home-node.html",
            ),
        ),
        practicalNotes = listOf(
            "German language at A1–B1 level is often requested; A2 is a common minimum for technical roles.",
            "Learn the difference between a job-seeker visa and a work permit: the job-seeker visa lets you attend interviews in Germany first.",
            "The Anerkennung (recognition) result decides your permit type, so start it early.",
        ),
    )

    // ------------------------------------------------------------------ UAE

    private fun unitedArabEmirates() = ExpatGuide(
        country = "United Arab Emirates",
        countryFlag = "🇦🇪",
        summary = "Employer-sponsored route. Your employer applies for your work permit and residence visa; " +
            "the process is employer-driven and usually quick once the offer is signed.",
        benefits = listOf(
            BenefitItem(
                category = BenefitCategory.WORK_VISA,
                title = "Employer-sponsored work permit",
                detail = "The employer applies to the Ministry of Human Resources and Emiratisation for a " +
                    "work permit, then for your residence visa. You do not apply directly.",
                sourceName = "MOHRE — Work permits",
                sourceUrl = "https://www.mohre.gov.ae/en/home.aspx",
            ),
            BenefitItem(
                category = BenefitCategory.SETTLEMENT,
                title = "Emirates ID and residence visa",
                detail = "After arrival you complete medical screening and biometrics, then receive an " +
                    "Emirates ID and a residence visa valid for the contract period.",
                sourceName = "ICP — Residency",
                sourceUrl = "https://icp.gov.ae/en/",
            ),
            BenefitItem(
                category = BenefitCategory.FAMILY,
                title = "Family sponsorship",
                detail = "Employees above a minimum salary threshold can sponsor a spouse and children, " +
                    "subject to salary and job-category rules.",
                sourceName = "ICP — Family sponsorship",
                sourceUrl = "https://icp.gov.ae/en/services",
            ),
            BenefitItem(
                category = BenefitCategory.HEALTH,
                title = "Employer-provided health insurance",
                detail = "Health insurance for the employee is mandatory for employers in the UAE and is " +
                    "usually arranged as part of the package.",
                sourceName = "UAE Government portal",
                sourceUrl = "https://u.ae/en/information-and-services/health-and-fitness/health-insurance",
            ),
            BenefitItem(
                category = BenefitCategory.TAX,
                title = "No personal income tax",
                detail = "The UAE does not levy personal income tax on salaries. There is a corporate tax " +
                    "regime, but it does not apply to your salary.",
                sourceName = "UAE Government portal — Taxes",
                sourceUrl = "https://u.ae/en/information-and-services/finance-and-investment/taxation",
            ),
        ),
        practicalNotes = listOf(
            "A legitimate employer never asks the worker to pay for the work permit. That cost is the employer's.",
            "Confirm the package includes accommodation, transport or an allowance, since these vary widely.",
            "Compare offers on total package, not base salary: housing and schooling dominate real living costs.",
        ),
    )

    // ------------------------------------------------------------------ Qatar

    private fun qatar() = ExpatGuide(
        country = "Qatar",
        countryFlag = "🇶🇦",
        summary = "Employer-sponsored route under Qatar's reformed labour rules. The employer obtains your " +
            "work permit and residence permit; minimum wage and job-change rules now apply to all workers.",
        benefits = listOf(
            BenefitItem(
                category = BenefitCategory.WORK_VISA,
                title = "Work permit via employer",
                detail = "The employer applies for your work permit and residence permit through the Ministry " +
                    "of Labour and the Interior. Workers must sign an employment contract before arrival.",
                sourceName = "Qatar Ministry of Labour",
                sourceUrl = "https://www.mol.gov.qa/en/",
            ),
            BenefitItem(
                category = BenefitCategory.SETTLEMENT,
                title = "Job change without an NOC",
                detail = "Workers can change employers before the end of the contract under defined notice " +
                    "conditions, without needing a no-objection certificate from the old employer.",
                sourceName = "Qatar Ministry of Labour — Labour reform",
                sourceUrl = "https://www.mol.gov.qa/en/pages/labourreforms",
            ),
            BenefitItem(
                category = BenefitCategory.TAX,
                title = "Minimum wage and no income tax",
                detail = "A non-discriminatory minimum wage applies to all workers regardless of nationality. " +
                    "There is no personal income tax on wages.",
                sourceName = "Qatar Ministry of Labour — Minimum wage",
                sourceUrl = "https://www.mol.gov.qa/en/pages/minimumwage",
            ),
            BenefitItem(
                category = BenefitCategory.FAMILY,
                title = "Family residence",
                detail = "Family residence permits are available to employees who meet the salary and " +
                    "profession requirements set by the Interior Ministry.",
                sourceName = "Qatar Ministry of Interior",
                sourceUrl = "https://portal.moi.gov.qa/",
            ),
        ),
        practicalNotes = listOf(
            "The employer must cover the cost of the work permit and the residence permit. Do not pay these yourself.",
            "Keep a signed copy of your contract: the terms filed with the ministry are the ones that bind the employer.",
            "Salaries must be paid by bank transfer under Qatari rules, which gives you a record of every payment.",
        ),
    )

    // ------------------------------------------------------------------ UK

    private fun unitedKingdom() = ExpatGuide(
        country = "United Kingdom",
        countryFlag = "🇬🇧",
        summary = "Points-based, employer-sponsored route. A licensed sponsor issues a certificate of " +
            "sponsorship and you apply for a Skilled Worker visa before you travel.",
        benefits = listOf(
            BenefitItem(
                category = BenefitCategory.WORK_VISA,
                title = "Skilled Worker visa",
                detail = "You need a job offer from a licensed sponsor, a certificate of sponsorship, and " +
                    "enough points for salary and skill level. The visa usually lasts up to five years.",
                sourceName = "GOV.UK — Skilled Worker visa",
                sourceUrl = "https://www.gov.uk/skilled-worker-visa",
            ),
            BenefitItem(
                category = BenefitCategory.FAMILY,
                title = "Dependants",
                detail = "A spouse or partner and children can usually apply to join you as dependants and " +
                    "may work, subject to the rules at the time of application.",
                sourceName = "GOV.UK — Bring your family",
                sourceUrl = "https://www.gov.uk/skilled-worker-visa/your-partner-and-children",
            ),
            BenefitItem(
                category = BenefitCategory.HEALTH,
                title = "NHS access via immigration health surcharge",
                detail = "You pay an immigration health surcharge as part of the application, which gives " +
                    "you access to NHS care on the same basis as residents.",
                sourceName = "GOV.UK — Immigration health surcharge",
                sourceUrl = "https://www.gov.uk/healthcare-immigration-application",
            ),
            BenefitItem(
                category = BenefitCategory.CITIZENSHIP,
                title = "Settlement after five years",
                detail = "Skilled Worker visa holders can normally apply for settlement after five years of " +
                    "continuous residence, subject to salary and absence rules.",
                sourceName = "GOV.UK — Settle in the UK",
                sourceUrl = "https://www.gov.uk/settle-in-the-uk",
            ),
        ),
        practicalNotes = listOf(
            "Check the sponsor on the official register of licensed sponsors before accepting an offer.",
            "The certificate of sponsorship is issued by the employer; you cannot obtain one yourself.",
            "Budget for the visa fee and the health surcharge, which are paid by the applicant.",
        ),
    )

    // ------------------------------------------------------------------ Australia

    private fun australia() = ExpatGuide(
        country = "Australia",
        countryFlag = "🇦🇺",
        summary = "Employer-sponsored and points-tested routes. For a sponsored role the employer nominates " +
            "you first; skilled independent visas are also available if your occupation is on the list.",
        benefits = listOf(
            BenefitItem(
                category = BenefitCategory.WORK_VISA,
                title = "Temporary Skill Shortage visa",
                detail = "The employer must be an approved sponsor and nominate the occupation. The visa lets " +
                    "you work for that sponsor in the nominated role.",
                sourceName = "Home Affairs — TSS visa",
                sourceUrl = "https://immi.homeaffairs.gov.au/visas/getting-a-visa/visa-listing/temporary-skill-shortage-482",
            ),
            BenefitItem(
                category = BenefitCategory.WORK_VISA,
                title = "Skilled independent visa",
                detail = "If your occupation is on the skilled occupation list and you score enough points, " +
                    "you can apply without an employer sponsor.",
                sourceName = "Home Affairs — Skilled independent",
                sourceUrl = "https://immi.homeaffairs.gov.au/visas/getting-a-visa/visa-listing/skilled-independent-189",
            ),
            BenefitItem(
                category = BenefitCategory.SETTLEMENT,
                title = "Skills assessment",
                detail = "Most skilled visas require a skills assessment from the relevant assessing authority " +
                    "for your occupation before you can apply.",
                sourceName = "Home Affairs — Skills assessment",
                sourceUrl = "https://immi.homeaffairs.gov.au/visas/working-in-australia/skills-assessment",
            ),
            BenefitItem(
                category = BenefitCategory.HEALTH,
                title = "Medicare",
                detail = "Some visa holders have access to Medicare. Permanent residents have full access; " +
                    "check your visa conditions before relying on it.",
                sourceName = "Services Australia — Medicare",
                sourceUrl = "https://www.servicesaustralia.gov.au/medicare",
            ),
            BenefitItem(
                category = BenefitCategory.CITIZENSHIP,
                title = "Permanent residence pathway",
                detail = "Temporary sponsored workers may be able to transition to permanent residence after " +
                    "meeting the work and salary requirements.",
                sourceName = "Home Affairs — Employer sponsored",
                sourceUrl = "https://immi.homeaffairs.gov.au/visas/getting-a-visa/visa-listing/employer-nomination-scheme-186",
            ),
        ),
        practicalNotes = listOf(
            "Only approved sponsors can nominate you; verify the business on the Home Affairs sponsor list.",
            "A skills assessment can take months, so start it while you are still interviewing.",
            "Be cautious of anyone charging a fee to 'guarantee' a visa or a nomination.",
        ),
    )

    // ------------------------------------------------------------------ Netherlands

    private fun netherlands() = ExpatGuide(
        country = "Netherlands",
        countryFlag = "🇳🇱",
        summary = "Employer-sponsored route. Recognised sponsors can bring highly skilled migrants in with a " +
            "salary threshold; the employer applies to the immigration service on your behalf.",
        benefits = listOf(
            BenefitItem(
                category = BenefitCategory.WORK_VISA,
                title = "Highly skilled migrant permit",
                detail = "Your employer must be a recognised sponsor and pay you at least the applicable " +
                    "salary threshold for your age group. The employer files the application.",
                sourceName = "IND — Highly skilled migrants",
                sourceUrl = "https://ind.nl/en/residence-permits/work/highly-skilled-migrant",
            ),
            BenefitItem(
                category = BenefitCategory.SETTLEMENT,
                title = "30% tax ruling",
                detail = "Eligible incoming employees can receive a tax-free allowance on part of their " +
                    "salary for a limited period, subject to conditions and salary limits.",
                sourceName = "Business.gov.nl — 30% ruling",
                sourceUrl = "https://business.gov.nl/regulation/30-percent-ruling/",
            ),
            BenefitItem(
                category = BenefitCategory.FAMILY,
                title = "Family members may work",
                detail = "Family members who join a highly skilled migrant can work in the Netherlands " +
                    "without a separate work permit.",
                sourceName = "IND — Family members",
                sourceUrl = "https://ind.nl/en/family",
            ),
            BenefitItem(
                category = BenefitCategory.CITIZENSHIP,
                title = "Permanent residence",
                detail = "After five years of continuous legal residence you can generally apply for permanent " +
                    "residence, subject to integration and income conditions.",
                sourceName = "IND — Permanent residence",
                sourceUrl = "https://ind.nl/en/permanent-residence",
            ),
        ),
        practicalNotes = listOf(
            "Ask whether the employer is a recognised sponsor; only they can use the fast migrant route.",
            "The salary threshold is reviewed annually, so confirm the current figure with the employer.",
            "Register with the municipality (gemeente) after arrival to get your BSN, which you need for work and banking.",
        ),
    )

    // ------------------------------------------------------------------ New Zealand

    private fun newZealand() = ExpatGuide(
        country = "New Zealand",
        countryFlag = "🇳🇿",
        summary = "Employer-driven route with an accredited employer system. The employer must be accredited " +
            "before it can support an Accredited Employer Work Visa for you.",
        benefits = listOf(
            BenefitItem(
                category = BenefitCategory.WORK_VISA,
                title = "Accredited Employer Work Visa",
                detail = "The employer applies for accreditation and a job check; you then apply for the work " +
                    "visa with the job token they issue.",
                sourceName = "Immigration New Zealand — AEWV",
                sourceUrl = "https://www.immigration.govt.nz/employ-migrant-workers/",
            ),
            BenefitItem(
                category = BenefitCategory.FAMILY,
                title = "Partner and children",
                detail = "Partners of some work visa holders can get an open work visa, and children may be " +
                    "treated as domestic students for school fees.",
                sourceName = "Immigration New Zealand — Family",
                sourceUrl = "https://www.immigration.govt.nz/new-zealand-visas/options/join-family",
            ),
            BenefitItem(
                category = BenefitCategory.HEALTH,
                title = "Public healthcare",
                detail = "Work visa holders staying two years or more generally qualify for publicly funded " +
                    "health services.",
                sourceName = "Health NZ — Eligibility",
                sourceUrl = "https://www.health.govt.nz/",
            ),
            BenefitItem(
                category = BenefitCategory.CITIZENSHIP,
                title = "Residence pathway",
                detail = "Skilled migrants may move to residence through the Skilled Migrant Category or the " +
                    "Green List occupations.",
                sourceName = "Immigration New Zealand — Skilled Migrant",
                sourceUrl = "https://www.immigration.govt.nz/new-zealand-visas/options/work",
            ),
        ),
        practicalNotes = listOf(
            "Check the employer's accreditation status; an unaccredited employer cannot support the visa.",
            "Some roles need registration with a New Zealand professional body before you can start.",
            "Verify the job token is issued to you personally and matches the role you were offered.",
        ),
    )

    // ------------------------------------------------------------------ Remote

    private fun remoteWorldwide() = ExpatGuide(
        country = "Worldwide",
        countryFlag = "🌍",
        summary = "Remote roles need no work visa, because you remain in Madagascar and invoice as a " +
            "contractor or are employed through an employer-of-record. The trade-off is tax and no relocation.",
        benefits = listOf(
            BenefitItem(
                category = BenefitCategory.WORK_VISA,
                title = "No visa required",
                detail = "You work from Madagascar, so there is no immigration permission to obtain. Confirm " +
                    "whether the contract is employment or self-employment.",
                sourceName = "OECD — Remote work guidance",
                sourceUrl = "https://www.oecd.org/tax/",
            ),
            BenefitItem(
                category = BenefitCategory.TAX,
                title = "Tax residency stays in Madagascar",
                detail = "If you remain tax resident in Madagascar you declare the income there. Read the " +
                    "contract carefully: an employer of record handles payroll tax, a contractor does not.",
                sourceName = "Madagascar Ministry of Economy and Finance",
                sourceUrl = "https://www.mef.gov.mg/",
            ),
            BenefitItem(
                category = BenefitCategory.HEALTH,
                title = "No employer health cover",
                detail = "Remote contractors normally arrange their own health cover. Budget for it from the " +
                    "gross fee before you accept a rate.",
                sourceName = "World Health Organization",
                sourceUrl = "https://www.who.int/",
            ),
            BenefitItem(
                category = BenefitCategory.SETTLEMENT,
                title = "Payments and banking",
                detail = "Agree the currency, payment method and invoice cadence in writing. Fees and " +
                    "exchange costs are usually the worker's responsibility.",
                sourceName = "Madagascar Central Bank",
                sourceUrl = "https://www.banky-foibe.mg/",
            ),
        ),
        practicalNotes = listOf(
            "Never accept a role that asks you to pay for equipment, training or 'registration' up front.",
            "Get the contract in writing and check which currency and payment schedule apply.",
            "Set aside money for your own tax and health cover; the gross rate is not your take-home pay.",
        ),
    )

    // ------------------------------------------------------------------ fallback

    private fun generic(country: String) = ExpatGuide(
        country = country.ifBlank { "This destination" },
        countryFlag = "🌍",
        summary = "We do not hold a detailed settlement guide for this destination yet. The route below is " +
            "how employer-sponsored relocation normally works, so you know what to ask before you apply.",
        benefits = listOf(
            BenefitItem(
                category = BenefitCategory.WORK_VISA,
                title = "Employer-sponsored work permit",
                detail = "Most international hires are sponsored by the employer, who applies to the national " +
                    "immigration authority on the worker's behalf. Ask which permit route applies to the role.",
                sourceName = "UN — Working abroad",
                sourceUrl = "https://www.un.org/en/",
            ),
            BenefitItem(
                category = BenefitCategory.RELOCATION,
                title = "Relocation package",
                detail = "Employers commonly cover flights, initial accommodation or a settling-in allowance. " +
                    "Confirm exactly what is included in writing before you accept.",
                sourceName = "International Labour Organization",
                sourceUrl = "https://www.ilo.org/",
            ),
            BenefitItem(
                category = BenefitCategory.HEALTH,
                title = "Health cover",
                detail = "Check whether public healthcare applies to work permit holders or whether the " +
                    "employer provides private insurance.",
                sourceName = "World Health Organization",
                sourceUrl = "https://www.who.int/",
            ),
            BenefitItem(
                category = BenefitCategory.FAMILY,
                title = "Family and dependants",
                detail = "Family reunification rules differ widely. Confirm whether a spouse may work and " +
                    "whether children can attend local schools.",
                sourceName = "International Organization for Migration",
                sourceUrl = "https://www.iom.int/",
            ),
        ),
        practicalNotes = listOf(
            "Find the destination country's immigration authority and read the work permit page yourself.",
            "Ask the employer to confirm in writing who pays visa, permit and relocation costs.",
            "Do not pay a recruiter for a job or a visa; legitimate employers cover permit costs.",
        ),
    )
}
