const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");
const app = fs.readFileSync(path.join(root, "app.js"), "utf8");
const data = fs.readFileSync(path.join(root, "mock-data.js"), "utf8");

const requiredSnippets = [
  "id: \"contacts\"",
  "id: \"tasks\"",
  "contacts: {",
  "tasks: {",
  "function renderContacts()",
  "function renderTasks()",
  "contacts: renderContacts",
  "tasks: renderTasks",
  "currentStandaloneContact",
  "allContactProfiles",
  "filteredStandaloneContacts",
  "contact-master-layout",
  "contact-search-box",
  "data-contact-search",
  "data-new-contact",
  "新增联系人",
  "compositionstart",
  "compositionend",
  "contactSearchComposing",
  "contactDetailMode",
  "emptyContactDraft",
  "contact-name-trigger",
  "data-contact-detail-open",
  "renderContactDetailModal",
  "data-contact-detail-form",
  "saveContactDetail",
  "保存更改",
  "textarea name=\"confirmed_profile\"",
  "contact-confirmed-profile",
  "contact-detail-grid",
  "contact-topic-card",
  "taskWorkbenchData",
  "taskFilterCatalog",
  "applyTaskToolbarFilter",
  "taskView",
  "selectedTaskPartner",
  "data-task-view",
  "data-task-partner-picker",
  "data-task-partner-option",
  "data-task-owner-filter",
  "task-panel-controls",
  "task-toolbar-filterbar",
  "task-partner-picker",
  "task-partner-menu",
  "task-view-tabs",
  "task-view-tab",
  "task-workbench-grid ${state.taskView === \"done\" ? \"done\" : \"follow\"}",
  "task-workbench-grid review",
  "state.taskView === \"done\"",
  "data-task-priority",
  "data-task-complete",
  "taskCompletedOverrides",
  "completedTasks",
  "taskCompletedList",
  "data-task-time-filter",
  "data-new-task",
  "添加任务",
  "const ownerFilter = isEmployeeScope() ? \"\"",
  "${ownerFilter}",
  "function iconSvg",
  "iconPaths",
  "class=\"ui-icon\"",
  "iconSvg(\"follow\")",
  "iconSvg(\"review\")",
  "iconSvg(\"ended\")",
  "iconSvg(\"prev\")",
  "iconSvg(\"next\")",
  "selectedSuggestions",
  "function taskContactName",
  "function taskContextLabel",
  "function taskTraceSource",
  "function reviewTaskDrafts",
  "function renderReviewTaskCard",
  "function taskReviewActions",
  "review-task-card",
  "task-card-summary",
  "data-task-toggle",
  "task-review-form",
  "task-review-evidence",
  "data-task-review-source",
  "textarea data-task-review-title",
  "task-card-review-actions",
  "task-execution-card",
  "task-execution-head",
  "task-title-block",
  "task-execution-meta",
  "task-meta-item",
  "const manuallyCompleted = (board.tasks || []).filter",
  "...manuallyCompleted",
  "联系人",
  "信息来源",
  "标题",
  "截止时间",
  "优先级",
  "开发流程阶段",
  "待完成",
  "rhein-cargo",
  "santos-foods",
  "shenzhen-orbit",
  "aurora-home",
  "atlasmed-devices",
  "kanto-retail",
  "客户开发模板",
  "task-workbench-grid",
  "task-list-panel",
  "task-ai-panel",
  "需审核任务",
  "归档到标准阶段",
  "task-board",
  "task-card-list",
  "task-empty-background",
  "已结束任务",
  "预期任务"
];

const forbiddenSnippets = [
  "function renderTopics()",
  "topics: renderTopics",
  "data-topic-name",
  "state.page = \"topics\"",
  "page: \"topics\"",
  "<div class=\"contact-schema-grid\">",
  "<div class=\"contact-profile-split\">",
  "tag(contact.status || \"pending\"",
  "tag(`${contact.confidence || 86}%`",
  "tag(contact.identity || contact.role || \"未知\"",
  "topic.source ? tag(topic.source"
  ,"data-task-stage"
  ,"task-stage-panel"
  ,"task-stage-item"
  ,"task-time-inline"
];

const missing = requiredSnippets.filter((snippet) => !app.includes(snippet) && !data.includes(snippet));
const forbidden = forbiddenSnippets.filter((snippet) => app.includes(snippet) || data.includes(snippet));

if (missing.length || forbidden.length) {
  if (missing.length) {
    console.error(`Missing contact/task page snippets: ${missing.join(", ")}`);
  }
  if (forbidden.length) {
    console.error(`Forbidden old topic/task snippets: ${forbidden.join(", ")}`);
  }
  process.exit(1);
}

console.log("Contact and task page snippets found.");
