import * as maplibregl from "/vendor/maplibre-gl.mjs";

window.maplibregl = maplibregl;

const API_BASE = `${window.location.protocol}//${window.location.hostname}:8081`;
const VECTOR_MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty";
const INTEREST_STORAGE_KEY = "mapploy-job-interests";
const DECISION_STORAGE_KEY = "mapploy-job-decisions";
const LEGACY_DECISION_STORAGE_KEY = "joblens-decisions";
const DECISION_META = {
  apply: { label: "Applied", markerClass: "decision-apply" },
  clarify: { label: "To clarify", markerClass: "decision-clarify" },
  skip: { label: "Skipped", markerClass: "decision-skip" },
};
const JOB_INTERESTS = [
  { id: "IT & Software", short: "IT", description: "Software, data, cloud, security and systems" },
  { id: "Engineering & Technical", short: "ENG", description: "Engineering, construction, automation and service" },
  { id: "Project & Product", short: "PM", description: "Project, product and agile coordination" },
  { id: "Sales & Customer", short: "CX", description: "Sales, consulting and customer success" },
  { id: "Finance & Administration", short: "FIN", description: "Finance, accounting and administration" },
  { id: "People & HR", short: "HR", description: "People operations and recruiting" },
  { id: "Operations & Logistics", short: "OPS", description: "Operations, warehousing and logistics" },
  { id: "Other roles", short: "+", description: "General and interdisciplinary opportunities" },
];

function loadSavedInterests() {
  try {
    const saved = localStorage.getItem(INTEREST_STORAGE_KEY);
    if (saved === null) return null;
    const values = JSON.parse(saved);
    return Array.isArray(values) ? values.filter((value) => JOB_INTERESTS.some((interest) => interest.id === value)) : null;
  } catch {
    return null;
  }
}

const savedInterests = loadSavedInterests();

function createClassicMapStyle() {
  return {
    version: 8,
    sources: {
      osm: {
        type: "raster",
        tiles: ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
        tileSize: 256,
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap contributors</a>',
      },
    },
    layers: [{ id: "osm", type: "raster", source: "osm", minzoom: 0, maxzoom: 19 }],
  };
}

const appState = {
  jobs: [],
  filteredJobs: [],
  selectedId: null,
  sync: null,
  syncIntervalMinutes: 1440,
  sourceCount: 7,
  interests: savedInterests || [],
  draftInterests: new Set(savedInterests || []),
  ollama: null,
  map: null,
  mapReady: false,
  map3d: false,
  map2dCamera: null,
  recentDecisionId: null,
  cvFile: null,
  cvReport: null,
  markers: new Map(),
};

const elements = {
  resultList: document.querySelector("#resultList"),
  resultCount: document.querySelector("#resultCount"),
  searchInput: document.querySelector("#searchInput"),
  locationFilter: document.querySelector("#locationFilter"),
  analysisFilter: document.querySelector("#analysisFilter"),
  interestButton: document.querySelector("#interestButton"),
  interestSummary: document.querySelector("#interestSummary"),
  interestOverlay: document.querySelector("#interestOverlay"),
  interestOptions: document.querySelector("#interestOptions"),
  saveInterestsButton: document.querySelector("#saveInterestsButton"),
  allRolesButton: document.querySelector("#allRolesButton"),
  cvButton: document.querySelector("#cvButton"),
  cvOverlay: document.querySelector("#cvOverlay"),
  cvCloseButton: document.querySelector("#cvCloseButton"),
  cvSetup: document.querySelector("#cvSetup"),
  cvContextText: document.querySelector("#cvContextText"),
  cvDropzone: document.querySelector("#cvDropzone"),
  cvFileInput: document.querySelector("#cvFileInput"),
  cvFileName: document.querySelector("#cvFileName"),
  cvAnalyzeButton: document.querySelector("#cvAnalyzeButton"),
  cvResult: document.querySelector("#cvResult"),
  syncButton: document.querySelector("#syncButton"),
  freshnessLabel: document.querySelector("#freshnessLabel"),
  sourceHealthText: document.querySelector("#sourceHealthText"),
  sourceHealth: document.querySelector(".source-health"),
  modelStatus: document.querySelector("#modelStatus"),
  mapSummary: document.querySelector("#mapSummary"),
  map3dToggle: document.querySelector("#map3dToggle"),
  mapViewLabel: document.querySelector("#mapViewLabel"),
  detailPanel: document.querySelector("#detailPanel"),
  detailContent: document.querySelector("#detailContent"),
  emptyDetail: document.querySelector("#emptyDetail"),
  toastRegion: document.querySelector("#toastRegion"),
};

function escapeHtml(value = "") {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function relativeTime(value) {
  if (!value) return "Not checked yet";
  const deltaSeconds = Math.round((new Date(value).getTime() - Date.now()) / 1000);
  const formatter = new Intl.RelativeTimeFormat("en", { numeric: "auto" });
  if (Math.abs(deltaSeconds) < 60) return formatter.format(deltaSeconds, "second");
  const minutes = Math.round(deltaSeconds / 60);
  if (Math.abs(minutes) < 60) return formatter.format(minutes, "minute");
  const hours = Math.round(minutes / 60);
  if (Math.abs(hours) < 24) return formatter.format(hours, "hour");
  return formatter.format(Math.round(hours / 24), "day");
}

function sourceInitials(company) {
  return company
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0])
    .join("")
    .toUpperCase();
}

function decisionFor(id) {
  try {
    const current = JSON.parse(localStorage.getItem(DECISION_STORAGE_KEY) || "{}");
    if (current[id]) return current[id];
    return JSON.parse(localStorage.getItem(LEGACY_DECISION_STORAGE_KEY) || "{}")[id] || null;
  } catch {
    return null;
  }
}

function saveDecision(id, decision) {
  let saved = {};
  try {
    saved = JSON.parse(localStorage.getItem(DECISION_STORAGE_KEY) || "{}");
  } catch {
    saved = {};
  }
  saved[id] = decision;
  localStorage.setItem(DECISION_STORAGE_KEY, JSON.stringify(saved));
}

function clarityScore(job) {
  const analysis = job.analysis || {};
  const unclearPenalty = (analysis.unclearInformation || []).length * 14;
  const evidenceItems = [
    ...(analysis.requiredSkills || []),
    ...(analysis.optionalSkills || []),
    ...(analysis.languageRequirements || []),
    ...(analysis.experienceRequirements || []),
    ...(analysis.contradictions || []),
  ];
  const unverifiedPenalty = evidenceItems.filter((item) => item.evidenceVerified === false).length * 8;
  return Math.max(18, Math.min(100, 100 - unclearPenalty - unverifiedPenalty));
}

function isNewJob(job) {
  if (!job.firstSeenAt) return false;
  const age = Date.now() - new Date(job.firstSeenAt).getTime();
  return age >= 0 && age < 48 * 60 * 60 * 1000;
}

function showToast(message, type = "info") {
  const toast = document.createElement("div");
  toast.className = `toast ${type === "error" ? "error" : ""}`;
  toast.textContent = message;
  elements.toastRegion.append(toast);
  setTimeout(() => toast.remove(), 4200);
}

async function fetchJson(url, options) {
  const requestUrl = url.startsWith("/api/") ? `${API_BASE}${url}` : url;
  const response = await fetch(requestUrl, options);
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || `Request failed with HTTP ${response.status}`);
  return payload;
}

function renderInterestSummary() {
  elements.interestSummary.textContent = appState.interests.length ? appState.interests.join(" · ") : "All job interests";
}

function renderInterestOptions() {
  elements.interestOptions.innerHTML = JOB_INTERESTS.map((interest) => {
    const selected = appState.draftInterests.has(interest.id);
    return `
      <label class="interest-option ${selected ? "selected" : ""}">
        <input type="checkbox" value="${escapeHtml(interest.id)}" ${selected ? "checked" : ""} />
        <span class="interest-option-icon" aria-hidden="true">${escapeHtml(interest.short)}</span>
        <span class="interest-option-copy">
          <strong>${escapeHtml(interest.id)}</strong>
          <small>${escapeHtml(interest.description)}</small>
        </span>
        <span class="interest-check" aria-hidden="true">✓</span>
      </label>`;
  }).join("");
  elements.interestOptions.querySelectorAll("input").forEach((input) => {
    input.addEventListener("change", () => {
      if (input.checked) appState.draftInterests.add(input.value);
      else appState.draftInterests.delete(input.value);
      input.closest(".interest-option")?.classList.toggle("selected", input.checked);
    });
  });
}

function openInterestDialog() {
  appState.draftInterests = new Set(appState.interests);
  renderInterestOptions();
  elements.interestOverlay.hidden = false;
  setTimeout(() => elements.interestOptions.querySelector("input")?.focus(), 0);
}

function closeInterestDialog() {
  elements.interestOverlay.hidden = true;
  elements.interestButton.focus();
}

function storeInterests(interests) {
  appState.interests = interests;
  localStorage.setItem(INTEREST_STORAGE_KEY, JSON.stringify(interests));
  renderInterestSummary();
  applyFilters();
  closeInterestDialog();
}

function selectedJob() {
  return appState.jobs.find((job) => job.id === appState.selectedId) || null;
}

function updateCvContext() {
  const job = selectedJob();
  elements.cvContextText.textContent = job
    ? `Job comparison: ${job.title} · ${job.company}`
    : "General CV review · select a vacancy first to add a job comparison";
}

function resetCvDialog() {
  appState.cvFile = null;
  appState.cvReport = null;
  elements.cvFileInput.value = "";
  elements.cvFileName.textContent = "No file selected";
  elements.cvDropzone.classList.remove("has-file", "dragging");
  elements.cvAnalyzeButton.disabled = true;
  elements.cvAnalyzeButton.innerHTML = '<span class="model-icon detail-ai-icon" aria-hidden="true">AI</span>Analyse CV locally';
  elements.cvSetup.hidden = false;
  elements.cvResult.hidden = true;
  elements.cvResult.innerHTML = "";
  updateCvContext();
}

function openCvDialog() {
  if (!elements.interestOverlay.hidden) elements.interestOverlay.hidden = true;
  resetCvDialog();
  elements.cvOverlay.hidden = false;
  setTimeout(() => elements.cvDropzone.focus(), 0);
}

function closeCvDialog() {
  elements.cvOverlay.hidden = true;
  resetCvDialog();
  elements.cvButton.focus();
}

function selectCvFile(file) {
  if (!file) return;
  const extensionAllowed = /\.(pdf|doc|docx|txt)$/i.test(file.name);
  if (!extensionAllowed) {
    showToast("Choose a PDF, DOC, DOCX or TXT CV.", "error");
    return;
  }
  if (file.size > 5 * 1024 * 1024) {
    showToast("The CV must not exceed 5 MB.", "error");
    return;
  }
  appState.cvFile = file;
  appState.cvReport = null;
  elements.cvFileName.textContent = `${file.name} · ${Math.max(1, Math.round(file.size / 1024))} KB`;
  elements.cvDropzone.classList.add("has-file");
  elements.cvAnalyzeButton.disabled = false;
}

function cvList(items, emptyText) {
  if (!items?.length) return `<li class="cv-empty-item">${escapeHtml(emptyText)}</li>`;
  return items.map((item) => `<li>${escapeHtml(item)}</li>`).join("");
}

function skillEvidenceList(items) {
  if (!items?.length) return '<p class="cv-muted">No explicit requirement matches were found.</p>';
  return `<ul class="cv-evidence-list">${items.map((item) => `
    <li>
      <strong>${escapeHtml(item.name)}</strong>
      <blockquote>“${escapeHtml(item.evidence)}”</blockquote>
    </li>`).join("")}</ul>`;
}

function renderCvReport(report) {
  const aiLabel = report.engine === "local-ai" ? "Local AI review" : "Rule-based fallback";
  const checklistIcons = { pass: "✓", review: "!", missing: "+" };
  const jobFit = report.jobFit
    ? `<section class="cv-report-section cv-job-fit">
        <div class="cv-section-heading">
          <div>
            <p class="eyebrow">SELECTED VACANCY</p>
            <h3>${escapeHtml(report.jobFit.title)}</h3>
            <span>${escapeHtml(report.jobFit.company)}</span>
          </div>
          <div class="cv-match-score" aria-label="${report.jobFit.matchScore} percent explicit requirement match">
            <strong>${report.jobFit.matchScore}%</strong><small>evidence match</small>
          </div>
        </div>
        <p class="cv-muted">${escapeHtml(report.jobFit.explanation)}</p>
        <div class="cv-fit-grid">
          <div>
            <h4>Matched requirements</h4>
            ${skillEvidenceList(report.jobFit.matchedRequirements)}
          </div>
          <div>
            <h4>Not found in CV text</h4>
            <div class="cv-missing-skills">
              ${report.jobFit.missingRequirements?.length
                ? report.jobFit.missingRequirements.map((item) => `<span>${escapeHtml(item)}</span>`).join("")
                : '<span class="complete">All explicit requirements were found</span>'}
            </div>
            ${report.jobFit.matchedOptionalSkills?.length ? `<h4 class="cv-optional-title">Matched optional skills</h4>${skillEvidenceList(report.jobFit.matchedOptionalSkills)}` : ""}
          </div>
        </div>
      </section>`
    : `<section class="cv-report-section cv-no-job">
        <strong>General CV review</strong>
        <span>Select a vacancy before opening CV Check to receive an additional job-fit comparison.</span>
      </section>`;

  elements.cvResult.innerHTML = `
    <div class="cv-result-top">
      <div class="cv-score" style="--cv-score:${Math.max(0, Math.min(100, report.completenessScore)) * 3.6}deg" aria-label="CV completeness score ${report.completenessScore} percent">
        <div><strong>${report.completenessScore}</strong><small>/ 100</small></div>
      </div>
      <div class="cv-result-summary">
        <span class="analysis-chip ${report.engine === "local-ai" ? "ai" : ""}">${escapeHtml(aiLabel)} · ${escapeHtml(report.model)}</span>
        <h3>CV completeness</h3>
        <p>${escapeHtml(report.summary)}</p>
        <small>${escapeHtml(report.fileName)} · ${report.wordCount} extracted words</small>
      </div>
      <button class="cv-again-button" id="cvAgainButton" type="button">Check another CV</button>
    </div>

    <div class="cv-local-banner"><span>✓</span><p><strong>Private processing confirmed</strong>${escapeHtml(report.privacy.statement)}</p></div>

    <section class="cv-report-section">
      <div class="cv-section-heading"><div><p class="eyebrow">TRANSPARENT CHECKS</p><h3>Your CV checklist</h3></div></div>
      <div class="cv-checklist">
        ${report.checklist.map((item) => `
          <article class="cv-check-item ${escapeHtml(item.status)}">
            <span class="cv-check-icon" aria-hidden="true">${checklistIcons[item.status] || "?"}</span>
            <div>
              <div class="cv-check-heading"><strong>${escapeHtml(item.label)}</strong><span>${item.points ?? 0}/${item.maxPoints ?? 0}</span></div>
              <p>${escapeHtml(item.detail)}</p>
            </div>
          </article>`).join("")}
      </div>
    </section>

    ${jobFit}

    <section class="cv-report-section cv-advice-grid">
      <div><h3>Strengths in the document</h3><ul class="cv-advice-list strengths">${cvList(report.strengths, "No strong structural signals detected yet.")}</ul></div>
      <div><h3>Recommended improvements</h3><ul class="cv-advice-list improvements">${cvList(report.improvements, "No major structural improvements detected.")}</ul></div>
    </section>`;
  elements.cvSetup.hidden = true;
  elements.cvResult.hidden = false;
  elements.cvResult.querySelector("#cvAgainButton").addEventListener("click", resetCvDialog);
}

async function analyzeCv() {
  if (!appState.cvFile) return;
  elements.cvAnalyzeButton.disabled = true;
  elements.cvAnalyzeButton.textContent = "Reading and reviewing locally…";
  const body = new FormData();
  body.append("file", appState.cvFile);
  const job = selectedJob();
  const query = job ? `?jobId=${encodeURIComponent(job.id)}` : "";
  try {
    const report = await fetchJson(`/api/cv/analyze${query}`, { method: "POST", body, cache: "no-store" });
    appState.cvReport = report;
    renderCvReport(report);
    showToast(`CV review complete · ${report.completenessScore}/100.`);
  } catch (error) {
    showToast(`CV review failed: ${error.message}`, "error");
    elements.cvAnalyzeButton.disabled = false;
    elements.cvAnalyzeButton.innerHTML = '<span class="model-icon detail-ai-icon" aria-hidden="true">AI</span>Analyse CV locally';
  }
}

function initMap() {
  if (!window.maplibregl) {
    document.querySelector("#map").innerHTML =
      '<div class="map-error"><strong>Map unavailable</strong><span>The vacancy list and evidence report still work.</span></div>';
    return;
  }

  appState.map = new window.maplibregl.Map({
    container: "map",
    center: [9.41, 53.3],
    zoom: 7.35,
    minZoom: 5,
    maxZoom: 18,
    attributionControl: true,
    style: createClassicMapStyle(),
  });
  appState.map.dragRotate.disable();
  appState.map.touchZoomRotate.disableRotation();
  appState.map.addControl(new window.maplibregl.NavigationControl({ showCompass: true }), "bottom-right");
  appState.map.on("load", () => {
    appState.mapReady = true;
    elements.map3dToggle.disabled = false;
    updateMapMarkers();
  });
}

function syncMapViewButton() {
  elements.map3dToggle.classList.toggle("active", appState.map3d);
  elements.map3dToggle.setAttribute("aria-pressed", String(appState.map3d));
  elements.map3dToggle.setAttribute(
    "aria-label",
    appState.map3d ? "Switch to flat map view" : "Switch to 3D building view",
  );
  elements.map3dToggle.title = appState.map3d
    ? "Return to the detailed classic OpenStreetMap view"
    : "Show OpenStreetMap buildings in 3D near the selected job";
  elements.mapViewLabel.textContent = appState.map3d ? "2D" : "3D";
}

function toggleMap3D() {
  if (!appState.map || !appState.mapReady) return;
  appState.map3d = !appState.map3d;
  const selectedJob = appState.jobs.find((job) => job.id === appState.selectedId);
  const center = selectedJob?.coordinates || appState.map.getCenter();
  elements.map3dToggle.disabled = true;

  if (appState.map3d) {
    const currentCenter = appState.map.getCenter();
    appState.map2dCamera = {
      center: [currentCenter.lng, currentCenter.lat],
      zoom: appState.map.getZoom(),
    };
    appState.map.dragRotate.enable();
    appState.map.touchZoomRotate.enableRotation();
    appState.map.once("style.load", () => {
      elements.map3dToggle.disabled = false;
      appState.map.flyTo({
        center,
        zoom: Math.max(appState.map.getZoom(), 15),
        pitch: 58,
        bearing: -18,
        duration: 1250,
        curve: 1.35,
        essential: false,
      });
      showToast("3D buildings enabled. Drag while holding right-click to rotate.");
    });
    appState.map.setStyle(VECTOR_MAP_STYLE_URL);
  } else {
    const flatCamera = appState.map2dCamera || { center, zoom: 7.35 };
    appState.map.dragRotate.disable();
    appState.map.touchZoomRotate.disableRotation();
    appState.map.once("style.load", () => {
      elements.map3dToggle.disabled = false;
      appState.map.easeTo({ ...flatCamera, pitch: 0, bearing: 0, duration: 900, essential: false });
      appState.map2dCamera = null;
      showToast("Detailed classic map restored.");
    });
    appState.map.setStyle(createClassicMapStyle());
  }
  syncMapViewButton();
}

function updateMapMarkers() {
  if (!appState.map || !appState.mapReady) return;
  for (const marker of appState.markers.values()) marker.remove();
  appState.markers.clear();

  const mappedJobs = appState.filteredJobs
    .filter((candidate) => candidate.active && candidate.coordinates)
    .sort((left, right) => Number(left.id === appState.selectedId) - Number(right.id === appState.selectedId));

  for (const job of mappedJobs) {
    const decision = decisionFor(job.id);
    const decisionMeta = DECISION_META[decision];
    const markerElement = document.createElement("button");
    markerElement.type = "button";
    markerElement.className = `map-marker ${decisionMeta?.markerClass || ""} ${job.id === appState.selectedId ? "selected" : ""} ${job.id === appState.recentDecisionId ? "status-updated" : ""}`;
    markerElement.setAttribute(
      "aria-label",
      `Open ${job.title} at ${job.company}${decisionMeta ? `, status ${decisionMeta.label}` : ""}`,
    );
    markerElement.innerHTML = `<span>${escapeHtml(sourceInitials(job.company))}</span>`;
    markerElement.addEventListener("click", () => selectJob(job.id, true));

    const popup = new window.maplibregl.Popup({ offset: 22, closeButton: false }).setHTML(
      `<div class="map-popup"><strong>${escapeHtml(job.title)}</strong><span>${escapeHtml(job.company)} · ${escapeHtml(job.location)}</span>${decisionMeta ? `<span class="map-popup-decision ${escapeHtml(decision)}">${escapeHtml(decisionMeta.label)}</span>` : ""}</div>`,
    );
    const marker = new window.maplibregl.Marker({ element: markerElement, anchor: "bottom" })
      .setLngLat(job.coordinates)
      .setPopup(popup)
      .addTo(appState.map);
    appState.markers.set(job.id, marker);
    if (job.id === appState.recentDecisionId) {
      setTimeout(() => {
        markerElement.classList.remove("status-updated");
        if (appState.recentDecisionId === job.id) appState.recentDecisionId = null;
      }, 700);
    }
  }
}

function decisionChip(decision) {
  const decisionMeta = DECISION_META[decision];
  return decisionMeta ? `<span class="decision-chip ${decision}">${escapeHtml(decisionMeta.label)}</span>` : "";
}

function renderJobCard(job) {
  const analysis = job.analysis || {};
  const score = clarityScore(job);
  const decision = decisionFor(job.id);
  const engineLabel = analysis.engine === "local-ai" ? "Local AI" : "Rule baseline";
  return `
    <button class="job-card ${job.id === appState.selectedId ? "selected" : ""}" type="button" role="listitem" data-job-id="${escapeHtml(job.id)}">
      <div class="job-card-top">
        <h2>${escapeHtml(job.title)}</h2>
        <span class="job-card-badges">
          ${isNewJob(job) ? '<span class="new-job-chip">New</span>' : ""}
          <span class="analysis-chip ${analysis.engine === "local-ai" ? "ai" : ""}">${engineLabel}</span>
        </span>
      </div>
      <p class="company-name">${escapeHtml(job.company)}</p>
      <div class="job-meta">
        <span>
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 10c0 5-8 11-8 11S4 15 4 10a8 8 0 1 1 16 0Z"/><circle cx="12" cy="10" r="2.5"/></svg>
          ${escapeHtml(job.location)}
        </span>
        ${job.category ? `<span>· ${escapeHtml(job.category)}</span>` : ""}
        ${job.schedule ? `<span>· ${escapeHtml(job.schedule)}</span>` : ""}
        ${job.seniority ? `<span>· ${escapeHtml(job.seniority)}</span>` : ""}
      </div>
      <div class="card-footer">
        <div>
          <div class="clarity-bar" title="Requirement clarity ${score}%"><span style="width:${score}%"></span></div>
          <small>${score}% requirement clarity</small>
        </div>
        ${decisionChip(decision)}
      </div>
    </button>`;
}

function renderList() {
  elements.resultCount.textContent = String(appState.filteredJobs.length);
  elements.mapSummary.textContent = `${appState.filteredJobs.filter((job) => job.coordinates).length} mapped vacancies`;
  if (!appState.filteredJobs.length) {
    elements.resultList.innerHTML = `
      <div class="empty-results">
        <strong>No matching vacancy</strong>
        Try a broader role, skill or location.
      </div>`;
  } else {
    elements.resultList.innerHTML = appState.filteredJobs.map(renderJobCard).join("");
    elements.resultList.querySelectorAll("[data-job-id]").forEach((card) => {
      card.addEventListener("click", () => selectJob(card.dataset.jobId, true));
    });
  }
  updateMapMarkers();
}

function matchesFilter(job) {
  const query = elements.searchInput.value.trim().toLocaleLowerCase("en");
  const location = elements.locationFilter.value;
  const engine = elements.analysisFilter.value;
  const interestMatches = !appState.interests.length || appState.interests.includes(job.category || "Other roles");
  const searchable = [
    job.title,
    job.company,
    job.location,
    job.department,
    job.rawDescription,
    ...(job.analysis?.requiredSkills || []).map((skill) => skill.name),
    ...(job.analysis?.optionalSkills || []).map((skill) => skill.name),
  ]
    .filter(Boolean)
    .join(" ")
    .toLocaleLowerCase("en");
  return (
    job.active &&
    job.inScope !== false &&
    interestMatches &&
    (!query || searchable.includes(query)) &&
    (location === "all" || job.location.toLocaleLowerCase("en").includes(location.toLocaleLowerCase("en"))) &&
    (engine === "all" || job.analysis?.engine === engine)
  );
}

function applyFilters() {
  appState.filteredJobs = appState.jobs.filter(matchesFilter);
  if (appState.selectedId && !appState.filteredJobs.some((job) => job.id === appState.selectedId)) {
    appState.selectedId = appState.filteredJobs[0]?.id || null;
  }
  renderList();
  renderDetail();
}

function chipList(items, className = "") {
  if (!items?.length) return '<span class="muted-inline">None stated</span>';
  return items.map((item) => `<span class="skill-chip ${className}">${escapeHtml(item.name)}</span>`).join("");
}

function evidenceItems(analysis) {
  const items = [];
  for (const item of analysis.requiredSkills || []) items.push({ label: `Required · ${item.name}`, ...item });
  for (const item of analysis.optionalSkills || []) items.push({ label: `Optional · ${item.name}`, ...item });
  for (const item of analysis.languageRequirements || []) {
    items.push({ label: `Language · ${item.language} ${item.level === "Not specified" ? "" : item.level}`, ...item });
  }
  for (const item of analysis.experienceRequirements || []) items.push({ label: "Experience", ...item });
  for (const item of analysis.contradictions || []) items.push({ label: `Possible conflict · ${item.finding}`, ...item });
  if (analysis.workplaceEvidence) {
    items.push({
      label: `Workplace · ${analysis.workplaceModel}`,
      evidence: analysis.workplaceEvidence,
      evidenceVerified: analysis.workplaceEvidenceVerified !== false,
    });
  }
  return items.filter((item) => item.evidence).slice(0, 10);
}

function renderEvidence(item) {
  const verified = item.evidenceVerified !== false;
  return `
    <li class="evidence-item ${verified ? "" : "unverified"}">
      <strong>${escapeHtml(item.label)}</strong>
      <blockquote>“${escapeHtml(item.evidence)}”</blockquote>
      <span class="evidence-status ${verified ? "" : "unverified"}">
        ${verified ? "Exact text verified" : "Evidence could not be verified"}
      </span>
    </li>`;
}

function renderDetail() {
  const job = appState.jobs.find((candidate) => candidate.id === appState.selectedId);
  if (!job) {
    elements.emptyDetail.hidden = false;
    elements.detailContent.hidden = true;
    elements.detailPanel.classList.remove("open");
    return;
  }

  const analysis = job.analysis || {};
  const evidence = evidenceItems(analysis);
  const decision = decisionFor(job.id);
  const localAi = analysis.engine === "local-ai";
  const verifiedAt = job.lastSeenAt ? new Date(job.lastSeenAt).toLocaleString("en-GB", { dateStyle: "medium", timeStyle: "short" }) : "unknown";
  const sourceUrl = /^https:\/\//.test(job.sourceUrl) ? job.sourceUrl : "#";

  elements.detailContent.innerHTML = `
    <button class="detail-close" id="detailClose" type="button" aria-label="Close evidence report">×</button>
    <header class="detail-header">
      <span class="status-chip">Still listed by employer</span>
      <h2>${escapeHtml(job.title)}</h2>
      <p class="detail-company">${escapeHtml(job.company)}</p>
      <div class="detail-meta">
        <span class="mini-chip">${escapeHtml(job.location)}</span>
        ${job.category ? `<span class="mini-chip">${escapeHtml(job.category)}</span>` : ""}
        ${job.schedule ? `<span class="mini-chip">${escapeHtml(job.schedule)}</span>` : ""}
        ${job.seniority ? `<span class="mini-chip">${escapeHtml(job.seniority)}</span>` : ""}
      </div>
      <div class="detail-actions">
        <button class="button button-primary" id="aiAnalyzeButton" type="button">
          <span class="model-icon detail-ai-icon">AI</span>
          ${localAi ? "Re-analyze locally" : "Analyze with local AI"}
        </button>
        <a class="source-link" href="${escapeHtml(sourceUrl)}" target="_blank" rel="noopener noreferrer">Original ad ↗</a>
      </div>
    </header>

    <section class="report-section">
      <div class="section-title-row">
        <h3>Compatibility report</h3>
        <span class="analysis-badge"><b>${localAi ? "Local AI" : "Rules"}</b>${escapeHtml(analysis.model || "Baseline")}</span>
      </div>
      <p class="analysis-summary">${escapeHtml(analysis.summary || "No analysis available.")}</p>
      <div class="detail-meta">
        <span class="mini-chip">Seniority: ${escapeHtml(analysis.seniority || "Unclear")}</span>
        <span class="mini-chip">Work model: ${escapeHtml(analysis.workplaceModel || "Unclear")}</span>
        <span class="mini-chip">Confidence: ${escapeHtml(analysis.confidence || "low")}</span>
      </div>

      <p class="sub-label">Required skills</p>
      <div class="skill-grid">${chipList(analysis.requiredSkills)}</div>
      <p class="sub-label">Optional skills</p>
      <div class="skill-grid">${chipList(analysis.optionalSkills, "optional")}</div>
    </section>

    <section class="report-section">
      <div class="section-title-row">
        <h3>Evidence from the advertisement</h3>
        <span class="analysis-badge">${evidence.filter((item) => item.evidenceVerified !== false).length}/${evidence.length} verified</span>
      </div>
      <ul class="evidence-list">
        ${evidence.length ? evidence.map(renderEvidence).join("") : '<li class="muted-inline">No explicit evidence was extracted.</li>'}
      </ul>
    </section>

    <section class="report-section">
      <div class="section-title-row"><h3>Information to clarify</h3></div>
      <ul class="unclear-list">
        ${(analysis.unclearInformation || []).length
          ? analysis.unclearInformation.map((item) => `<li>${escapeHtml(item)}</li>`).join("")
          : "<li>No major missing information was detected.</li>"}
      </ul>
    </section>

    <section class="decision-section">
      <h3>Your decision</h3>
      <p>The system provides evidence. The applicant remains responsible for the decision. Last verified ${escapeHtml(verifiedAt)}.</p>
      <div class="decision-row">
        ${["apply", "clarify", "skip"]
          .map(
            (option) =>
              `<button class="decision-button ${decision === option ? "active" : ""}" data-decision="${option}" type="button">${option[0].toUpperCase() + option.slice(1)}</button>`,
          )
          .join("")}
      </div>
    </section>`;

  elements.emptyDetail.hidden = true;
  elements.detailContent.hidden = false;
  elements.detailPanel.classList.add("open");

  elements.detailContent.querySelector("#detailClose").addEventListener("click", () => {
    elements.detailPanel.classList.remove("open");
  });
  elements.detailContent.querySelector("#aiAnalyzeButton").addEventListener("click", () => analyzeSelectedJob(job.id));
  elements.detailContent.querySelectorAll("[data-decision]").forEach((button) => {
    button.addEventListener("click", () => {
      saveDecision(job.id, button.dataset.decision);
      appState.recentDecisionId = job.id;
      renderDetail();
      renderList();
      showToast(`${DECISION_META[button.dataset.decision].label} — map marker updated.`);
    });
  });
}

function selectJob(id, moveMap = false) {
  appState.selectedId = id;
  renderList();
  renderDetail();
  const job = appState.jobs.find((candidate) => candidate.id === id);
  if (moveMap && job?.coordinates && appState.map) {
    appState.map.flyTo({
      center: job.coordinates,
      zoom: Math.max(appState.map.getZoom(), appState.map3d ? 15 : 10.4),
      pitch: appState.map3d ? 58 : 0,
      bearing: appState.map3d ? -18 : 0,
      duration: 1050,
      curve: 1.35,
      essential: false,
    });
    setTimeout(() => appState.markers.get(id)?.togglePopup(), 760);
  }
}

async function analyzeSelectedJob(id) {
  const button = document.querySelector("#aiAnalyzeButton");
  if (button) {
    button.disabled = true;
    button.textContent = "Local AI is reading the ad…";
  }
  try {
    const payload = await fetchJson(`/api/jobs/${encodeURIComponent(id)}/analyze`, { method: "POST" });
    const index = appState.jobs.findIndex((job) => job.id === id);
    if (index >= 0) appState.jobs[index] = payload.job;
    applyFilters();
    renderDetail();
    showToast(`Analysis completed with ${payload.job.analysis.model}.`);
  } catch (error) {
    showToast(`Local AI analysis failed: ${error.message}`, "error");
    renderDetail();
  }
}

function updateSyncUi(payload) {
  appState.sync = payload.lastSyncSummary || appState.sync;
  const summary = appState.sync;
  elements.freshnessLabel.textContent = summary?.checkedAt ? `Checked ${relativeTime(summary.checkedAt)}` : "Not checked yet";
  if (summary) {
    const cadence = appState.syncIntervalMinutes >= 1440 ? "daily refresh" : `auto ${appState.syncIntervalMinutes} min`;
    elements.sourceHealthText.textContent = `${summary.sourcesChecked}/${appState.sourceCount} companies · ${summary.activeJobs} active · ${cadence}`;
    elements.sourceHealth.classList.toggle("error", Boolean(summary.errors?.length));
  } else {
    elements.sourceHealthText.textContent = payload.syncing ? "Checking official employer feeds…" : "No feed data yet";
  }
}

async function loadJobs(attempt = 0) {
  try {
    const payload = await fetchJson("/api/jobs");
    appState.jobs = payload.jobs || [];
    updateSyncUi(payload);
    applyFilters();
    if (!appState.selectedId && appState.filteredJobs.length) selectJob(appState.filteredJobs[0].id, false);
    if (!appState.jobs.length && payload.syncing && attempt < 10) {
      setTimeout(() => loadJobs(attempt + 1), 700);
    }
  } catch (error) {
    elements.resultList.innerHTML = `<div class="empty-results"><strong>Could not load vacancies</strong>${escapeHtml(error.message)}</div>`;
    showToast(error.message, "error");
  }
}

async function loadStatus() {
  try {
    const payload = await fetchJson("/api/status");
    appState.ollama = payload.ollama;
    appState.syncIntervalMinutes = payload.syncIntervalMinutes || 1440;
    appState.sourceCount = payload.feeds?.length || 7;
    updateSyncUi({ lastSyncSummary: appState.sync });
    if (payload.ollama.available && payload.ollama.model) {
      elements.modelStatus.innerHTML = `<span class="model-icon" aria-hidden="true">AI</span><span>${escapeHtml(payload.ollama.model)} · local</span>`;
    } else {
      elements.modelStatus.innerHTML = '<span class="model-icon" aria-hidden="true">AI</span><span>Rule baseline only</span>';
    }
  } catch {
    elements.modelStatus.innerHTML = '<span class="model-icon" aria-hidden="true">AI</span><span>Rule baseline only</span>';
  }
}

async function refreshFeeds() {
  elements.syncButton.disabled = true;
  const original = elements.syncButton.innerHTML;
  elements.syncButton.textContent = "Refreshing…";
  try {
    const payload = await fetchJson("/api/sync", { method: "POST" });
    appState.jobs = payload.jobs || [];
    appState.sync = payload.summary;
    updateSyncUi({ lastSyncSummary: payload.summary });
    applyFilters();
    renderDetail();
    const newJobs = payload.summary.added ? ` · ${payload.summary.added} new` : "";
    showToast(`${payload.summary.activeJobs} active jobs verified from ${payload.summary.sourcesChecked} companies${newJobs}.`);
  } catch (error) {
    showToast(`Feed refresh failed: ${error.message}`, "error");
  } finally {
    elements.syncButton.disabled = false;
    elements.syncButton.innerHTML = original;
  }
}

elements.searchInput.addEventListener("input", applyFilters);
elements.locationFilter.addEventListener("change", applyFilters);
elements.analysisFilter.addEventListener("change", applyFilters);
elements.cvButton.addEventListener("click", openCvDialog);
elements.cvCloseButton.addEventListener("click", closeCvDialog);
elements.cvFileInput.addEventListener("change", () => selectCvFile(elements.cvFileInput.files?.[0]));
elements.cvAnalyzeButton.addEventListener("click", analyzeCv);
for (const eventName of ["dragenter", "dragover"]) {
  elements.cvDropzone.addEventListener(eventName, (event) => {
    event.preventDefault();
    elements.cvDropzone.classList.add("dragging");
  });
}
for (const eventName of ["dragleave", "drop"]) {
  elements.cvDropzone.addEventListener(eventName, (event) => {
    event.preventDefault();
    elements.cvDropzone.classList.remove("dragging");
  });
}
elements.cvDropzone.addEventListener("drop", (event) => selectCvFile(event.dataTransfer?.files?.[0]));
elements.cvDropzone.addEventListener("keydown", (event) => {
  if (event.key !== "Enter" && event.key !== " ") return;
  event.preventDefault();
  elements.cvFileInput.click();
});
elements.interestButton.addEventListener("click", openInterestDialog);
elements.interestSummary.addEventListener("click", openInterestDialog);
elements.saveInterestsButton.addEventListener("click", () => {
  if (!appState.draftInterests.size) {
    showToast("Choose at least one interest or select all roles.", "error");
    return;
  }
  storeInterests([...appState.draftInterests]);
});
elements.allRolesButton.addEventListener("click", () => storeInterests([]));
elements.syncButton.addEventListener("click", refreshFeeds);
elements.map3dToggle.addEventListener("click", toggleMap3D);
document.addEventListener("keydown", (event) => {
  if (event.key === "/" && document.activeElement !== elements.searchInput) {
    event.preventDefault();
    elements.searchInput.focus();
  }
  if (event.key === "Escape") {
    if (!elements.cvOverlay.hidden) closeCvDialog();
    else elements.detailPanel.classList.remove("open");
  }
});

initMap();
syncMapViewButton();
renderInterestSummary();
if (savedInterests === null) openInterestDialog();
loadJobs();
loadStatus();
