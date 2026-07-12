const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");
const css = fs.readFileSync(path.join(root, "styles.css"), "utf8");

const required = [
  ".page-title p { display: none; }",
  ".toolbar-head span { display: none; }",
  ".module-toolbar span { display: none; }",
  ".partner-detail-head p { display: none; }",
  ".section-headline span",
  "display: none;",
  ".task-board-head p { display: none; }",
  ".ai-secretary-head span",
  ".ai-tool-drawer button span",
  ".settings-hero p",
  ".settings-panel-head span",
  ".settings-tab span",
  ".channel-config-head span,"
];

const missing = required.filter((snippet) => !css.includes(snippet));

if (missing.length) {
  console.error(`Missing hidden module description snippets: ${missing.join(", ")}`);
  process.exit(1);
}

console.log("Muted module descriptions are hidden.");
