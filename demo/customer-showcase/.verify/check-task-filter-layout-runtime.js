const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");
const app = fs.readFileSync(path.join(root, "app.js"), "utf8");
const css = fs.readFileSync(path.join(root, "styles.css"), "utf8");

const required = [
  "const ownerFilter = isEmployeeScope() ? \"\"",
  "<div class=\"task-toolbar-filterbar\">",
  "<select data-task-time-filter>",
  "${ownerFilter}",
  "repeat(auto-fit, minmax(170px, 1fr))"
];

const forbidden = [
  "<label class=\"task-time-inline\">",
  ".task-time-inline"
];

const missing = required.filter((snippet) => !app.includes(snippet) && !css.includes(snippet));
const foundForbidden = forbidden.filter((snippet) => app.includes(snippet) || css.includes(snippet));

if (missing.length || foundForbidden.length) {
  if (missing.length) console.error(`Missing task filter layout snippets: ${missing.join(", ")}`);
  if (foundForbidden.length) console.error(`Forbidden split task filter snippets: ${foundForbidden.join(", ")}`);
  process.exit(1);
}

console.log("Task filter layout snippets found.");
