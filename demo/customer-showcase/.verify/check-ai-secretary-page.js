const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");
const app = fs.readFileSync(path.join(root, "app.js"), "utf8");
const css = fs.readFileSync(path.join(root, "styles.css"), "utf8");

const required = [
  "function renderAiConfig()",
  "function renderAiAssistantWidget()",
  "data-ai-assistant-toggle",
  "data-ai-tool-toggle",
  "data-ai-jump",
  "data-ai-knowledge-flow",
  "data-ai-chat-send",
  "aiAssistantOpen",
  "aiAssistantToolsOpen",
  "ai-config-layout",
  "ai-api-panel",
  "ai-persona-panel",
  "ai-secretary-widget",
  "ai-secretary-surface",
  "ai-secretary-panel",
  "ai-chat-log",
  "ai-chat-message",
  "ai-chat-composer",
  "ai-tool-drawer",
  "ai-tool-drawer-head",
  "AI API",
  "API Key",
  "AI 画像",
  "AI 能力",
  "知识库沉淀",
  "输入问题"
];

const forbidden = [
  "panel(\"待确认建议\"",
  "panel(\"当前建议\"",
  "id: \"ai\"",
  "page: \"ai\"",
  "data-ai-jump=\"ai\"",
  "class=\"ai-secretary-jumps\"",
  ".ai-secretary-jumps",
  "ai-knowledge-submit"
];

const missing = required.filter((snippet) => !app.includes(snippet) && !css.includes(snippet));
const old = forbidden.filter((snippet) => app.includes(snippet) || css.includes(snippet));

if (missing.length || old.length) {
  if (missing.length) console.error(`Missing AI secretary snippets: ${missing.join(", ")}`);
  if (old.length) console.error(`Forbidden old AI secretary snippets: ${old.join(", ")}`);
  process.exit(1);
}

console.log("AI secretary page snippets found.");
