window.CRM_SHOWCASE_DATA = {
  nav: [
    { id: "workbench", label: "个人工作台", mark: "01" },
    { id: "communication", label: "信息管理", mark: "02" },
    { id: "partners", label: "合作企业", mark: "03" },
    { id: "contacts", label: "联系对象", mark: "04" },
    { id: "tasks", label: "跟进任务", mark: "05" },
    { id: "dashboard", label: "团队看板", mark: "06" },
    { id: "settings", label: "系统配置", mark: "07" }
  ],

  roles: [
    { id: "employee", label: "员工", scope: "本人负责数据" },
    { id: "supervisor", label: "主管", scope: "团队数据" },
    { id: "boss", label: "老板", scope: "公司汇总" },
    { id: "admin", label: "管理员", scope: "配置与授权" }
  ],

  statuses: [
    { label: "邮件 IMCP", value: "正常", tone: "green" },
    { label: "AI 队列", value: "3 待确认", tone: "amber" },
    { label: "RocketMQ", value: "运行中", tone: "green" },
    { label: "pgvector", value: "索引就绪", tone: "blue" },
    { label: "MinIO", value: "附件可用", tone: "green" }
  ],

  workflow: [
    { title: "信息进入系统", page: "communication", result: "邮件 IMCP、WhatsApp Business API、企业微信 API 和电话录入进入统一入口" },
    { title: "AI 识别身份", page: "communication", result: "识别联系人、合作方归属和物流业务要素" },
    { title: "人工确认", page: "contacts", result: "员工在联系人档案内确认身份、画像和关联 Topic" },
    { title: "Topic 更新", page: "contacts", result: "沟通沉淀到联系人下的业务主题和时间线" },
    { title: "任务写入", page: "tasks", result: "正式生成独立待办、负责人、截止时间和审计记录" },
    { title: "看板刷新", page: "dashboard", result: "老板看到进度、逾期、高价值机会和风险" }
  ],

  pageCopy: {
    workbench: {
      eyebrow: "每日入口",
      title: "个人工作台",
      subtitle: "",
      tags: ["成果聚合", "信息可视化", "今日跟进"]
    },
    communication: {
      eyebrow: "信息入口",
      title: "信息管理",
      subtitle: "统一管理邮件 IMCP、WhatsApp Business API、企业微信 API 和电话录入的信息。",
      tags: ["多渠道接入", "待归档", "原文受控"]
    },
    partners: {
      eyebrow: "主数据",
      title: "合作方管理",
      subtitle: "统一管理客户、供应商、代理、内部协作对象和未知对象。",
      tags: ["客户", "供应商", "代理", "风险"]
    },
    contacts: {
      eyebrow: "联系人主档",
      title: "联系人",
      subtitle: "联系人是身份、渠道、画像和 Topic 的第一归属。",
      tags: ["身份字典", "AI 画像", "Topic 时间线"]
    },
    tasks: {
      eyebrow: "执行事项",
      title: "任务",
      subtitle: "任务独立管理负责人、截止时间、状态和来源 Topic。",
      tags: ["待办", "负责人", "截止时间"]
    },
    dashboard: {
      eyebrow: "管理视角",
      title: "团队看板",
      subtitle: "老板和主管看团队进度、逾期、风险和 AI 建议处理情况。",
      tags: ["汇总", "风险", "不默认看原文"]
    },
    settings: {
      eyebrow: "基础配置",
      title: "系统配置",
      subtitle: "维护角色权限、数据范围、渠道账号、字典和 AI 秘书配置。",
      tags: ["权限", "字典", "渠道", "AI 秘书"]
    }
  },

  informationChannels: [
    {
      name: "邮件 IMCP",
      status: "已连接",
      mode: "自动同步",
      scope: "业务邮箱 inbox@logicrm.demo",
      config: ["IMCP Server: imcp.logicrm.demo", "同步频率: 5 分钟", "附件: 自动入库 MinIO"],
      rule: "单封邮件形成信息页；同一联系人连续邮件可合并到同一 Topic。",
      actions: ["测试连接", "同步设置", "附件规则"]
    },
    {
      name: "WhatsApp Business API",
      status: "已连接",
      mode: "按天聚合",
      scope: "Business Account: LA-sales",
      config: ["Webhook: 已启用", "聚合键: 联系人 + 日期", "原文: 按聊天窗口保存"],
      rule: "同一联系人一天内的聊天记录先聚合，再生成一条 AI 摘要，不逐条推送给 AI。",
      actions: ["Webhook 配置", "聚合规则", "重拉当天记录"]
    },
    {
      name: "企业微信 API",
      status: "待确认",
      mode: "按天聚合",
      scope: "企微客户群 / 外部联系人",
      config: ["回调 URL: 已生成", "密钥: 待管理员确认", "附件: 支持报价单解析"],
      rule: "同一外部联系人一天内的企业微信聊天和附件聚合为一个信息页。",
      actions: ["确认密钥", "回调测试", "附件解析规则"]
    },
    {
      name: "电话录入",
      status: "可录入",
      mode: "人工录入",
      scope: "员工手动登记电话纪要",
      config: ["必填: 联系人、合作方、时间", "可选: 录音链接、纪要文本", "AI: 录入后生成摘要建议"],
      rule: "电话由员工在信息管理页录入，保存后进入 AI 摘要确认流程。",
      actions: ["新增电话记录", "录音上传", "纪要模板"]
    }
  ],
  partners: [
    {
      id: "blueharbor",
      name: "BlueHarbor Imports",
      type: "客户",
      country: "美国",
      city: "Los Angeles",
      owner: "Elena Wang",
      stage: "需求确认中",
      value: "高价值机会",
      risk: "中风险",
      tags: ["海运", "40HQ", "洛杉矶", "价格敏感"],
      summary: "美国进口商，近期关注宁波到 LAX 40HQ 报价、燃油附加费、目的港杂费和预计船期。",
      nextAction: "补充 Ningbo -> LAX 40HQ 报价并确认本周船期"
    },
    {
      id: "pacific-star",
      name: "Pacific Star Logistics",
      type: "海外代理",
      country: "美国",
      city: "Long Beach",
      owner: "David Liu",
      stage: "报价后跟进",
      value: "中价值",
      risk: "高风险",
      tags: ["海外仓", "卡车派送", "入仓预约"],
      summary: "合作代理，最近两次反馈延迟，主管需要关注入仓窗口和异常响应。",
      nextAction: "跟进周三入仓预约和装箱单提交"
    },
    {
      id: "ningbo-atlas",
      name: "Ningbo Atlas Forwarding",
      type: "供应商",
      country: "中国",
      city: "宁波",
      owner: "Amy Zhao",
      stage: "合作谈判中",
      value: "中价值",
      risk: "附件异常",
      tags: ["船代", "宁波", "报价单", "附件解析失败"],
      summary: "宁波本地供应商，报价附件已保存到 MinIO，但旧版表格抽取失败，需要重试。",
      nextAction: "要求供应商补发标准报价模板"
    }
  ],

  workbenchPerformance: {
    week: {
      label: "本周",
      previousLabel: "上周",
      metrics: [
        { label: "开发成功客户", value: 3, previous: 2, unit: "个" },
        { label: "正在推进客户", value: 18, previous: 15, unit: "个" },
        { label: "等待录入客户", value: 6, previous: 9, unit: "个" }
      ],
      line: {
        labels: ["周一", "周二", "周三", "周四", "周五", "周六", "周日"],
        current: [12, 18, 15, 22, 30, 8, 5],
        previous: [9, 12, 14, 18, 20, 6, 3]
      },
      bars: [
        { label: "邮件 IMCP", current: 42, previous: 31 },
        { label: "WhatsApp Business API", current: 28, previous: 23 },
        { label: "企业微信 API", current: 18, previous: 15 },
        { label: "电话录入", current: 12, previous: 8 }
      ]
    },
    month: {
      label: "本月",
      previousLabel: "上月",
      metrics: [
        { label: "开发成功客户", value: 11, previous: 8, unit: "个" },
        { label: "正在推进客户", value: 46, previous: 39, unit: "个" },
        { label: "等待录入客户", value: 21, previous: 28, unit: "个" }
      ],
      line: {
        labels: ["第1周", "第2周", "第3周", "第4周"],
        current: [64, 72, 88, 95],
        previous: [51, 66, 70, 74]
      },
      bars: [
        { label: "邮件 IMCP", current: 168, previous: 139 },
        { label: "WhatsApp Business API", current: 104, previous: 92 },
        { label: "企业微信 API", current: 76, previous: 61 },
        { label: "电话录入", current: 39, previous: 32 }
      ]
    },
    year: {
      label: "今年",
      previousLabel: "去年",
      metrics: [
        { label: "开发成功客户", value: 74, previous: 58, unit: "个" },
        { label: "正在推进客户", value: 162, previous: 141, unit: "个" },
        { label: "等待录入客户", value: 49, previous: 67, unit: "个" }
      ],
      line: {
        labels: ["1月", "2月", "3月", "4月", "5月", "6月"],
        current: [190, 214, 238, 260, 292, 319],
        previous: [172, 188, 201, 226, 244, 263]
      },
      bars: [
        { label: "邮件 IMCP", current: 980, previous: 840 },
        { label: "WhatsApp Business API", current: 664, previous: 590 },
        { label: "企业微信 API", current: 438, previous: 410 },
        { label: "电话录入", current: 205, previous: 188 }
      ]
    }
  },

  workbenchCustomerProgress: {
    day: {
      label: "今日",
      previousLabel: "昨日",
      metrics: [
        { label: "开发成功客户", value: 1, previous: 0, unit: "个" },
        { label: "正在推进客户", value: 7, previous: 6, unit: "个" },
        { label: "等待录入客户", value: 2, previous: 3, unit: "个" }
      ],
      line: {
        labels: ["08", "10", "12", "14", "16", "18"],
        current: [2, 4, 6, 7, 9, 8],
        previous: [1, 3, 4, 5, 6, 5]
      },
      stages: [
        { label: "新线索", current: 4, previous: 3 },
        { label: "已联系", current: 6, previous: 5 },
        { label: "需求确认", current: 5, previous: 4 },
        { label: "报价中", current: 3, previous: 2 },
        { label: "已成交", current: 1, previous: 0 }
      ]
    },
    month: {
      label: "本月",
      previousLabel: "上月",
      metrics: [
        { label: "开发成功客户", value: 11, previous: 8, unit: "个" },
        { label: "正在推进客户", value: 46, previous: 39, unit: "个" },
        { label: "等待录入客户", value: 21, previous: 28, unit: "个" }
      ],
      line: {
        labels: ["第1周", "第2周", "第3周", "第4周"],
        current: [18, 25, 34, 41],
        previous: [14, 21, 28, 31]
      },
      stages: [
        { label: "新线索", current: 18, previous: 16 },
        { label: "已联系", current: 27, previous: 23 },
        { label: "需求确认", current: 19, previous: 14 },
        { label: "报价中", current: 11, previous: 9 },
        { label: "已成交", current: 11, previous: 8 }
      ]
    },
    year: {
      label: "今年",
      previousLabel: "去年",
      metrics: [
        { label: "开发成功客户", value: 74, previous: 58, unit: "个" },
        { label: "正在推进客户", value: 162, previous: 141, unit: "个" },
        { label: "等待录入客户", value: 49, previous: 67, unit: "个" }
      ],
      line: {
        labels: ["1月", "2月", "3月", "4月", "5月", "6月"],
        current: [64, 88, 109, 127, 149, 171],
        previous: [52, 67, 82, 100, 118, 135]
      },
      stages: [
        { label: "新线索", current: 74, previous: 58 },
        { label: "已联系", current: 108, previous: 96 },
        { label: "需求确认", current: 89, previous: 71 },
        { label: "报价中", current: 63, previous: 55 },
        { label: "已成交", current: 74, previous: 58 }
      ]
    }
  },

  workbenchCustomers: {
    week: {
      label: "本周",
      rows: [
        { id: "blueharbor", name: "BlueHarbor Imports", stage: "需求确认中", updatedAt: "06-15 09:42", owner: "Elena Wang", signal: "报价待补充" },
        { id: "pacific-star", name: "Pacific Star Logistics", stage: "报价后跟进", updatedAt: "06-14 18:05", owner: "David Liu", signal: "入仓预约" },
        { id: "ningbo-atlas", name: "Ningbo Atlas Forwarding", stage: "合作谈判中", updatedAt: "06-14 15:31", owner: "Amy Zhao", signal: "附件重试" }
      ]
    },
    month: {
      label: "本月",
      rows: [
        { id: "blueharbor", name: "BlueHarbor Imports", stage: "需求确认中", updatedAt: "06-15 09:42", owner: "Elena Wang", signal: "高价值机会" },
        { id: "northstar-retail", name: "Northstar Retail Group", stage: "报价中", updatedAt: "06-12 16:20", owner: "Elena Wang", signal: "欧洲线询价" },
        { id: "pacific-star", name: "Pacific Star Logistics", stage: "报价后跟进", updatedAt: "06-10 11:18", owner: "David Liu", signal: "代理协同" },
        { id: "ningbo-atlas", name: "Ningbo Atlas Forwarding", stage: "合作谈判中", updatedAt: "06-08 10:04", owner: "Amy Zhao", signal: "供应报价" }
      ]
    },
    year: {
      label: "本年",
      rows: [
        { id: "blueharbor", name: "BlueHarbor Imports", stage: "需求确认中", updatedAt: "06-15 09:42", owner: "Elena Wang", signal: "持续开发" },
        { id: "pacific-star", name: "Pacific Star Logistics", stage: "报价后跟进", updatedAt: "06-10 11:18", owner: "David Liu", signal: "复购机会" },
        { id: "northstar-retail", name: "Northstar Retail Group", stage: "报价中", updatedAt: "05-28 14:35", owner: "Elena Wang", signal: "新航线" },
        { id: "gulfmart", name: "GulfMart Trading", stage: "已成交", updatedAt: "05-16 17:10", owner: "David Liu", signal: "首单完成" },
        { id: "ningbo-atlas", name: "Ningbo Atlas Forwarding", stage: "合作谈判中", updatedAt: "04-22 09:20", owner: "Amy Zhao", signal: "供应网络" }
      ]
    }
  },

  customerTopicProfiles: [
    {
      id: "blueharbor",
      name: "BlueHarbor Imports",
      summary: "美国进口商，重点推进 Ningbo -> LAX 40HQ 报价和目的港费用说明。",
      contacts: [
        {
          name: "Lucy Chen",
          role: "采购经理",
          channel: "邮件 IMCP + WhatsApp Business API",
          profile: "关注总成本、船期稳定性和目的港收费透明度。",
          topics: [
            { name: "洛杉矶航线开发", source: "邮件 IMCP + WhatsApp Business API", stage: "进行中", updatedAt: "06-15", note: "Lucy 多次追问 Ningbo -> LAX 40HQ 报价和船期。", timeline: [["06-12", "新线索", "Lucy 首次询问 LAX 航线报价"], ["06-14", "已联系", "WhatsApp Business API 补充 40HQ 和船期要求"], ["06-15", "需求确认中", "邮件 IMCP 明确燃油附加费和目的港杂费"]] },
            { name: "目的港费用说明", source: "邮件 IMCP", stage: "待补充", updatedAt: "06-14", note: "Lucy 要求报价里单列目的港 handling 和杂费。", timeline: [["06-13", "已联系", "询问总价是否包含目的港费用"], ["06-14", "待补充", "邮件 IMCP 要求 destination handling 单独列明"]] }
          ]
        },
        {
          name: "Mike Howard",
          role: "运营负责人",
          channel: "电话录入",
          profile: "更关注到港时效、仓库预约和异常响应。",
          topics: [
            { name: "LAX 入仓预约", source: "电话录入", stage: "待确认", updatedAt: "06-13", note: "Mike 在电话中强调仓库预约窗口必须提前确认。", timeline: [["06-11", "新线索", "Mike 提到仓库窗口紧张"], ["06-13", "待确认", "电话录入记录需确认 LAX 仓库接收时间"]] },
            { name: "滞箱费风险", source: "电话录入", stage: "观察中", updatedAt: "06-11", note: "Mike 关注到港后提柜延迟造成的额外费用。", timeline: [["06-10", "风险识别", "提到上一次目的港产生 demurrage"], ["06-11", "观察中", "要求报价补充免箱期说明"]] }
          ]
        }
      ]
    },
    {
      id: "pacific-star",
      name: "Pacific Star Logistics",
      summary: "海外代理，当前重点是入仓预约、装箱单提交和异常响应节奏。",
      contacts: [
        {
          name: "Marco Ruiz",
          role: "海外仓协同人",
          channel: "WhatsApp Business API + 电话录入",
          profile: "更关注入仓预约和装箱单提前提交。",
          topics: [
            { name: "Pacific 入仓异常跟进", source: "WhatsApp Business API", stage: "暂停", updatedAt: "06-14", note: "Marco 的 WhatsApp Business API 记录显示入仓动作依赖装箱单。", timeline: [["06-10", "新线索", "Marco 提到入仓窗口需要预约"], ["06-13", "进行中", "确认周三上午可收货"], ["06-14", "暂停", "等待周二中午前装箱单"]] },
            { name: "入仓窗口确认", source: "WhatsApp Business API + 电话录入", stage: "进行中", updatedAt: "06-14", note: "Marco 通过 WhatsApp Business API 和电话录入确认可收货时间。", timeline: [["06-13", "已联系", "电话录入确认仓库本周可接收"], ["06-14", "进行中", "WhatsApp Business API 明确周三上午窗口"]] }
          ]
        },
        {
          name: "Sofia Gomez",
          role: "客服窗口",
          channel: "邮件 IMCP",
          profile: "负责异常升级和 POD 文件确认。",
          topics: [
            { name: "POD 文件回传", source: "邮件 IMCP", stage: "待办", updatedAt: "06-12", note: "Sofia 通过邮件 IMCP 承诺首单完成后回传签收文件。", timeline: [["06-12", "待办", "邮件 IMCP 确认 POD 文件回传责任人"]] }
          ]
        }
      ]
    },
    {
      id: "ningbo-atlas",
      name: "Ningbo Atlas Forwarding",
      summary: "宁波本地供应商，报价模板和附件解析质量影响后续供应协同。",
      contacts: [
        {
          name: "Amy Zhao",
          role: "报价窗口",
          channel: "企业微信 API",
          profile: "能快速给价，但报价附件格式不稳定。",
          topics: [
            { name: "报价模板修正", source: "企业微信", stage: "待确认", updatedAt: "06-14", note: "Amy 的企业微信沟通显示报价模板格式需要统一。", timeline: [["06-13", "问题识别", "附件解析失败，定位到旧模板"], ["06-14", "待确认", "要求补发标准模板后再入库"]] },
            { name: "附件解析重试", source: "企业微信 + 附件", stage: "进行中", updatedAt: "06-14", note: "Amy 提供的旧版报价表触发附件解析重试。", timeline: [["06-14", "失败可重试", "旧版表格抽取失败"], ["06-14", "进行中", "等待标准模板后重新解析"]] }
          ]
        },
        {
          name: "Kevin Xu",
          role: "订舱协同",
          channel: "企业微信 API",
          profile: "负责船期和舱位确认。",
          topics: [
            { name: "宁波船期确认", source: "企业微信", stage: "进行中", updatedAt: "06-13", note: "Kevin 的企业微信记录用于确认本周可用船期。", timeline: [["06-12", "已联系", "询问宁波本周舱位"], ["06-13", "进行中", "补充本周可用船期"]] }
          ]
        }
      ]
    },
    {
      id: "northstar-retail",
      name: "Northstar Retail Group",
      summary: "欧洲零售客户，正在比较汉堡和鹿特丹两条航线。",
      contacts: [
        {
          name: "Anna Keller",
          role: "采购负责人",
          channel: "邮件 IMCP",
          profile: "偏好清晰的价格版本和交付时效对比。",
          topics: [
            { name: "欧洲线报价", source: "邮件 IMCP", stage: "报价中", updatedAt: "06-12", note: "Anna 邮件 IMCP 要求 Hamburg / Rotterdam 双方案。", timeline: [["06-10", "新线索", "Anna 首次询问欧洲线"], ["06-12", "报价中", "确认 Hamburg / Rotterdam 双方案"]] },
            { name: "旺季附加费说明", source: "邮件 IMCP", stage: "待补充", updatedAt: "06-11", note: "Anna 通过邮件 IMCP 要求 peak season surcharge 单独说明。", timeline: [["06-11", "待补充", "邮件 IMCP 要求单列旺季附加费"]] }
          ]
        }
      ]
    },
    {
      id: "gulfmart",
      name: "GulfMart Trading",
      summary: "中东贸易客户，首单已成交，后续关注复购和到港异常。",
      contacts: [
        {
          name: "Omar Said",
          role: "物流经理",
          channel: "WhatsApp Business API",
          profile: "关注清关文件完整性和目的港响应速度。",
          topics: [
            { name: "首单复盘", source: "WhatsApp Business API", stage: "已成交", updatedAt: "05-16", note: "Omar 在 WhatsApp Business API 中反馈首单已完成并愿意复购。", timeline: [["05-10", "试单中", "确认首单发运"], ["05-16", "已成交", "反馈收货完成并记录复购机会"]] },
            { name: "清关资料清单", source: "WhatsApp + 文件", stage: "已确认", updatedAt: "05-12", note: "Omar 提供清关文件要求，形成下一单模板。", timeline: [["05-11", "资料收集", "收到清关资料要求"], ["05-12", "已确认", "确认下一单沿用清单模板"]] }
          ]
        }
      ]
    }
  ],
  contacts: [
    {
      name: "Lucy Chen",
      partner: "BlueHarbor Imports",
      role: "采购经理",
      identity: "客户",
      channel: "邮件 IMCP + WhatsApp Business API",
      confidence: 86,
      status: "待确认",
      profile: "关注总成本、船期稳定性和目的港收费透明度。回复节奏较快，适合给结构化报价和明确下一步。",
      topics: [
        { name: "洛杉矶航线开发", source: "邮件 IMCP + WhatsApp Business API", stage: "进行中", updatedAt: "06-15", note: "Lucy 的邮件 IMCP 和 WhatsApp Business API 汇总出 Ningbo -> LAX 40HQ 报价 Topic" },
        { name: "目的港费用说明", source: "邮件 IMCP", stage: "待补充", updatedAt: "06-14", note: "Lucy 邮件 IMCP 要求单列 destination handling" }
      ]
    },
    {
      name: "Marco Ruiz",
      partner: "Pacific Star Logistics",
      role: "海外仓协调人",
      identity: "代理",
      channel: "WhatsApp Business API + 电话录入",
      confidence: 92,
      status: "已确认",
      profile: "更关注入仓预约和异常响应，需要提前拿到装箱单。",
      topics: [
        { name: "Pacific 入仓异常跟进", source: "WhatsApp Business API", stage: "暂停", updatedAt: "06-14", note: "Marco 的 WhatsApp Business API 记录显示入仓动作依赖装箱单" },
        { name: "入仓窗口确认", source: "WhatsApp Business API + 电话录入", stage: "进行中", updatedAt: "06-14", note: "Marco 通过 WhatsApp Business API 和电话录入确认可收货时间" }
      ]
    },
    {
      name: "Amy Zhao",
      partner: "Ningbo Atlas Forwarding",
      role: "报价窗口",
      identity: "供应商",
      channel: "企业微信 API",
      confidence: 78,
      status: "待补充",
      profile: "能快速给价，但报价附件格式不稳定。",
      topics: [
        { name: "报价模板修正", source: "企业微信", stage: "待确认", updatedAt: "06-14", note: "Amy 的企业微信沟通显示报价模板格式需要统一" },
        { name: "附件解析重试", source: "企业微信 + 附件", stage: "进行中", updatedAt: "06-14", note: "Amy 提供的旧版报价表触发附件解析重试" }
      ]
    }
  ],

  messages: [
    {
      id: "mail-001",
      channel: "邮件 IMCP",
      mode: "自动同步",
      title: "Ningbo to LAX 40HQ rate request",
      partner: "BlueHarbor Imports",
      contact: "Lucy Chen",
      time: "2026-06-15 09:42",
      status: "待确认",
      confidence: 86,
      summary: "客户希望获取宁波到洛杉矶 1x40HQ 报价，要求列明燃油附加费、目的港杂费和预计船期。",
      raw: "Hi Elena,\n\nCould you help quote Ningbo to LAX for 1x40HQ this week? Please include fuel surcharge, destination handling and estimated sailing schedule.\n\nBest,\nLucy Chen\nBlueHarbor Imports",
      facts: ["POL: Ningbo", "POD: LAX", "柜型: 1x40HQ", "时效: 本周报价", "费用: 燃油附加费/目的港杂费"]
    },
    {
      id: "wa-001",
      channel: "WhatsApp Business API",
      mode: "当日聊天聚合",
      title: "Marco 06-14 WhatsApp 当日聊天总结",
      partner: "Pacific Star Logistics",
      contact: "Marco Ruiz",
      time: "2026-06-14 18:05",
      status: "已归档",
      confidence: 91,
      summary: "AI 将 Marco 06-14 当天 WhatsApp 聊天聚合为一个信息页：对方确认周三上午可入仓，但要求周二中午前发送装箱单，并提醒入仓窗口需要提前锁定。",
      raw: "09:18 Marco: Warehouse may receive on Wednesday morning.\n11:42 Marco: Please send packing list before Tuesday noon.\n15:30 Marco: If PL is late, receiving slot may move to Friday.\n18:05 Marco: Wednesday morning slot is still available for now.",
      facts: ["聚合方式: 同一联系人同一天", "节点: 入仓", "时间: 周三上午", "待办: 周二中午前发装箱单"]
    },
    {
      id: "wa-002",
      channel: "WhatsApp Business API",
      mode: "当日聊天聚合",
      title: "Marco 06-13 WhatsApp 装箱单提醒",
      partner: "Pacific Star Logistics",
      contact: "Marco Ruiz",
      time: "2026-06-13 17:40",
      status: "待确认",
      confidence: 84,
      summary: "Marco 当天多次提醒装箱单需要提前给仓库，否则周三上午窗口可能被释放。",
      raw: "10:20 Marco: Please send PL as early as possible.\n14:12 Marco: Warehouse needs PL before slot confirmation.\n17:40 Marco: If PL is late, Wednesday slot may not hold.",
      facts: ["聚合方式: 同一联系人同一天", "文件: 装箱单", "风险: 入仓窗口释放", "节点: 周三上午"]
    },
    {
      id: "wa-003",
      channel: "WhatsApp Business API",
      mode: "当日聊天聚合",
      title: "Marco 06-12 WhatsApp 入仓资料确认",
      partner: "Pacific Star Logistics",
      contact: "Marco Ruiz",
      time: "2026-06-12 16:15",
      status: "已归档",
      confidence: 88,
      summary: "Marco 确认入仓资料需要包含装箱单、唛头照片和收货预约编号。",
      raw: "09:30 Marco: Need packing list and mark photo.\n13:05 Marco: Appointment number is also required.\n16:15 Marco: Send all docs in one email if possible.",
      facts: ["资料: 装箱单", "资料: 唛头照片", "资料: 预约编号", "建议: 合并发送"]
    },
    {
      id: "wa-004",
      channel: "WhatsApp Business API",
      mode: "当日聊天聚合",
      title: "Marco 06-11 WhatsApp 预约窗口沟通",
      partner: "Pacific Star Logistics",
      contact: "Marco Ruiz",
      time: "2026-06-11 19:10",
      status: "已归档",
      confidence: 82,
      summary: "Marco 表示仓库本周窗口紧张，建议先锁定周三上午，再根据资料完整度确认最终入仓。",
      raw: "11:22 Marco: Warehouse slots are tight this week.\n15:02 Marco: Wednesday morning may work.\n19:10 Marco: Final confirmation depends on complete documents.",
      facts: ["窗口: 周三上午", "风险: 仓库窗口紧张", "条件: 资料完整", "动作: 先锁定预约"]
    },
    {
      id: "wecom-001",
      channel: "企业微信 API",
      mode: "当日聊天聚合",
      title: "Amy 06-14 企业微信当日聊天总结",
      partner: "Ningbo Atlas Forwarding",
      contact: "Amy Zhao",
      time: "2026-06-14 15:31",
      status: "失败可重试",
      confidence: 64,
      summary: "AI 将 Amy 06-14 当天企业微信聊天与附件信息聚合为一个信息页：报价表已收到并保存，但旧版表格抽取失败，需要员工确认后重试或要求补发标准模板。",
      raw: "10:12 Amy: 我先发本周五前有效的费率表。\n10:14 Amy: 附件是旧模板，格式可能和你们标准不一样。\n14:50 Amy: 如果打不开我可以补一个新版。\n15:31 系统: 附件已保存，文本抽取失败，可重试。",
      facts: ["聚合方式: 同一联系人同一天", "附件: 费率表", "存储: MinIO", "异常: 文本抽取失败"]
    },
    {
      id: "call-001",
      channel: "电话录入",
      mode: "人工录入",
      title: "试单意向电话记录",
      partner: "Pacific Star Logistics",
      contact: "Marco Ruiz",
      time: "2026-06-13 11:20",
      status: "待确认",
      confidence: 72,
      summary: "电话中确认客户愿意先做试单，但需要先拿到入仓窗口和装箱单截止时间。",
      raw: "Marco 电话说明：可以先做一票试单，周三上午仓库可以收货，但周二中午前必须提供装箱单。",
      facts: ["来源: 电话录入", "意向: 试单", "节点: 周二中午前装箱单", "入仓: 周三上午"]
    }
  ],

  topics: [
    {
      name: "洛杉矶航线开发",
      partner: "BlueHarbor Imports",
      status: "进行中",
      priority: "高",
      current: "已确认客户需要 Ningbo -> LAX 40HQ 报价，等待补充报价方案。",
      events: [
        ["06-12", "识别 BlueHarbor Imports 初次询价，创建候选合作方"],
        ["06-14", "WhatsApp Business API 记录显示客户关注洛杉矶仓库入仓窗口"],
        ["06-15", "AI 建议将新邮件关联到洛杉矶航线开发 Topic"]
      ]
    },
    {
      name: "Pacific 入仓异常跟进",
      partner: "Pacific Star Logistics",
      status: "暂停",
      priority: "中",
      current: "入仓预约等待代理回复，已产生逾期提醒。",
      events: [
        ["06-10", "创建入仓异常 Topic"],
        ["06-13", "电话录入确认客户愿意先做试单"],
        ["06-14", "WhatsApp 确认装箱单发送截止时间"]
      ]
    }
  ],

  tasks: [
    { title: "补充 Ningbo -> LAX 40HQ 报价", owner: "Elena Wang", due: "今天 10:00", status: "待确认", partner: "BlueHarbor Imports" },
    { title: "发送 Pacific 入仓装箱单", owner: "David Liu", due: "今天 12:00", status: "待办", partner: "Pacific Star Logistics" },
    { title: "要求 Atlas 补发标准报价模板", owner: "Amy Zhao", due: "明天 11:30", status: "进行中", partner: "Ningbo Atlas Forwarding" },
    { title: "复盘目的港杂费投诉案例", owner: "Elena Wang", due: "已逾期 1 天", status: "逾期", partner: "BlueHarbor Imports" }
  ],

  suggestions: [
    {
      minStep: 0,
      type: "身份识别",
      target: "Lucy Chen",
      confidence: 86,
      status: "待确认",
      content: "Lucy Chen 可能是 BlueHarbor Imports 的采购经理，负责洛杉矶航线报价沟通。",
      evidence: "发件域名、邮件 IMCP 签名、历史 WhatsApp Business API 昵称和询价主题一致。",
      write: "确认后写入联系人关系、合作方关联和审计日志。"
    },
    {
      minStep: 2,
      type: "Topic 建议",
      target: "洛杉矶航线开发",
      confidence: 82,
      status: "待确认",
      content: "建议将新邮件关联到已有 Topic: 洛杉矶航线开发，并新增一条时间线事件。",
      evidence: "合作方、港口、航线和此前两次沟通主题相同。",
      write: "确认后写入 topic_events，不覆盖原始沟通。"
    },
    {
      minStep: 4,
      type: "任务与阶段建议",
      target: "BlueHarbor Imports",
      confidence: 79,
      status: "待确认",
      content: "建议创建报价任务，并将合作阶段推进到需求确认中。",
      evidence: "客户已明确航线、柜型、费用关注点和报价时效，但尚未完成报价。",
      write: "确认后同一事务写入 tasks、partners.stage、stage_histories 和 audit_logs。"
    },
    {
      minStep: 5,
      type: "看板同步",
      target: "个人工作台 / 团队看板",
      confidence: 100,
      status: "已同步",
      content: "已将确认后的任务、阶段和 Topic 事件反映到工作台与团队看板。",
      evidence: "看板只消费正式业务对象，不直接消费未确认 AI 建议。",
      write: "刷新工作台统计和团队聚合结果。"
    }
  ],

  issues: [
    { title: "目的港杂费被客户质疑", type: "报价", status: "已沉淀", reuse: "报价前单列费用说明，避免客户只看总价。" },
    { title: "海外仓入仓预约变更", type: "时效", status: "处理中", reuse: "要求代理在预约前 24 小时二次确认。" },
    { title: "供应商附件格式异常", type: "资料", status: "待分类", reuse: "优先要求标准模板，附件失败不阻断正文入库。" }
  ],

  dashboard: {
    metrics: [
      ["新增合作方", "18", "本周"],
      ["活跃 Topic", "42", "+9"],
      ["逾期任务", "7", "需主管关注"],
      ["待确认 AI", "23", "员工处理"],
      ["高价值机会", "11", "报价阶段"],
      ["高风险合作方", "4", "附件/时效/投诉"]
    ],
    stage: [
      ["新线索", 12],
      ["已建立联系", 18],
      ["需求确认中", 19],
      ["待报价", 8],
      ["报价后跟进", 14],
      ["合作谈判中", 6]
    ],
    team: [
      ["Elena Wang", "9 待办", "2 逾期", "7 AI 待确认"],
      ["David Liu", "6 待办", "3 逾期", "4 AI 待确认"],
      ["Amy Zhao", "5 待办", "1 附件异常", "3 AI 待确认"]
    ]
  },

  settings: [
    ["角色权限", "employee / supervisor / boss / admin", "服务端强校验"],
    ["数据范围", "本人 / 团队 / 公司 / 指定成员", "前端只展示，不做真相"],
    ["渠道账号", "邮件 IMCP、WhatsApp Business API、企业微信 API、电话录入", "统一进入信息管理"],
    ["AI 参数", "模型、超时、重试、限流", "失败可重试"],
    ["字典配置", "合作方类型、阶段、任务类型、风险等级", "前端从字典读取"],
    ["审计日志", "原文查看、建议确认、阶段变更", "敏感动作留痕"]
  ]
};
// 展示 demo 扩展样本：用于合作方管理、信息管理和联系人 Topic 演示。
(() => {
  const data = window.CRM_SHOWCASE_DATA;
  const extraPartners = [
    { id: "aurora-home", name: "Aurora Home Living", type: "客户", country: "加拿大", city: "Vancouver", owner: "Elena Wang", stage: "已建立联系", value: "中价值", risk: "低风险", tags: ["家居", "加拿大", "海运拼箱", "旺季补货"], summary: "加拿大家居进口商，正在评估华南到 Vancouver 的拼箱和整柜组合方案。", nextAction: "发送 LCL 与 20GP 成本对比" },
    { id: "rhein-cargo", name: "Rhein Cargo GmbH", type: "海外代理", country: "德国", city: "Hamburg", owner: "David Liu", stage: "需求确认中", value: "高价值机会", risk: "中风险", tags: ["欧洲", "铁路", "汉堡", "清关"], summary: "德国代理，关注中欧铁路时效和汉堡港拥堵替代方案。", nextAction: "确认铁路班列舱位和目的港清关责任" },
    { id: "santos-foods", name: "Santos Foods Import", type: "客户", country: "巴西", city: "Santos", owner: "Marco Lin", stage: "报价中", value: "高价值机会", risk: "汇率风险", tags: ["食品", "冷链", "南美", "Santos"], summary: "巴西食品进口商，询问冷链柜、目的港查验和汇率有效期。", nextAction: "补充冷链附加费与报价有效期" },
    { id: "shenzhen-orbit", name: "Shenzhen Orbit Supply", type: "供应商", country: "中国", city: "深圳", owner: "Amy Zhao", stage: "供应评估", value: "中价值", risk: "报价波动", tags: ["拖车", "深圳", "蛇口", "供应商"], summary: "深圳本地拖车供应商，报价响应快但旺季价格波动较大。", nextAction: "确认蛇口/盐田旺季拖车附加费" },
    { id: "atlas-med", name: "AtlasMed Devices", type: "客户", country: "阿联酋", city: "Dubai", owner: "David Liu", stage: "试单中", value: "高价值机会", risk: "文件风险", tags: ["医疗器械", "Dubai", "空运", "清关文件"], summary: "迪拜医疗器械客户，试单关注空运时效、清关文件和温控证明。", nextAction: "整理空运文件清单并确认温控证明" },
    { id: "kanto-retail", name: "Kanto Retail Chain", type: "客户", country: "日本", city: "Tokyo", owner: "Elena Wang", stage: "新线索", value: "潜在机会", risk: "待识别", tags: ["日本", "零售", "时效", "小批量"], summary: "日本零售连锁新线索，初步咨询小批量补货和门到门时效。", nextAction: "确认首批 SKU 数量和派送城市" }
  ];
  data.partners.push(...extraPartners);

  const extraProfiles = [
    { id: "aurora-home", name: "Aurora Home Living", summary: "加拿大家居客户，关注旺季补货、拼箱成本和目的港派送稳定性。", contacts: [
      { name: "Mia Thompson", role: "采购主管", channel: "邮件 IMCP + WhatsApp Business API", profile: "喜欢对比表和明确报价有效期，常追问旺季空间。", topics: [
        { name: "Vancouver 旺季补货", source: "邮件 IMCP", stage: "已联系", updatedAt: "06-13", note: "Mia 要求比较 LCL 与 20GP 成本。", timeline: [["06-11", "新线索", "询问 Vancouver 补货方案"], ["06-13", "已联系", "确认需要 LCL/20GP 对比"]] },
        { name: "目的港派送范围", source: "WhatsApp Business API", stage: "待补充", updatedAt: "06-12", note: "Mia 关注 Richmond 和 Burnaby 派送附加费。", timeline: [["06-12", "待补充", "询问两地派送价差"]] }
      ]},
      { name: "Noah Reed", role: "仓储协调", channel: "电话录入", profile: "关注入仓预约、托盘要求和送仓窗口。", topics: [
        { name: "加拿大仓库预约", source: "电话录入", stage: "进行中", updatedAt: "06-10", note: "Noah 电话确认仓库只接受工作日上午预约。", timeline: [["06-10", "进行中", "确认送仓窗口"]] }
      ]}
    ]},
    { id: "rhein-cargo", name: "Rhein Cargo GmbH", summary: "德国代理，关注中欧铁路、汉堡拥堵替代和清关责任划分。", contacts: [
      { name: "Lukas Weber", role: "欧洲线路经理", channel: "邮件 IMCP", profile: "重视时效可信度和异常预案。", topics: [
        { name: "汉堡拥堵替代方案", source: "邮件 IMCP", stage: "需求确认中", updatedAt: "06-14", note: "Lukas 要求补充 Rotterdam 替代路径。", timeline: [["06-12", "已联系", "反馈 Hamburg 拥堵"], ["06-14", "需求确认中", "要求 Rotterdam 备选"]] },
        { name: "中欧铁路班列舱位", source: "企业微信 API", stage: "报价中", updatedAt: "06-13", note: "Lukas 询问六月下旬班列可用舱位。", timeline: [["06-13", "报价中", "等待铁路报价"]] }
      ]}
    ]},
    { id: "santos-foods", name: "Santos Foods Import", summary: "巴西食品客户，关注冷链柜、查验、汇率和报价有效期。", contacts: [
      { name: "Camila Rocha", role: "进口经理", channel: "WhatsApp Business API", profile: "对价格敏感，但愿意为冷链稳定性付费。", topics: [
        { name: "Santos 冷链报价", source: "WhatsApp Business API", stage: "报价中", updatedAt: "06-15", note: "Camila 要求列明 reefer surcharge 和插电费。", timeline: [["06-13", "新线索", "询问冷链柜"], ["06-15", "报价中", "确认费用拆分"]] },
        { name: "巴西查验文件", source: "邮件 IMCP", stage: "待确认", updatedAt: "06-14", note: "客户要求提前确认食品类文件。", timeline: [["06-14", "待确认", "收集清关文件要求"]] }
      ]}
    ]},
    { id: "shenzhen-orbit", name: "Shenzhen Orbit Supply", summary: "深圳拖车供应商，适合用于华南起运港供应协同。", contacts: [
      { name: "Chen Rui", role: "调度经理", channel: "企业微信 API + 电话录入", profile: "响应快，需提前锁定旺季附加费。", topics: [
        { name: "蛇口拖车旺季价", source: "企业微信 API", stage: "待确认", updatedAt: "06-12", note: "Chen Rui 报价包含临时拥堵费，需要员工确认。", timeline: [["06-12", "待确认", "等待书面报价"]] }
      ]}
    ]},
    { id: "atlas-med", name: "AtlasMed Devices", summary: "迪拜医疗器械客户，当前试单关注空运和清关文件。", contacts: [
      { name: "Aisha Khan", role: "供应链负责人", channel: "邮件 IMCP + 电话录入", profile: "关注文件完整性、温控证明和航班截单时间。", topics: [
        { name: "Dubai 医疗器械空运试单", source: "邮件 IMCP", stage: "试单中", updatedAt: "06-15", note: "Aisha 要求空运文件清单和温控证明模板。", timeline: [["06-13", "资料收集", "收到产品类别"], ["06-15", "试单中", "确认空运文件清单"]] },
        { name: "温控证明模板", source: "电话录入", stage: "待补充", updatedAt: "06-14", note: "电话中确认客户需要温控证明样例。", timeline: [["06-14", "待补充", "准备模板"]] }
      ]}
    ]},
    { id: "kanto-retail", name: "Kanto Retail Chain", summary: "日本零售新线索，关注小批量补货和门到门时效。", contacts: [
      { name: "Yuki Tanaka", role: "采购窗口", channel: "邮件 IMCP", profile: "回复谨慎，偏好清晰时效承诺和日文资料。", topics: [
        { name: "东京门到门小批量", source: "邮件 IMCP", stage: "新线索", updatedAt: "06-09", note: "Yuki 初步询问深圳到东京门到门小批量补货。", timeline: [["06-09", "新线索", "收到首封询价"]] }
      ]}
    ]}
  ];
  data.customerTopicProfiles.push(...extraProfiles);

  const extraMessages = [
    { id: "aur-mail-001", channel: "邮件 IMCP", mode: "自动同步", title: "Vancouver LCL and 20GP cost comparison", partner: "Aurora Home Living", contact: "Mia Thompson", time: "2026-06-13 10:25", status: "待确认", confidence: 83, summary: "客户要求比较 LCL 与 20GP 的成本、时效和目的港派送差异。", raw: "Hi Elena, please compare LCL and 20GP to Vancouver for July replenishment.", facts: ["目的港: Vancouver", "方案: LCL/20GP", "时间: 7月补货", "派送: Richmond/Burnaby"] },
    { id: "aur-wa-001", channel: "WhatsApp Business API", mode: "当日聊天聚合", title: "Mia 06-12 WhatsApp 派送范围总结", partner: "Aurora Home Living", contact: "Mia Thompson", time: "2026-06-12 18:10", status: "已归档", confidence: 87, summary: "Mia 当天集中询问 Richmond 和 Burnaby 派送是否有附加费。", raw: "Mia: Can you include Richmond and Burnaby delivery cost?", facts: ["派送: Richmond", "派送: Burnaby", "关注: 附加费"] },
    { id: "rhein-mail-001", channel: "邮件 IMCP", mode: "自动同步", title: "Hamburg congestion alternative request", partner: "Rhein Cargo GmbH", contact: "Lukas Weber", time: "2026-06-14 16:40", status: "待确认", confidence: 81, summary: "代理要求补充 Hamburg 拥堵下 Rotterdam 替代方案和责任边界。", raw: "Please prepare an alternative routing via Rotterdam due to Hamburg congestion.", facts: ["港口: Hamburg", "备选: Rotterdam", "关注: 清关责任"] },
    { id: "rhein-wecom-001", channel: "企业微信 API", mode: "当日聊天聚合", title: "Lukas 06-13 铁路舱位沟通", partner: "Rhein Cargo GmbH", contact: "Lukas Weber", time: "2026-06-13 17:15", status: "已归档", confidence: 78, summary: "Lukas 询问六月下旬中欧铁路班列舱位和延误风险。", raw: "六月下旬还有铁路舱位吗？如果延误超过 5 天怎么处理？", facts: ["线路: 中欧铁路", "时间: 六月下旬", "风险: 延误"] },
    { id: "santos-wa-001", channel: "WhatsApp Business API", mode: "当日聊天聚合", title: "Camila 06-15 Santos 冷链报价总结", partner: "Santos Foods Import", contact: "Camila Rocha", time: "2026-06-15 11:35", status: "待确认", confidence: 86, summary: "Camila 要求冷链报价拆出 reefer surcharge、插电费和查验可能费用。", raw: "Please split reefer surcharge, plug-in fee and inspection possibility.", facts: ["柜型: Reefer", "目的港: Santos", "费用: 插电费", "风险: 查验"] },
    { id: "santos-mail-001", channel: "邮件 IMCP", mode: "自动同步", title: "Food import document checklist", partner: "Santos Foods Import", contact: "Camila Rocha", time: "2026-06-14 09:28", status: "已归档", confidence: 80, summary: "客户询问食品进口文件和巴西目的港查验准备。", raw: "Could you send the checklist for food import documents before booking?", facts: ["品类: 食品", "文件: 清关清单", "目的国: 巴西"] },
    { id: "orbit-wecom-001", channel: "企业微信 API", mode: "当日聊天聚合", title: "Chen Rui 06-12 蛇口拖车报价", partner: "Shenzhen Orbit Supply", contact: "Chen Rui", time: "2026-06-12 14:08", status: "待确认", confidence: 74, summary: "供应商给出蛇口拖车报价，但旺季拥堵费需单独确认。", raw: "蛇口拖车基础价可做，旺季拥堵费要看当天排队。", facts: ["港口: 蛇口", "费用: 拥堵费", "类型: 拖车"] },
    { id: "orbit-call-001", channel: "电话录入", mode: "人工录入", title: "盐田夜间提柜电话确认", partner: "Shenzhen Orbit Supply", contact: "Chen Rui", time: "2026-06-11 20:30", status: "已归档", confidence: 71, summary: "电话确认盐田夜间提柜可做，但需提前一天提供柜号。", raw: "Chen Rui 电话说明：夜间提柜可安排，柜号和放行信息要提前一天给。", facts: ["港口: 盐田", "时间: 夜间", "要求: 提前给柜号"] },
    { id: "atlas-med-mail-001", channel: "邮件 IMCP", mode: "自动同步", title: "Dubai medical device air freight trial", partner: "AtlasMed Devices", contact: "Aisha Khan", time: "2026-06-15 15:20", status: "待确认", confidence: 84, summary: "客户要求空运试单报价，并确认温控证明和清关文件。", raw: "Please quote air freight trial to Dubai and confirm temperature control certificate.", facts: ["目的地: Dubai", "方式: 空运", "文件: 温控证明"] },
    { id: "atlas-med-call-001", channel: "电话录入", mode: "人工录入", title: "温控证明模板电话沟通", partner: "AtlasMed Devices", contact: "Aisha Khan", time: "2026-06-14 13:50", status: "待确认", confidence: 77, summary: "电话中客户要求先看温控证明模板，再决定是否走试单。", raw: "Aisha 电话说明：先发 certificate sample，再确认试单安排。", facts: ["文件: certificate sample", "阶段: 试单前确认"] },
    { id: "kanto-mail-001", channel: "邮件 IMCP", mode: "自动同步", title: "Tokyo door-to-door small batch inquiry", partner: "Kanto Retail Chain", contact: "Yuki Tanaka", time: "2026-06-09 10:05", status: "待确认", confidence: 69, summary: "日本零售客户初步询问深圳到东京小批量门到门服务。", raw: "We are checking small batch DDP style delivery from Shenzhen to Tokyo.", facts: ["起运: 深圳", "目的地: Tokyo", "模式: 门到门", "批量: 小批量"] }
  ];
  data.messages.push(...extraMessages);

  const extraTopics = extraProfiles.flatMap((profile) => profile.contacts.flatMap((contact) => contact.topics.map((topic) => ({
    name: topic.name,
    partner: profile.name,
    status: topic.stage || "进行中",
    priority: topic.stage === "报价中" || topic.stage === "试单中" ? "高" : "中",
    current: topic.note,
    events: topic.timeline.map((step) => [step[0], step[2] || step[1]])
  }))));
  data.topics.push(...extraTopics);

  data.workbenchCustomers.month.rows.push(
    { id: "aurora-home", name: "Aurora Home Living", stage: "已建立联系", updatedAt: "06-13 10:25", owner: "Elena Wang", signal: "加拿大补货" },
    { id: "santos-foods", name: "Santos Foods Import", stage: "报价中", updatedAt: "06-15 11:35", owner: "Marco Lin", signal: "冷链报价" },
    { id: "atlas-med", name: "AtlasMed Devices", stage: "试单中", updatedAt: "06-15 15:20", owner: "David Liu", signal: "空运试单" }
  );
  data.workbenchCustomers.year.rows.push(
    { id: "aurora-home", name: "Aurora Home Living", stage: "已建立联系", updatedAt: "06-13 10:25", owner: "Elena Wang", signal: "旺季补货" },
    { id: "rhein-cargo", name: "Rhein Cargo GmbH", stage: "需求确认中", updatedAt: "06-14 16:40", owner: "David Liu", signal: "欧洲代理" },
    { id: "santos-foods", name: "Santos Foods Import", stage: "报价中", updatedAt: "06-15 11:35", owner: "Marco Lin", signal: "南美冷链" },
    { id: "atlas-med", name: "AtlasMed Devices", stage: "试单中", updatedAt: "06-15 15:20", owner: "David Liu", signal: "医疗器械" },
    { id: "kanto-retail", name: "Kanto Retail Chain", stage: "新线索", updatedAt: "06-09 10:05", owner: "Elena Wang", signal: "日本小批量" }
  );
})();
