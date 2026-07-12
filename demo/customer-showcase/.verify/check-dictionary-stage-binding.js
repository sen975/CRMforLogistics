const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");
const app = fs.readFileSync(path.join(root, "app.js"), "utf8");

const required = [
  "function businessDictionary()",
  "function dictionaryOptions(kind, selected)",
  "partner-type-select",
  "partner-risk-select",
  "contact-identity-select",
  "dictionary-search-input",
  "dictionary-card-actions",
  "data-dictionary-add",
  "data-dictionary-delete"
];

const forbidden = ["partner-stage-group-select", "task-stage-group-select", "stage-dictionary-card"];

const missing = required.filter((snippet) => !app.includes(snippet));
const foundForbidden = forbidden.filter((snippet) => app.includes(snippet));

if (missing.length || foundForbidden.length) {
  if (missing.length) console.error(`Missing dictionary/stage snippets: ${missing.join(", ")}`);
  if (foundForbidden.length) console.error(`Forbidden old stage snippets: ${foundForbidden.join(", ")}`);
  process.exit(1);
}

console.log("Business dictionary CRUD snippets found.");
