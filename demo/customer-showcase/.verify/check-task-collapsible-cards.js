const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");
const app = fs.readFileSync(path.join(root, "app.js"), "utf8");
const css = fs.readFileSync(path.join(root, "styles.css"), "utf8");

const required = [
  "taskExpanded",
  "function isTaskExpanded(id)",
  "data-task-toggle",
  "aria-expanded",
  "task-card-summary",
  "function renderPrioritySelect(id, priority)",
  "${renderPrioritySelect(id, priority)}",
  "<span>${contactName} / ${task.stage}</span>",
  "task-execution-card ${expanded ?",
  "review-task-card ${expanded ?",
  "${expanded ? `<div class=\"task-execution-meta\">",
  "<div class=\"task-review-form\">",
  "const taskToggleTarget = event.target.closest(\"[data-task-toggle]\")",
  ".task-priority-pill.high",
  ".task-priority-pill.medium",
  ".task-priority-pill.low",
  ".task-execution-card.collapsed",
  ".review-task-card.collapsed"
];

const forbidden = [
  "<article class=\"task-card task-execution-card\">",
  "<article class=\"review-task-card\">",
  "<span>${contactName} / ${task.object}</span>",
  "<span>${contactName} / ${contextLabel}</span>",
  "<span>优先级</span>",
  "task-priority-item",
  "task-panel-head"
];

const missing = required.filter((snippet) => !app.includes(snippet) && !css.includes(snippet));
const foundForbidden = forbidden.filter((snippet) => app.includes(snippet));

if (missing.length || foundForbidden.length) {
  if (missing.length) console.error(`Missing collapsible task card snippets: ${missing.join(", ")}`);
  if (foundForbidden.length) console.error(`Forbidden non-collapsible task snippets: ${foundForbidden.join(", ")}`);
  process.exit(1);
}

console.log("Collapsible task card snippets found.");
