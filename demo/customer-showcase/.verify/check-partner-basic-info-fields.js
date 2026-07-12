const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");
const app = fs.readFileSync(path.join(root, "app.js"), "utf8");

const start = app.indexOf("function renderPartnerInfoConfig(partner)");
const end = app.indexOf("function renderPartnerTabActions", start);
const renderer = start >= 0 && end > start ? app.slice(start, end) : "";

const required = [
  "function partnerWebsiteUrl(partner)",
  "function partnerTimezone(partner)",
  "function partnerCurrency(partner)",
  "function partnerLanguage(partner)",
  "<span>官网 URL</span>",
  "<span>时区</span>",
  "<span>货币</span>",
  "<span>语言</span>",
  "<span>下一步动作</span>"
];

const forbidden = ["价值判断"];

const missing = required.filter((snippet) => !app.includes(snippet));
const foundForbidden = forbidden.filter((snippet) => renderer.includes(snippet));

if (!renderer || missing.length || foundForbidden.length) {
  if (!renderer) console.error("Partner info config renderer not found.");
  if (missing.length) console.error(`Missing partner basic info fields: ${missing.join(", ")}`);
  if (foundForbidden.length) console.error(`Forbidden partner basic info fields: ${foundForbidden.join(", ")}`);
  process.exit(1);
}

console.log("Partner basic info fields found.");
