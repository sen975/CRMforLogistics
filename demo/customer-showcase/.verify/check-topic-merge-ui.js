const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");
const app = fs.readFileSync(path.join(root, "app.js"), "utf8");

const requiredSnippets = [
  "topic-merge-head",
  "topic-merge-card",
  "data-merge-topic",
  "data-topic-picker",
  "data-create-contact-topic",
  "initialParams.get(\"messageDetail\")",
  "event.target.closest(\"[data-merge-topic]\")",
  "event.target.closest(\"[data-topic-picker]\")",
  "event.target.closest(\"[data-create-contact-topic]\")",
  "state.topicArchives[message.id]",
  "messageFactParts",
  "message-fact-section",
  "message-fact-list",
  "topic-merge-section"
];

const forbiddenSnippets = [
  "<div class=\"fact-grid\">${message.facts.map",
  "message-insight-grid"
];

const missing = requiredSnippets.filter((snippet) => !app.includes(snippet));
const forbidden = forbiddenSnippets.filter((snippet) => app.includes(snippet));

if (missing.length || forbidden.length) {
  if (missing.length) {
    console.error(`Missing message detail UI snippets: ${missing.join(", ")}`);
  }
  if (forbidden.length) {
    console.error(`Forbidden message detail fact UI snippets: ${forbidden.join(", ")}`);
  }
  process.exit(1);
}

console.log("Message detail topic merge and fact UI snippets found.");
