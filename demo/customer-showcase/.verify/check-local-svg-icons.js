const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");
const app = fs.readFileSync(path.join(root, "app.js"), "utf8");
const css = fs.readFileSync(path.join(root, "styles.css"), "utf8");
const iconRoot = path.resolve(root, "..", "customer", "svg");

const requiredApp = [
  "const iconSources = {",
  "workbench: \"\\u4eea\\u8868\\u76d8.svg\"",
  "communication: \"\\u90ae\\u4ef6.svg\"",
  "partners: \"\\u516c\\u6587\\u5305.svg\"",
  "contacts: \"\\u7528\\u6237.svg\"",
  "tasks: \"\\u5706\\u5f62\\u590d\\u9009\\u6846.svg\"",
  "dashboard: \"\\u6f14\\u793a\\u56fe\\u8868.svg\"",
  "settings: \"\\u9f7f\\u8f6e.svg\"",
  "prev: \"\\u5de6\\u7bad\\u5934.svg\"",
  "next: \"\\u53f3\\u7bad\\u5934.svg\"",
  "function iconAssetUrl(fileName)",
  "<img class=\"ui-icon local-icon\"",
  "src=\"${iconAssetUrl(source)}\"",
  "data-new-task>${iconSvg(\"add\")}",
  "data-open-channel-config>${iconSvg(\"settings\")}",
  "data-new-contact>${iconSvg(\"add-user\")}"
];

const requiredFiles = [
  "\u4eea\u8868\u76d8.svg",
  "\u90ae\u4ef6.svg",
  "\u516c\u6587\u5305.svg",
  "\u7528\u6237.svg",
  "\u5706\u5f62\u590d\u9009\u6846.svg",
  "\u6f14\u793a\u56fe\u8868.svg",
  "\u9f7f\u8f6e.svg",
  "\u5de6\u7bad\u5934.svg",
  "\u53f3\u7bad\u5934.svg",
  "\u52a0\u53f7.svg",
  "\u6dfb\u52a0\u7528\u6237.svg"
];

const requiredCss = [
  ".ui-icon.local-icon",
  "object-fit: contain",
  ".button.primary .ui-icon.local-icon"
];

const forbiddenCss = [
  "-webkit-mask: var(--icon-url) center / contain no-repeat",
  "mask: var(--icon-url) center / contain no-repeat"
];

const missingApp = requiredApp.filter((snippet) => !app.includes(snippet));
const missingCss = requiredCss.filter((snippet) => !css.includes(snippet));
const foundForbiddenCss = forbiddenCss.filter((snippet) => css.includes(snippet));
const missingFiles = requiredFiles.filter((fileName) => !fs.existsSync(path.join(iconRoot, fileName)));
const brokenMojibake = /\?+\.svg/.test(app);

if (missingApp.length || missingCss.length || foundForbiddenCss.length || missingFiles.length || brokenMojibake) {
  if (missingApp.length) console.error(`Missing local SVG app snippets: ${missingApp.join(", ")}`);
  if (missingCss.length) console.error(`Missing local SVG css snippets: ${missingCss.join(", ")}`);
  if (foundForbiddenCss.length) console.error(`Forbidden local SVG mask snippets: ${foundForbiddenCss.join(", ")}`);
  if (missingFiles.length) console.error(`Missing local SVG files: ${missingFiles.join(", ")}`);
  if (brokenMojibake) console.error("Found broken question-mark SVG filenames in app.js.");
  process.exit(1);
}

console.log("Local SVG icon snippets found.");
