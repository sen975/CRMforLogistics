const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");
const app = fs.readFileSync(path.join(root, "app.js"), "utf8");

const required = [
  "function taskOwnerName(task, board)",
  "return partnerOwner || board.owner || task.owner || currentEmployeeOwner()",
  "<span>负责人</span><strong>${ownerName}</strong>",
  "<input value=\"${ownerName}\" />",
  "contact: \"Lucy Chen\", owner: \"Elena Wang\"",
  "if (task.source === \"人工新增\") return `${contactName} 手动记录 / ${context}`"
];

const forbidden = [
  "owner: \"Lucy Chen\"",
  "<span>负责人</span><strong>${task.owner}</strong>",
  "<input value=\"${task.owner}\" />",
  "return `${task.owner || board.owner} 手动记录 / ${context}`"
];

const missing = required.filter((snippet) => !app.includes(snippet));
const old = forbidden.filter((snippet) => app.includes(snippet));

if (missing.length || old.length) {
  if (missing.length) console.error(`Missing task owner snippets: ${missing.join(", ")}`);
  if (old.length) console.error(`Forbidden task owner snippets: ${old.join(", ")}`);
  process.exit(1);
}

console.log("Task owner semantics snippets found.");
