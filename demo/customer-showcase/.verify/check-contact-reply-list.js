const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");
const app = fs.readFileSync(path.join(root, "app.js"), "utf8");
const css = fs.readFileSync(path.join(root, "styles.css"), "utf8");

const required = [
  "function contactReplyState(contact)",
  "label: replied ? \"已回复\" : \"未回复\"",
  "contact-row contact-master-row",
  "contact-row-main",
  "contact-reply-pill",
  ".contact-master-row",
  ".contact-row-main",
  ".contact-reply-pill.replied",
  ".contact-reply-pill.unreplied",
  "${item.role_title || item.role || item.identity || \"职称待补充\"} / ${item.partner || \"未知合作方\"}"
];

const forbidden = [
  "contact-row ${contact.name === item.name && contact.partner === item.partner ? \"active\" : \"\"}"
];

const missing = required.filter((snippet) => !app.includes(snippet) && !css.includes(snippet));
const foundForbidden = forbidden.filter((snippet) => app.includes(snippet));

if (missing.length || foundForbidden.length) {
  if (missing.length) console.error(`Missing contact list reply snippets: ${missing.join(", ")}`);
  if (foundForbidden.length) console.error(`Forbidden old contact status snippets: ${foundForbidden.join(", ")}`);
  process.exit(1);
}

console.log("Contact list reply layout snippets found.");
