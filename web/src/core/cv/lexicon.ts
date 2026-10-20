import { foldCvText } from "./normalizer";

/**
 * Vocabulary the analyser matches against. Direct port of the Kotlin `CvLexicon` in
 * `data/cv/analysis`. Keeping it here as data (rather than inline regexes) makes it obvious
 * what the analyser can and cannot recognise, and lets coverage grow without touching
 * extraction logic.
 *
 * Aliases are matched on word boundaries against folded (accent-free, lowercased) text.
 * Canonical names are what the user sees, so a CV saying "LOTO" and one saying
 * "lockout tagout" produce the same skill.
 */

export enum SkillCategory {
  TECHNICAL = "TECHNICAL",
  TOOLS = "TOOLS",
  SOFTWARE = "SOFTWARE",
  SOFT = "SOFT",
  SAFETY = "SAFETY",
  LOGISTICS = "LOGISTICS",
  DOMAIN = "DOMAIN",
}

export enum CvSection {
  SUMMARY = "SUMMARY",
  EXPERIENCE = "EXPERIENCE",
  EDUCATION = "EDUCATION",
  SKILLS = "SKILLS",
  LANGUAGES = "LANGUAGES",
  CERTIFICATIONS = "CERTIFICATIONS",
  PROJECTS = "PROJECTS",
  CONTACT = "CONTACT",
  INTERESTS = "INTERESTS",
  REFERENCES = "REFERENCES",
  UNKNOWN = "UNKNOWN",
}

export interface SkillDefinition {
  canonical: string;
  category: SkillCategory;
  aliases: string[];
}

export const CV_SKILLS: SkillDefinition[] = [
  // --- Industrial / maintenance ---
  { canonical: "Preventive maintenance", category: SkillCategory.TECHNICAL, aliases: ["preventive maintenance", "planned maintenance", "preventative maintenance", "preventive", "maintenance preventive", "maintenance préventive", "preventative"] },
  { canonical: "Electrical diagnostics", category: SkillCategory.TECHNICAL, aliases: ["electrical diagnostics", "electrical fault", "electrical maintenance", "electrical troubleshooting", "electrical systems", "diagnostic electrique", "diagnostic électrique", "diagnostics"] },
  { canonical: "Mechanical repair", category: SkillCategory.TECHNICAL, aliases: ["mechanical repair", "mechanical maintenance", "mechanical fitting", "mechanical systems", "mecanique", "mécanique", "mechanical", "mechanic"] },
  { canonical: "Mechanical seals", category: SkillCategory.TECHNICAL, aliases: ["mechanical seal", "mechanical seals", "seal replacement", "gland packing", "joint mecanique", "joints"] },
  { canonical: "Rotating equipment", category: SkillCategory.TECHNICAL, aliases: ["rotating equipment", "pumps", "pompes", "pump repair", "compressors", "compresseurs", "gearbox", "reducteur", "réducteur", "bearing", "roulement", "coupling", "accouplement"] },
  { canonical: "Hydraulics", category: SkillCategory.TECHNICAL, aliases: ["hydraulic", "hydraulics", "hydraulic systems", "hydraulique"] },
  { canonical: "Pneumatics", category: SkillCategory.TECHNICAL, aliases: ["pneumatic", "pneumatics", "pneumatique"] },
  { canonical: "Welding", category: SkillCategory.TECHNICAL, aliases: ["welding", "mig welding", "tig welding", "arc welding", "soudure", "soudeur"] },
  { canonical: "HVAC & refrigeration", category: SkillCategory.TECHNICAL, aliases: ["hvac", "refrigeration", "air conditioning", "climatisation", "froid"] },
  { canonical: "CNC machining", category: SkillCategory.TECHNICAL, aliases: ["cnc", "machining", "lathe", "milling", "usinage", "tour", "fraisage"] },
  { canonical: "Automation & PLC", category: SkillCategory.TOOLS, aliases: ["plc", "programmable logic controller", "automation", "automate", "scada", "siemens s7", "allen bradley", "api"] },
  { canonical: "CMMS software", category: SkillCategory.SOFTWARE, aliases: ["cmms", "maintenance management software", "maximo", "sap pm", "gmao"] },
  { canonical: "Predictive maintenance", category: SkillCategory.TECHNICAL, aliases: ["predictive maintenance", "condition monitoring", "vibration analysis", "vibration", "analysis vibratoire", "analyse vibratoire"] },
  { canonical: "Metrology & measurement", category: SkillCategory.TECHNICAL, aliases: ["metrology", "metrologie", "métrologie", "calibration", "calibrage", "measurement", "measuring instruments", "micrometer", "pied a coulisse", "vernier"] },
  { canonical: "Technical documentation", category: SkillCategory.SOFT, aliases: ["technical documentation", "documentation technique", "maintenance reports", "rapports de maintenance", "procedures", "procédures", "work instructions"] },

  // --- Safety ---
  { canonical: "Workplace safety", category: SkillCategory.SAFETY, aliases: ["lockout", "tagout", "loto", "workplace safety", "safety compliance", "hse", "occupational health"] },
  { canonical: "ISO standards", category: SkillCategory.SAFETY, aliases: ["iso 9001", "iso 45001", "iso standards", "quality management system"] },
  { canonical: "First aid", category: SkillCategory.SAFETY, aliases: ["first aid", "secourisme", "cpr"] },

  // --- Software / IT ---
  { canonical: "Microsoft Excel", category: SkillCategory.SOFTWARE, aliases: ["excel", "spreadsheets", "tableur"] },
  { canonical: "Microsoft Office", category: SkillCategory.SOFTWARE, aliases: ["microsoft office", "ms office", "word and excel", "office suite"] },
  { canonical: "SAP", category: SkillCategory.SOFTWARE, aliases: ["sap", "erp"] },
  { canonical: "AutoCAD", category: SkillCategory.SOFTWARE, aliases: ["autocad", "cad design", "drafting"] },
  { canonical: "SolidWorks", category: SkillCategory.SOFTWARE, aliases: ["solidworks", "solid works"] },
  { canonical: "Python", category: SkillCategory.SOFTWARE, aliases: ["python"] },
  { canonical: "SQL", category: SkillCategory.SOFTWARE, aliases: ["sql", "mysql", "postgresql", "database queries"] },
  { canonical: "Java", category: SkillCategory.SOFTWARE, aliases: ["java", "kotlin"] },
  { canonical: "Data analysis", category: SkillCategory.SOFTWARE, aliases: ["data analysis", "analytics", "power bi", "tableau"] },
  { canonical: "Cloud platforms", category: SkillCategory.SOFTWARE, aliases: ["aws", "azure", "google cloud", "cloud computing"] },

  // --- Logistics / operations ---
  { canonical: "Inventory management", category: SkillCategory.LOGISTICS, aliases: ["inventory management", "stock control", "spare parts", "warehouse"] },
  { canonical: "Supply chain", category: SkillCategory.LOGISTICS, aliases: ["supply chain", "procurement", "logistics"] },
  { canonical: "Forklift operation", category: SkillCategory.LOGISTICS, aliases: ["forklift", "chariot elevateur", "clark"] },
  { canonical: "Driving licence", category: SkillCategory.LOGISTICS, aliases: ["driving licence", "driving license", "permis de conduire", "class b licence"] },

  // --- Domain / professional ---
  { canonical: "Quality control", category: SkillCategory.DOMAIN, aliases: ["quality control", "quality assurance", "qa", "controle qualite"] },
  { canonical: "Project management", category: SkillCategory.DOMAIN, aliases: ["project management", "pmp", "prince2", "agile", "scrum"] },
  { canonical: "Customer service", category: SkillCategory.DOMAIN, aliases: ["customer service", "client relations", "service client"] },
  { canonical: "Accounting", category: SkillCategory.DOMAIN, aliases: ["accounting", "bookkeeping", "comptabilite", "invoicing"] },
  { canonical: "Nursing", category: SkillCategory.DOMAIN, aliases: ["nursing", "patient care", "caregiver", "soins infirmiers"] },
  { canonical: "Teaching", category: SkillCategory.DOMAIN, aliases: ["teaching", "tutoring", "training delivery", "instructor"] },
  { canonical: "Sales", category: SkillCategory.DOMAIN, aliases: ["sales", "business development", "commercial"] },
  { canonical: "Marketing", category: SkillCategory.DOMAIN, aliases: ["marketing", "digital marketing", "seo", "social media"] },
  { canonical: "Engineering design", category: SkillCategory.DOMAIN, aliases: ["engineering design", "technical drawing", "product design"] },
  { canonical: "Team supervision", category: SkillCategory.SOFT, aliases: ["supervision", "team lead", "team leader", "supervised", "mentoring", "trained"] },
  { canonical: "Problem solving", category: SkillCategory.SOFT, aliases: ["problem solving", "troubleshooting", "root cause"] },
  { canonical: "Communication", category: SkillCategory.SOFT, aliases: ["communication", "presentation skills", "reporting"] },
];

/** Languages we can recognise, including the ones a Malagasy CV is likely to list. */
export const CV_LANGUAGES: string[] = [
  "English", "French", "Malagasy", "German", "Spanish", "Portuguese", "Italian",
  "Arabic", "Mandarin", "Chinese", "Dutch", "Swahili", "Hindi", "Russian", "Japanese",
  "Turkish", "Korean", "Indonesian", "Greek", "Polish", "Romanian", "Hebrew", "Norwegian",
  "Swedish", "Danish", "Finnish", "Czech", "Hindi", "Bengali", "Urdu", "Persian",
];

/**
 * How a CV spells each language, keyed by the canonical English name. A CV written in
 * French says "Anglais", not "English"; both must resolve to the same skill so a
 * francophone candidate is not scored as having no languages. Iteration order is the order
 * languages are reported, so it matches the Kotlin map exactly.
 */
export const CV_LANGUAGE_ALIASES: Record<string, string[]> = {
  English: ["english", "anglais", "ingles", "inglés", "englisch"],
  French: ["french", "français", "francais", "frances", "französisch", "franzosich"],
  Malagasy: ["malagasy", "malgache"],
  German: ["german", "allemand", "alemán", "aleman", "deutsch"],
  Spanish: ["spanish", "espagnol", "espanol", "español"],
  Italian: ["italian", "italien", "italiano"],
  Portuguese: ["portuguese", "portugais", "português", "portugues"],
  Arabic: ["arabic", "arabe", "árabe", "arabe"],
  Mandarin: ["mandarin", "chinese", "chinois", "putonghua"],
  Chinese: ["chinese", "chinois", "mandarin"],
  Dutch: ["dutch", "néerlandais", "neerlandais", "holandés"],
  Swahili: ["swahili", "kiswahili"],
  Turkish: ["turkish", "turc", "turco"],
  Korean: ["korean", "coréen", "coreen"],
  Russian: ["russian", "russe", "ruso"],
  Japanese: ["japanese", "japonais", "japonés"],
  Hindi: ["hindi", "hindî"],
};

/** Degree and diploma markers, longest first so "master" wins over "ma". */
export const CV_DEGREE_MARKERS: string[] = [
  "doctor of philosophy", "phd", "doctorate", "master", "masters", "magistère", "magistere",
  "bachelor", "licence", "licenciatura", "engineer", "ingénieur", "ingenieur",
  "bts", "dut", "hnd", "diploma", "diplôme", "diplome", "advanced diploma",
  "associate degree", "baccalauréat", "baccalaureat", "bac", "cap", "certificate of higher education",
  "higher national diploma", "technician diploma", "certificate",
];

export const CV_INSTITUTION_MARKERS: string[] = [
  "university", "université", "universite", "institute", "institut", "college", "collège",
  "school", "école", "ecole", "polytechnic", "academy", "académie", "academie", "lycée", "lycee",
  "faculty", "faculté", "faculte", "centre de formation", "training centre", "training center",
];

export const CV_CERTIFICATION_MARKERS: string[] = [
  "red seal", "certified", "certification", "certificate", "accredited", "licensed",
  "ccna", "ccnp", "comptia", "aws certified", "microsoft certified", "pmp", "prince2",
  "osha", "nebosh", "iso 9001", "iso 45001", "first aid", "forklift licence",
  "welding certificate", "electrical licence", "safety certificate", "habilitación", "habilitation",
];

/** Words that make a line a job title rather than a sentence. */
export const CV_TITLE_MARKERS: string[] = [
  "technician", "technicien", "engineer", "ingénieur", "ingenieur", "manager", "supervisor",
  "superviseur", "operator", "opérateur", "operateur", "specialist", "spécialiste", "specialiste",
  "analyst", "analyste", "consultant", "coordinator", "coordinateur", "assistant", "assistant(e)",
  "director", "directeur", "lead", "head of", "officer", "responsable", "chargé", "charge",
  "developer", "développeur", "developpeur", "nurse", "infirmier", "infirmière", "teacher",
  "enseignant", "accountant", "comptable", "electrician", "électricien", "electricien",
  "mechanic", "mécanicien", "mecanicien", "fitter", "welder", "soudeur", "driver", "chauffeur",
  "chef", "agent", "associate", "advisor", "adviser", "architect", "architecte", "planner",
];

/** Country names used to detect a location line and preferred destinations. */
export const CV_COUNTRIES: string[] = [
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
];

/** Major cities that frequently stand in for a country on a CV header. */
export const CV_CITIES: string[] = [
  "Antananarivo", "Toamasina", "Antsirabe", "Fianarantsoa", "Mahajanga", "Toliara",
  "Nairobi", "Johannesburg", "Cape Town", "Dubai", "Abu Dhabi", "Doha", "Riyadh",
  "Paris", "Lyon", "Marseille", "London", "Manchester", "Berlin", "Munich", "Frankfurt",
  "Amsterdam", "Brussels", "Geneva", "Zurich", "Madrid", "Barcelona", "Lisbon", "Rome",
  "Milan", "Toronto", "Vancouver", "Montreal", "Ottawa", "Ottawa", "Calgary",
  "New York", "Chicago", "Houston", "Sydney", "Melbourne", "Auckland", "Singapore",
  "Hong Kong", "Tokyo", "Seoul", "Kuala Lumpur", "Port Louis", "Saint-Denis",
];

/** Country names and cities, accent-folded, for tolerant matching against folded text. */
export const CV_FOLDED_PLACES: Set<string> = new Set(
  [...CV_COUNTRIES, ...CV_CITIES].map((place) => foldCvText(place)),
);

/** Relocation signals, checked in order. */
export const CV_RELOCATION_PHRASES: Array<[string, string]> = [
  ["willing to relocate", "Willing to relocate"],
  ["open to relocation", "Open to relocation"],
  ["ready to relocate", "Ready to relocate"],
  ["mobility", "Mobile"],
  ["mobile", "Mobile"],
  ["relocation", "Relocation"],
  ["remote only", "Remote only"],
  ["remote work", "Remote"],
  ["no relocation", "Not willing to relocate"],
];

/** Visa signals. */
export const CV_VISA_PHRASES: Array<[string, string]> = [
  ["work permit", "Mentions a work permit"],
  ["visa sponsorship", "Needs visa sponsorship"],
  ["sponsorship", "Needs visa sponsorship"],
  ["residence permit", "Residence permit mentioned"],
  ["carte de séjour", "Residence permit mentioned"],
  ["passeport", "Passport mentioned"],
  ["passport", "Passport mentioned"],
];

/**
 * Section headings, keyed by the normalised heading text. Built by assigning in the same
 * order as the Kotlin `buildMap`, so a key shared by two sections resolves to the last one,
 * exactly as Kotlin does.
 */
export const CV_SECTION_KEYWORDS: Record<string, CvSection> = (() => {
  const map: Record<string, CvSection> = {};
  const add = (section: CvSection, keys: string[]) => keys.forEach((key) => { map[key] = section; });

  add(CvSection.SUMMARY, ["summary", "profile", "professional summary", "about me", "about", "objective", "career objective", "profil", "résumé", "resume", "présentation", "presentation", "objectif", "professional profile", "career summary", "personal summary", "summary of experience", "a propos", "à propos", "overview", "executive summary"]);
  add(CvSection.EXPERIENCE, ["experience", "work experience", "professional experience", "employment", "employment history", "work history", "career", "expérience", "experience professionnelle", "parcours professionnel", "expériences", "historique professionnel", "professional background", "work background", "job history", "relevant experience", "career history", "expérience professionnelle", "parcours", "expérience de travail", "experience de travail", "expérience professionnelles", "professional experience summary"]);
  add(CvSection.EDUCATION, ["education", "academic background", "qualifications", "academic", "formation", "éducation", "education et formation", "études", "etudes", "diplômes", "diplomes", "education and training", "academic qualifications", "educational background", "academic history", "qualification", "formation et diplômes", "formation et diplomes", "education & training", "études et diplômes", "parcours scolaire", "scolarité", "scolarite"]);
  add(CvSection.SKILLS, ["skills", "technical skills", "core skills", "key skills", "competencies", "areas of expertise", "expertise", "compétences", "competences", "compétences techniques", "savoir-faire", "skill set", "skills summary", "professional skills", "additional skills", "core competencies", "key competencies", "technical competencies", "savoir faire", "compétences clés", "competences cles", "compétences professionnelles", "aptitudes", "technical expertise", "hard skills", "soft skills", "skills & abilities", "skills and abilities"]);
  add(CvSection.LANGUAGES, ["languages", "language skills", "langues", "langues parlées", "langues parlees", "language", "langues etrangeres", "langues étrangères", "foreign languages", "language proficiency", "linguistic skills", "competences linguistiques"]);
  add(CvSection.CERTIFICATIONS, ["certifications", "certificates", "licences", "licenses", "training", "certifications et formations", "attestations", "certifications and training", "professional certifications", "certifications & licences", "licences & certifications", "certifications et licence", "cursus", "formations", "certification", "professional development", "certifications & training"]);
  add(CvSection.PROJECTS, ["projects", "selected projects", "projets", "réalisations", "realisations", "personal projects", "project experience", "key projects", "projets personnels", "réalisations professionnelles"]);
  add(CvSection.CONTACT, ["contact", "contact details", "coordonnées", "coordonnees", "informations personnelles", "contact information", "personal details", "personal information", "contact info", "coordonnées personnelles", "coordonnees personnelles", "details", "détails"]);
  add(CvSection.INTERESTS, ["interests", "hobbies", "centres d'intérêt", "loisirs", "activités", "activites", "interest", "centres d'interet", "activities", "hobbies and interests", "interests and hobbies", "activités extra-professionnelles"]);
  add(CvSection.REFERENCES, ["references", "références", "references professionnelles", "professional references", "referees", "références professionnelles", "reference"]);

  return map;
})();
