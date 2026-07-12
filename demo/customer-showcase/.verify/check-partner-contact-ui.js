const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");
const app = fs.readFileSync(path.join(root, "app.js"), "utf8");
const css = fs.readFileSync(path.join(root, "styles.css"), "utf8");

const requiredAppSnippets = [
  "partner-topic-card",
  "partner-topic-trigger",
  "partner-topic-detail",
  "partner-topic-timeline",
  "data-partner-topic-toggle",
  "aria-expanded",
  "expandedPartnerTopic",
  "renderAllKeepingScroll",
  ".contact-topic-list",
  ".partner-topic-list",
  "closest(\".partner-topic-list\")",
  "topic.timeline",
  "item.role",
  "item.status",
  "partner-detail-actions",
  "partner-owner-pin",
  "data-start-website-analysis",
  "data-start-due-report",
  "data-confirm-partner-tab",
  "data-edit-partner-tab",
  "renderPartnerOrderCommunication",
  "order",
  "订单沟通",
  "谈判订单结果",
  "上传结果",
  "报价结果",
  "成交状态",
  "订单附件",
  "历史订单",
  "partner-order-table",
  "data-upload-order-result",
  "开始官网分析",
  "开始背调报告",
  "确认",
  "修改"
];

const requiredCssSnippets = [
  ".partner-contact-layout",
  "minmax(220px, 0.55fr) minmax(0, 1.45fr)",
  ".partner-contact-list .contact-row",
  "grid-template-rows: auto minmax(0, 1fr)",
  ".partner-topic-list:hover",
  "flex-direction: column",
  "overflow: visible",
  "overscroll-behavior: contain",
  ".partner-topic-card",
  ".partner-topic-trigger",
  ".partner-topic-detail",
  ".partner-topic-timeline"
];

const forbiddenAppSnippets = [
  "${item.role} / ${item.channel}",
  "partner-topic-card-head"
];

const missing = [
  ...requiredAppSnippets.filter((snippet) => !app.includes(snippet)),
  ...requiredCssSnippets.filter((snippet) => !css.includes(snippet))
];
const forbidden = forbiddenAppSnippets.filter((snippet) => app.includes(snippet));

if (missing.length || forbidden.length) {
  if (missing.length) {
    console.error(`Missing partner contact UI snippets: ${missing.join(", ")}`);
  }
  if (forbidden.length) {
    console.error(`Forbidden partner contact UI snippets: ${forbidden.join(", ")}`);
  }
  process.exit(1);
}

console.log("Partner contact UI snippets found.");
