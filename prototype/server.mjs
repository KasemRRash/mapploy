import http from "node:http";
import { createHash } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const PUBLIC_DIR = path.join(ROOT, "public");
const DATA_DIR = path.join(ROOT, "data");
const DATA_FILE = path.join(DATA_DIR, "jobs.json");
const PORT = Number(process.env.PORT || 4173);
const SYNC_INTERVAL_MINUTES = Math.max(5, Number(process.env.SYNC_INTERVAL_MINUTES || 1440));

const FEEDS = [
  {
    key: "hmmh",
    name: "hmmh multimediahaus AG",
    url: "https://hmmh.jobs.personio.de/xml?language=de",
    jobBaseUrl: "https://hmmh.jobs.personio.de/job/",
  },
  {
    key: "governikus",
    name: "Governikus Software GmbH",
    url: "https://governikus.jobs.personio.de/xml?language=de",
    jobBaseUrl: "https://governikus.jobs.personio.de/job/",
  },
  {
    key: "we4it-group",
    name: "WE4IT Group",
    url: "https://we4it-group.jobs.personio.de/xml?language=de",
    jobBaseUrl: "https://we4it-group.jobs.personio.de/job/",
  },
  {
    key: "hoppe-marine",
    name: "Hoppe Marine GmbH",
    url: "https://hoppe-marine-gmbh.jobs.personio.de/xml?language=de",
    jobBaseUrl: "https://hoppe-marine-gmbh.jobs.personio.de/job/",
  },
  {
    key: "spaceteams",
    name: "Spaceteams GmbH",
    url: "https://spaceteams.jobs.personio.de/xml?language=de",
    jobBaseUrl: "https://spaceteams.jobs.personio.de/job/",
  },
  {
    key: "ip-dynamics",
    name: "IP Dynamics GmbH",
    url: "https://ip-dynamics-gmbh.jobs.personio.de/xml?language=de",
    jobBaseUrl: "https://ip-dynamics-gmbh.jobs.personio.de/job/",
  },
  {
    key: "sog",
    name: "SOG Business-Software GmbH",
    url: "https://personio-sog.jobs.personio.de/xml?language=de",
    jobBaseUrl: "https://personio-sog.jobs.personio.de/job/",
  },
];

const LOCATION_COORDINATES = {
  bremen: [8.8017, 53.0793],
  hamburg: [9.9937, 53.5511],
  berlin: [13.405, 52.52],
  erfurt: [11.0299, 50.9848],
  kempten: [10.316, 47.726],
  "köln": [6.9603, 50.9375],
  cologne: [6.9603, 50.9375],
};

const SKILLS = [
  ["JavaScript", /\bjavascript\b/i],
  ["TypeScript", /\btypescript\b/i],
  ["Spring Boot", /\bspring\s*boot\b/i],
  ["Java", /\bjava\b(?!script)/i],
  ["React", /\breact(?:\.js)?\b/i],
  ["Angular", /\bangular\b/i],
  ["Vue", /\bvue(?:\.js)?\b/i],
  ["Python", /\bpython\b/i],
  ["C#", /\bc#\b/i],
  [".NET", /\b\.net\b/i],
  ["Docker", /\bdocker\b/i],
  ["Kubernetes", /\bkubernetes\b|\bk8s\b/i],
  ["PostgreSQL", /\bpostgres(?:ql)?\b/i],
  ["SQL", /\bsql\b/i],
  ["AWS", /\baws\b|amazon web services/i],
  ["Azure", /\bazure\b/i],
  ["Git", /\bgit\b/i],
  ["Linux", /\blinux\b/i],
  ["REST", /\brest(?:ful)?\b/i],
  ["JUnit", /\bjunit\b/i],
  ["Cypress", /\bcypress\b/i],
  ["Scrum", /\bscrum\b/i],
];

const state = {
  jobs: [],
  lastSyncAt: null,
  lastSyncSummary: null,
  syncError: null,
  syncing: false,
};

function decodeXml(value = "") {
  return value
    .replace(/^\s*<!\[CDATA\[|\]\]>\s*$/g, "")
    .replace(/&#x([0-9a-f]+);/gi, (_, code) => String.fromCodePoint(parseInt(code, 16)))
    .replace(/&#(\d+);/g, (_, code) => String.fromCodePoint(Number(code)))
    .replace(/&nbsp;/gi, " ")
    .replace(/&amp;/gi, "&")
    .replace(/&lt;/gi, "<")
    .replace(/&gt;/gi, ">")
    .replace(/&quot;/gi, '"')
    .replace(/&apos;/gi, "'");
}

function stripHtml(value = "") {
  return decodeXml(value)
    .replace(/<\s*br\s*\/?>/gi, "\n")
    .replace(/<\s*\/\s*(?:p|li|ul|ol|h\d)\s*>/gi, "\n")
    .replace(/<\s*li[^>]*>/gi, "• ")
    .replace(/<[^>]+>/g, " ")
    .replace(/[\t ]+/g, " ")
    .replace(/\s*\n\s*/g, "\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

function tagValue(xml, tag) {
  const match = xml.match(new RegExp(`<${tag}(?:\\s[^>]*)?>([\\s\\S]*?)<\\/${tag}>`, "i"));
  return match ? stripHtml(match[1]) : "";
}

function tagBlocks(xml, tag) {
  return [...xml.matchAll(new RegExp(`<${tag}(?:\\s[^>]*)?>([\\s\\S]*?)<\\/${tag}>`, "gi"))].map(
    (match) => match[1],
  );
}

function stableHash(value) {
  return createHash("sha256").update(value).digest("hex");
}

function coordinatesFor(location, id) {
  const normalized = location.toLocaleLowerCase("de-DE");
  const entry = Object.entries(LOCATION_COORDINATES).find(([name]) => normalized.includes(name));
  if (!entry) return null;
  const seed = [...String(id)].reduce((sum, char) => sum + char.charCodeAt(0), 0);
  const angle = ((seed % 12) / 12) * Math.PI * 2;
  const radius = 0.004 + (seed % 4) * 0.0015;
  return [entry[1][0] + Math.cos(angle) * radius, entry[1][1] + Math.sin(angle) * radius];
}

function sentenceList(text) {
  return text
    .split(/\n+|(?<=[.!?])\s+(?=[A-ZÄÖÜ])/)
    .map((sentence) => sentence.replace(/^•\s*/, "").trim())
    .filter((sentence) => sentence.length >= 8);
}

function findEvidence(sentences, pattern) {
  return sentences.find((sentence) => pattern.test(sentence)) || "";
}

function baselineAnalyze(job) {
  const sentences = sentenceList(job.rawDescription);
  const required = [];
  const optional = [];
  const optionalContext = /idealerweise|wünschenswert|von vorteil|nice[ -]?to[ -]?have|optional|bonus/i;

  for (const [name, pattern] of SKILLS) {
    const evidence = findEvidence(sentences, pattern);
    if (!evidence) continue;
    const item = { name, evidence, evidenceVerified: true };
    if (optionalContext.test(evidence)) optional.push(item);
    else required.push(item);
  }

  const languagePatterns = [
    ["German", /(?:deutsch(?:kenntnisse)?|german).*/i],
    ["English", /(?:englisch(?:kenntnisse)?|english).*/i],
  ];
  const languageRequirements = languagePatterns
    .map(([language, pattern]) => {
      const evidence = findEvidence(sentences, pattern);
      if (!evidence) return null;
      const level = evidence.match(/\b(?:A1|A2|B1|B2|C1|C2)\b/i)?.[0]?.toUpperCase() || "Not specified";
      return { language, level, evidence, evidenceVerified: true };
    })
    .filter(Boolean);

  const experienceRequirements = sentences
    .filter((sentence) => /\b\d+\s*(?:\+\s*)?(?:jahre?|years?)\b/i.test(sentence))
    .slice(0, 3)
    .map((evidence) => ({ text: evidence, evidence, evidenceVerified: true }));

  const workplaceEvidence = findEvidence(sentences, /remote|homeoffice|home office|hybrid|vor ort|on[- ]site/i);
  let workplaceModel = "Unclear";
  if (/hybrid/i.test(workplaceEvidence)) workplaceModel = "Hybrid";
  else if (/remote|homeoffice|home office/i.test(workplaceEvidence)) workplaceModel = "Remote or hybrid";
  else if (/vor ort|on[- ]site/i.test(workplaceEvidence)) workplaceModel = "On-site";

  const contradictions = [];
  if (/\bjunior\b/i.test(job.title)) {
    const seniorEvidence = findEvidence(sentences, /\b(?:[3-9]|\d{2,})\s*(?:\+\s*)?(?:jahre?|years?)\b|architektur|architecture|mentoring|lead/i);
    if (seniorEvidence) {
      contradictions.push({
        finding: "The junior title may conflict with the requested experience or responsibility.",
        evidence: seniorEvidence,
        evidenceVerified: true,
      });
    }
  }

  const unclearInformation = [];
  if (!languageRequirements.length) unclearInformation.push("No explicit language requirement was found.");
  if (!workplaceEvidence) unclearInformation.push("The workplace model is not stated clearly.");
  if (!/gehalt|salary|vergütung|euro|€/i.test(job.rawDescription)) unclearInformation.push("No salary range was found.");
  if (!experienceRequirements.length) unclearInformation.push("No explicit number of years of experience was found.");

  const detectedCount = required.length + optional.length + languageRequirements.length + experienceRequirements.length;
  return {
    engine: "rules",
    model: "Transparent rule-based baseline",
    generatedAt: new Date().toISOString(),
    summary: `${detectedCount} explicit requirement signals found. ${unclearInformation.length} important items remain unclear.`,
    seniority: job.seniority || "Unclear",
    workplaceModel,
    workplaceEvidence,
    requiredSkills: required,
    optionalSkills: optional,
    languageRequirements,
    experienceRequirements,
    contradictions,
    unclearInformation,
    confidence: detectedCount > 5 ? "medium" : "low",
  };
}

function categoryFor(job) {
  const title = String(job.title || "").toLocaleLowerCase("de-DE");
  const role = `${title} ${job.department || ""}`.toLocaleLowerCase("de-DE");
  if (/human resources|people|personal|recruiting|talent/i.test(role)) return "People & HR";
  if (/sales|vertrieb|account|customer success|pre-sales|marketing/i.test(role)) return "Sales & Customer";
  if (/finance|buchhalt|steuer|kaufmänn|sachbearb/i.test(role)) return "Finance & Administration";
  if (/\bit[- ]?projekt/i.test(title)) return "IT & Software";
  if (/project|projekt|product|produkt|scrum|continuity|pmo/i.test(role)) return "Project & Product";
  if (/software|developer|entwick|\bit\b|devops|cloud|data|frontend|backend|full.?stack|test|systemadmin|informatik|architect|architekt|digital|cyber|security|erp|sap|\bki\b|\bai\b|microsoft 365|azure/i.test(role)) return "IT & Software";
  if (/engineer|engineering|techniker|technik|konstruktion|mechanik|mechatronik|plc|automation|service/i.test(role)) return "Engineering & Technical";
  if (/operations|logistik|lager|material|ersatzteil/i.test(role)) return "Operations & Logistics";
  return "Other roles";
}

function isInPrototypeScope(job) {
  return /bremen|hamburg|remote/i.test(job.location || "");
}

function parsePersonio(xml, feed, now) {
  return tagBlocks(xml, "position").map((positionXml) => {
    const externalJobId = tagValue(positionXml, "id");
    const descriptionSections = tagBlocks(positionXml, "jobDescription").map((section) => {
      const name = tagValue(section, "name");
      const valueMatch = section.match(/<value(?:\s[^>]*)?>([\s\S]*?)<\/value>/i);
      const value = valueMatch ? stripHtml(valueMatch[1]) : "";
      return { name, value };
    });
    const rawDescription = descriptionSections
      .filter((section) => section.value)
      .map((section) => `[${section.name || "Section"}]\n${section.value}`)
      .join("\n\n");
    const location = tagValue(positionXml, "office") || "Location not specified";
    const job = {
      id: `${feed.key}:${externalJobId}`,
      sourceKey: feed.key,
      sourceName: feed.name,
      sourceType: "Official Personio employer feed",
      sourceFeedUrl: feed.url,
      sourceUrl: `${feed.jobBaseUrl}${externalJobId}`,
      externalJobId,
      title: tagValue(positionXml, "name") || "Untitled vacancy",
      company: tagValue(positionXml, "subcompany") || feed.name,
      location,
      coordinates: coordinatesFor(location, externalJobId),
      department: tagValue(positionXml, "department"),
      employmentType: tagValue(positionXml, "employmentType"),
      seniority: tagValue(positionXml, "seniority"),
      schedule: tagValue(positionXml, "schedule"),
      sourceCreatedAt: tagValue(positionXml, "createdAt") || null,
      rawDescription,
      descriptionSections,
      lastSeenAt: now,
      lastCheckedAt: now,
      active: true,
      consecutiveMissingCount: 0,
    };
    job.category = categoryFor(job);
    job.contentHash = stableHash(
      [job.title, job.company, job.location, job.seniority, job.employmentType, rawDescription].join("\n"),
    );
    return job;
  });
}

async function loadState() {
  try {
    const saved = JSON.parse(await readFile(DATA_FILE, "utf8"));
    state.jobs = Array.isArray(saved.jobs) ? saved.jobs : [];
    state.lastSyncAt = saved.lastSyncAt || null;
    state.lastSyncSummary = saved.lastSyncSummary || null;
  } catch (error) {
    if (error.code !== "ENOENT") console.warn("Could not load saved jobs:", error.message);
  }
}

async function saveState() {
  await mkdir(DATA_DIR, { recursive: true });
  await writeFile(
    DATA_FILE,
    JSON.stringify(
      { jobs: state.jobs, lastSyncAt: state.lastSyncAt, lastSyncSummary: state.lastSyncSummary },
      null,
      2,
    ),
    "utf8",
  );
}

async function syncFeeds() {
  if (state.syncing) return state.lastSyncSummary;
  state.syncing = true;
  state.syncError = null;
  const now = new Date().toISOString();
  const existingById = new Map(state.jobs.map((job) => [job.id, job]));
  const completedSources = new Set();
  const seenIds = new Set();
  const observedIds = new Set();
  const errors = [];
  let added = 0;
  let changed = 0;

  try {
    for (const feed of FEEDS) {
      try {
        const response = await fetch(feed.url, {
          headers: { "User-Agent": "JobLens-BIP-Prototype/0.1 (educational project)" },
          signal: AbortSignal.timeout(20_000),
        });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const parsedJobsFromFeed = parsePersonio(await response.text(), feed, now);
        for (const job of parsedJobsFromFeed) observedIds.add(job.id);
        const parsedJobs = parsedJobsFromFeed.filter(isInPrototypeScope);
        completedSources.add(feed.key);

        for (const incoming of parsedJobs) {
          seenIds.add(incoming.id);
          const existing = existingById.get(incoming.id);
          const baseline = baselineAnalyze(incoming);
          if (!existing) {
            incoming.firstSeenAt = now;
            incoming.inScope = true;
            incoming.baseline = baseline;
            incoming.analysis = baseline;
            existingById.set(incoming.id, incoming);
            added += 1;
          } else {
            const contentChanged = existing.contentHash !== incoming.contentHash;
            const merged = {
              ...existing,
              ...incoming,
              firstSeenAt: existing.firstSeenAt || now,
              inScope: true,
              baseline,
              analysis: contentChanged ? baseline : existing.analysis || baseline,
            };
            existingById.set(incoming.id, merged);
            if (contentChanged) changed += 1;
          }
        }
      } catch (error) {
        errors.push(`${feed.name}: ${error.message}`);
      }
    }

    for (const [id, job] of existingById) {
      if (!completedSources.has(job.sourceKey) || seenIds.has(id)) continue;
      if (observedIds.has(id)) {
        existingById.set(id, {
          ...job,
          inScope: false,
          lastSeenAt: now,
          lastCheckedAt: now,
          consecutiveMissingCount: 0,
          active: true,
        });
        continue;
      }
      const consecutiveMissingCount = (job.consecutiveMissingCount || 0) + 1;
      existingById.set(id, {
        ...job,
        inScope: job.inScope !== false,
        lastCheckedAt: now,
        consecutiveMissingCount,
        active: consecutiveMissingCount < 3,
      });
    }

    state.jobs = [...existingById.values()].sort((a, b) => {
      if (a.active !== b.active) return a.active ? -1 : 1;
      return `${a.location}${a.title}`.localeCompare(`${b.location}${b.title}`, "en");
    });
    state.lastSyncAt = now;
    state.lastSyncSummary = {
      checkedAt: now,
      activeJobs: state.jobs.filter((job) => job.active && job.inScope !== false).length,
      totalJobs: state.jobs.length,
      added,
      changed,
      sourcesChecked: completedSources.size,
      errors,
    };
    state.syncError = errors.length ? errors.join("; ") : null;
    await saveState();
    return state.lastSyncSummary;
  } finally {
    state.syncing = false;
  }
}

async function findOllamaModel() {
  const response = await fetch("http://127.0.0.1:11434/api/tags", { signal: AbortSignal.timeout(2500) });
  if (!response.ok) throw new Error(`Ollama returned HTTP ${response.status}`);
  const payload = await response.json();
  const names = (payload.models || []).map((model) => model.name);
  const preferred = [process.env.OLLAMA_MODEL, "qwen3:4b-instruct", "qwen3:4b", "gemma3:4b", "mistral:7b"].filter(Boolean);
  return preferred.find((candidate) => names.some((name) => name === candidate || name.startsWith(`${candidate}:`))) || names[0] || null;
}

const AI_SCHEMA = {
  type: "object",
  properties: {
    summary: { type: "string" },
    seniority: { type: "string" },
    workplaceModel: { type: "string" },
    workplaceEvidence: { type: "string" },
    requiredSkills: {
      type: "array",
      items: {
        type: "object",
        properties: { name: { type: "string" }, evidence: { type: "string" } },
        required: ["name", "evidence"],
      },
    },
    optionalSkills: {
      type: "array",
      items: {
        type: "object",
        properties: { name: { type: "string" }, evidence: { type: "string" } },
        required: ["name", "evidence"],
      },
    },
    languageRequirements: {
      type: "array",
      items: {
        type: "object",
        properties: {
          language: { type: "string" },
          level: { type: "string" },
          evidence: { type: "string" },
        },
        required: ["language", "level", "evidence"],
      },
    },
    experienceRequirements: {
      type: "array",
      items: {
        type: "object",
        properties: { text: { type: "string" }, evidence: { type: "string" } },
        required: ["text", "evidence"],
      },
    },
    contradictions: {
      type: "array",
      items: {
        type: "object",
        properties: { finding: { type: "string" }, evidence: { type: "string" } },
        required: ["finding", "evidence"],
      },
    },
    unclearInformation: { type: "array", items: { type: "string" } },
    confidence: { type: "string", enum: ["low", "medium", "high"] },
  },
  required: [
    "summary",
    "seniority",
    "workplaceModel",
    "workplaceEvidence",
    "requiredSkills",
    "optionalSkills",
    "languageRequirements",
    "experienceRequirements",
    "contradictions",
    "unclearInformation",
    "confidence",
  ],
};

function normalizedEvidence(value) {
  return String(value || "")
    .toLocaleLowerCase("de-DE")
    .replace(/[“”„\"']/g, "")
    .replace(/\s+/g, " ")
    .trim();
}

function verifyEvidence(job, analysis) {
  const haystack = normalizedEvidence(job.rawDescription);
  const verify = (item) => {
    const needle = normalizedEvidence(item.evidence);
    return { ...item, evidenceVerified: needle.length >= 6 && haystack.includes(needle) };
  };
  const baseline = baselineAnalyze(job);
  const requiredSkills = (analysis.requiredSkills || []).map(verify);
  const optionalSkills = (analysis.optionalSkills || []).map(verify);
  const languageRequirements = (analysis.languageRequirements || []).map((item) => {
    const verified = verify(item);
    const explicitLevel = String(item.evidence || "").match(/\b(?:A1|A2|B1|B2|C1|C2)\b/i)?.[0]?.toUpperCase();
    return { ...verified, level: explicitLevel || "Not specified" };
  });
  const experienceRequirements = (analysis.experienceRequirements || []).map(verify);
  const contradictions = (analysis.contradictions || []).map(verify);
  const workplacePattern = /remote|homeoffice|home office|hybrid|vor ort|on[- ]site/i;
  const suppliedWorkplaceEvidence = normalizedEvidence(analysis.workplaceEvidence);
  const workplaceEvidenceIsRelevant =
    suppliedWorkplaceEvidence.length >= 6 &&
    haystack.includes(suppliedWorkplaceEvidence) &&
    workplacePattern.test(analysis.workplaceEvidence || "");
  const workplaceEvidence = workplaceEvidenceIsRelevant
    ? analysis.workplaceEvidence
    : baseline.workplaceEvidence || "";
  let workplaceModel = "Unclear";
  if (/hybrid/i.test(workplaceEvidence)) workplaceModel = "Hybrid";
  else if (/remote|homeoffice|home office/i.test(workplaceEvidence)) workplaceModel = "Remote or hybrid";
  else if (/vor ort|on[- ]site/i.test(workplaceEvidence)) workplaceModel = "On-site";
  const unclearKey = (item) => {
    const normalized = item.toLowerCase();
    if (/years?|jahre?/.test(normalized)) return "experience-years";
    if (/salary|gehalt|vergütung/.test(normalized)) return "salary";
    if (/work(?:place)? model|arbeitsmodell/.test(normalized)) return "workplace";
    if (/language|sprache/.test(normalized)) return "language";
    return normalized;
  };
  const unclearInformation = [
    ...(analysis.unclearInformation || []),
    ...baseline.unclearInformation,
  ]
    .filter((item) => !(workplaceEvidence && unclearKey(item) === "workplace"))
    .filter((item, index, array) => array.findIndex((candidate) => unclearKey(candidate) === unclearKey(item)) === index);
  const evidenceItems = [requiredSkills, optionalSkills, languageRequirements, experienceRequirements, contradictions].flat();
  const verifiedShare = evidenceItems.length
    ? evidenceItems.filter((item) => item.evidenceVerified).length / evidenceItems.length
    : 0;
  const confidence = analysis.confidence === "high" && verifiedShare < 1 ? "medium" : analysis.confidence;

  return {
    ...analysis,
    requiredSkills,
    optionalSkills,
    languageRequirements,
    experienceRequirements,
    contradictions,
    workplaceModel,
    workplaceEvidence,
    workplaceEvidenceVerified: Boolean(workplaceEvidence),
    unclearInformation,
    confidence,
  };
}

async function analyzeWithOllama(job) {
  const model = await findOllamaModel();
  if (!model) throw new Error("No local Ollama model is installed.");
  const prompt = `You analyse a job advertisement for an evidence-grounded student prototype.\n\nRules:\n- Return English labels and summaries.\n- Evidence must be copied exactly from the original German or English advertisement.\n- Never infer a language level, number of years, skill or work model that is not explicit.\n- Separate mandatory skills from optional or desirable skills.\n- Put missing or ambiguous information in unclearInformation.\n- A contradiction is a genuine tension, not merely missing information.\n\nJob title: ${job.title}\nCompany: ${job.company}\nLocation: ${job.location}\nSource seniority: ${job.seniority || "not supplied"}\n\nADVERTISEMENT:\n${job.rawDescription.slice(0, 12_000)}`;
  const response = await fetch("http://127.0.0.1:11434/api/generate", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      model,
      prompt,
      stream: false,
      format: AI_SCHEMA,
      options: { temperature: 0, num_ctx: 4096 },
    }),
    signal: AbortSignal.timeout(120_000),
  });
  if (!response.ok) throw new Error(`Ollama returned HTTP ${response.status}`);
  const payload = await response.json();
  let parsed;
  try {
    parsed = JSON.parse(payload.response);
  } catch {
    throw new Error("The local model did not return valid structured JSON.");
  }
  return verifyEvidence(job, {
    ...parsed,
    engine: "local-ai",
    model,
    generatedAt: new Date().toISOString(),
  });
}

function publicJob(job) {
  return {
    ...job,
    descriptionSections: undefined,
  };
}

function sendJson(response, status, payload) {
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
  });
  response.end(JSON.stringify(payload));
}

async function readBody(request) {
  const chunks = [];
  for await (const chunk of request) chunks.push(chunk);
  return Buffer.concat(chunks).toString("utf8");
}

async function serveStatic(response, pathname) {
  const vendorName = pathname.startsWith("/vendor/") ? pathname.slice("/vendor/".length) : null;
  const allowedVendor = vendorName && /^maplibre-gl(?:-shared|-worker)?(?:-dev)?\.(?:mjs|css)(?:\.map)?$/.test(vendorName);
  const relativePath = pathname === "/" ? "index.html" : pathname.replace(/^\/+/, "");
  const filePath = allowedVendor
    ? path.join(ROOT, "node_modules", "maplibre-gl", "dist", vendorName)
    : path.resolve(PUBLIC_DIR, relativePath);
  if (!allowedVendor && !filePath.startsWith(path.resolve(PUBLIC_DIR))) {
    response.writeHead(403);
    response.end("Forbidden");
    return;
  }
  try {
    const content = await readFile(filePath);
    const extension = path.extname(filePath).toLowerCase();
    const types = {
      ".html": "text/html; charset=utf-8",
      ".css": "text/css; charset=utf-8",
      ".js": "text/javascript; charset=utf-8",
      ".mjs": "text/javascript; charset=utf-8",
      ".map": "application/json; charset=utf-8",
      ".svg": "image/svg+xml",
      ".png": "image/png",
    };
    response.writeHead(200, {
      "Content-Type": types[extension] || "application/octet-stream",
      "Cache-Control": "no-store, max-age=0",
    });
    response.end(content);
  } catch (error) {
    response.writeHead(error.code === "ENOENT" ? 404 : 500);
    response.end(error.code === "ENOENT" ? "Not found" : "Server error");
  }
}

const server = http.createServer(async (request, response) => {
  const requestUrl = new URL(request.url, `http://${request.headers.host || `localhost:${PORT}`}`);
  const { pathname } = requestUrl;

  try {
    if (request.method === "GET" && pathname === "/api/jobs") {
      sendJson(response, 200, {
        jobs: state.jobs.map(publicJob),
        lastSyncAt: state.lastSyncAt,
        lastSyncSummary: state.lastSyncSummary,
        syncing: state.syncing,
        syncError: state.syncError,
      });
      return;
    }

    if (request.method === "GET" && pathname === "/api/status") {
      let ollama = { available: false, model: null };
      try {
        ollama = { available: true, model: await findOllamaModel() };
      } catch (error) {
        ollama.error = error.message;
      }
      sendJson(response, 200, {
        ollama,
        syncIntervalMinutes: SYNC_INTERVAL_MINUTES,
        feeds: FEEDS.map(({ key, name, url }) => ({ key, name, url })),
      });
      return;
    }

    if (request.method === "POST" && pathname === "/api/sync") {
      await readBody(request);
      const summary = await syncFeeds();
      sendJson(response, 200, { summary, jobs: state.jobs.map(publicJob) });
      return;
    }

    const analysisMatch = pathname.match(/^\/api\/jobs\/([^/]+)\/analyze$/);
    if (request.method === "POST" && analysisMatch) {
      await readBody(request);
      const id = decodeURIComponent(analysisMatch[1]);
      const job = state.jobs.find((candidate) => candidate.id === id);
      if (!job) {
        sendJson(response, 404, { error: "Job not found." });
        return;
      }
      try {
        job.analysis = await analyzeWithOllama(job);
        await saveState();
        sendJson(response, 200, { job: publicJob(job) });
      } catch (error) {
        sendJson(response, 502, { error: error.message, fallback: job.analysis });
      }
      return;
    }

    await serveStatic(response, pathname);
  } catch (error) {
    console.error(error);
    sendJson(response, 500, { error: "Unexpected server error." });
  }
});

await loadState();
server.listen(PORT, "127.0.0.1", () => {
  console.log(`Mapploy prototype running at http://127.0.0.1:${PORT}`);
});

syncFeeds().then((summary) => {
  console.log(`Feed sync complete: ${summary.activeJobs} active jobs from ${summary.sourcesChecked} sources.`);
}).catch((error) => {
  state.syncError = error.message;
  console.error("Initial feed sync failed:", error.message);
});

setInterval(() => {
  syncFeeds()
    .then((summary) => console.log(`Scheduled feed sync: ${summary.activeJobs} active jobs.`))
    .catch((error) => console.error("Scheduled feed sync failed:", error.message));
}, SYNC_INTERVAL_MINUTES * 60_000).unref();
