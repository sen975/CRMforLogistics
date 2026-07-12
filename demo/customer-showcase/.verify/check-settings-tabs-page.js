const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");
const app = fs.readFileSync(path.join(root, "app.js"), "utf8");
const css = fs.readFileSync(path.join(root, "styles.css"), "utf8");

const requiredAppSnippets = [
  "settingsTab: \"users\"",
  "settingsTabs()",
  "renderSettingsTabs",
  "iconSvg(id, label)",
  "renderSettingsUsers",
  "renderSettingsDictionary",
  "renderSettingsChannels",
  "renderSettingsTemplate",
  "renderSettingsRisk",
  "renderSettingsAudit",
  "data-settings-tab",
  "data-settings-action",
  "data-settings-logout",
  "用户与权限",
  "业务字典",
  "信息渠道",
  "AI 能力",
  "开发模板",
  "风险与黑名单",
  "审计日志",
  "ChangeMe123!",
  "SALES_REP",
  "WhatsApp Business API",
  "AI 可按模板生成阶段和任务草稿",
  "业务页面只消费这些规则",
  "if (target === \"settings\") state.settingsTab = \"ai\""
];

const requiredCssSnippets = [
  ".settings-shell",
  ".settings-hero",
  ".settings-tabs",
  ".settings-tab",
  ".settings-tab .ui-icon",
  ".settings-tab.active",
  ".settings-work-panel",
  ".settings-panel-head",
  ".settings-split",
  ".settings-form",
  ".settings-table",
  ".settings-dictionary-grid",
  ".settings-channel-grid",
  ".settings-risk-grid",
  ".settings-stage-row",
  ".settings-weight-box"
];

const forbiddenAppSnippets = [
  "panel(\"AI 秘书配置\"",
  "id: \"ai\"",
  "page: \"ai\"",
  "data-ai-jump=\"ai\""
];

const missing = [
  ...requiredAppSnippets.filter((snippet) => !app.includes(snippet)),
  ...requiredCssSnippets.filter((snippet) => !css.includes(snippet))
];
const forbidden = forbiddenAppSnippets.filter((snippet) => app.includes(snippet));

if (missing.length || forbidden.length) {
  if (missing.length) {
    console.error(`Missing settings tabs snippets: ${missing.join(", ")}`);
  }
  if (forbidden.length) {
    console.error(`Forbidden old settings snippets: ${forbidden.join(", ")}`);
  }
  process.exit(1);
}

console.log("Settings tabs page snippets found.");
