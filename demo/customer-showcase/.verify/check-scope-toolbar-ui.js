const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");
const app = fs.readFileSync(path.join(root, "app.js"), "utf8");

const required = [
  "function currentEmployeeOwner()",
  "return \"Elena Wang\"",
  "function isEmployeeScope()",
  "function scopedPartners()",
  "function scopedMessages()",
  "function scopedProfiles()",
  "return scopedPartners().filter",
  "return [...new Set(scopedMessages().map",
  "filter((contact) => isPartnerVisible(contact.partner))",
  "const toolbarPages = [\"communication\", \"contacts\"]",
  "classList.toggle(\"hidden\", !toolbarPages.includes(state.page))",
  "renderTaskPanelFilters(board)",
  "const visibleEntries = Object.entries(boards).filter",
  "item.owner === currentEmployeeOwner()",
  "taskFilterCatalog().partnerOptions.some",
  "if (pageTarget.dataset.settingsTab) state.settingsTab = pageTarget.dataset.settingsTab"
];

const forbidden = [
  "const toolbarRight = state.page === \"communication\"\n      ? `${communicationFilter}${communicationConfigButton}`\n      : state.page === \"tasks\"",
  "return data.partners.filter((partner) => {",
  "return [...new Set(data.messages.map((item) => item.partner))]"
];

const missing = required.filter((snippet) => !app.includes(snippet));
const old = forbidden.filter((snippet) => app.includes(snippet));

if (missing.length || old.length) {
  if (missing.length) console.error(`Missing scope/toolbar snippets: ${missing.join(", ")}`);
  if (old.length) console.error(`Forbidden old scope/toolbar snippets: ${old.join(", ")}`);
  process.exit(1);
}

console.log("Scope and toolbar snippets found.");
