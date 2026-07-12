const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");
const app = fs.readFileSync(path.join(root, "app.js"), "utf8");
const css = fs.readFileSync(path.join(root, "styles.css"), "utf8");

const rendererStart = app.indexOf("function renderPartnerEnterpriseList()");
const rendererEnd = app.indexOf("function renderPartnerInfoConfig", rendererStart);
const listRenderer = rendererStart >= 0 && rendererEnd > rendererStart ? app.slice(rendererStart, rendererEnd) : "";

const inputStart = app.indexOf('document.body.addEventListener("input"');
const inputEnd = app.indexOf("});", inputStart);
const inputHandler = inputStart >= 0 && inputEnd > inputStart ? app.slice(inputStart, inputEnd) : "";

const requiredSnippets = [
  "partner-enterprise-link",
  "data-open-partner-detail",
  "partner.type",
  "partnerListFilters",
  "partnerListFilterDrafts",
  "filteredPartners",
  "data-partner-name-filter",
  "data-partner-owner-filter",
  "data-partner-date-filter",
  "data-apply-partner-filters",
  "data-reset-partner-filters",
  "partner-name-options",
  "partner-owner-options",
  "<datalist id=\"partner-name-options\">",
  "<datalist id=\"partner-owner-options\">",
  "partner-list-filterbar",
  ".partner-enterprise-link",
  "grid-template-columns: minmax(240px, 1.45fr) minmax(110px, 0.62fr) minmax(120px, 0.7fr) minmax(150px, 0.85fr) minmax(120px, 0.7fr)"
];

const forbiddenRendererSnippets = [
  "<span>操作</span>",
  ">详情</button>"
];

const forbiddenInputSnippets = [
  "data-partner-name-filter]\")) {\n        state.partnerListFilters.name = event.target.value;\n        renderPageContent();",
  "data-partner-owner-filter]\")) {\n        state.partnerListFilters.owner = event.target.value;\n        renderPageContent();",
  "data-partner-date-filter]\")) {\n        state.partnerListFilters.date = event.target.value;\n        renderPageContent();"
];

const missing = requiredSnippets.filter((snippet) => !app.includes(snippet) && !css.includes(snippet));
const forbidden = [
  ...forbiddenRendererSnippets.filter((snippet) => listRenderer.includes(snippet)),
  ...forbiddenInputSnippets.filter((snippet) => inputHandler.includes(snippet))
];

if (!listRenderer || !inputHandler || missing.length || forbidden.length) {
  if (!listRenderer) {
    console.error("Partner list renderer not found.");
  }
  if (!inputHandler) {
    console.error("Input handler not found.");
  }
  if (missing.length) {
    console.error(`Missing partner list UI snippets: ${missing.join(", ")}`);
  }
  if (forbidden.length) {
    console.error(`Forbidden partner list UI snippets: ${forbidden.join(", ")}`);
  }
  process.exit(1);
}

console.log("Partner list UI snippets found.");
