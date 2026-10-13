package com.aiglobal.jobfinder.data.cv

/**
 * Vocabulary the analyser matches against. Keeping it here as data (rather than inline
 * regexes) makes it obvious what the analyser can and cannot recognise, and lets us
 * extend coverage without touching extraction logic.
 *
 * Aliases are matched on word boundaries against lowercased text. Canonical names are
 * what the user sees, so a CV saying "LOTO" and one saying "lockout tagout" produce the
 * same skill.
 */
internal object CvLexicon {

    data class SkillDefinition(
        val canonical: String,
        val category: SkillCategory,
        val aliases: List<String>,
    )

    enum class SkillCategory { TECHNICAL, TOOLS, SOFTWARE, SOFT, SAFETY, LOGISTICS, DOMAIN }

    val skills: List<SkillDefinition> = listOf(
        // --- Industrial / maintenance ---
        SkillDefinition("Preventive maintenance", SkillCategory.TECHNICAL, listOf("preventive maintenance", "planned maintenance", "preventative maintenance", "preventive")),
        SkillDefinition("Electrical diagnostics", SkillCategory.TECHNICAL, listOf("electrical diagnostics", "electrical fault", "electrical maintenance", "electrical troubleshooting", "electrical systems")),
        SkillDefinition("Mechanical repair", SkillCategory.TECHNICAL, listOf("mechanical repair", "mechanical maintenance", "mechanical fitting", "mechanical systems", "mecanique")),
        SkillDefinition("Hydraulics", SkillCategory.TECHNICAL, listOf("hydraulic", "hydraulics", "hydraulic systems")),
        SkillDefinition("Pneumatics", SkillCategory.TECHNICAL, listOf("pneumatic", "pneumatics")),
        SkillDefinition("Welding", SkillCategory.TECHNICAL, listOf("welding", "mig welding", "tig welding", "arc welding", "soudure")),
        SkillDefinition("HVAC & refrigeration", SkillCategory.TECHNICAL, listOf("hvac", "refrigeration", "air conditioning", "climatisation")),
        SkillDefinition("CNC machining", SkillCategory.TECHNICAL, listOf("cnc", "machining", "lathe", "milling")),
        SkillDefinition("Automation & PLC", SkillCategory.TOOLS, listOf("plc", "programmable logic controller", "automation", "scada", "siemens s7", "allen bradley")),
        SkillDefinition("CMMS software", SkillCategory.SOFTWARE, listOf("cmms", "maintenance management software", "maximo", "sap pm")),
        SkillDefinition("Predictive maintenance", SkillCategory.TECHNICAL, listOf("predictive maintenance", "condition monitoring", "vibration analysis")),

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
        add(Section.SUMMARY, "summary", "profile", "professional summary", "about me", "about", "objective", "career objective", "profil", "résumé", "resume", "présentation", "presentation", "objectif")
        add(Section.EXPERIENCE, "experience", "work experience", "professional experience", "employment", "employment history", "work history", "career", "expérience", "experience professionnelle", "parcours professionnel", "expériences", "historique professionnel")
        add(Section.EDUCATION, "education", "academic background", "qualifications", "academic", "formation", "éducation", "education et formation", "études", "etudes", "diplômes", "diplomes")
        add(Section.SKILLS, "skills", "technical skills", "core skills", "key skills", "competencies", "areas of expertise", "expertise", "compétences", "competences", "compétences techniques", "savoir-faire")
        add(Section.LANGUAGES, "languages", "language skills", "langues", "langues parlées", "langues parlees")
        add(Section.CERTIFICATIONS, "certifications", "certificates", "licences", "licenses", "training", "certifications et formations", "attestations")
        add(Section.PROJECTS, "projects", "selected projects", "projets", "réalisations", "realisations")
        add(Section.CONTACT, "contact", "contact details", "coordonnées", "coordonnees", "informations personnelles")
        add(Section.INTERESTS, "interests", "hobbies", "centres d'intérêt", "loisirs", "activités", "activites")
        add(Section.REFERENCES, "references", "références", "references professionnelles")
    }

    enum class Section { SUMMARY, EXPERIENCE, EDUCATION, SKILLS, LANGUAGES, CERTIFICATIONS, PROJECTS, CONTACT, INTERESTS, REFERENCES, UNKNOWN }
}
