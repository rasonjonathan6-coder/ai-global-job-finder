package com.aiglobal.jobfinder.data.cv.analysis

/**
 * Vocabulary the analyser matches against. Keeping it here as data (rather than inline
 * regexes) makes it obvious what the analyser can and cannot recognise, and lets us
 * extend coverage without touching extraction logic.
 *
 * Aliases are matched on word boundaries against lowercased text. Canonical names are
 * what the user sees, so a CV saying "LOTO" and one saying "lockout tagout" produce the
 * same skill.
 */
object CvLexicon {

    data class SkillDefinition(
        val canonical: String,
        val category: SkillCategory,
        val aliases: List<String>,
    )

    enum class SkillCategory { TECHNICAL, TOOLS, SOFTWARE, SOFT, SAFETY, LOGISTICS, DOMAIN }

    val skills: List<SkillDefinition> = listOf(
        // --- Industrial / maintenance ---
        SkillDefinition("Preventive maintenance", SkillCategory.TECHNICAL, listOf("preventive maintenance", "planned maintenance", "preventative maintenance", "preventive", "maintenance preventive", "maintenance préventive", "preventative")),
        SkillDefinition("Electrical diagnostics", SkillCategory.TECHNICAL, listOf("electrical diagnostics", "electrical fault", "electrical maintenance", "electrical troubleshooting", "electrical systems", "diagnostic electrique", "diagnostic électrique", "diagnostics")),
        SkillDefinition("Mechanical repair", SkillCategory.TECHNICAL, listOf("mechanical repair", "mechanical maintenance", "mechanical fitting", "mechanical systems", "mecanique", "mécanique", "mechanical", "mechanic")),
        SkillDefinition("Mechanical seals", SkillCategory.TECHNICAL, listOf("mechanical seal", "mechanical seals", "seal replacement", "gland packing", "joint mecanique", "joints")),
        SkillDefinition("Rotating equipment", SkillCategory.TECHNICAL, listOf("rotating equipment", "pumps", "pompes", "pump repair", "compressors", "compresseurs", "gearbox", "reducteur", "réducteur", "bearing", "roulement", "coupling", "accouplement")),
        SkillDefinition("Hydraulics", SkillCategory.TECHNICAL, listOf("hydraulic", "hydraulics", "hydraulic systems", "hydraulique")),
        SkillDefinition("Pneumatics", SkillCategory.TECHNICAL, listOf("pneumatic", "pneumatics", "pneumatique")),
        SkillDefinition("Welding", SkillCategory.TECHNICAL, listOf("welding", "mig welding", "tig welding", "arc welding", "soudure", "soudeur")),
        SkillDefinition("HVAC & refrigeration", SkillCategory.TECHNICAL, listOf("hvac", "refrigeration", "air conditioning", "climatisation", "froid")),
        SkillDefinition("CNC machining", SkillCategory.TECHNICAL, listOf("cnc", "machining", "lathe", "milling", "usinage", "tour", "fraisage")),
        SkillDefinition("Automation & PLC", SkillCategory.TOOLS, listOf("plc", "programmable logic controller", "automation", "automate", "scada", "siemens s7", "allen bradley", "api")),
        SkillDefinition("CMMS software", SkillCategory.SOFTWARE, listOf("cmms", "maintenance management software", "maximo", "sap pm", "gmao")),
        SkillDefinition("Predictive maintenance", SkillCategory.TECHNICAL, listOf("predictive maintenance", "condition monitoring", "vibration analysis", "vibration", "analysis vibratoire", "analyse vibratoire")),
        SkillDefinition("Metrology & measurement", SkillCategory.TECHNICAL, listOf("metrology", "metrologie", "métrologie", "calibration", "calibrage", "measurement", "measuring instruments", "micrometer", "pied a coulisse", "vernier")),
        SkillDefinition("Technical documentation", SkillCategory.SOFT, listOf("technical documentation", "documentation technique", "maintenance reports", "rapports de maintenance", "procedures", "procédures", "work instructions")),

        // --- Safety ---
        SkillDefinition("Workplace safety", SkillCategory.SAFETY, listOf("lockout", "tagout", "loto", "workplace safety", "safety compliance", "hse", "occupational health")),
        SkillDefinition("ISO standards", SkillCategory.SAFETY, listOf("iso 9001", "iso 45001", "iso standards", "quality management system")),
        SkillDefinition("First aid", SkillCategory.SAFETY, listOf("first aid", "secourisme", "cpr")),

        // --- Software / IT ---
        SkillDefinition("Microsoft Excel", SkillCategory.SOFTWARE, listOf("excel", "spreadsheets", "tableur")),
        SkillDefinition("Microsoft Office", SkillCategory.SOFTWARE, listOf("microsoft office", "ms office", "word and excel", "office suite")),
        SkillDefinition("SAP", SkillCategory.SOFTWARE, listOf("sap", "erp")),
        SkillDefinition("AutoCAD", SkillCategory.SOFTWARE, listOf("autocad", "cad design", "drafting")),
        SkillDefinition("SolidWorks", SkillCategory.SOFTWARE, listOf("solidworks", "solid works")),
        SkillDefinition("Python", SkillCategory.SOFTWARE, listOf("python")),
        SkillDefinition("SQL", SkillCategory.SOFTWARE, listOf("sql", "mysql", "postgresql", "database queries")),
        SkillDefinition("Java", SkillCategory.SOFTWARE, listOf("java", "kotlin")),
        SkillDefinition("Data analysis", SkillCategory.SOFTWARE, listOf("data analysis", "analytics", "power bi", "tableau")),
        SkillDefinition("Cloud platforms", SkillCategory.SOFTWARE, listOf("aws", "azure", "google cloud", "cloud computing")),

        // --- Logistics / operations ---
        SkillDefinition("Inventory management", SkillCategory.LOGISTICS, listOf("inventory management", "stock control", "spare parts", "warehouse")),
        SkillDefinition("Supply chain", SkillCategory.LOGISTICS, listOf("supply chain", "procurement", "logistics")),
        SkillDefinition("Forklift operation", SkillCategory.LOGISTICS, listOf("forklift", "chariot elevateur", "clark")),
        SkillDefinition("Driving licence", SkillCategory.LOGISTICS, listOf("driving licence", "driving license", "permis de conduire", "class b licence")),

        // --- Domain / professional ---
        SkillDefinition("Quality control", SkillCategory.DOMAIN, listOf("quality control", "quality assurance", "qa", "controle qualite")),
        SkillDefinition("Project management", SkillCategory.DOMAIN, listOf("project management", "pmp", "prince2", "agile", "scrum")),
        SkillDefinition("Customer service", SkillCategory.DOMAIN, listOf("customer service", "client relations", "service client")),
        SkillDefinition("Accounting", SkillCategory.DOMAIN, listOf("accounting", "bookkeeping", "comptabilite", "invoicing")),
        SkillDefinition("Nursing", SkillCategory.DOMAIN, listOf("nursing", "patient care", "caregiver", "soins infirmiers")),
        SkillDefinition("Teaching", SkillCategory.DOMAIN, listOf("teaching", "tutoring", "training delivery", "instructor")),
        SkillDefinition("Sales", SkillCategory.DOMAIN, listOf("sales", "business development", "commercial")),
        SkillDefinition("Marketing", SkillCategory.DOMAIN, listOf("marketing", "digital marketing", "seo", "social media")),
        SkillDefinition("Engineering design", SkillCategory.DOMAIN, listOf("engineering design", "technical drawing", "product design")),
        SkillDefinition("Team supervision", SkillCategory.SOFT, listOf("supervision", "team lead", "team leader", "supervised", "mentoring", "trained")),
        SkillDefinition("Problem solving", SkillCategory.SOFT, listOf("problem solving", "troubleshooting", "root cause")),
        SkillDefinition("Communication", SkillCategory.SOFT, listOf("communication", "presentation skills", "reporting")),
    )

    /** Languages we can recognise, including the ones a Malagasy CV is likely to list. */
    val languages: List<String> = listOf(
        "English", "French", "Malagasy", "German", "Spanish", "Portuguese", "Italian",
        "Arabic", "Mandarin", "Chinese", "Dutch", "Swahili", "Hindi", "Russian", "Japanese",
        "Turkish", "Korean", "Indonesian", "Greek", "Polish", "Romanian", "Hebrew", "Norwegian",
        "Swedish", "Danish", "Finnish", "Czech", "Hindi", "Bengali", "Urdu", "Persian",
    )

    /**
     * How a CV spells each language, keyed by the canonical English name. A CV written in
     * French says "Anglais", not "English"; both must resolve to the same skill so a
     * francophone candidate is not scored as having no languages.
     */
    val languageAliases: Map<String, List<String>> = mapOf(
        "English" to listOf("english", "anglais", "ingles", "inglés", "englisch"),
        "French" to listOf("french", "français", "francais", "frances", "französisch", "franzosich"),
        "Malagasy" to listOf("malagasy", "malgache"),
        "German" to listOf("german", "allemand", "alemán", "aleman", "deutsch"),
        "Spanish" to listOf("spanish", "espagnol", "espanol", "español"),
        "Italian" to listOf("italian", "italien", "italiano"),
        "Portuguese" to listOf("portuguese", "portugais", "português", "portugues"),
        "Arabic" to listOf("arabic", "arabe", "árabe", "arabe"),
        "Mandarin" to listOf("mandarin", "chinese", "chinois", "putonghua"),
        "Chinese" to listOf("chinese", "chinois", "mandarin"),
        "Dutch" to listOf("dutch", "néerlandais", "neerlandais", "holandés"),
        "Swahili" to listOf("swahili", "kiswahili"),
        "Turkish" to listOf("turkish", "turc", "turco"),
        "Korean" to listOf("korean", "coréen", "coreen"),
        "Russian" to listOf("russian", "russe", "ruso"),
        "Japanese" to listOf("japanese", "japonais", "japonés"),
        "Hindi" to listOf("hindi", "hindî"),
    )

    /** Degree and diploma markers, longest first so "master" wins over "ma". */
    val degreeMarkers: List<String> = listOf(
        "doctor of philosophy", "phd", "doctorate", "master", "masters", "magistère", "magistere",
        "bachelor", "licence", "licenciatura", "engineer", "ingénieur", "ingenieur",
        "bts", "dut", "hnd", "diploma", "diplôme", "diplome", "advanced diploma",
        "associate degree", "baccalauréat", "baccalaureat", "bac", "cap", "certificate of higher education",
        "higher national diploma", "technician diploma", "certificate",
    )

    val institutionMarkers: List<String> = listOf(
        "university", "université", "universite", "institute", "institut", "college", "collège",
        "school", "école", "ecole", "polytechnic", "academy", "académie", "academie", "lycée", "lycee",
        "faculty", "faculté", "faculte", "centre de formation", "training centre", "training center",
    )

    val certificationMarkers: List<String> = listOf(
        "red seal", "certified", "certification", "certificate", "accredited", "licensed",
        "ccna", "ccnp", "comptia", "aws certified", "microsoft certified", "pmp", "prince2",
        "osha", "nebosh", "iso 9001", "iso 45001", "first aid", "forklift licence",
        "welding certificate", "electrical licence", "safety certificate", "habilitación", "habilitation",
    )

    /** Words that make a line a job title rather than a sentence. */
    val titleMarkers: List<String> = listOf(
        "technician", "technicien", "engineer", "ingénieur", "ingenieur", "manager", "supervisor",
        "superviseur", "operator", "opérateur", "operateur", "specialist", "spécialiste", "specialiste",
        "analyst", "analyste", "consultant", "coordinator", "coordinateur", "assistant", "assistant(e)",
        "director", "directeur", "lead", "head of", "officer", "responsable", "chargé", "charge",
        "developer", "développeur", "developpeur", "nurse", "infirmier", "infirmière", "teacher",
        "enseignant", "accountant", "comptable", "electrician", "électricien", "electricien",
        "mechanic", "mécanicien", "mecanicien", "fitter", "welder", "soudeur", "driver", "chauffeur",
        "chef", "agent", "associate", "advisor", "adviser", "architect", "architecte", "planner",
    )

    /** Country names used to detect a location line and preferred destinations. */
    val countries: List<String> = listOf(
        "Madagascar", "Canada", "France", "Germany", "Allemagne", "Belgium", "Belgique", "Switzerland",
        "Suisse", "Luxembourg", "Netherlands", "Pays-Bas", "Ireland", "Irlande", "Norway", "Norvège",
        "Sweden", "Suède", "Denmark", "Danemark", "Finland", "Finlande", "Austria", "Autriche",
        "Italy", "Italie", "Spain", "Espagne", "Portugal", "United Kingdom", "UK", "England",
        "United States", "USA", "Australia", "Australie", "New Zealand", "Nouvelle-Zélande",
        "United Arab Emirates", "UAE", "Émirats arabes unis", "Qatar", "Saudi Arabia", "Arabie saoudite",
        "Kuwait", "Oman", "Bahrain", "South Africa", "Afrique du Sud", "Mauritius", "Maurice",
        "Japan", "Japon", "South Korea", "Corée du Sud", "Singapore", "Singapour", "Malaysia",
        "Brazil", "Brésil", "Mexico", "Mexique", "Morocco", "Maroc", "Tunisia", "Tunisie",
        "Réunion", "Reunion", "Mayotte", "Comoros", "Comores", "Seychelles", "Kenya", "Rwanda",
    )

    /** Major cities that frequently stand in for a country on a CV header. */
    val cities: List<String> = listOf(
        "Antananarivo", "Toamasina", "Antsirabe", "Fianarantsoa", "Mahajanga", "Toliara",
        "Nairobi", "Johannesburg", "Cape Town", "Dubai", "Abu Dhabi", "Doha", "Riyadh",
        "Paris", "Lyon", "Marseille", "London", "Manchester", "Berlin", "Munich", "Frankfurt",
        "Amsterdam", "Brussels", "Geneva", "Zurich", "Madrid", "Barcelona", "Lisbon", "Rome",
        "Milan", "Toronto", "Vancouver", "Montreal", "Ottawa", "Ottawa", "Calgary",
        "New York", "Chicago", "Houston", "Sydney", "Melbourne", "Auckland", "Singapore",
        "Hong Kong", "Tokyo", "Seoul", "Kuala Lumpur", "Port Louis", "Saint-Denis",
    )

    /** Country names and cities, accent-folded, for tolerant matching against folded text. */
    val foldedPlaces: Set<String> =
        (countries + cities).map { CvTextNormalizer.fold(it) }.toSet()

    /** Relocation signals, checked in order. */
    val relocationPhrases: List<Pair<String, String>> = listOf(
        "willing to relocate" to "Willing to relocate",
        "open to relocation" to "Open to relocation",
        "ready to relocate" to "Ready to relocate",
        "mobility" to "Mobile",
        "mobile" to "Mobile",
        "relocation" to "Relocation",
        "remote only" to "Remote only",
        "remote work" to "Remote",
        "no relocation" to "Not willing to relocate",
    )

    /** Visa signals. */
    val visaPhrases: List<Pair<String, String>> = listOf(
        "work permit" to "Mentions a work permit",
        "visa sponsorship" to "Needs visa sponsorship",
        "sponsorship" to "Needs visa sponsorship",
        "residence permit" to "Residence permit mentioned",
        "carte de séjour" to "Residence permit mentioned",
        "passeport" to "Passport mentioned",
        "passport" to "Passport mentioned",
    )

    /** Section headings, keyed by the normalised heading text. */
    val sectionKeywords: Map<String, Section> = buildMap {
        fun add(section: Section, vararg keys: String) = keys.forEach { put(it, section) }
        add(Section.SUMMARY, "summary", "profile", "professional summary", "about me", "about", "objective", "career objective", "profil", "résumé", "resume", "présentation", "presentation", "objectif", "professional profile", "career summary", "personal summary", "summary of experience", "a propos", "à propos", "overview", "executive summary")
        add(Section.EXPERIENCE, "experience", "work experience", "professional experience", "employment", "employment history", "work history", "career", "expérience", "experience professionnelle", "parcours professionnel", "expériences", "historique professionnel", "professional background", "work background", "job history", "relevant experience", "career history", "expérience professionnelle", "parcours", "expérience de travail", "experience de travail", "expérience professionnelles", "professional experience summary")
        add(Section.EDUCATION, "education", "academic background", "qualifications", "academic", "formation", "éducation", "education et formation", "études", "etudes", "diplômes", "diplomes", "education and training", "academic qualifications", "educational background", "academic history", "qualification", "formation et diplômes", "formation et diplomes", "education & training", "études et diplômes", "parcours scolaire", "scolarité", "scolarite")
        add(Section.SKILLS, "skills", "technical skills", "core skills", "key skills", "competencies", "areas of expertise", "expertise", "compétences", "competences", "compétences techniques", "savoir-faire", "skill set", "skills summary", "professional skills", "additional skills", "core competencies", "key competencies", "technical competencies", "savoir faire", "compétences clés", "competences cles", "compétences professionnelles", "aptitudes", "technical expertise", "hard skills", "soft skills", "skills & abilities", "skills and abilities")
        add(Section.LANGUAGES, "languages", "language skills", "langues", "langues parlées", "langues parlees", "language", "langues etrangeres", "langues étrangères", "foreign languages", "language proficiency", "linguistic skills", "competences linguistiques")
        add(Section.CERTIFICATIONS, "certifications", "certificates", "licences", "licenses", "training", "certifications et formations", "attestations", "certifications and training", "professional certifications", "certifications & licences", "licences & certifications", "certifications et licence", "cursus", "formations", "certification", "professional development", "certifications & training")
        add(Section.PROJECTS, "projects", "selected projects", "projets", "réalisations", "realisations", "personal projects", "project experience", "key projects", "projets personnels", "réalisations professionnelles")
        add(Section.CONTACT, "contact", "contact details", "coordonnées", "coordonnees", "informations personnelles", "contact information", "personal details", "personal information", "contact info", "coordonnées personnelles", "coordonnees personnelles", "details", "détails")
        add(Section.INTERESTS, "interests", "hobbies", "centres d'intérêt", "loisirs", "activités", "activites", "interest", "centres d'interet", "activities", "hobbies and interests", "interests and hobbies", "activités extra-professionnelles")
        add(Section.REFERENCES, "references", "références", "references professionnelles", "professional references", "referees", "références professionnelles", "reference")
    }

    enum class Section { SUMMARY, EXPERIENCE, EDUCATION, SKILLS, LANGUAGES, CERTIFICATIONS, PROJECTS, CONTACT, INTERESTS, REFERENCES, UNKNOWN }
}
