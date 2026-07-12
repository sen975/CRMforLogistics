const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");
const app = fs.readFileSync(path.join(root, "app.js"), "utf8");
const css = fs.readFileSync(path.join(root, "styles.css"), "utf8");

const required = [
  "aiAssistantToolsOpen: false",
  "data-ai-tool-toggle",
  "ai-secretary-surface",
  "ai-tool-drawer",
  "ai-chat-log",
  "ai-chat-composer",
  "data-ai-chat-send",
  "打开功能侧栏",
  "收起功能侧栏",
  "知识库沉淀",
  "state.aiAssistantToolsOpen = !state.aiAssistantToolsOpen",
  "if (!state.aiAssistantOpen) state.aiAssistantToolsOpen = false",
  "initialParams.get(\"aiTools\") === \"1\"",
  "state.aiAssistantToolsOpen = false",
  ".ai-secretary-surface",
  ".ai-tool-drawer",
  ".ai-tool-drawer button:not(.icon-button)"
];

const forbidden = [
  "class=\"ai-secretary-jumps\"",
  ".ai-secretary-jumps",
  "ai-knowledge-submit"
];

const missing = required.filter((snippet) => !app.includes(snippet) && !css.includes(snippet));
const old = forbidden.filter((snippet) => app.includes(snippet) || css.includes(snippet));

if (missing.length || old.length) {
  if (missing.length) console.error(`Missing AI assistant drawer snippets: ${missing.join(", ")}`);
  if (old.length) console.error(`Forbidden old AI assistant snippets: ${old.join(", ")}`);
  process.exit(1);
}

console.log("AI assistant drawer snippets found.");
