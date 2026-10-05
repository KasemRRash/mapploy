import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const OUTPUT_DIR = path.join(ROOT, "evaluation");
const BASE_URL = process.env.JOBLENS_URL || "http://127.0.0.1:4173";

const SAMPLE_IDS = [
  "governikus:2692491",
  "governikus:2702976",
  "governikus:2782579",
  "governikus:2654358",
  "hmmh:2573113",
  "hmmh:2619229",
  "hoppe-marine:2748418",
  "ip-dynamics:2096212",
  "ip-dynamics:1251531",
  "ip-dynamics:2709468",
  "sog:1551002",
  "spaceteams:631970",
  "spaceteams:2539644",
  "we4it-group:2422780",
  "we4it-group:2553931",
];

async function requestJson(url, options = {}) {
  const response = await fetch(url, { ...options, signal: AbortSignal.timeout(135_000) });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || `HTTP ${response.status}`);
  return payload;
}

function evidenceItems(analysis) {
  const items = [
    ...(analysis.requiredSkills || []),
    ...(analysis.optionalSkills || []),
    ...(analysis.languageRequirements || []),
    ...(analysis.experienceRequirements || []),
    ...(analysis.contradictions || []),
  ];
  if (analysis.workplaceEvidence) {
    items.push({
      evidence: analysis.workplaceEvidence,
      evidenceVerified: analysis.workplaceEvidenceVerified !== false,
    });
  }
  return items.filter((item) => item.evidence);
}

function validSchema(analysis) {
  return (
    analysis &&
    typeof analysis.summary === "string" &&
    typeof analysis.seniority === "string" &&
    typeof analysis.workplaceModel === "string" &&
    Array.isArray(analysis.requiredSkills) &&
    Array.isArray(analysis.optionalSkills) &&
    Array.isArray(analysis.languageRequirements) &&
    Array.isArray(analysis.experienceRequirements) &&
    Array.isArray(analysis.contradictions) &&
    Array.isArray(analysis.unclearInformation)
  );
}

function sourceFacts(job) {
  const text = job.rawDescription || "";
  return {
    language: /deutsch|englisch|german|english/i.test(text),
    workplace: /remote|homeoffice|home office|hybrid|vor ort|on[- ]site|mobil(?:e[nsr]?|es)? arbeiten/i.test(text),
    experience: /erfahrung|experience|berufspraxis|kenntnisse/i.test(text),
  };
}

function extractedFacts(analysis) {
  return {
    language: (analysis.languageRequirements || []).length > 0,
    workplace: Boolean(analysis.workplaceModel && !/^unclear$/i.test(analysis.workplaceModel)),
    experience: (analysis.experienceRequirements || []).length > 0,
  };
}

function metricsFor(rows, key) {
  let evidenceTotal = 0;
  let evidenceVerified = 0;
  let claims = 0;
  let schemaValid = 0;
  let sourcePositiveFields = 0;
  let sourcePositiveFieldsFound = 0;

  for (const row of rows) {
    const analysis = row[key];
    if (validSchema(analysis)) schemaValid += 1;
    const evidence = evidenceItems(analysis);
    evidenceTotal += evidence.length;
    evidenceVerified += evidence.filter((item) => item.evidenceVerified !== false).length;
    claims += evidence.length;

    const reference = sourceFacts(row.job);
    const extracted = extractedFacts(analysis);
    for (const field of Object.keys(reference)) {
      if (!reference[field]) continue;
      sourcePositiveFields += 1;
      if (extracted[field]) sourcePositiveFieldsFound += 1;
    }
  }

  return {
    jobs: rows.length,
    schemaValidityRate: schemaValid / rows.length,
    exactEvidenceRate: evidenceTotal ? evidenceVerified / evidenceTotal : 0,
    exactEvidenceClaims: evidenceVerified,
    totalEvidenceClaims: evidenceTotal,
    averageClaimsPerJob: claims / rows.length,
    explicitFieldRecall: sourcePositiveFields ? sourcePositiveFieldsFound / sourcePositiveFields : 0,
    explicitFieldsFound: sourcePositiveFieldsFound,
    explicitFieldsInSource: sourcePositiveFields,
  };
}

const initial = await requestJson(`${BASE_URL}/api/jobs`);
const initialById = new Map(initial.jobs.map((job) => [job.id, job]));
const missing = SAMPLE_IDS.filter((id) => !initialById.has(id));
if (missing.length) throw new Error(`Sample jobs missing: ${missing.join(", ")}`);

const timings = new Map();
const failures = [];
for (const [index, id] of SAMPLE_IDS.entries()) {
  const current = initialById.get(id);
  if (current.analysis?.engine === "local-ai") {
    timings.set(id, 0);
    console.log(`[${index + 1}/${SAMPLE_IDS.length}] Reused ${current.title}`);
    continue;
  }
  const startedAt = Date.now();
  try {
    const payload = await requestJson(`${BASE_URL}/api/jobs/${encodeURIComponent(id)}/analyze`, { method: "POST" });
    timings.set(id, Date.now() - startedAt);
    console.log(`[${index + 1}/${SAMPLE_IDS.length}] Analysed ${payload.job.title} in ${timings.get(id)} ms`);
  } catch (error) {
    failures.push({ id, error: error.message });
    timings.set(id, Date.now() - startedAt);
    console.log(`[${index + 1}/${SAMPLE_IDS.length}] FAILED ${current.title}: ${error.message}`);
  }
}

const finalPayload = await requestJson(`${BASE_URL}/api/jobs`);
const finalById = new Map(finalPayload.jobs.map((job) => [job.id, job]));
const rows = SAMPLE_IDS.map((id) => {
  const job = finalById.get(id);
  return {
    job,
    baseline: job.baseline || (job.analysis?.engine === "rules" ? job.analysis : null),
    ai: job.analysis,
    latencyMs: timings.get(id) || 0,
  };
}).filter((row) => row.baseline && row.ai?.engine === "local-ai");

const baselineMetrics = metricsFor(rows, "baseline");
const aiMetrics = metricsFor(rows, "ai");
const measuredLatencies = rows.map((row) => row.latencyMs).filter((value) => value > 0);
aiMetrics.averageLatencyMs = measuredLatencies.length
  ? measuredLatencies.reduce((sum, value) => sum + value, 0) / measuredLatencies.length
  : null;

const report = {
  generatedAt: new Date().toISOString(),
  scope: "Engineering pilot on current official employer advertisements",
  limitations: [
    "The source-pattern cross-check covers language, workplace and experience signals only.",
    "The group still needs to validate must-have versus optional classifications manually.",
    "This pilot measures exact evidence grounding and structured output, not user decision time.",
  ],
  requestedSampleSize: SAMPLE_IDS.length,
  completedSampleSize: rows.length,
  failures,
  baseline: baselineMetrics,
  localAi: aiMetrics,
  jobs: rows.map(({ job, baseline, ai, latencyMs }) => ({
    id: job.id,
    title: job.title,
    company: job.company,
    source: job.sourceName,
    location: job.location,
    latencyMs,
    baselineClaims: evidenceItems(baseline).length,
    baselineVerified: evidenceItems(baseline).filter((item) => item.evidenceVerified !== false).length,
    aiClaims: evidenceItems(ai).length,
    aiVerified: evidenceItems(ai).filter((item) => item.evidenceVerified !== false).length,
    aiUnclearItems: (ai.unclearInformation || []).length,
    reviewStatus: "Team review required for must-have versus optional classification",
  })),
};

const percent = (value) => `${Math.round(value * 100)}%`;
const seconds = (value) => (value == null ? "n/a" : `${(value / 1000).toFixed(1)} s`);
const markdown = `# JobLens pilot evaluation\n\nGenerated: ${report.generatedAt}\n\n## Scope\n\n${report.completedSampleSize} current vacancies from official employer feeds. This engineering audit checks structured output, explicit field recall and whether each evidence quote occurs exactly in the source advertisement.\n\n| Measure | Rule baseline | Local Gemma 3 4B |\n|---|---:|---:|\n| Valid structured outputs | ${percent(baselineMetrics.schemaValidityRate)} | ${percent(aiMetrics.schemaValidityRate)} |\n| Exact evidence rate | ${percent(baselineMetrics.exactEvidenceRate)} (${baselineMetrics.exactEvidenceClaims}/${baselineMetrics.totalEvidenceClaims}) | ${percent(aiMetrics.exactEvidenceRate)} (${aiMetrics.exactEvidenceClaims}/${aiMetrics.totalEvidenceClaims}) |\n| Explicit field recall | ${percent(baselineMetrics.explicitFieldRecall)} | ${percent(aiMetrics.explicitFieldRecall)} |\n| Average evidence claims per job | ${baselineMetrics.averageClaimsPerJob.toFixed(1)} | ${aiMetrics.averageClaimsPerJob.toFixed(1)} |\n| Average local AI time | n/a | ${seconds(aiMetrics.averageLatencyMs)} |\n\n## Job sample\n\n| Job | Company | Rules | Local AI | Team classification review |\n|---|---|---:|---:|---|\n${report.jobs
  .map(
    (job) =>
      `| ${job.title.replaceAll("|", "/")} | ${job.company.replaceAll("|", "/")} | ${job.baselineVerified}/${job.baselineClaims} exact | ${job.aiVerified}/${job.aiClaims} exact | Pending |`,
  )
  .join("\n")}\n\n## Interpretation\n\nThe rules provide a transparent baseline with exact quotes but produce fewer semantic claims. The local model extracts more information and keeps the vacancy text on the laptop. The application flags every quote that cannot be found verbatim.\n\n## Limitations\n\n${report.limitations.map((item) => `- ${item}`).join("\n")}\n`;

await mkdir(OUTPUT_DIR, { recursive: true });
await writeFile(path.join(OUTPUT_DIR, "pilot-evaluation.json"), JSON.stringify(report, null, 2), "utf8");
await writeFile(path.join(OUTPUT_DIR, "pilot-evaluation.md"), markdown, "utf8");

console.log(`Evaluation complete: ${rows.length}/${SAMPLE_IDS.length} jobs.`);
console.log(`Rules exact evidence: ${percent(baselineMetrics.exactEvidenceRate)}.`);
console.log(`Local AI exact evidence: ${percent(aiMetrics.exactEvidenceRate)}.`);
console.log(`Report: ${path.join(OUTPUT_DIR, "pilot-evaluation.md")}`);
