const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");
const css = fs.readFileSync(path.join(root, "styles.css"), "utf8");

const requiredSnippets = [
  ".contact-master-layout",
  "contact-master-panel",
  "grid-template-rows: auto auto minmax(0, 1fr)",
  ".contact-master-list",
  ".contact-master-detail",
  ".partner-contact-layout",
  "align-items: stretch",
  "height: clamp(460px",
  ".partner-contact-list",
  ".partner-contact-profile",
  ".partner-topic-list",
  "grid-template-rows: auto minmax(0, 1fr)",
  "overflow-y: auto",
  "overscroll-behavior: contain",
  "scrollbar-gutter: stable",
  "scrollbar-color: transparent transparent",
  "::-webkit-scrollbar-thumb",
  ":hover::-webkit-scrollbar-thumb"
];

const missing = requiredSnippets.filter((snippet) => !css.includes(snippet));

if (missing.length) {
  console.error(`Missing contact scroll UI snippets: ${missing.join(", ")}`);
  process.exit(1);
}

console.log("Contact scroll UI snippets found.");
