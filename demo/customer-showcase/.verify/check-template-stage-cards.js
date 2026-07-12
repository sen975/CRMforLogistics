const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");
const app = fs.readFileSync(path.join(root, "app.js"), "utf8");
const css = fs.readFileSync(path.join(root, "styles.css"), "utf8");

const requiredApp = [
  "function renderSettingsTemplate()",
  "const stageGroups = [",
  "settings-stage-group-card",
  "settings-substage-row",
  "data-substage-add",
  "data-substage-delete",
  "\\u9501\\u5b9a",
  "\\u5b50\\u6d41\\u7a0b",
  "\\u7acb\\u9879\\u8c03\\u7814",
  "\\u9700\\u6c42\\u786e\\u8ba4",
  "\\u8ba2\\u5355\\u8c08\\u5224",
  "\\u5408\\u4f5c\\u5b8c\\u6210",
  "\\u6682\\u7f13\\u5f00\\u53d1"
];

const forbiddenApp = [
  "\\u7ebf\\u7d22\\u8bc6\\u522b",
  "\\u62a5\\u4ef7\\u4e2d",
  "\\u5df2\\u8f6c\\u5316"
];

const requiredCss = [
  ".settings-stage-group-card",
  ".settings-substage-row",
  ".settings-substage-list",
  ".settings-stage-group-grid.locked"
];

const missingApp = requiredApp.filter((snippet) => !app.includes(snippet));
const staleApp = forbiddenApp.filter((snippet) => app.includes(snippet));
const missingCss = requiredCss.filter((snippet) => !css.includes(snippet));

if (missingApp.length || staleApp.length || missingCss.length) {
  if (missingApp.length) console.error(`Missing template app snippets: ${missingApp.join(", ")}`);
  if (staleApp.length) console.error(`Stale template stage snippets remain: ${staleApp.join(", ")}`);
  if (missingCss.length) console.error(`Missing template css snippets: ${missingCss.join(", ")}`);
  process.exit(1);
}

console.log("Template stage card snippets found.");
