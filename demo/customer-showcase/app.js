(function () {
  const data = window.CRM_SHOWCASE_DATA;

  const state = {
    page: "workbench",
    role: "employee",
    step: 0,
    selectedPartner: "blueharbor",
    partnerView: "list",
    partnerDetailTab: "contacts",
    selectedContact: "Lucy Chen",
    selectedMessage: "mail-001",
    selectedTopic: "洛杉矶航线开发",
    selectedSuggestion: 0,
    selectedCustomerId: "blueharbor",
    selectedTaskPartner: "blueharbor",
    taskView: "follow",
    taskPartnerPickerOpen: false,
    taskTimeFilter: "all",
    taskPriorityOverrides: {},
    taskCompletedOverrides: {},
    taskExpanded: {},
    aiAssistantOpen: false,
    aiAssistantToolsOpen: false,
    settingsTab: "users",
    aggregate: "week",
    customerAggregate: "month",
    progressAggregate: "month",
    statsOpen: false,
    customerDetailOpen: false,
    channelFormOpen: false,
    messageDetailOpen: false,
    contactDetailOpen: false,
    contactDetailMode: "edit",
    selectedChannel: "WhatsApp Business API",
    informationCompanyQuery: "",
    contactSearchQuery: "",
    contactSearchComposing: false,
    companyChannelTabs: {},
    companyDateFilters: {},
    companyPages: {},
    companyCardsOpen: {},
    partnerListFilters: {
      name: "",
      owner: "",
      date: ""
    },
    partnerListFilterDrafts: {
      name: "",
      owner: "",
      date: ""
    },
    rawVisible: false,
    summaryEditVisible: false,
    topicPickerOpen: false,
    topicArchives: {},
    expandedPartnerTopic: "洛杉矶航线开发",
    accepted: {
      identity: false,
      topic: false,
      task: false,
      profile: false
    }
  };

  const initialParams = new URLSearchParams(window.location.search);
  if (data.nav.some((item) => item.id === initialParams.get("page"))) {
    state.page = initialParams.get("page");
  }
  if (data.roles.some((item) => item.id === initialParams.get("role"))) {
    state.role = initialParams.get("role");
  }
  if (initialParams.get("page") === "ai") {
    state.page = "settings";
  }
  if (data.messages.some((item) => item.id === initialParams.get("message"))) {
    state.selectedMessage = initialParams.get("message");
  }
  if (initialParams.get("messageDetail") === "1") {
    state.messageDetailOpen = true;
  }
  if (["week", "month", "year"].includes(initialParams.get("aggregate"))) {
    state.aggregate = initialParams.get("aggregate");
  }
  if (["week", "month", "year"].includes(initialParams.get("customerAggregate"))) {
    state.customerAggregate = initialParams.get("customerAggregate");
  }
  if (["day", "month", "year"].includes(initialParams.get("progressAggregate"))) {
    state.progressAggregate = initialParams.get("progressAggregate");
  }
  if (initialParams.get("customer")) {
    state.selectedCustomerId = initialParams.get("customer");
  }
  if (initialParams.get("taskPartner")) {
    state.selectedTaskPartner = initialParams.get("taskPartner");
  }
  if (["follow", "review", "done"].includes(initialParams.get("taskView"))) {
    state.taskView = initialParams.get("taskView");
  }
  if (initialParams.get("taskOpen") === "first") {
    state.taskExpanded.__first = true;
  } else if (initialParams.get("taskOpen")) {
    state.taskExpanded[initialParams.get("taskOpen")] = true;
  }
  if (["all", "today", "tomorrow", "week", "overdue"].includes(initialParams.get("taskTime"))) {
    state.taskTimeFilter = initialParams.get("taskTime");
  }
  if (data.partners.some((item) => item.id === initialParams.get("partner"))) {
    state.selectedPartner = initialParams.get("partner");
  }
  if (initialParams.get("partnerDetail") === "1") {
    state.partnerView = "detail";
  }
  if (["config", "website", "report", "order", "contacts"].includes(initialParams.get("partnerTab"))) {
    state.partnerDetailTab = initialParams.get("partnerTab");
  }
  if (initialParams.get("partnerContact")) {
    state.selectedContact = initialParams.get("partnerContact");
  }
  if (initialParams.get("contact")) {
    state.selectedContact = initialParams.get("contact");
  }
  if (initialParams.get("contactDetail") === "1") {
    state.contactDetailOpen = true;
  }
  if (initialParams.get("company")) {
    state.companyCardsOpen[initialParams.get("company")] = true;
  }

  const iconPaths = {
    workbench: `<path d="M4 5.5h16"/><path d="M7 5.5v13"/><path d="M4 18.5h16"/><path d="M12 9h5"/><path d="M12 13h5"/>`,
    communication: `<path d="M4 6.5h16v11H4z"/><path d="m4.5 7 7.5 5.5L19.5 7"/>`,
    partners: `<path d="M5 19V6.5l7-3 7 3V19"/><path d="M9 19v-5h6v5"/><path d="M8 9h1.5M14.5 9H16M8 12h1.5M14.5 12H16"/>`,
    contacts: `<path d="M8 19v-1.5c0-2 2-3.5 4-3.5s4 1.5 4 3.5V19"/><circle cx="12" cy="8" r="3"/><path d="M4 5h2M4 12h2M4 19h2M18 5h2M18 12h2M18 19h2"/>`,
    tasks: `<path d="M7 5h12v14H7z"/><path d="M4 8h3M4 13h3M4 18h3"/><path d="M10 9h5M10 13h6M10 17h4"/>`,
    dashboard: `<path d="M4 19V5"/><path d="M4 19h16"/><path d="M8 16v-5"/><path d="M12 16V8"/><path d="M16 16v-9"/>`,
    settings: `<path d="M12 15.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Z"/><path d="M19 13.5v-3l-2-.4a6.6 6.6 0 0 0-.6-1.5l1.1-1.7-2.1-2.1-1.7 1.1a6.6 6.6 0 0 0-1.5-.6L11.8 3h-3l-.4 2.3a6.6 6.6 0 0 0-1.5.6L5.2 4.8 3.1 6.9l1.1 1.7a6.6 6.6 0 0 0-.6 1.5L2 10.5v3l1.6.4c.1.5.3 1 .6 1.5l-1.1 1.7 2.1 2.1 1.7-1.1c.5.3 1 .5 1.5.6l.4 2.3h3l.4-2.3c.5-.1 1-.3 1.5-.6l1.7 1.1 2.1-2.1-1.1-1.7c.3-.5.5-1 .6-1.5l2-.4Z"/>`,
    follow: `<path d="M5 12h8"/><path d="m10 9 3 3-3 3"/><path d="M4 5h16v14H4z"/>`,
    review: `<path d="M6 5h12v14H6z"/><path d="M9 9h6M9 13h4"/><path d="m14 17 1.2 1.2L18 15.5"/>`,
    ended: `<path d="M5 5h14v14H5z"/><path d="m8.5 12 2.2 2.2 4.8-5"/><path d="M3 8h2M3 16h2"/>`,
    prev: `<path d="M15 6 9 12l6 6"/><path d="M9.5 12H20"/>`,
    next: `<path d="m9 6 6 6-6 6"/><path d="M4 12h10.5"/>`,
    users: `<path d="M8 19v-1.2c0-1.8 1.8-3.2 4-3.2s4 1.4 4 3.2V19"/><circle cx="12" cy="8" r="3"/><path d="M17 10.5c1.3.3 2.3 1.3 2.3 2.5V14"/><path d="M5 14v-1c0-1.2 1-2.2 2.3-2.5"/>`,
    dictionary: `<path d="M5 4h11a3 3 0 0 1 3 3v13H8a3 3 0 0 1-3-3V4Z"/><path d="M8 8h7M8 12h6"/>`,
    channels: `<path d="M4 7h16"/><path d="M4 12h12"/><path d="M4 17h8"/><circle cx="18" cy="12" r="2"/><circle cx="14" cy="17" r="2"/>`,
    ai: `<path d="M7 8h10v8H7z"/><path d="M9 4v4M15 4v4M9 16v4M15 16v4M4 10h3M4 14h3M17 10h3M17 14h3"/><path d="M10 12h.1M14 12h.1"/>`,
    template: `<path d="M5 5h14v14H5z"/><path d="M8 9h8M8 13h8M8 17h5"/><path d="M16 3v4"/>`,
    risk: `<path d="M12 3 21 19H3L12 3Z"/><path d="M12 9v4"/><path d="M12 16h.1"/>`,
    audit: `<path d="M6 4h12v16H6z"/><path d="M9 8h6M9 12h6M9 16h3"/><path d="m14 17 1.2 1.2L18 15"/>`,
    stats: `<path d="M4 19V5"/><path d="M4 19h16"/><path d="M8 16v-5M12 16V8M16 16v-9"/>`,
    customer: `<path d="M5 19V6l7-3 7 3v13"/><path d="M9 19v-5h6v5"/>`,
    default: `<path d="M5 5h14v14H5z"/><path d="M8 9h8M8 13h6"/>`
  };

  const iconSources = {
    workbench: "\u4eea\u8868\u76d8.svg",
    communication: "\u90ae\u4ef6.svg",
    partners: "\u516c\u6587\u5305.svg",
    contacts: "\u7528\u6237.svg",
    tasks: "\u5706\u5f62\u590d\u9009\u6846.svg",
    dashboard: "\u6f14\u793a\u56fe\u8868.svg",
    settings: "\u9f7f\u8f6e.svg",
    follow: "\u65d7\u5e1c.svg",
    review: "\u6536\u4ef6\u7bb1\u586b\u5145.svg",
    ended: "\u5706\u5f62\u590d\u9009\u6846.svg",
    prev: "\u5de6\u7bad\u5934.svg",
    next: "\u53f3\u7bad\u5934.svg",
    users: "\u7528\u6237\u7ec4.svg",
    dictionary: "\u6253\u5f00\u7684\u4e66.svg",
    channels: "\u6536\u4ef6\u7bb1\u586b\u5145.svg",
    ai: "\u95ea\u7535.svg",
    template: "\u6a21\u5757.svg",
    risk: "\u8b66\u544a\u4e09\u89d2.svg",
    audit: "\u7968\u636e.svg",
    stats: "\u67f1\u72b6\u56fe.svg",
    customer: "\u516c\u6587\u5305.svg",
    add: "\u52a0\u53f7.svg",
    "add-user": "\u6dfb\u52a0\u7528\u6237.svg",
    search: "\u641c\u7d22.svg",
    save: "\u8f6f\u76d8.svg",
    delete: "\u5783\u573e\u6876.svg",
    export: "\u5bfc\u51fa.svg"
  };

  function iconAssetUrl(fileName) {
    return `../customer/svg/${encodeURIComponent(fileName)}`;
  }

  function iconSvg(name, label = "") {
    const source = iconSources[name];
    if (source) {
      const accessibility = label ? `alt="${label}"` : `alt="" aria-hidden="true"`;
      return `<img class="ui-icon local-icon" ${accessibility} src="${iconAssetUrl(source)}">`;
    }
    const title = label ? `<title>${label}</title>` : "";
    return `<svg class="ui-icon" viewBox="0 0 24 24" aria-hidden="${label ? "false" : "true"}" focusable="false">${title}${iconPaths[name] || iconPaths.default}</svg>`;
  }

  function moduleIconName(title) {
    if (title.includes("客户")) return "customer";
    if (title.includes("任务")) return "tasks";
    if (title.includes("信息")) return "communication";
    if (title.includes("统计") || title.includes("看板")) return "stats";
    return "default";
  }
  function businessDictionary() {
    return {
      partnerTypes: ["\u5ba2\u6237", "\u4f9b\u5e94\u5546", "\u6d77\u5916\u4ee3\u7406", "\u540c\u884c", "\u5185\u90e8", "\u672a\u77e5", "\u5176\u4ed6"],
      contactIdentities: ["\u51b3\u7b56\u4eba", "\u64cd\u4f5c\u5bf9\u63a5", "\u8d22\u52a1", "\u91c7\u8d2d", "\u9500\u552e", "\u672a\u77e5"],
      topicTypes: ["\u62a5\u4ef7", "\u8ba2\u5355\u8c08\u5224", "\u6e05\u5173\u8d44\u6599", "\u8d26\u671f", "\u98ce\u9669\u4e8b\u4ef6", "\u5176\u4ed6"],
      taskTypes: ["\u8ddf\u8fdb", "\u5ba1\u6838", "\u8865\u5145\u8d44\u6599", "\u62a5\u4ef7\u786e\u8ba4", "\u98ce\u9669\u590d\u6838", "\u5df2\u7ed3\u675f"],
      risks: ["\u4f4e\u98ce\u9669", "\u4e2d\u98ce\u9669", "\u9ad8\u98ce\u9669", "\u9ed1\u540d\u5355", "\u6c47\u7387\u98ce\u9669", "\u6587\u4ef6\u98ce\u9669", "\u62a5\u4ef7\u6ce2\u52a8", "\u5f85\u8bc6\u522b"]
    };
  }

  function dictionaryOptions(kind, selected) {
    const items = businessDictionary()[kind] || [];
    return items.map((item) => `<option value="${item}" ${item === selected ? "selected" : ""}>${item}</option>`).join("");
  }
  if (initialParams.get("newContact") === "1") {
    state.contactDetailMode = "create";
    state.contactDetailOpen = true;
  }
  state.expandedPartnerTopic = firstContactTopicName(currentContact()) || state.expandedPartnerTopic;
  if (initialParams.get("customerDetail") === "1") {
    state.customerDetailOpen = true;
  }
  if (data.informationChannels.some((item) => item.name === initialParams.get("channelForm"))) {
    state.selectedChannel = initialParams.get("channelForm");
    state.channelFormOpen = true;
  }
  if (initialParams.get("stats") === "1") {
    state.statsOpen = true;
  }
  if (initialParams.get("aiAssistant") === "1") {
    state.aiAssistantOpen = true;
  }
  if (initialParams.get("aiTools") === "1") {
    state.aiAssistantOpen = true;
    state.aiAssistantToolsOpen = true;
  }
  if (["users", "dictionary", "channels", "ai", "template", "risk", "audit"].includes(initialParams.get("settingsTab"))) {
    state.settingsTab = initialParams.get("settingsTab");
  }

  const $ = (selector) => document.querySelector(selector);

  function tag(text, tone) {
    return `<span class="tag ${tone || ""}">${text}</span>`;
  }

  function toast(text) {
    const el = $("#toast");
    el.textContent = text;
    el.classList.add("visible");
    window.clearTimeout(toast.timer);
    toast.timer = window.setTimeout(() => el.classList.remove("visible"), 2200);
  }

  function currentRole() {
    return data.roles.find((item) => item.id === state.role) || data.roles[0];
  }

  function currentEmployeeOwner() {
    return "Elena Wang";
  }

  function isEmployeeScope() {
    return state.role === "employee";
  }

  function scopedPartners() {
    if (!isEmployeeScope()) return data.partners;
    return data.partners.filter((partner) => partner.owner === currentEmployeeOwner());
  }

  function scopedPartnerNames() {
    return scopedPartners().map((partner) => partner.name);
  }

  function isPartnerVisible(partnerName) {
    return !isEmployeeScope() || scopedPartnerNames().includes(partnerName);
  }

  function scopedProfiles() {
    const visibleIds = scopedPartners().map((partner) => partner.id);
    return data.customerTopicProfiles.filter((profile) => visibleIds.includes(profile.id));
  }

  function scopedMessages() {
    const visibleNames = scopedPartnerNames();
    return data.messages.filter((message) => !isEmployeeScope() || visibleNames.includes(message.partner));
  }

  function currentPartner() {
    const partners = scopedPartners();
    return partners.find((item) => item.id === state.selectedPartner) || partners[0] || data.partners[0];
  }

  function partnerProfile() {
    return scopedProfiles().find((item) => item.id === currentPartner().id);
  }

  function partnerContacts() {
    const profile = partnerProfile();
    if (profile && profile.contacts) return profile.contacts;
    return data.contacts.filter((item) => item.partner === currentPartner().name);
  }

  function currentContact() {
    const contacts = partnerContacts();
    return contacts.find((item) => item.name === state.selectedContact) || contacts[0] || data.contacts[0];
  }

  function allContactProfiles() {
    const profileContacts = scopedProfiles().flatMap((profile) => (profile.contacts || []).map((contact) => ({
      ...contact,
      partner: profile.name,
      partnerId: profile.id,
      confidence: contact.confidence || 86,
      status: contact.status || "pending",
      identity: contact.identity || contact.role || "未知",
      updatedAt: contact.topics && contact.topics[0] ? contact.topics[0].updatedAt : "暂无"
    })));
    const baseContacts = data.contacts
      .filter((contact) => isPartnerVisible(contact.partner))
      .map((contact) => ({
      ...contact,
      partnerId: data.partners.find((partner) => partner.name === contact.partner)?.id || "",
      updatedAt: contact.topics && contact.topics[0] ? contact.topics[0].updatedAt : "暂无"
    }));
    return [...profileContacts, ...baseContacts].filter((contact, index, list) =>
      list.findIndex((item) => item.name === contact.name && item.partner === contact.partner) === index
    );
  }

  function currentStandaloneContact() {
    const contacts = allContactProfiles();
    return contacts.find((item) => item.name === state.selectedContact) || contacts[0] || currentContact();
  }

  function emptyContactDraft() {
    const base = currentStandaloneContact();
    return {
      name: "",
      role: "",
      role_title: "",
      identity: "",
      partner: base.partner || currentPartner().name,
      partnerId: base.partnerId || currentPartner().id,
      email: "",
      phone: "",
      whatsapp_no: "",
      wecom_id: "",
      timezone: "",
      status: "pending",
      confidence: 80,
      profile: "",
      ai_profile: "",
      confirmed_profile: "",
      topics: [],
      updatedAt: "新增"
    };
  }

  function filteredStandaloneContacts() {
    const query = state.contactSearchQuery.trim().toLowerCase();
    const contacts = allContactProfiles();
    if (!query) return contacts;
    return contacts.filter((contact) => [
      contact.name,
      contact.identity,
      contact.role,
      contact.role_title,
      contact.partner,
      contact.channel
    ].filter(Boolean).some((value) => String(value).toLowerCase().includes(query)));
  }

  function contactReplyState(contact) {
    const status = String(contact.status || "").trim();
    const replied = ["已回复", "已确认", "confirmed", "merged", "已归档"].includes(status);
    return {
      label: replied ? "已回复" : "未回复",
      tone: replied ? "replied" : "unreplied"
    };
  }

  function firstContactTopicName(contact) {
    return contact && contact.topics && contact.topics[0] ? contact.topics[0].name : "";
  }

  function partnerUpdatedAt(partner) {
    const message = data.messages
      .filter((item) => item.partner === partner.name)
      .sort((a, b) => b.time.localeCompare(a.time))[0];
    return message ? message.time.slice(0, 16) : "暂无信息";
  }

  function filteredPartners() {
    const name = state.partnerListFilters.name.trim().toLowerCase();
    const owner = state.partnerListFilters.owner.trim().toLowerCase();
    const date = state.partnerListFilters.date;
    return scopedPartners().filter((partner) => {
      const updatedAt = partnerUpdatedAt(partner);
      const matchesName = !name || partner.name.toLowerCase().includes(name);
      const matchesOwner = !owner || partner.owner.toLowerCase().includes(owner);
      const matchesDate = !date || updatedAt.startsWith(date);
      return matchesName && matchesOwner && matchesDate;
    });
  }

  function applyPartnerFilters() {
    state.partnerListFilters = { ...state.partnerListFilterDrafts };
  }

  function applyContactSearch(input) {
    const cursor = input.selectionStart;
    state.contactSearchQuery = input.value;
    renderPageContent();
    const nextInput = document.querySelector("[data-contact-search]");
    if (nextInput) {
      nextInput.focus();
      nextInput.setSelectionRange(cursor, cursor);
    }
  }

  function saveContactDetail(form) {
    const isNew = state.contactDetailMode === "create";
    const contact = isNew ? emptyContactDraft() : currentStandaloneContact();
    const values = Object.fromEntries(new FormData(form).entries());
    const patch = {
      name: values.name || contact.name,
      role: values.role_title || contact.role || "",
      role_title: values.role_title || contact.role_title || "",
      identity: values.identity_id || contact.identity || contact.role || "",
      email: values.email || "",
      phone: values.phone || "",
      whatsapp_no: values.whatsapp_no || "",
      wecom_id: values.wecom_id || "",
      timezone: values.timezone || "",
      status: values.status || contact.status || "pending",
      confidence: Number(values.confidence_score) || contact.confidence || 86,
      profile: values.ai_profile || contact.profile || "",
      ai_profile: values.ai_profile || contact.ai_profile || contact.profile || "",
      confirmed_profile: values.confirmed_profile || contact.confirmed_profile || contact.profile || ""
    };
    if (isNew) {
      const partner = data.partners.find((item) => item.name === contact.partner || item.id === contact.partnerId);
      data.contacts.push({
        ...patch,
        partner: contact.partner,
        partnerId: contact.partnerId || (partner ? partner.id : ""),
        channel: "人工新增",
        topics: [],
        updatedAt: "刚刚"
      });
      state.selectedContact = patch.name;
      state.contactDetailMode = "edit";
      renderAll();
      toast("联系人已新增。");
      return;
    }
    const update = (item) => Object.assign(item, patch);
    data.customerTopicProfiles.forEach((profile) => {
      (profile.contacts || [])
        .filter((item) => item.name === contact.name && profile.name === contact.partner)
        .forEach(update);
    });
    data.contacts
      .filter((item) => item.name === contact.name && item.partner === contact.partner)
      .forEach(update);
    state.selectedContact = patch.name;
    renderAll();
    toast("联系人主档已保存。");
  }

  function resetPartnerFilters() {
    state.partnerListFilterDrafts = { name: "", owner: "", date: "" };
    state.partnerListFilters = { name: "", owner: "", date: "" };
  }

  function syncScopeSelection() {
    const partner = currentPartner();
    if (partner) state.selectedPartner = partner.id;
    const customerGroup = currentWorkbenchCustomerGroup();
    if (!customerGroup.rows.some((row) => row.id === state.selectedCustomerId)) {
      state.selectedCustomerId = customerGroup.rows[0] ? customerGroup.rows[0].id : "";
    }
    const taskBoard = taskWorkbenchData();
    const contact = currentStandaloneContact();
    if (contact) state.selectedContact = contact.name;
    const message = currentMessage();
    if (message) state.selectedMessage = message.id;
    if (taskBoard && taskBoard.id) state.selectedTaskPartner = taskBoard.id;
  }

  function channelTabLabel(name) {
    const labels = {
      "WhatsApp Business API": "WhatsApp",
      "企业微信 API": "企业微信",
      "邮件 IMCP": "邮件",
      "电话录入": "电话录入"
    };
    return labels[name] || name;
  }

  function orderedInformationChannels() {
    const order = ["WhatsApp Business API", "企业微信 API", "邮件 IMCP", "电话录入"];
    return [...data.informationChannels].sort((a, b) => order.indexOf(a.name) - order.indexOf(b.name));
  }

  function channelKey(name) {
    const keys = {
      "WhatsApp Business API": "whatsapp",
      "企业微信 API": "wecom",
      "邮件 IMCP": "mail",
      "电话录入": "phone"
    };
    return keys[name] || "mail";
  }

  function channelMark(channel) {
    const key = channelKey(channel);
    const labels = {
      whatsapp: "WA",
      wecom: "企微",
      mail: "?",
      phone: "?"
    };
    return `<span class="channel-mark ${key}" aria-label="${channelTabLabel(channel)}">${labels[key]}</span>`;
  }

  function companyNames() {
    return [...new Set(scopedMessages().map((item) => item.partner))];
  }

  function companyMessages(company) {
    return scopedMessages().filter((item) => item.partner === company);
  }

  function selectedCompanyChannel(company) {
    return state.companyChannelTabs[company] || orderedInformationChannels()[0].name;
  }

  function companyDateFilter(company) {
    return state.companyDateFilters[company] || "";
  }

  function filteredCompanies() {
    const query = state.informationCompanyQuery.trim().toLowerCase();
    return companyNames().filter((company) => {
      const matchesText = !query || company.toLowerCase().includes(query);
      return matchesText;
    });
  }

  function filteredCompanyMessages(company) {
    const channel = selectedCompanyChannel(company);
    const date = companyDateFilter(company);
    return companyMessages(company).filter((item) => {
      const matchesChannel = item.channel === channel;
      const matchesDate = !date || item.time.startsWith(date);
      return matchesChannel && matchesDate;
    });
  }

  function pagedCompanyMessages(company) {
    const pageSize = 3;
    const rows = filteredCompanyMessages(company);
    const totalPages = Math.max(1, Math.ceil(rows.length / pageSize));
    const page = Math.min(Number(state.companyPages[company] || 1), totalPages);
    return {
      rows: rows.slice((page - 1) * pageSize, page * pageSize),
      page,
      totalPages,
      total: rows.length
    };
  }

  function contactForMessage(message) {
    const contact = data.contacts.find((item) => item.name === message.contact && item.partner === message.partner);
    if (contact) return contact;
    const profile = data.customerTopicProfiles.find((item) => item.name === message.partner || item.id === message.partner);
    return profile && profile.contacts
      ? profile.contacts.find((item) => item.name === message.contact)
      : null;
  }

  function messageTopics(message) {
    const contact = contactForMessage(message);
    return contact && contact.topics ? contact.topics : [];
  }

  function fallbackTopicName(message) {
    const fact = message.facts.find((item) => item.includes("POD:") || item.includes("目的港:") || item.includes("目的地:"));
    const place = fact ? fact.split(":").slice(1).join(":").trim() : message.partner;
    return `${place} 跟进`;
  }

  function messageArchive(message) {
    if (state.topicArchives[message.id]) return state.topicArchives[message.id];
    const topics = messageTopics(message);
    if (message.status === "已归档" && topics[0]) {
      return {
        topicName: topics[0].name,
        contactName: message.contact,
        mode: "已有归档"
      };
    }
    return null;
  }

  function messageFactParts(fact) {
    const parts = fact.split(":");
    if (parts.length < 2) {
      return { label: "要点", value: fact };
    }
    return {
      label: parts[0].trim(),
      value: parts.slice(1).join(":").trim()
    };
  }

  function currentChannelConfig() {
    return data.informationChannels.find((item) => item.name === state.selectedChannel) || data.informationChannels[0];
  }

  function currentMessage() {
    const messages = scopedMessages();
    return messages.find((item) => item.id === state.selectedMessage) || messages[0] || data.messages[0];
  }

  function currentTopic() {
    return data.topics.find((item) => item.name === state.selectedTopic) || data.topics[0];
  }

  function currentSuggestion() {
    return data.suggestions.filter((item) => state.step >= item.minStep).slice(-1)[0] || data.suggestions[0];
  }

  function selectedSuggestion() {
    return data.suggestions[state.selectedSuggestion] || currentSuggestion();
  }

  function currentWorkbenchCustomerGroup() {
    const group = data.workbenchCustomers[state.customerAggregate] || data.workbenchCustomers.month;
    const rows = isEmployeeScope()
      ? group.rows.filter((row) => scopedPartners().some((partner) => partner.id === row.id))
      : group.rows;
    return { ...group, rows };
  }

  function currentCustomerProfile() {
    const group = currentWorkbenchCustomerGroup();
    return data.customerTopicProfiles.find((item) => item.id === (state.selectedCustomerId || group.rows[0]?.id)) || data.customerTopicProfiles[0];
  }

  function currentCustomerRow() {
    const group = currentWorkbenchCustomerGroup();
    return group.rows.find((item) => item.id === state.selectedCustomerId) || group.rows[0] || null;
  }

  function canViewRaw() {
    return state.role === "employee" || state.role === "supervisor";
  }

  function activePageMeta() {
    return data.pageCopy[state.page];
  }

  function breadcrumbItems() {
    const copy = activePageMeta();
    const root = [{ label: "CRM Demo", page: "workbench" }];
    const map = {
      workbench: ["个人工作台"],
      communication: ["信息管理", currentMessage().title],
      partners: ["合作企业", currentPartner().name],
      contacts: ["联系对象", currentContact().name],
      tasks: ["跟进任务"],
      dashboard: ["团队看板"],
      settings: ["系统配置"]
    };
    return root.concat(map[state.page].map((label) => ({ label })));
  }

  function renderNav() {
    $("#mainNav").innerHTML = data.nav.map((item) => `
      <button class="nav-item ${state.page === item.id ? "active" : ""}" data-page="${item.id}">
        <span class="nav-icon">${iconSvg(item.id, item.label)}</span>
        <strong>${item.label}</strong>
      </button>
    `).join("");
  }

  function renderBreadcrumb() {
    $("#breadcrumb").innerHTML = breadcrumbItems().map((item, index, list) => `
      <span class="crumb ${index === list.length - 1 ? "current" : ""}" ${item.page ? `data-page="${item.page}"` : ""}>${item.label}</span>
    `).join(`<span class="crumb-sep">/</span>`);
  }

  function renderPageTitle() {
    const copy = activePageMeta();
    const taskCreateButton = state.page === "tasks"
      ? `<button class="button primary small icon-label page-title-action" type="button" data-new-task>${iconSvg("add")}<span>添加任务</span></button>`
      : "";
    $("#pageTitle").innerHTML = `
      <span class="eyebrow">${copy.eyebrow}</span>
      <div class="page-title-row">
        <h1>${copy.title}</h1>
        ${taskCreateButton}
      </div>
      <p>${copy.subtitle}</p>
    `;
  }

  function renderRoleSwitch() {
    $("#roleSwitch").innerHTML = data.roles.map((role) => `
      <button class="role-button ${state.role === role.id ? "active" : ""}" data-role="${role.id}" title="${role.scope}">${role.label}</button>
    `).join("");
  }

  function renderToolbar() {
    const toolbarPages = ["communication", "contacts"];
    $("#pageToolbar").classList.toggle("hidden", !toolbarPages.includes(state.page));
    if (!toolbarPages.includes(state.page)) {
      $("#pageToolbar").innerHTML = "";
      return;
    }
    const copy = activePageMeta();
    const communicationFilter = state.page === "communication"
      ? `
        <div class="info-toolbar-filter">
          <input class="toolbar-input" value="${state.informationCompanyQuery}" placeholder="输入公司名称筛选" list="companyNameOptions" data-company-query>
          <datalist id="companyNameOptions">
            ${companyNames().map((name) => `<option value="${name}"></option>`).join("")}
          </datalist>
        </div>
      `
      : "";
    const communicationConfigButton = state.page === "communication"
      ? `<button class="button primary small icon-label" data-open-channel-config>${iconSvg("settings")}<span>信息配置</span></button>`
      : "";
    const contactCreateButton = state.page === "contacts"
      ? `<button class="button primary small icon-label" data-new-contact>${iconSvg("add-user")}<span>新增联系人</span></button>`
      : "";
    const toolbarRight = state.page === "communication"
      ? `${communicationFilter}${communicationConfigButton}`
      : `${contactCreateButton}${tag(currentRole().label, "blue")} ${tag(currentRole().scope)}`;
    $("#pageToolbar").innerHTML = `
      <div class="toolbar-head">
        <div>
          <strong>${copy.title}</strong>
          <span>${copy.tags.join(" · ")}</span>
        </div>
        <div class="toolbar-meta">${toolbarRight}</div>
      </div>
    `;
  }

  function statCard(label, value, note, tone) {
    return `
      <div class="stat-card ${tone || ""}">
        <span>${label}</span>
        <strong>${value}</strong>
        <small>${note}</small>
      </div>
    `;
  }

  function compareText(current, previous, unit) {
    const diff = current - previous;
    if (diff === 0) return `与对比周期持平`;
    const sign = diff > 0 ? "+" : "";
    return `${sign}${diff}${unit || ""} / 对比 ${previous}${unit || ""}`;
  }

  function renderLineChart(chart, label, previousLabel, title = "信息次数折线图", axis = "Y: 数量 / X: 日期") {
    const width = 680;
    const height = 230;
    const pad = 34;
    const allValues = chart.current.concat(chart.previous);
    const max = Math.max(...allValues, 1);
    const stepX = (width - pad * 2) / Math.max(chart.labels.length - 1, 1);
    const point = (value, index) => {
      const x = pad + index * stepX;
      const y = height - pad - (value / max) * (height - pad * 2);
      return `${x},${y}`;
    };
    const currentPoints = chart.current.map(point).join(" ");
    const previousPoints = chart.previous.map(point).join(" ");
    return `
      <div class="chart-card">
        <div class="chart-head"><strong>${title}</strong><span>${axis}</span></div>
        <svg class="line-chart" viewBox="0 0 ${width} ${height}" role="img" aria-label="${title}">
          <line x1="${pad}" y1="${height - pad}" x2="${width - pad}" y2="${height - pad}" class="axis" />
          <line x1="${pad}" y1="${pad}" x2="${pad}" y2="${height - pad}" class="axis" />
          <polyline points="${previousPoints}" class="line previous" />
          <polyline points="${currentPoints}" class="line current" />
          ${chart.current.map((value, index) => `<circle cx="${point(value, index).split(",")[0]}" cy="${point(value, index).split(",")[1]}" r="4" class="dot current-dot"><title>${chart.labels[index]} ${label}: ${value}</title></circle>`).join("")}
          ${chart.labels.map((item, index) => `<text x="${pad + index * stepX}" y="${height - 10}" text-anchor="middle" class="chart-label">${item}</text>`).join("")}
        </svg>
        <div class="chart-legend"><span class="legend-current">${label}</span><span class="legend-previous">${previousLabel}</span></div>
      </div>
    `;
  }

  function renderBarChart(items, label, previousLabel, title = "信息类型条形图", axis = "Y: 数量 / X: 数据类型") {
    const max = Math.max(...items.flatMap((item) => [item.current, item.previous]), 1);
    return `
      <div class="chart-card">
        <div class="chart-head"><strong>${title}</strong><span>${axis}</span></div>
        <div class="bar-chart" role="img" aria-label="${title}">
          ${items.map((item) => `
            <div class="bar-group">
              <span>${item.label}</span>
              <div class="bars">
                <i class="bar-current" style="height:${Math.max(8, item.current / max * 100)}%"><em>${item.current}</em></i>
                <i class="bar-previous" style="height:${Math.max(8, item.previous / max * 100)}%"><em>${item.previous}</em></i>
              </div>
            </div>
          `).join("")}
        </div>
        <div class="chart-legend"><span class="legend-current">${label}</span><span class="legend-previous">${previousLabel}</span></div>
      </div>
    `;
  }

  function panel(title, body, actions) {
    return `
      <section class="panel module-card">
        <div class="module-head">
          <h2>${iconSvg(moduleIconName(title), title)}<span>${title}</span></h2>
          ${actions || ""}
        </div>
        ${body}
      </section>
    `;
  }

  function renderStatsModal() {
    if (!state.statsOpen) return "";
    const perf = data.workbenchPerformance[state.aggregate];
    const customer = data.workbenchCustomerProgress[state.progressAggregate];
    return `
      <section class="stats-overlay" role="dialog" aria-modal="true" aria-label="产出统计">
        <div class="stats-window panel">
          <div class="stats-window-head">
            <div>
              <strong>产出统计</strong>
            </div>
            <button class="button small" data-close-stats>关闭</button>
          </div>

          <section class="stats-section">
            <div class="stats-section-head">
              <div>
                <strong>工作成果</strong>
                <span>${perf.label}成果聚合，可与${perf.previousLabel}对比</span>
              </div>
              <div class="aggregate-switch" aria-label="聚合周期切换">
                ${[
                  ["week", "按周"],
                  ["month", "按月"],
                  ["year", "按年"]
                ].map((item) => `<button class="period-button ${state.aggregate === item[0] ? "active" : ""}" data-aggregate="${item[0]}">${item[1]}</button>`).join("")}
              </div>
            </div>
            <div class="stat-grid three compact-stats">
              ${perf.metrics.map((metric, index) => statCard(metric.label, `${metric.value}${metric.unit}`, compareText(metric.value, metric.previous, metric.unit), index === 2 ? "amber" : "blue")).join("")}
            </div>
            <div class="two-col chart-grid symmetric">
              ${renderLineChart(perf.line, perf.label, perf.previousLabel)}
              ${renderBarChart(perf.bars, perf.label, perf.previousLabel)}
            </div>
          </section>

          <section class="stats-section">
            <div class="stats-section-head">
              <div>
                <strong>客户跟进进度</strong>
                <span>${customer.label}客户聚合，查看跟进节奏和阶段分布，可与${customer.previousLabel}对比</span>
              </div>
              <div class="aggregate-switch" aria-label="客户聚合周期切换">
                ${[
                  ["day", "按日"],
                  ["month", "按月"],
                  ["year", "按年"]
                ].map((item) => `<button class="period-button ${state.progressAggregate === item[0] ? "active" : ""}" data-customer-aggregate="${item[0]}">${item[1]}</button>`).join("")}
              </div>
            </div>
            <div class="stat-grid three compact-stats">
              ${customer.metrics.map((metric, index) => statCard(metric.label, `${metric.value}${metric.unit}`, compareText(metric.value, metric.previous, metric.unit), index === 2 ? "amber" : "blue")).join("")}
            </div>
            <div class="two-col chart-grid symmetric">
              ${renderLineChart(customer.line, customer.label, customer.previousLabel, "客户跟进趋势", "Y: 客户数 / X: 日期")}
              ${renderBarChart(customer.stages, customer.label, customer.previousLabel, "客户阶段分析", "Y: 客户数 / X: 客户阶段")}
            </div>
          </section>
        </div>
      </section>
    `;
  }

  function renderWorkbenchCustomers() {
    const group = currentWorkbenchCustomerGroup();
    const rows = group.rows;
    const currentRow = currentCustomerRow();
    return `
      ${panel("客户聚合", `
        <div class="module-toolbar">
          <div>
            <strong>${group.label}</strong>
            <span>按本周 / 本月 / 本年直接查看客户名称、阶段和更新时间。</span>
          </div>
          <div class="module-actions">
            <button class="button primary" data-open-stats>产出统计</button>
            <div class="aggregate-switch" aria-label="客户聚合周期切换">
              ${[
                ["week", "本周"],
                ["month", "本月"],
                ["year", "本年"]
              ].map((item) => `<button class="period-button ${state.customerAggregate === item[0] ? "active" : ""}" data-workbench-aggregate="${item[0]}">${item[1]}</button>`).join("")}
            </div>
          </div>
        </div>
        <div class="customer-table" role="table" aria-label="客户聚合列表">
          <div class="customer-table-head" role="row">
            <span>客户名称</span>
            <span>阶段</span>
            <span>更新时间</span>
            <span>操作</span>
          </div>
          ${rows.map((row) => `
            <div class="customer-table-row ${currentRow && currentRow.id === row.id ? "active" : ""}" role="row">
              <strong>${row.name}</strong>
              <span>${row.stage}</span>
              <span>${row.updatedAt}</span>
              <button class="button small" data-open-customer-detail="${row.id}">详情</button>
            </div>
          `).join("")}
        </div>
      `)}
    `;
  }
  function renderWorkbench() {
    const pending = Math.max(0, 3 - Number(state.accepted.identity) - Number(state.accepted.topic) - Number(state.accepted.task));
    const visibleTasks = data.tasks.filter((task) => !isEmployeeScope() || task.owner === currentEmployeeOwner());
    const visibleSuggestions = data.suggestions.filter((item) => {
      if (item.status === "已同步") return false;
      if (!isEmployeeScope()) return true;
      return scopedPartnerNames().some((name) => String(item.target || "").includes(name));
    });
    return `
      ${renderStatsModal()}
      ${renderCustomerDetailOverlay()}
      ${renderWorkbenchCustomers()}

      <div class="two-col symmetric workbench-action-grid">
        ${panel("本日应该跟进的任务", `
          <div class="list-stack">
            ${visibleTasks.map((task) => `
              <button class="task-row ${task.status === "逾期" ? "danger" : ""}" data-task-partner="${taskBoardIdForPartnerName(task.partner)}">
                <div>
                  <strong>${task.title}</strong>
                  <span>${task.partner} / ${task.owner}</span>
                </div>
                <div>${tag(task.status, task.status === "逾期" ? "red" : task.status === "待确认" ? "amber" : "blue")}<small>${task.due}</small></div>
              </button>
            `).join("") || `<div class="masked-box">暂无本人负责客户的跟进任务</div>`}
          </div>
        `)}
        ${panel("应该确认的 AI 信息", `
          <div class="list-stack">
            ${visibleSuggestions.map((item, index) => `
              <button class="task-row" data-page="settings" data-settings-tab="ai" data-suggestion-index="${index}">
                <div>
                  <strong>${item.type}</strong>
                  <span>${item.target}</span>
                </div>
                <div class="line-wrap">${tag(item.status, "amber")} ${tag(`${item.confidence}%`, "blue")}</div>
              </button>
            `).join("") || `<div class="masked-box">暂无待确认 AI 信息</div>`}
          </div>
        `)}
      </div>
    `;
  }

  function renderCustomerDetailOverlay() {
    if (!state.customerDetailOpen) return "";
    const profile = currentCustomerProfile();
    if (!profile) return "";
    return `
      <section class="detail-overlay" role="dialog" aria-modal="true" aria-label="联系人 Topic 画像">
        <div class="detail-window panel">
          <div class="detail-window-head">
            <div>
              <strong>${profile.name}</strong>
              <span>合作商只作为归档对象；Topic 先归属于具体联系人，再汇总到该合作商。</span>
            </div>
            <button class="button small" data-close-customer-detail>关闭</button>
          </div>
          <div class="detail-contact-list">
            ${profile.contacts.map((contact) => `
              <section class="detail-contact-card">
                <div class="detail-contact-head">
                  <div>
                    <strong>${contact.name}</strong>
                    <span>${contact.role}</span>
                  </div>
                  ${tag(contact.channel, "blue")}
                </div>
                <p>${contact.profile}</p>
                <div class="topic-list contact-topic-list">
                  ${contact.topics.map((topic) => `
                    <article class="topic-item contact-topic-item">
                      <div class="topic-item-head">
                        <div>
                          <strong>${topic.name}</strong>
                          <span>来自 ${topic.source} 的信息总结</span>
                        </div>
                        <div class="line-wrap">${tag(topic.stage, topic.stage === "进行中" || topic.stage === "报价中" || topic.stage === "已成交" ? "green" : "amber")} ${tag(topic.updatedAt, "blue")}</div>
                      </div>
                      <p>${topic.note}</p>
                      <div class="topic-timeline">
                        ${topic.timeline.map((step) => `
                          <div>
                            <time>${step[0]}</time>
                            <strong>${step[1]}</strong>
                            <span>${step[2]}</span>
                          </div>
                        `).join("")}
                      </div>
                    </article>
                  `).join("")}
                </div>
              </section>
            `).join("")}
          </div>
        </div>
      </section>
    `;
  }
  function renderChannelFormModal() {
    if (!state.channelFormOpen) return "";
    const channel = currentChannelConfig();
    return `
      <section class="detail-overlay" role="dialog" aria-modal="true" aria-label="${channel.name}配置表单">
        <div class="channel-form-window panel">
          <div class="detail-window-head">
            <div>
              <strong>${channel.name}</strong>
              <span>${channel.rule}</span>
            </div>
            <button class="button small" data-close-channel-form>关闭</button>
          </div>
          <div class="channel-tabs" role="tablist" aria-label="信息配置渠道">
            ${orderedInformationChannels().map((item) => `
              <button class="channel-tab ${item.name === state.selectedChannel ? "active" : ""}" role="tab" aria-selected="${item.name === state.selectedChannel}" data-channel-tab="${item.name}">
                ${channelTabLabel(item.name)}
              </button>
            `).join("")}
          </div>
          <div class="channel-form-grid">
            <label><span>接入状态</span><input value="${channel.status}" /></label>
            <label><span>接入方式</span><input value="${channel.mode}" /></label>
            <label class="wide"><span>接入范围</span><input value="${channel.scope}" /></label>
            ${channel.config.map((item, index) => `<label><span>配置项 ${index + 1}</span><input value="${item}" /></label>`).join("")}
          </div>
          <div class="action-row">
            ${channel.actions.map((action) => `<button class="button small" data-channel-action="${action}">${action}</button>`).join("")}
            <button class="button primary small" data-close-channel-form>保存配置</button>
          </div>
        </div>
      </section>
    `;
  }

  function renderMessageDetailModal() {
    if (!state.messageDetailOpen) return "";
    const message = currentMessage();
    const topics = messageTopics(message);
    const archive = messageArchive(message);
    const suggestedTopic = archive
      ? topics.find((topic) => topic.name === archive.topicName) || { name: archive.topicName, stage: archive.mode || "已并入", note: "该信息已并入联系人 Topic。" }
      : topics[0] || { name: fallbackTopicName(message), stage: "新建建议", note: "当前联系人暂无可直接承接的 Topic，建议新建联系人 Topic。" };
    const isAggregated = message.mode.includes("聚合");
    const showRaw = state.rawVisible && canViewRaw();
    return `
      <section class="detail-overlay" role="dialog" aria-modal="true" aria-label="${message.title}详情">
        <div class="message-detail-window panel">
          <header class="message-modal-head">
            <div class="message-modal-title">
              ${channelMark(message.channel)}
              <div>
                <h2>${message.title}</h2>
                <span>${message.partner} / ${message.contact} / ${message.time}</span>
              </div>
            </div>
            <button class="button small" data-close-message-detail>关闭</button>
          </header>

          <div class="message-detail-grid">
            <div class="message-meta-row">
              ${tag(message.channel, "blue")}
              ${tag(message.mode, isAggregated ? "green" : "")}
              ${tag(message.status, message.status === "失败可重试" ? "red" : message.status === "待确认" ? "amber" : "green")}
            </div>

            <section class="summary-box summary-control message-summary-card">
              <div class="summary-head">
                <strong>AI 摘要</strong>
                <div class="action-row">
                  <button class="button success small" data-summary-action="confirm">确定</button>
                  <button class="button small" data-summary-action="edit">修改</button>
                  <button class="button danger small" data-summary-action="delete">删除</button>
                </div>
              </div>
              <span>${message.summary}</span>
              <textarea class="edit-box ${state.summaryEditVisible ? "visible" : ""}" aria-label="修改 AI 摘要">${message.summary}</textarea>
            </section>

            <section class="detail-block message-fact-section">
              <strong>关键信息</strong>
              <div class="message-fact-list">
                ${message.facts.map((fact) => {
                  const item = messageFactParts(fact);
                  return `<div class="message-fact-item"><span>${item.label}</span><strong>${item.value}</strong></div>`;
                }).join("")}
              </div>
            </section>

            <section class="detail-block topic-merge-section">
              <div class="topic-merge-head">
                <strong>Topic 归档建议</strong>
                ${tag(archive ? "已并入 Topic" : "待人工确认", archive ? "green" : "amber")}
              </div>
              <div class="topic-merge-card ${archive ? "merged" : ""}">
                <div class="topic-merge-flow">
                  <span>消息</span>
                  <span>${message.contact}</span>
                  <span>${suggestedTopic.name}</span>
                </div>
                <p>${archive ? `已并入 ${archive.contactName} / ${archive.topicName}，合作方通过联系人间接关联。` : `AI 建议把这条信息并入 ${message.contact} 的「${suggestedTopic.name}」。Topic 先归属于联系人，再关联到 ${message.partner}。`}</p>
                <div class="topic-chip-list">
                  ${topics.map((topic) => `<span>${topic.name} · ${topic.stage || topic.status || "进行中"}</span>`).join("") || "<span>该联系人暂无 Topic</span>"}
                </div>
                <div class="topic-merge-actions">
                  <button class="button success small" data-merge-topic="${suggestedTopic.name}">并入该 Topic</button>
                  <button class="button small" data-topic-picker="${message.id}">选择其他 Topic</button>
                  <button class="button primary small" data-create-contact-topic="${message.id}">新建联系人 Topic</button>
                </div>
                ${state.topicPickerOpen ? `
                  <div class="topic-picker-list" aria-label="选择联系人 Topic">
                    ${topics.map((topic) => `
                      <button type="button" data-merge-topic="${topic.name}">
                        <strong>${topic.name}</strong>
                        <span>${topic.stage || "进行中"} · ${topic.note || "来自联系人历史信息"}</span>
                      </button>
                    `).join("") || `<div class="masked-box">没有可选 Topic，请新建联系人 Topic。</div>`}
                  </div>
                ` : ""}
              </div>
            </section>

            ${isAggregated ? `<section class="compact-note">${message.channel} 已按同一联系人同一天聚合，AI 只总结聚合后的信息页，不逐条总结推送消息。</section>` : ""}

            <section class="raw-message-panel">
              <div class="raw-message-head">
                <strong>消息原件</strong>
                <button class="button small" id="toggleRaw">${state.rawVisible ? "隐藏原件" : "查看原件"}</button>
              </div>
              ${showRaw ? `<pre class="raw-box">${message.raw}</pre>` : `<div class="masked-box">默认隐藏，点击后按当前角色权限展示。</div>`}
            </section>
          </div>
        </div>
      </section>
    `;
  }

  function renderCompanyInformationCard(company) {
    const channel = selectedCompanyChannel(company);
    const pageData = pagedCompanyMessages(company);
    const messages = companyMessages(company);
    const expanded = state.companyCardsOpen[company] === true;
    return `
      <section class="company-info-card ${expanded ? "expanded" : "collapsed"}">
        <button class="company-info-trigger" type="button" data-company-toggle="${company}" aria-expanded="${expanded}">
          <div class="company-info-head">
            <div>
              <h2>${company}</h2>
              <span>${messages.length} 条信息 · ${messages.map((item) => item.contact).filter((value, index, list) => list.indexOf(value) === index).join("、")}</span>
            </div>
          </div>
          <span class="company-toggle-pill">${expanded ? "收起" : "展开"}</span>
        </button>
        <div class="company-info-body">
          <div class="company-channel-tabs" role="tablist" aria-label="${company}信息渠道">
            ${orderedInformationChannels().map((item) => `
              <button class="company-channel-tab ${item.name === channel ? "active" : ""}" data-company-channel="${company}" data-channel-name="${item.name}">
                ${channelMark(item.name)}<span>${channelTabLabel(item.name)}</span>
              </button>
            `).join("")}
          </div>
          <div class="company-table-tools">
            <label>
              <span>按时间筛选</span>
              <input type="date" value="${companyDateFilter(company)}" data-company-date="${company}">
            </label>
            <span>${channelTabLabel(channel)} · ${pageData.total} 条</span>
          </div>
          <div class="information-table">
            <div class="information-table-head">
              <span>信息主题</span>
              <span>对象</span>
              <span>日期</span>
              <span>信息状态</span>
              <span>详情</span>
            </div>
            <div class="information-table-body">
              ${pageData.rows.map((message) => `
                <div class="information-table-row">
                  <strong>${message.title}</strong>
                  <span>${message.contact}</span>
                  <span>${message.time.slice(0, 10)}</span>
                  <span>${tag(messageArchive(message) ? "已并入 Topic" : message.status, message.status === "失败可重试" ? "red" : messageArchive(message) ? "green" : message.status === "待确认" ? "amber" : "green")}</span>
                  <button class="button small" data-message-detail="${message.id}">详情</button>
                </div>
              `).join("") || `<div class="empty-row">当前渠道和时间范围内暂无信息。</div>`}
            </div>
          </div>
          <div class="table-pagination">
            <button class="button small icon-label" data-company-page="${company}" data-page-dir="-1" ${pageData.page <= 1 ? "disabled" : ""}>${iconSvg("prev")}<span>上一页</span></button>
            <span>${pageData.page} / ${pageData.totalPages}</span>
            <button class="button small icon-label" data-company-page="${company}" data-page-dir="1" ${pageData.page >= pageData.totalPages ? "disabled" : ""}><span>下一页</span>${iconSvg("next")}</button>
          </div>
        </div>
      </section>
    `;
  }

  function renderCommunication() {
    const companies = filteredCompanies();
    return `
      ${renderChannelFormModal()}
      ${renderMessageDetailModal()}
      <div class="company-info-grid">
        ${companies.map((company) => renderCompanyInformationCard(company)).join("") || `<div class="panel empty-state">没有匹配的公司信息。</div>`}
      </div>
    `;
  }

  function renderPartnerEnterpriseList() {
    const partners = filteredPartners();
    const allVisiblePartners = scopedPartners();
    const ownerOptions = allVisiblePartners
      .map((partner) => partner.owner)
      .filter((owner, index, list) => list.indexOf(owner) === index)
      .sort((a, b) => a.localeCompare(b));
    return `
      ${panel("企业列表", `
        <div class="partner-list-filterbar">
          <label>
            <span>客户名</span>
            <input value="${state.partnerListFilterDrafts.name}" placeholder="输入或选择企业名称" list="partner-name-options" data-partner-name-filter>
            <datalist id="partner-name-options">
              ${allVisiblePartners.map((partner) => `<option value="${partner.name}"></option>`).join("")}
            </datalist>
          </label>
          <label>
            <span>负责人</span>
            <input value="${state.partnerListFilterDrafts.owner}" placeholder="输入或选择负责人" list="partner-owner-options" data-partner-owner-filter>
            <datalist id="partner-owner-options">
              ${ownerOptions.map((owner) => `<option value="${owner}"></option>`).join("")}
            </datalist>
          </label>
          <label>
            <span>更新时间</span>
            <input type="date" value="${state.partnerListFilterDrafts.date}" data-partner-date-filter>
          </label>
          <div class="partner-list-filter-actions">
            <button class="button primary small" data-apply-partner-filters>查询</button>
            <button class="button small" data-reset-partner-filters>重置</button>
          </div>
        </div>
        <div class="partner-enterprise-table">
          <div class="partner-enterprise-head">
            <span>企业名</span>
            <span>合作对象</span>
            <span>阶段</span>
            <span>更新时间</span>
            <span>负责人</span>
          </div>
          ${partners.map((partner) => `
            <div class="partner-enterprise-row">
              <button class="partner-enterprise-link" type="button" data-open-partner-detail="${partner.id}">${partner.name}</button>
              <span>${partner.type}</span>
              <span>${partner.stage}</span>
              <span>${partnerUpdatedAt(partner)}</span>
              <span>${partner.owner}</span>
            </div>
          `).join("") || `<div class="empty-row">没有匹配的合作方。</div>`}
        </div>
      `)}
    `;
  }

  function partnerWebsiteUrl(partner) {
    const fallback = String(partner.name || "partner").toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");
    return partner.websiteUrl || partner.website || `https://www.${fallback}.com`;
  }

  function partnerTimezone(partner) {
    const map = {
      美国: "America/Los_Angeles",
      加拿大: "America/Vancouver",
      德国: "Europe/Berlin",
      巴西: "America/Sao_Paulo",
      中国: "Asia/Shanghai",
      阿联酋: "Asia/Dubai",
      日本: "Asia/Tokyo"
    };
    return partner.timezone || map[partner.country] || "Asia/Shanghai";
  }

  function partnerCurrency(partner) {
    const map = { 美国: "USD", 加拿大: "CAD", 德国: "EUR", 巴西: "BRL", 中国: "CNY", 阿联酋: "AED", 日本: "JPY" };
    return partner.currency || map[partner.country] || "USD";
  }

  function partnerLanguage(partner) {
    const map = { 美国: "英语", 加拿大: "英语", 德国: "德语 / 英语", 巴西: "葡萄牙语", 中国: "中文", 阿联酋: "英语 / 阿拉伯语", 日本: "日语" };
    return partner.language || map[partner.country] || "英语";
  }

  function renderPartnerInfoConfig(partner) {
    return `
      <div class="partner-basic-form">
        <label><span>公司名称</span><input value="${partner.name}" /></label>
        <label><span>合作方类型</span><select class="partner-type-select">${dictionaryOptions("partnerTypes", partner.type)}</select></label>
        <label class="wide"><span>官网 URL</span><input value="${partnerWebsiteUrl(partner)}" /></label>
        <label><span>国家/地区</span><input value="${partner.country}" /></label>
        <label><span>城市</span><input value="${partner.city}" /></label>
        <label><span>时区</span><input value="${partnerTimezone(partner)}" /></label>
        <label><span>货币</span><input value="${partnerCurrency(partner)}" /></label>
        <label><span>语言</span><input value="${partnerLanguage(partner)}" /></label>
        <label><span>负责人</span><input value="${partner.owner}" /></label>
        <label><span>当前阶段</span><input value="${partner.stage}" /></label>
        <label><span>风险标签</span><select class="partner-risk-select">${dictionaryOptions("risks", partner.risk)}</select></label>
        <label class="wide"><span>业务标签</span><input value="${partner.tags.join("、")}" /></label>
        <label class="wide"><span>下一步动作</span><input value="${partner.nextAction}" /></label>
        <label class="wide"><span>企业摘要</span><textarea>${partner.summary}</textarea></label>
      </div>
      ${renderPartnerTabActions("config")}
    `;
  }

  function renderPartnerTabActions(tab) {
    return `
      <div class="partner-tab-actions">
        <button class="button success small" type="button" data-confirm-partner-tab="${tab}">确认</button>
        <button class="button small" type="button" data-edit-partner-tab="${tab}">修改</button>
      </div>
    `;
  }

  function renderPartnerWebsiteAnalysis(partner) {
    const profile = partnerProfile();
    const keywords = (partner.tags || []).concat((profile && profile.contacts ? profile.contacts.map((contact) => contact.role) : [])).slice(0, 6);
    return `
      <div class="partner-tab-grid">
        <section class="partner-mini-card wide">
          <strong>官网分析摘要</strong>
          <p>${partner.name} 官网信息显示其业务重点与 ${partner.tags.join("、")} 相关。AI 建议优先核对服务范围、目的港覆盖和报价口径。</p>
        </section>
        <section class="partner-mini-card">
          <strong>主营方向</strong>
          <div class="fact-grid">${keywords.map((item) => `<span>${item}</span>`).join("")}</div>
        </section>
        <section class="partner-mini-card">
          <strong>页面信号</strong>
          <p>更新频率：近 30 天有内容变化；联系入口：邮箱和 WhatsApp 均可用；报价入口：需要人工二次确认。</p>
        </section>
      </div>
      ${renderPartnerTabActions("website")}
    `;
  }

  function renderPartnerDueDiligence(partner) {
    const riskTone = partner.risk.includes("高") || partner.risk.includes("异常") ? "red" : "amber";
    return `
      <div class="partner-tab-grid">
        <section class="partner-mini-card">
          <strong>背调结论</strong>
          <p>${partner.type} / ${partner.country} ${partner.city}，当前风险标记为 ${partner.risk}。</p>
          ${tag(partner.risk, riskTone)}
        </section>
        <section class="partner-mini-card">
          <strong>合作记录</strong>
          <p>当前阶段：${partner.stage}；负责人：${partner.owner}；下一步：${partner.nextAction}。</p>
        </section>
        <section class="partner-mini-card wide">
          <strong>需人工确认</strong>
          <div class="fact-grid">
            <span>账期与付款主体</span><span>发票抬头</span><span>历史异常</span><span>目的港责任边界</span>
          </div>
        </section>
      </div>
      ${renderPartnerTabActions("report")}
    `;
  }

  function renderPartnerOrderCommunication(partner) {
    return `
      <div class="partner-order-layout">
        <section class="partner-order-form">
          <div class="section-headline">
            <strong>谈判订单结果</strong>
            <span>上传本次订单谈判结果，后续可进入任务、报价和成交复盘。</span>
          </div>
          <div class="partner-basic-form order-form">
            <label><span>订单编号</span><input value="BH-LAX-40HQ-0618" /></label>
            <label><span>对接联系人</span><input value="${partnerContacts()[0]?.name || "待确认"}" /></label>
            <label><span>航线/服务</span><input value="Ningbo -> LAX / 40HQ" /></label>
            <label><span>报价结果</span><input value="USD 2,460 all-in，目的港杂费单列" /></label>
            <label><span>成交状态</span><select><option>谈判中</option><option>已成交</option><option>未成交</option><option>待客户确认</option></select></label>
            <label><span>预计出运</span><input value="2026-06-28" /></label>
            <label class="wide"><span>订单附件</span><input type="file" /></label>
            <label class="wide"><span>谈判备注</span><textarea>客户要求确认本周船期、目的港 handling 和燃油附加费是否锁价。</textarea></label>
          </div>
          <div class="partner-tab-actions">
            <button class="button primary small" type="button" data-upload-order-result="${partner.id}">上传结果</button>
            <button class="button small" type="button" data-edit-partner-tab="order">保存草稿</button>
          </div>
        </section>
        <section class="partner-order-history">
          <div class="section-headline">
            <strong>历史订单</strong>
            <span>显示该企业过往订单，按合作方归档，可追溯最近沟通。</span>
          </div>
          <div class="partner-order-table">
            <div class="partner-order-table-head">
              <span>订单</span><span>状态</span><span>金额</span>
            </div>
            ${[
              ["BH-LAX-40HQ-0618", "待客户确认", "USD 2,460", "Ningbo -> LAX 40HQ", "报价已发送，等待客户确认目的港费用口径。"],
              ["BH-LAX-RESTOCK-0615", "谈判中", "待确认", "LAX 旺季补货", "客户补充船期和柜型要求，已进入报价阶段。"],
              ["BH-HISTORY-0612", "已归档", "历史参考", "历史询价归档", "作为本次报价参考，不生成正式订单。"]
            ].map((row) => `
              <article class="partner-order-record">
                <div>
                  <strong>${row[0]}</strong>
                  <small>${row[3]}</small>
                  <p>${row[4]}</p>
                </div>
                <span>${row[1]}</span>
                <b>${row[2]}</b>
              </article>
            `).join("")}
          </div>
        </section>
      </div>
    `;
  }

  function renderPartnerContactsTab() {
    const contact = currentContact();
    return `
      <div class="partner-contact-layout">
        <div class="partner-contact-list">
          ${partnerContacts().map((item) => `
            <button class="contact-row ${contact.name === item.name ? "active" : ""}" data-contact-name="${item.name}">
              ${tag(item.status || "待确认", item.status === "已确认" ? "green" : "amber")}
              <div>
                <strong>${item.name}</strong>
                <span>${item.role}</span>
              </div>
            </button>
          `).join("")}
        </div>
        <section class="contact-profile partner-contact-profile">
          <div class="profile-card compact-card">
            <div class="avatar">${contact.name.slice(0, 1)}</div>
            <div class="profile-body">
              <div class="line-wrap">${tag(contact.role, "blue")} ${tag(contact.channel, "blue")}</div>
              <h3>${contact.name}</h3>
              <span>${currentPartner().name}</span>
              <p>${contact.profile}</p>
            </div>
          </div>
          <div class="partner-topic-list">
            ${(contact.topics || []).map((topic) => {
              const expanded = state.expandedPartnerTopic === topic.name || (!state.expandedPartnerTopic && topic.name === firstContactTopicName(contact));
              return `
                <article class="partner-topic-card ${expanded ? "expanded" : ""}">
                  <button class="partner-topic-trigger" type="button" data-partner-topic-toggle="${topic.name}" aria-expanded="${expanded}">
                    <div>
                      <div class="line-wrap">
                        ${tag(topic.stage || "进行中", topic.stage === "已成交" || topic.stage === "进行中" || topic.stage === "报价中" ? "green" : "amber")}
                        ${topic.updatedAt ? tag(topic.updatedAt, "blue") : ""}
                      </div>
                      <strong>${topic.name}</strong>
                    </div>
                    <span>${expanded ? "收起" : "展开"}</span>
                  </button>
                  ${expanded ? `
                    <div class="partner-topic-detail">
                      <p>${topic.note}</p>
                      <div class="partner-topic-timeline">
                        ${(topic.timeline || []).map((step) => `
                          <div>
                            <span>${step[0]}</span>
                            <strong>${step[1]}</strong>
                            <p>${step[2]}</p>
                          </div>
                        `).join("")}
                      </div>
                    </div>
                  ` : ""}
                </article>
              `;
            }).join("") || `<div class="masked-box">该联系人暂无 Topic。</div>`}
          </div>
        </section>
      </div>
    `;
  }

  function renderPartnerDetail() {
    const partner = currentPartner();
    const tabs = [
      ["config", "信息配置"],
      ["website", "官网分析"],
      ["report", "背调报告"],
      ["order", "订单沟通"],
      ["contacts", "联系人"]
    ];
    const body = {
      config: () => renderPartnerInfoConfig(partner),
      website: () => renderPartnerWebsiteAnalysis(partner),
      report: () => renderPartnerDueDiligence(partner),
      order: () => renderPartnerOrderCommunication(partner),
      contacts: () => renderPartnerContactsTab()
    }[state.partnerDetailTab] || (() => renderPartnerContactsTab());
    const riskTone = partner.risk.includes("高") || partner.risk.includes("异常") ? "red" : "amber";
    const tabRunButton = state.partnerDetailTab === "website"
      ? `<button class="button primary small icon-label" type="button" data-start-website-analysis="${partner.id}">${iconSvg("search")}<span>开始官网分析</span></button>`
      : state.partnerDetailTab === "report"
        ? `<button class="button primary small icon-label" type="button" data-start-due-report="${partner.id}">${iconSvg("risk")}<span>开始背调报告</span></button>`
        : "";
    return `
      <section class="partner-detail-shell panel">
        <div class="partner-detail-head">
          <button class="button small" data-back-partner-list>返回企业列表</button>
          <div>
            <h2>${partner.name}</h2>
            <p>${partner.summary}</p>
          </div>
          <div class="partner-detail-actions">
            ${tag(partner.risk, riskTone)}
            <div class="partner-owner-pin">
              <span>负责人</span>
              <strong>${partner.owner}</strong>
            </div>
            ${tabRunButton}
          </div>
        </div>
        <div class="partner-detail-tabs" role="tablist" aria-label="企业详情页签">
          ${tabs.map(([id, label]) => `<button class="partner-detail-tab ${state.partnerDetailTab === id ? "active" : ""}" data-partner-tab="${id}">${label}</button>`).join("")}
        </div>
        <div class="partner-detail-body">${body()}</div>
      </section>
    `;
  }

  function renderPartners() {
    return state.partnerView === "detail" ? renderPartnerDetail() : renderPartnerEnterpriseList();
  }

  function renderContactTopicCards(contact, className) {
    return (contact.topics || []).map((topic) => {
      const expanded = state.expandedPartnerTopic === topic.name || (!state.expandedPartnerTopic && topic.name === firstContactTopicName(contact));
      return `
        <article class="${className || "contact-topic-card"} ${expanded ? "expanded" : ""}">
          <button class="partner-topic-trigger" type="button" data-partner-topic-toggle="${topic.name}" aria-expanded="${expanded}">
            <div>
              <div class="line-wrap">
                ${tag(topic.stage || topic.status || "进行中", topic.stage === "已成交" || topic.stage === "进行中" || topic.stage === "报价中" ? "green" : "amber")}
                ${topic.updatedAt ? tag(topic.updatedAt, "blue") : ""}
              </div>
              <strong>${topic.name}</strong>
            </div>
            <span>${expanded ? "收起" : "展开"}</span>
          </button>
          ${expanded ? `
            <div class="partner-topic-detail">
              <p>${topic.note || topic.current || "暂无确认总结。"}</p>
              <div class="partner-topic-timeline">
                ${(topic.timeline || topic.events || []).map((step) => `
                  <div>
                    <span>${step[0]}</span>
                    <strong>${step[1]}</strong>
                    <p>${step[2] || step[1]}</p>
                  </div>
                `).join("")}
              </div>
            </div>
          ` : ""}
        </article>
      `;
    }).join("") || `<div class="masked-box">该联系人暂无 Topic。</div>`;
  }

  function renderContactDetailModal() {
    if (!state.contactDetailOpen) return "";
    const isNew = state.contactDetailMode === "create";
    const contact = isNew ? emptyContactDraft() : currentStandaloneContact();
    const fields = [
      ["name", "姓名", contact.name || ""],
      ["role_title", "职位", contact.role || contact.role_title || ""],
      ["identity_id", "身份", contact.identity || contact.role || "未知"],
      ["email", "邮箱", contact.email || ""],
      ["phone", "电话", contact.phone || ""],
      ["whatsapp_no", "WhatsApp 号", contact.whatsapp_no || (contact.channel && contact.channel.includes("WhatsApp") ? "已识别" : "")],
      ["wecom_id", "企业微信 ID", contact.wecom_id || (contact.channel && contact.channel.includes("企业微信") ? "已识别" : "")],
      ["timezone", "时区", contact.timezone || ""],
      ["status", "状态", contact.status || "pending"],
      ["confidence_score", "身份置信度", contact.confidence || 86]
    ];
    const renderContactField = ([name, label, value]) => {
      if (name === "identity_id") {
        return `<label><span>${label}</span><select class="contact-identity-select" name="${name}">${dictionaryOptions("contactIdentities", value)}</select></label>`;
      }
      if (name === "status") {
        return `<label><span>${label}</span><select name="${name}">${["pending", "confirmed", "merged", "disabled"].map((item) => `<option value="${item}" ${item === value ? "selected" : ""}>${item}</option>`).join("")}</select></label>`;
      }
      return `<label><span>${label}</span><input name="${name}" value="${value}"></label>`;
    };
    const title = isNew ? "新增联系人" : contact.name;
    return `
      <section class="detail-overlay" role="dialog" aria-modal="true" aria-label="${title} 联系人主档">
        <form class="contact-detail-window panel" data-contact-detail-form>
          <div class="detail-window-head">
            <div>
              <strong>${title}</strong>
              <span>${isNew ? "填写联系人主档并保存到当前联系人列表" : `${contact.role || contact.role_title || "职位待补充"} / ${contact.partner || "未知合作方"}`}</span>
            </div>
            <div class="modal-actions">
              <button class="button small" type="button" data-close-contact-detail>关闭</button>
              <button class="button primary small" type="submit">保存更改</button>
            </div>
          </div>
          <div class="contact-detail-grid">
            <label>
              <span>所属合作方</span>
              <input value="${contact.partner || "未知合作方"}" disabled>
            </label>
            <label>
              <span>partner_id</span>
              <input value="${contact.partnerId || "N/A"}" disabled>
            </label>
            ${fields.map(renderContactField).join("")}
          </div>
          <div class="contact-detail-profile-grid">
            <section class="summary-box">
              <strong>AI 人物画像</strong>
              <textarea name="ai_profile">${contact.profile || contact.ai_profile || "暂无 AI 画像。"}</textarea>
            </section>
            <section class="summary-box">
              <strong>确认画像</strong>
              <textarea name="confirmed_profile">${contact.confirmed_profile || contact.profile || "待员工确认。"}</textarea>
            </section>
          </div>
        </form>
      </section>
    `;
  }

  function renderContacts() {
    const contacts = filteredStandaloneContacts();
    const contact = contacts.find((item) => item.name === state.selectedContact) || contacts[0] || currentStandaloneContact();
    return `
      <div class="contact-master-layout">
        <section class="panel module-card contact-master-panel">
          <div class="module-head">
            <h2>联系人列表</h2>
          </div>
          <label class="contact-search-box">
            <span>搜索联系人</span>
            <input value="${state.contactSearchQuery}" placeholder="姓名、身份、合作方" data-contact-search>
          </label>
          <div class="contact-master-list">
            ${contacts.length ? contacts.map((item) => {
              const reply = contactReplyState(item);
              return `
              <button class="contact-row contact-master-row ${contact.name === item.name && contact.partner === item.partner ? "active" : ""}" data-contact-name="${item.name}">
                <div class="contact-row-main">
                  <strong>${item.name}</strong>
                  <span>${item.role_title || item.role || item.identity || "职称待补充"} / ${item.partner || "未知合作方"}</span>
                </div>
                <span class="contact-reply-pill ${reply.tone}">${reply.label}</span>
              </button>
            `; }).join("") : `<div class="masked-box">没有匹配的联系人。</div>`}
          </div>
        </section>
        <section class="contact-master-detail panel">
          <div class="contact-master-head">
            <div>
              <button class="contact-name-trigger" type="button" data-contact-detail-open>${contact.name}</button>
              <p>${contact.role || contact.role_title || "职位待补充"} / ${contact.partner || "未知合作方"}</p>
            </div>
          </div>
          <section class="contact-confirmed-profile">
            <strong>确认画像</strong>
            <span>${contact.confirmed_profile || contact.profile || "待员工确认。"}</span>
          </section>
          <div class="contact-topic-section">
            <div class="section-headline">
              <strong>联系人 Topic</strong>
              <span>Topic 直接归入联系人详情，任务在独立任务页管理。</span>
            </div>
            <div class="contact-topic-list">
              ${renderContactTopicCards(contact, "contact-topic-card")}
            </div>
          </div>
        </section>
      </div>
      ${renderContactDetailModal()}
    `;
  }

  function taskBoardCatalog() {
    return {
      blueharbor: {
        template: "客户开发模板",
        partner: "BlueHarbor Imports",
        topic: "洛杉矶航线开发",
        owner: "Elena Wang",
        activeStage: "报价中",
        stages: [
          { name: "新线索", status: "已完成", tasks: 1, ai: 0, updatedAt: "06-12" },
          { name: "联系人确认", status: "已完成", tasks: 2, ai: 0, updatedAt: "06-14" },
          { name: "需求确认", status: "进行中", tasks: 3, ai: 2, updatedAt: "06-15" },
          { name: "报价中", status: "待确认", tasks: 3, ai: 3, updatedAt: "今天" },
          { name: "试单中", status: "未开始", tasks: 0, ai: 0, updatedAt: "-" },
          { name: "成交", status: "未开始", tasks: 0, ai: 0, updatedAt: "-" }
        ],
        tasks: [
          { title: "补充 Ningbo -> LAX 40HQ 报价", source: "AI 建议", object: "Lucy Chen / 洛杉矶航线开发", owner: "Elena Wang", due: "今天 10:00", priority: "高", status: "待确认", stage: "报价中" },
          { title: "确认目的港 handling 和杂费是否单列", source: "模板任务", object: "目的港费用说明", owner: "Elena Wang", due: "今天 16:00", priority: "中", status: "待办", stage: "报价中" },
          { title: "询问目标出运时间和有效期", source: "人工新增", object: "BlueHarbor Imports", contact: "Lucy Chen", owner: "Elena Wang", due: "明天 12:00", priority: "中", status: "进行中", stage: "报价中" }
        ],
        completedTasks: [
          { title: "确认 Lucy Chen 决策角色", source: "模板任务", object: "BlueHarbor Imports", owner: "Elena Wang", due: "06-14", priority: "中", status: "已完成", stage: "联系人确认" },
          { title: "归档 LAX 航线历史询价", source: "人工新增", object: "洛杉矶航线开发", owner: "Elena Wang", due: "06-15", priority: "低", status: "已完成", stage: "需求确认" }
        ],
        suggestions: [
          { action: "推进阶段到「报价中」", reason: "客户已明确 LAX 40HQ、船期和费用拆分需求。", evidence: "邮件 IMCP、WhatsApp 汇总", impact: "更新合作方阶段、Topic 当前进展、生成报价任务" },
          { action: "生成 3 条报价阶段任务", reason: "模板要求报价前确认费用边界、出运时间和报价有效期。", evidence: "客户历史询价与模板任务匹配", impact: "写入 tasks，并归档到「报价中」阶段" },
          { action: "保留员工自定义任务", reason: "员工补充的目标出运时间不改变流程阶段，但能作为执行动作。", evidence: "人工新增说明", impact: "任务来源标记为人工新增，仍绑定标准阶段" }
        ]
      },
      "rhein-cargo": {
        template: "海外代理开发模板",
        partner: "Rhein Cargo GmbH",
        topic: "汉堡拥堵替代方案",
        owner: "David Liu",
        activeStage: "能力确认",
        stages: [
          { name: "代理初筛", status: "已完成", tasks: 1, ai: 0, updatedAt: "06-10" },
          { name: "能力确认", status: "进行中", tasks: 3, ai: 2, updatedAt: "06-14" },
          { name: "账期确认", status: "未开始", tasks: 1, ai: 1, updatedAt: "-" },
          { name: "试合作", status: "未开始", tasks: 0, ai: 0, updatedAt: "-" },
          { name: "稳定合作", status: "未开始", tasks: 0, ai: 0, updatedAt: "-" }
        ],
        tasks: [
          { title: "确认 Rotterdam 替代路径报价", source: "AI 建议", object: "Lukas Weber / 汉堡拥堵替代方案", owner: "David Liu", due: "今天 18:00", priority: "高", status: "待确认", stage: "能力确认" },
          { title: "补充中欧铁路六月下旬舱位", source: "模板任务", object: "中欧铁路班列舱位", owner: "David Liu", due: "明天 11:00", priority: "中", status: "待办", stage: "能力确认" },
          { title: "记录清关责任边界", source: "人工新增", object: "Rhein Cargo GmbH", owner: "Amy Zhao", due: "周五 16:00", priority: "中", status: "进行中", stage: "能力确认" }
        ],
        completedTasks: [
          { title: "确认德国代理覆盖港口", source: "模板任务", object: "Rhein Cargo GmbH", owner: "David Liu", due: "06-10", priority: "中", status: "已完成", stage: "代理初筛" },
          { title: "整理汉堡拥堵沟通纪要", source: "AI 建议", object: "汉堡拥堵替代方案", owner: "David Liu", due: "06-14", priority: "中", status: "已完成", stage: "能力确认" }
        ],
        suggestions: [
          { action: "套用「海外代理开发模板」", reason: "合作对象为海外代理，任务应围绕能力、账期和试合作归档。", evidence: "合作对象类型、邮件 IMCP、企业微信 API", impact: "阶段字典切换为代理开发流程" },
          { action: "生成能力确认任务", reason: "对方正在讨论港口替代和铁路舱位，属于代理能力确认。", evidence: "汉堡拥堵替代方案 Topic", impact: "写入 2 条任务到「能力确认」阶段" }
        ]
      },
      "santos-foods": {
        template: "客户开发模板",
        partner: "Santos Foods Import",
        topic: "Santos 冷链报价",
        owner: "Marco Lin",
        activeStage: "报价中",
        stages: [
          { name: "新线索", status: "已完成", tasks: 1, ai: 0, updatedAt: "06-13" },
          { name: "联系人确认", status: "已完成", tasks: 1, ai: 0, updatedAt: "06-14" },
          { name: "需求确认", status: "已完成", tasks: 2, ai: 1, updatedAt: "06-15" },
          { name: "报价中", status: "进行中", tasks: 3, ai: 2, updatedAt: "今天" },
          { name: "试单中", status: "未开始", tasks: 0, ai: 0, updatedAt: "-" }
        ],
        tasks: [
          { title: "补充 reefer surcharge 和插电费", source: "AI 建议", object: "Camila Torres / Santos 冷链报价", owner: "Marco Lin", due: "今天 15:00", priority: "高", status: "待确认", stage: "报价中" },
          { title: "确认巴西食品类查验文件", source: "模板任务", object: "巴西查验文件", owner: "Marco Lin", due: "明天 10:00", priority: "高", status: "待办", stage: "报价中" },
          { title: "设置报价汇率有效期", source: "人工新增", object: "Santos Foods Import", owner: "Marco Lin", due: "明天 17:00", priority: "中", status: "进行中", stage: "报价中" }
        ],
        completedTasks: [
          { title: "确认冷链货物品名和温区", source: "模板任务", object: "Santos 冷链报价", owner: "Marco Lin", due: "06-15", priority: "高", status: "已完成", stage: "需求确认" }
        ],
        suggestions: [
          { action: "保持阶段为「报价中」", reason: "冷链费用、查验文件和汇率有效期仍未确认完。", evidence: "WhatsApp Business API、邮件 IMCP", impact: "不推进试单，补齐报价阶段任务" },
          { action: "标记文件风险", reason: "食品类文件缺失会影响后续清关。", evidence: "巴西查验文件 Topic", impact: "生成高优先级模板任务" }
        ]
      },
      "shenzhen-orbit": {
        template: "供应商开发模板",
        partner: "Shenzhen Orbit Supply",
        topic: "蛇口拖车旺季价",
        owner: "Amy Zhao",
        activeStage: "供应评估",
        stages: [
          { name: "供应商初筛", status: "已完成", tasks: 1, ai: 0, updatedAt: "06-11" },
          { name: "供应评估", status: "进行中", tasks: 3, ai: 1, updatedAt: "06-12" },
          { name: "服务验证", status: "未开始", tasks: 1, ai: 0, updatedAt: "-" },
          { name: "合作评级", status: "未开始", tasks: 0, ai: 0, updatedAt: "-" }
        ],
        tasks: [
          { title: "确认蛇口/盐田旺季拖车附加费", source: "AI 建议", object: "Chen Rui / 蛇口拖车旺季价", owner: "Amy Zhao", due: "今天 17:30", priority: "中", status: "待确认", stage: "供应评估" },
          { title: "补充报价有效期和车辆响应时间", source: "模板任务", object: "Shenzhen Orbit Supply", owner: "Amy Zhao", due: "明天 12:00", priority: "中", status: "待办", stage: "供应评估" },
          { title: "记录旺季报价波动原因", source: "人工新增", object: "拖车供应评估", owner: "Amy Zhao", due: "周五 10:00", priority: "低", status: "进行中", stage: "供应评估" }
        ],
        completedTasks: [
          { title: "确认深圳拖车服务区域", source: "模板任务", object: "Shenzhen Orbit Supply", owner: "Amy Zhao", due: "06-11", priority: "中", status: "已完成", stage: "供应商初筛" }
        ],
        suggestions: [
          { action: "套用「供应商开发模板」", reason: "合作对象是供应商，不能使用客户开发漏斗。", evidence: "合作对象类型、企业微信 API", impact: "阶段归档到供应评估和服务验证" },
          { action: "补充服务验证前置任务", reason: "价格波动未解释前不应进入服务验证。", evidence: "蛇口拖车旺季价 Topic", impact: "新增供应评估任务" }
        ]
      },
      "aurora-home": {
        template: "客户开发模板",
        partner: "Aurora Home Living",
        topic: "美国家居旺季补货",
        owner: "Elena Wang",
        activeStage: "需求确认",
        stages: [
          { name: "新线索", status: "已完成", tasks: 1, ai: 0, updatedAt: "06-09" },
          { name: "联系人确认", status: "已完成", tasks: 2, ai: 0, updatedAt: "06-12" },
          { name: "需求确认", status: "进行中", tasks: 4, ai: 2, updatedAt: "今天" },
          { name: "报价中", status: "未开始", tasks: 1, ai: 0, updatedAt: "-" },
          { name: "试单中", status: "未开始", tasks: 0, ai: 0, updatedAt: "-" }
        ],
        tasks: [
          { title: "确认 7 月 LA 仓入仓窗口", source: "AI 建议", object: "Mia Carter / 美国家居旺季补货", owner: "Elena Wang", due: "今天 14:00", priority: "高", status: "待确认", stage: "需求确认" },
          { title: "补齐产品体积重和装柜比例", source: "模板任务", object: "Aurora Home Living", owner: "Elena Wang", due: "明天 10:30", priority: "中", status: "待办", stage: "需求确认" },
          { title: "确认是否需要目的港拆柜派送", source: "人工新增", object: "美国家居旺季补货", owner: "Elena Wang", due: "周五 15:00", priority: "中", status: "进行中", stage: "需求确认" }
        ],
        completedTasks: [
          { title: "确认美国采购联系人身份", source: "模板任务", object: "Aurora Home Living", owner: "Elena Wang", due: "06-12", priority: "中", status: "已完成", stage: "联系人确认" }
        ],
        suggestions: [
          { action: "保持在「需求确认」", reason: "入仓窗口、体积重和派送方式未闭合。", evidence: "邮件 IMCP、WhatsApp 汇总", impact: "补齐需求后再进入报价" },
          { action: "生成目的港派送确认任务", reason: "客户提到 LA 仓，但未确认是否需要卡车派送。", evidence: "联系人 Topic 时间线", impact: "新增需求确认阶段任务" }
        ]
      },
      "atlasmed-devices": {
        template: "客户开发模板",
        partner: "AtlasMed Devices",
        topic: "医疗设备欧洲空运",
        owner: "David Liu",
        activeStage: "试单中",
        stages: [
          { name: "新线索", status: "已完成", tasks: 1, ai: 0, updatedAt: "06-02" },
          { name: "联系人确认", status: "已完成", tasks: 2, ai: 0, updatedAt: "06-05" },
          { name: "需求确认", status: "已完成", tasks: 3, ai: 0, updatedAt: "06-08" },
          { name: "报价中", status: "已完成", tasks: 3, ai: 1, updatedAt: "06-12" },
          { name: "试单中", status: "进行中", tasks: 3, ai: 2, updatedAt: "今天" },
          { name: "成交", status: "未开始", tasks: 0, ai: 0, updatedAt: "-" }
        ],
        tasks: [
          { title: "跟进 AMS 首票到港查验结果", source: "AI 建议", object: "Noah Klein / 医疗设备欧洲空运", owner: "David Liu", due: "今天 17:00", priority: "高", status: "待确认", stage: "试单中" },
          { title: "记录温控证明和保险文件", source: "模板任务", object: "医疗设备欧洲空运", owner: "David Liu", due: "明天 11:00", priority: "高", status: "待办", stage: "试单中" },
          { title: "复盘首票时效偏差", source: "人工新增", object: "AtlasMed Devices", owner: "David Liu", due: "周五 12:00", priority: "中", status: "进行中", stage: "试单中" }
        ],
        completedTasks: [
          { title: "确认医疗设备运输条件", source: "模板任务", object: "医疗设备欧洲空运", owner: "David Liu", due: "06-08", priority: "高", status: "已完成", stage: "需求确认" },
          { title: "完成 AMS 试单报价确认", source: "AI 建议", object: "AtlasMed Devices", owner: "David Liu", due: "06-12", priority: "高", status: "已完成", stage: "报价中" }
        ],
        suggestions: [
          { action: "保留试单阶段", reason: "首票查验结果和温控文件仍未确认。", evidence: "电话录入、邮件 IMCP", impact: "暂不推进成交" },
          { action: "生成文件复核任务", reason: "医疗设备需要保留保险和温控证明。", evidence: "开发模板与 Topic 摘要", impact: "新增高优先级任务" }
        ]
      },
      "kanto-retail": {
        template: "客户开发模板",
        partner: "Kanto Retail Chain",
        topic: "日本门店补货计划",
        owner: "Elena Wang",
        activeStage: "联系人确认",
        stages: [
          { name: "新线索", status: "已完成", tasks: 1, ai: 0, updatedAt: "06-15" },
          { name: "联系人确认", status: "进行中", tasks: 2, ai: 1, updatedAt: "今天" },
          { name: "需求确认", status: "未开始", tasks: 1, ai: 0, updatedAt: "-" },
          { name: "报价中", status: "未开始", tasks: 0, ai: 0, updatedAt: "-" },
          { name: "试单中", status: "未开始", tasks: 0, ai: 0, updatedAt: "-" }
        ],
        tasks: [
          { title: "确认采购经理与物流窗口关系", source: "AI 建议", object: "Yuki Tanaka / 日本门店补货计划", owner: "Elena Wang", due: "今天 16:00", priority: "中", status: "待确认", stage: "联系人确认" },
          { title: "补齐联系人电话和时区", source: "模板任务", object: "Kanto Retail Chain", owner: "Elena Wang", due: "明天 10:00", priority: "中", status: "待办", stage: "联系人确认" }
        ],
        completedTasks: [
          { title: "登记 Kanto 初始线索来源", source: "模板任务", object: "Kanto Retail Chain", owner: "Elena Wang", due: "06-15", priority: "低", status: "已完成", stage: "新线索" }
        ],
        suggestions: [
          { action: "先确认联系人身份", reason: "当前只识别到采购经理，物流窗口未确认。", evidence: "企业微信 API、邮件签名", impact: "不直接进入需求确认" },
          { action: "生成联系人补全任务", reason: "缺少电话和时区会影响后续跟进。", evidence: "联系人主档字段缺失", impact: "新增联系人确认阶段任务" }
        ]
      }
    };
  }

  function taskWorkbenchData() {
    const boards = taskBoardCatalog();
    const visibleEntries = Object.entries(boards).filter(([, item]) => !isEmployeeScope() || item.owner === currentEmployeeOwner());
    const fallbackId = visibleEntries[0] ? visibleEntries[0][0] : "blueharbor";
    const selectedIsVisible = visibleEntries.some(([id]) => id === state.selectedTaskPartner);
    if (!selectedIsVisible) state.selectedTaskPartner = fallbackId;
    const board = boards[state.selectedTaskPartner] || boards[fallbackId] || boards.blueharbor;
    const stageNotes = {
      新线索: { title: "新线索阶段任务", tasks: [{ title: "确认线索来源和初步需求", source: "模板任务", object: board.partner, owner: board.owner, due: "已完成", priority: "中", status: "已完成", stage: "新线索" }], suggestions: [{ action: "保持线索归档", reason: "该阶段已完成，保留来源证据即可。", evidence: "历史邮件与联系人记录", impact: "不新增执行任务" }] },
      联系人确认: { title: "联系人确认阶段任务", tasks: [{ title: "确认联系人身份和决策角色", source: "模板任务", object: board.partner, owner: board.owner, due: "已完成", priority: "中", status: "已完成", stage: "联系人确认" }], suggestions: [{ action: "联系人身份已确认", reason: "身份信息已进入联系人主档。", evidence: "邮件签名、聊天昵称、确认画像", impact: "允许后续 Topic 归入联系人" }] },
      需求确认: { title: "需求确认阶段任务", tasks: [{ title: "补齐目的港、柜型、时效和费用边界", source: "模板任务", object: board.topic, owner: board.owner, due: "今天", priority: "中", status: "进行中", stage: "需求确认" }], suggestions: [{ action: "检查需求字段是否完整", reason: "进入报价前必须有目的港、货量、费用边界。", evidence: "Topic 时间线", impact: "缺失字段会生成补充任务" }] },
      报价中: { title: "报价中阶段任务", tasks: board.tasks.filter((task) => task.stage === "报价中"), suggestions: board.suggestions },
      能力确认: { title: "能力确认阶段任务", tasks: board.tasks.filter((task) => task.stage === "能力确认"), suggestions: board.suggestions },
      供应评估: { title: "供应评估阶段任务", tasks: board.tasks.filter((task) => task.stage === "供应评估"), suggestions: board.suggestions },
      服务验证: { title: "服务验证阶段任务", tasks: board.tasks.filter((task) => task.stage === "服务验证"), suggestions: [{ action: "等待供应评估完成", reason: "报价稳定性和响应时间未确认前，不建议进入服务验证。", evidence: "供应评估任务未完成", impact: "当前阶段不生成正式服务验证任务" }] },
      账期确认: { title: "账期确认阶段任务", tasks: board.tasks.filter((task) => task.stage === "账期确认"), suggestions: [{ action: "暂缓账期确认", reason: "能力确认尚未闭合，账期任务暂不展开。", evidence: "开发模板阶段顺序", impact: "保留在标准阶段，等待前置阶段完成" }] },
      试合作: { title: "试合作阶段任务", tasks: board.tasks.filter((task) => task.stage === "试合作"), suggestions: [{ action: "试合作未开始", reason: "前置阶段尚未完成。", evidence: "模板阶段状态", impact: "不生成执行任务" }] },
      试单中: { title: "试单中阶段任务", tasks: board.tasks.filter((task) => task.stage === "试单中"), suggestions: [{ action: "试单尚未开始", reason: "报价阶段仍有待确认任务。", evidence: "报价阶段任务状态", impact: "不推进合作方阶段" }] },
      成交: { title: "成交阶段任务", tasks: [], suggestions: [{ action: "成交阶段未开始", reason: "尚未产生试单完成证据。", evidence: "Topic 时间线无成交事件", impact: "不生成成交任务" }] },
      合作评级: { title: "合作评级阶段任务", tasks: [], suggestions: [{ action: "合作评级未开始", reason: "服务验证尚未完成。", evidence: "供应商开发模板", impact: "等待服务验证后评级" }] },
      稳定合作: { title: "稳定合作阶段任务", tasks: [], suggestions: [{ action: "稳定合作未开始", reason: "试合作阶段未完成。", evidence: "海外代理开发模板", impact: "不推进到稳定合作" }] },
      代理初筛: { title: "代理初筛阶段任务", tasks: [{ title: "确认代理所在市场和服务范围", source: "模板任务", object: board.partner, owner: board.owner, due: "已完成", priority: "中", status: "已完成", stage: "代理初筛" }], suggestions: [{ action: "代理初筛已完成", reason: "基础市场和联系人已确认。", evidence: "合作方资料", impact: "允许进入能力确认" }] },
      供应商初筛: { title: "供应商初筛阶段任务", tasks: [{ title: "确认供应商资源类型和服务区域", source: "模板任务", object: board.partner, owner: board.owner, due: "已完成", priority: "中", status: "已完成", stage: "供应商初筛" }], suggestions: [{ action: "供应商初筛已完成", reason: "基础资源类型已确认。", evidence: "合作方资料", impact: "允许进入供应评估" }] }
    };
    const templateCompletedTasks = Object.values(stageNotes)
      .flatMap((item) => item.tasks || [])
      .filter((task) => task.status === "已完成");
    const expectedEndedTasks = Object.entries(stageNotes)
      .flatMap(([stage, item]) => (item.suggestions || [])
        .filter((suggestion) => String(suggestion.impact || "").includes("不生成"))
        .map((suggestion) => ({
          title: suggestion.action,
          source: "预期任务",
          object: suggestion.evidence || board.topic || board.partner,
          owner: board.owner,
          due: "未触发",
          priority: "低",
          status: "已结束",
          stage,
          infoSource: `${board.template} / ${suggestion.reason}`
        })));
    const completedTasks = [...templateCompletedTasks, ...(board.completedTasks || []), ...expectedEndedTasks];
    return { id: state.selectedTaskPartner, ...board, selectedSuggestions: board.suggestions || [], completedTasks };
  }

  function taskBoardIdForPartnerName(partnerName) {
    return Object.entries(taskBoardCatalog()).find(([, board]) => board.partner === partnerName)?.[0] || state.selectedTaskPartner;
  }

  function taskFilterCatalog() {
    const boards = taskBoardCatalog();
    const entries = Object.entries(boards).filter(([, item]) => !isEmployeeScope() || item.owner === currentEmployeeOwner());
    return {
      partnerOptions: entries.map(([id, item]) => ({
        id,
        name: item.partner
      })),
      ownerOptions: entries
        .map(([, item]) => item.owner)
        .filter((owner, index, list) => list.indexOf(owner) === index)
    };
  }

  function taskKey(task) {
    return [state.selectedTaskPartner, task.stage, task.title].join("::");
  }

  function isTaskExpanded(id) {
    return Boolean(state.taskExpanded[id]);
  }

  function taskContactName(task, board) {
    if (task.contact) return task.contact;
    const contacts = (data.customerTopicProfiles.find((profile) => profile.name === board.partner)?.contacts || [])
      .concat(data.contacts.filter((contact) => contact.partner === board.partner));
    const object = String(task.object || "");
    const matched = contacts.find((contact) => object.includes(contact.name));
    return matched ? matched.name : (contacts[0] ? contacts[0].name : "待确认");
  }

  function taskContextLabel(task, contactName) {
    const object = String(task.object || "").trim();
    if (!object) return "未归档主题";
    const normalized = object.replace(new RegExp(`^${contactName}\\s*/\\s*`), "").trim();
    return normalized || object;
  }

  function taskOwnerName(task, board) {
    const partnerOwner = scopedPartners().find((partner) => partner.name === board.partner)?.owner;
    return partnerOwner || board.owner || task.owner || currentEmployeeOwner();
  }

  function renderTaskPanelFilters(board) {
    const filters = taskFilterCatalog();
    const ownerFilter = isEmployeeScope() ? "" : `
          <label>
            <span>负责人</span>
            <input value="${board.owner}" list="taskOwnerOptions" data-task-owner-filter>
            <datalist id="taskOwnerOptions">${filters.ownerOptions.map((name) => `<option value="${name}"></option>`).join("")}</datalist>
          </label>
    `;
    return `
      <div class="task-panel-controls">
        <div class="task-view-tabs">
          <button class="task-view-tab ${state.taskView === "follow" ? "active" : ""}" type="button" data-task-view="follow">${iconSvg("follow")}<span>需跟进任务</span></button>
          <button class="task-view-tab ${state.taskView === "review" ? "active" : ""}" type="button" data-task-view="review">${iconSvg("review")}<span>需审核任务</span></button>
          <button class="task-view-tab ${state.taskView === "done" ? "active" : ""}" type="button" data-task-view="done">${iconSvg("ended")}<span>已结束任务</span></button>
        </div>
        <div class="task-toolbar-filterbar">
          <label>
            <span>合作方</span>
            <div class="task-partner-picker">
              <button class="task-partner-trigger" type="button" data-task-partner-picker>
                <strong>${board.partner}</strong>
                <span>?</span>
              </button>
              ${state.taskPartnerPickerOpen ? `
                <div class="task-partner-menu">
                  ${filters.partnerOptions.map((item) => `
                    <button class="task-partner-option ${state.selectedTaskPartner === item.id ? "active" : ""}" type="button" data-task-partner-option="${item.id}">
                      ${item.name}
                    </button>
                  `).join("")}
                </div>
              ` : ""}
            </div>
          </label>
          <label>
            <span>时间</span>
            <select data-task-time-filter>
              <option value="all" ${state.taskTimeFilter === "all" ? "selected" : ""}>全部时间</option>
              <option value="today" ${state.taskTimeFilter === "today" ? "selected" : ""}>今天</option>
              <option value="tomorrow" ${state.taskTimeFilter === "tomorrow" ? "selected" : ""}>明天</option>
              <option value="week" ${state.taskTimeFilter === "week" ? "selected" : ""}>本周</option>
              <option value="overdue" ${state.taskTimeFilter === "overdue" ? "selected" : ""}>已逾期</option>
            </select>
          </label>
          ${ownerFilter}
        </div>
      </div>
    `;
  }

  function taskTraceSource(task, board, contactName) {
    if (task.infoSource) return task.infoSource;
    const context = taskContextLabel(task, contactName);
    if (task.source === "AI 建议") return `${contactName} 的信息记录 / ${context}`;
    if (task.source === "模板任务") return `${board.template} / ${context}`;
    if (task.source === "人工新增") return `${contactName} 手动记录 / ${context}`;
    return `${contactName} / ${context}`;
  }

  function taskPriority(task) {
    return state.taskPriorityOverrides[taskKey(task)] || task.priority;
  }

  function renderPrioritySelect(id, priority) {
    return `
      <label class="task-priority-pill ${priority === "高" ? "high" : priority === "中" ? "medium" : "low"}" aria-label="任务优先级">
        <select data-task-priority="${id}">
          ${["高", "中", "低"].map((item) => `<option value="${item}" ${item === priority ? "selected" : ""}>${item}</option>`).join("")}
        </select>
      </label>
    `;
  }

  function taskCompleted(task) {
    return task.status === "已完成" || task.status === "已结束" || task.source === "预期任务" || Boolean(state.taskCompletedOverrides[taskKey(task)]);
  }

  function taskMatchesTime(task) {
    const due = String(task.due || "");
    if (state.taskTimeFilter === "all") return true;
    if (state.taskTimeFilter === "today") return due.includes("今天");
    if (state.taskTimeFilter === "tomorrow") return due.includes("明天");
    if (state.taskTimeFilter === "week") return due.includes("今天") || due.includes("明天") || due.includes("周");
    if (state.taskTimeFilter === "overdue") return due.includes("逾期") || due.includes("已超时");
    return true;
  }

  function visibleTasks(tasks, mode) {
    return tasks.filter((task) => {
      const done = taskCompleted(task);
      if (mode === "done" && !done) return false;
      if (mode !== "done" && done) return false;
      return taskMatchesTime(task);
    });
  }

  function taskCompletedList(board) {
    const manuallyCompleted = (board.tasks || []).filter((task) => Boolean(state.taskCompletedOverrides[taskKey(task)]));
    return visibleTasks([...(board.completedTasks || []), ...manuallyCompleted], "done");
  }

  function reviewTaskDrafts(board) {
    return (board.selectedSuggestions || []).map((item, index) => ({
      title: item.action,
      source: item.evidence || board.topic || board.partner,
      object: board.topic || board.partner,
      contact: taskContactName({ object: item.evidence || board.topic }, board),
      owner: board.owner,
      due: index === 0 ? "今天" : "明天",
      priority: index === 0 ? "高" : "中",
      status: "待审核",
      stage: board.activeStage || (board.stages && board.stages[0] ? board.stages[0].name : "待确认"),
      reason: item.reason,
      impact: item.impact
    }));
  }

  function taskReviewActions(id) {
    return `
      <div class="task-card-review-actions">
        <button class="button success small" type="button" data-task-review-accept="${id}">确认创建</button>
        <button class="button small" type="button" data-task-review-edit="${id}">保存修改</button>
        <button class="button danger small" type="button" data-task-review-ignore="${id}">忽略</button>
      </div>
    `;
  }

  function applyTaskToolbarFilter(input) {
    const value = input.value.trim();
    if (!value) return;
    const boards = taskBoardCatalog();
    const visibleEntries = Object.entries(boards).filter(([, item]) => !isEmployeeScope() || item.owner === currentEmployeeOwner());
    const normalize = (text) => String(text || "").trim().toLowerCase();
    const pickBoard = (predicate) => visibleEntries.find(([, item]) => predicate(item));
    const matchText = (text) => {
      const source = normalize(text);
      const target = normalize(value);
      return source === target || source.includes(target);
    };
    if (input.matches("[data-task-owner-filter]")) {
      const match = pickBoard((item) => matchText(item.owner));
      if (match) state.selectedTaskPartner = match[0];
      renderAll();
      return;
    }
  }

  function renderTasks() {
    const board = taskWorkbenchData();
    const currentTasks = state.taskView === "done"
      ? taskCompletedList(board)
      : visibleTasks(board.tasks, "follow");
    if (state.taskExpanded.__first && currentTasks[0]) {
      state.taskExpanded[taskKey(currentTasks[0])] = true;
    }
    function renderReviewTaskCard(task) {
      const id = taskKey(task);
      const priority = taskPriority(task);
      const contactName = task.contact || taskContactName(task, board);
      const ownerName = taskOwnerName(task, board);
      const expanded = isTaskExpanded(id);
      return `
        <article class="review-task-card ${expanded ? "expanded" : "collapsed"}">
          <div class="task-execution-head">
            <button class="task-card-summary" type="button" data-task-toggle="${id}" aria-expanded="${expanded}">
              <div class="task-title-block">
                <strong>${task.title}</strong>
                <span>${contactName} / ${task.stage}</span>
              </div>
            </button>
            <div class="task-execution-actions">
              ${renderPrioritySelect(id, priority)}
              ${tag("待审核", "blue")}
            </div>
          </div>
          ${expanded ? `
            <div class="task-review-form">
              <label class="wide">
                <span>标题</span>
                <textarea data-task-review-title="${id}" rows="2">${task.title}</textarea>
              </label>
              <label>
                <span>联系人</span>
                <input value="${contactName}" />
              </label>
              <label>
                <span>信息来源</span>
                <input data-task-review-source="${id}" value="${task.source}" />
              </label>
              <label>
                <span>开发流程阶段</span>
                <input value="${task.stage}" />
              </label>
              <label>
                <span>截止时间</span>
                <input value="${task.due}" />
              </label>
              <label>
                <span>负责人</span>
                <input value="${ownerName}" />
              </label>
            </div>
            <div class="task-review-evidence">
              <strong>AI 判断依据</strong>
              <p>${task.reason}</p>
              ${task.impact ? `<p>${task.impact}</p>` : ""}
            </div>
            ${taskReviewActions(id)}
          ` : ""}
        </article>
      `;
    }
    const renderTaskCard = (task, mode) => {
      const id = taskKey(task);
      const priority = taskPriority(task);
      const statusText = mode === "done" ? (task.status === "已结束" ? "已结束" : "已完成") : "待完成";
      const contactName = task.contact || taskContactName(task, board);
      const traceSource = taskTraceSource(task, board, contactName);
      const ownerName = taskOwnerName(task, board);
      const expanded = isTaskExpanded(id);
      return `
        <article class="task-card task-execution-card ${expanded ? "expanded" : "collapsed"}">
          <div class="task-execution-head">
            <button class="task-card-summary" type="button" data-task-toggle="${id}" aria-expanded="${expanded}">
              <div class="task-title-block">
                <strong>${task.title}</strong>
                <span>${contactName} / ${task.stage}</span>
              </div>
            </button>
            <div class="task-execution-actions">
              ${renderPrioritySelect(id, priority)}
              ${tag(statusText, mode === "done" ? "green" : "blue")}
              ${mode === "follow" ? `<button class="button success small" type="button" data-task-complete="${id}">完成</button>` : ""}
            </div>
          </div>
          ${expanded ? `<div class="task-execution-meta">
            <div class="task-meta-item"><span>联系人</span><strong>${contactName}</strong></div>
            <div class="task-meta-item"><span>阶段</span><strong>${task.stage}</strong></div>
            <div class="task-meta-item"><span>截止时间</span><strong>${task.due}</strong></div>
            <div class="task-meta-item"><span>负责人</span><strong>${ownerName}</strong></div>

            <div class="task-meta-item trace"><span>信息来源</span><strong>${traceSource}</strong></div>
          </div>` : ""}
        </article>
      `;
    };
    const taskListPanel = currentTasks.length ? `
        <section class="task-list-panel">
          <div class="task-card-list">
            ${currentTasks.map((task) => renderTaskCard(task, state.taskView === "done" ? "done" : "follow")).join("")}
          </div>
        </section>
      ` : `<div class="task-empty-background">无数据</div>`;
    const followView = `
      <div class="task-workbench-grid ${state.taskView === "done" ? "done" : "follow"}">
        ${taskListPanel}
      </div>
    `;
    const reviewView = `
      <div class="task-workbench-grid review">
        <section class="task-ai-panel">
          <div class="task-card-list">
            ${reviewTaskDrafts(board).length ? reviewTaskDrafts(board).map((task) => renderReviewTaskCard(task)).join("") : `<div class="masked-box">该阶段暂无 AI 建议。</div>`}
          </div>
        </section>
      </div>
    `;
    return `
      <section class="task-board panel">
        <div class="task-board-head">
          <div>
            <h2>${board.partner} 任务</h2>
            <p>${board.template} / ${board.topic}。任务可以由模板、信息记录或人工创建，但必须归档到标准阶段。</p>
          </div>
        </div>
        ${renderTaskPanelFilters(board)}
        ${state.taskView === "review" ? reviewView : followView}
      </section>
    `;
  }

  function renderAiConfig() {
    return `
      <div class="ai-config-layout">
        <section class="ai-api-panel">
          <div class="section-headline">
            <strong>AI API 配置</strong>
            <span>AI 能力配置统一管理模型、密钥、回调和调用边界，业务页面只消费 AI 能力。</span>
          </div>
          <div class="ai-config-form">
            <label><span>模型</span><select><option>GPT-4.1 业务协作</option><option>GPT-4.1 mini 快速摘要</option></select></label>
            <label><span>API Key</span><input value="sk-live-********-logicrm" /></label>
            <label><span>回调地址</span><input value="https://crm.demo/api/ai/callback" /></label>
            <label><span>向量库</span><input value="pgvector / customer-topic-index" /></label>
            <label><span>超时策略</span><input value="30 秒超时，失败进入人工重试队列" /></label>
            <label><span>写入策略</span><input value="AI 仅生成草稿，正式写入需员工确认" /></label>
          </div>
          <div class="ai-config-actions">
            <button class="button primary small" type="button" data-ai-config-action="save">保存配置</button>
            <button class="button small" type="button" data-ai-config-action="test">测试连接</button>
          </div>
        </section>
        <section class="ai-persona-panel">
          <div class="section-headline">
            <strong>AI 画像</strong>
            <span>定义秘书形象、说话方式、业务偏好和默认审核口径。</span>
          </div>
          <div class="ai-persona-grid">
            <label><span>秘书形象</span><input value="谨慎的跨境物流业务助理" /></label>
            <label><span>语气</span><select><option>简洁、可追溯、少打扰</option><option>主动提醒、解释充分</option></select></label>
            <label><span>个性定制</span><textarea>优先提醒报价、船期、目的港费用、联系人身份变化；不直接改写正式数据，只生成可确认草稿。</textarea></label>
            <label><span>审核偏好</span><textarea>高风险合作方、费用口径变化、联系人身份置信度低于 85% 时必须进入人工审核。</textarea></label>
          </div>
        </section>
        <section class="ai-policy-panel">
          <strong>能力边界</strong>
          <div class="fact-grid">
            <span>不直接归档企业 Topic</span>
            <span>先识别联系人再关联合作方</span>
            <span>任务进入独立任务页</span>
            <span>所有写入保留审计记录</span>
          </div>
        </section>
      </div>
    `;
  }

  function renderAiAssistantWidget() {
    const messages = [
      {
        role: "assistant",
        text: "我可以帮你查待审核信息、解释任务来源，或把业务问题沉淀到 AI 知识库。"
      },
      {
        role: "user",
        text: "帮我看今天需要审核的任务。"
      },
      {
        role: "assistant",
        text: "今天有 2 类需要处理：报价阶段任务审核、联系人身份确认。你可以点右上角功能进入对应页面。"
      }
    ];
    return `
      <div class="ai-secretary-widget ${state.aiAssistantOpen ? "open" : ""} ${state.aiAssistantToolsOpen ? "tools-open" : ""}">
        ${state.aiAssistantOpen ? `
          <section class="ai-secretary-surface">
            <section class="ai-secretary-panel">
              <div class="ai-secretary-head">
                <div>
                  <strong>问 AI 秘书</strong>
                  <span>可提问，也可沉淀业务解决方案。</span>
                </div>
                <div class="ai-secretary-head-actions">
                  <button class="icon-button ai-tool-toggle ${state.aiAssistantToolsOpen ? "active" : ""}" type="button" data-ai-tool-toggle aria-label="打开功能侧栏">?</button>
                  <button class="icon-button" type="button" data-ai-assistant-toggle>×</button>
                </div>
              </div>
              <div class="ai-chat-log" aria-label="AI 聊天记录">
                ${messages.map((message) => `
                  <div class="ai-chat-message ${message.role}">
                    <span>${message.role === "assistant" ? "AI" : "我"}</span>
                    <p>${message.text}</p>
                  </div>
                `).join("")}
              </div>
              <div class="ai-chat-composer">
                <button class="icon-button" type="button" aria-label="添加">＋</button>
                <input placeholder="输入问题，例如：这条任务为什么生成？">
                <button class="button primary small" type="button" data-ai-chat-send>发送</button>
              </div>
            </section>
            ${state.aiAssistantToolsOpen ? `
              <aside class="ai-tool-drawer" aria-label="AI 功能入口">
                <div class="ai-tool-drawer-head">
                  <strong>功能</strong>
                  <button class="icon-button" type="button" data-ai-tool-toggle aria-label="收起功能侧栏">×</button>
                </div>
                <button type="button" data-ai-jump="communication"><strong>信息审核</strong><span>归档信息来源和 Topic</span></button>
                <button type="button" data-ai-jump="tasks"><strong>任务审核</strong><span>确认 AI 任务草稿</span></button>
                <button type="button" data-ai-jump="contacts"><strong>联系人确认</strong><span>审核身份和画像</span></button>
                <button type="button" data-ai-jump="settings"><strong>AI 配置</strong><span>模型、画像和边界</span></button>
                <button type="button" data-ai-knowledge-flow><strong>知识库沉淀</strong><span>提交问题和解决方案</span></button>
              </aside>
            ` : ""}
          </section>
        ` : ""}
        <button class="ai-secretary-avatar" type="button" data-ai-assistant-toggle aria-label="打开 AI 秘书">
          <span>AI</span>
        </button>
      </div>
    `;
  }

  function renderDashboard() {
    return `
      ${panel("管理摘要", `
        <div class="stat-grid three compact-stats">
          ${data.dashboard.metrics.map((metric, index) => statCard(metric[0], metric[1], metric[2], index === 2 || index === 5 ? "red" : index === 3 ? "amber" : "blue")).join("")}
        </div>
      `)}

      <div class="two-col">
        ${panel("阶段分布", `
          <div class="bar-list">
            ${data.dashboard.stage.map((row) => `<div class="bar-row"><span>${row[0]}</span><div><i style="width:${row[1] * 4}%"></i></div><strong>${row[1]}</strong></div>`).join("")}
          </div>
        `)}
        ${panel("团队跟进", `
          <div class="list-stack">
            ${data.dashboard.team.map((row) => `<div class="task-row"><div><strong>${row[0]}</strong><span>${row[1]} / ${row[2]} / ${row[3]}</span></div>${tag("查看", "blue")}</div>`).join("")}
          </div>
        `)}
      </div>
    `;
  }

  function settingsTabs() {
    return [
      ["users", "用户与权限", "账号、角色、数据范围"],
      ["dictionary", "业务字典", "客户、联系人、Topic、任务字段"],
      ["channels", "信息渠道", "邮箱、WhatsApp、企微、电话"],
      ["ai", "AI 能力", "模型、画像、写入边界"],
      ["template", "开发模板", "阶段、任务生成、评分权重"],
      ["risk", "风险与黑名单", "屏蔽规则、风险标签"],
      ["audit", "审计日志", "确认、修改、追溯记录"]
    ];
  }

  function renderSettingsTabs() {
    return `
      <div class="settings-tabs" role="tablist" aria-label="系统设置功能">
        ${settingsTabs().map(([id, label, desc]) => `
          <button class="settings-tab ${state.settingsTab === id ? "active" : ""}" type="button" role="tab" aria-selected="${state.settingsTab === id}" data-settings-tab="${id}">
            ${iconSvg(id, label)}
            <strong>${label}</strong>
            <span>${desc}</span>
          </button>
        `).join("")}
      </div>
    `;
  }

  function renderSettingsUsers() {
    return `
      <section class="settings-work-panel">
        <div class="settings-panel-head">
          <div>
            <strong>用户与权限</strong>
            <span>创建系统使用者，配置角色、负责范围和是否允许查看原始信息。</span>
          </div>
          <button class="button primary small icon-label" type="button" data-settings-action="create-user">${iconSvg("add-user")}<span>新增用户</span></button>
        </div>
        <div class="settings-split">
          <form class="settings-form">
            <label><span>邮箱</span><input value="sales01@logicrm.demo" /></label>
            <label><span>姓名</span><input value="Mia Zhang" /></label>
            <label><span>初始密码</span><input value="ChangeMe123!" /></label>
            <label><span>角色</span><select><option>SALES_REP</option><option>SUPERVISOR</option><option>BOSS</option><option>ADMIN</option></select></label>
            <label><span>数据范围</span><select><option>本人负责客户</option><option>团队客户</option><option>全部客户</option><option>指定成员</option></select></label>
            <label><span>账号状态</span><select><option>启用</option><option>停用</option><option>待激活</option></select></label>
          </form>
          <div class="settings-table compact">
            <div><strong>用户</strong><strong>角色</strong><strong>数据范围</strong><strong>状态</strong></div>
            ${[
              ["Mia Zhang", "SALES_REP", "本人负责客户", "启用"],
              ["Ethan Liu", "SUPERVISOR", "华东团队", "启用"],
              ["Grace Wang", "ADMIN", "全部客户", "启用"]
            ].map((row) => `<div>${row.map((cell) => `<span>${cell}</span>`).join("")}</div>`).join("")}
          </div>
        </div>
        <div class="permission-table settings-permission-table">
          <div><strong>功能</strong><strong>员工</strong><strong>主管</strong><strong>老板</strong><strong>管理员</strong></div>
          ${[
            ["原始消息查看", "本人授权", "团队授权", "汇总查看", "配置授权"],
            ["联系人确认", "本人处理", "团队复核", "查看", "配置"],
            ["任务审核", "本人草稿", "团队审核", "查看", "配置"],
            ["系统设置", "否", "否", "只读", "可维护"]
          ].map((row) => `<div>${row.map((cell) => `<span>${cell}</span>`).join("")}</div>`).join("")}
        </div>
      </section>
    `;
  }

  function renderSettingsDictionary() {
    const groups = [
      ["合作方类型", ["客户", "供应商", "海外代理", "同行", "内部", "未知", "其他"]],
      ["联系人身份", ["决策人", "操作对接", "财务", "采购", "销售", "未知"]],
      ["Topic 类型", ["报价", "样品", "订单谈判", "清关资料", "账期", "风险事件"]],
      ["任务类型", ["跟进", "审核", "补充资料", "报价确认", "风险复核", "已结束"]],
      ["风险等级", ["低风险", "中风险", "高风险", "黑名单"]]
    ];
    return `
      <section class="settings-work-panel">
        <div class="settings-panel-head">
          <div>
            <strong>业务字典</strong>
            <span>统一合作方、联系人、Topic、任务的分类口径，避免每个人自由命名造成数据混乱。</span>
          </div>
          <button class="button small icon-label" type="button" data-settings-action="save-dictionary">${iconSvg("save")}<span>保存字典</span></button>
        </div>
        <div class="settings-dictionary-grid">
          ${groups.map(([title, items]) => `
            <div class="settings-dictionary-card">
              <div class="section-headline"><strong>${title}</strong><span>系统字段可选项</span></div>
              <div class="line-wrap">${items.map((item) => tag(item, "blue")).join("")}</div>
            </div>
          `).join("")}
        </div>
      </section>
    `;
  }

  function renderSettingsChannels() {
    return `
      <section class="settings-work-panel">
        <div class="settings-panel-head">
          <div>
            <strong>信息渠道</strong>
            <span>配置消息来源和同步规则，信息先归到联系人，再沉淀 Topic 与任务。</span>
          </div>
          <button class="button primary small icon-label" type="button" data-settings-action="connect-channel">${iconSvg("add")}<span>新增渠道</span></button>
        </div>
        <div class="settings-channel-grid">
          ${[
            ["邮件 IMCP", "sales@logicrm.demo", "同步中", "邮件主题、正文、附件摘要"],
            ["WhatsApp Business API", "+1 213 *** 8840", "同步中", "同一联系人同一天聊天聚合"],
            ["企业微信 API", "wwc_8d21", "同步中", "成员聊天、客户身份线索"],
            ["电话录入", "人工录入表单", "启用", "通话纪要、承诺事项、后续任务"]
          ].map((row) => `
            <div class="settings-channel-card">
              <strong>${row[0]}</strong>
              <span>${row[1]}</span>
              <small>${row[3]}</small>
              ${tag(row[2], "green")}
            </div>
          `).join("")}
        </div>
      </section>
    `;
  }

  function renderSettingsTemplate() {
    const stageGroups = [
      ["01", "\u7acb\u9879\u8c03\u7814", "\u9501\u5b9a\u7684\u5f00\u53d1\u8d77\u70b9\uff0c\u6536\u9f50\u5408\u4f5c\u65b9\u3001\u8054\u7cfb\u4eba\u548c\u57fa\u7840\u9700\u6c42\u8bc1\u636e\u3002", ["\u6765\u6e90\u6838\u5b9e", "\u8054\u7cfb\u4eba\u8eab\u4efd\u786e\u8ba4", "\u4f01\u4e1a\u57fa\u7840\u8d44\u6599\u8865\u5168"]],
      ["02", "\u9700\u6c42\u786e\u8ba4", "\u9501\u5b9a\u7684\u9700\u6c42\u5f52\u6863\u9636\u6bb5\uff0c\u5b50\u6d41\u7a0b\u53ef\u6309\u56e2\u961f\u53e3\u5f84\u8c03\u6574\u3002", ["\u9700\u6c42\u6536\u96c6", "\u63a8\u8350\u65b9\u6848", "\u786e\u8ba4\u65b9\u6848"]],
      ["03", "\u8ba2\u5355\u8c08\u5224", "\u9501\u5b9a\u7684\u8c08\u5224\u5f52\u6863\u9636\u6bb5\uff0c\u7528\u6765\u627f\u63a5\u62a5\u4ef7\u3001\u6761\u6b3e\u548c\u8ba2\u5355\u6587\u4ef6\u3002", ["\u8d39\u7528\u62c6\u5206", "\u8d26\u671f\u786e\u8ba4", "\u8ba2\u5355\u9644\u4ef6\u590d\u6838"]],
      ["04", "\u5408\u4f5c\u5b8c\u6210", "\u9501\u5b9a\u7684\u6210\u4ea4\u5f52\u6863\u9636\u6bb5\uff0c\u8bb0\u5f55\u9996\u5355\u3001\u590d\u76d8\u548c\u957f\u671f\u7ef4\u62a4\u5165\u53e3\u3002", ["\u9996\u5355\u5b8c\u6210", "\u590d\u76d8\u5f52\u6863", "\u8f6c\u5165\u957f\u671f\u7ef4\u62a4"]],
      ["05", "\u6682\u7f13\u5f00\u53d1", "\u9501\u5b9a\u7684\u6682\u505c\u5f52\u6863\u9636\u6bb5\uff0c\u7528\u6765\u4fdd\u7559\u539f\u56e0\u548c\u540e\u7eed\u518d\u542f\u52a8\u6761\u4ef6\u3002", ["\u6682\u7f13\u539f\u56e0", "\u518d\u542f\u52a8\u6761\u4ef6", "\u98ce\u9669\u590d\u6838"]]
    ];
    return `
      <section class="settings-work-panel">
        <div class="settings-panel-head">
          <div>
            <strong>\u5f00\u53d1\u6a21\u677f</strong>
            <span>\u4e3b\u9636\u6bb5\u7531\u7cfb\u7edf\u9501\u5b9a\uff0c\u53ea\u80fd\u5728\u5bf9\u5e94\u4e3b\u9636\u6bb5\u4e0b\u7ef4\u62a4\u5b50\u6d41\u7a0b\u3002</span>
          </div>
          <button class="button small icon-label" type="button" data-settings-action="save-template">${iconSvg("save")}<span>\u4fdd\u5b58\u6a21\u677f</span></button>
        </div>
        <div class="settings-template-layout">
          <div class="settings-stage-group-grid locked">
            ${stageGroups.map(([number, title, desc, substages]) => `
              <section class="settings-stage-group-card">
                <div class="settings-stage-group-head">
                  <span>${number}</span>
                  <div><strong>${title}</strong><small>${desc}</small></div>
                  ${tag("\u9501\u5b9a", "blue")}
                </div>
                <div class="settings-substage-list">
                  ${substages.map((item, index) => `
                    <label class="settings-substage-row">
                      <span>${index + 1}</span>
                      <input value="${item}">
                      <button class="icon-button danger" type="button" data-substage-delete="${title}:${index}" aria-label="\u5220\u9664${item}">${iconSvg("delete")}</button>
                    </label>
                  `).join("")}
                </div>
                <button class="button small icon-label" type="button" data-substage-add="${title}">${iconSvg("add")}<span>\u65b0\u589e\u5b50\u6d41\u7a0b</span></button>
              </section>
            `).join("")}
          </div>
          <div class="settings-weight-box">
            <strong>\u8bc4\u5206\u6743\u91cd</strong>
            ${[
              ["\u8054\u7cfb\u4eba\u8eab\u4efd\u7f6e\u4fe1\u5ea6", "25%"],
              ["\u6700\u8fd1\u6c9f\u901a\u6d3b\u8dc3\u5ea6", "20%"],
              ["\u62a5\u4ef7/\u8ba2\u5355\u610f\u5411", "25%"],
              ["\u98ce\u9669\u4e0e\u9ed1\u540d\u5355\u547d\u4e2d", "20%"],
              ["\u8d44\u6599\u5b8c\u6574\u5ea6", "10%"]
            ].map((row) => `<div><span>${row[0]}</span><strong>${row[1]}</strong></div>`).join("")}
          </div>
        </div>
      </section>
    `;
  }
  function renderSettingsRisk() {
    return `
      <section class="settings-work-panel">
        <div class="settings-panel-head">
          <div>
            <strong>风险与黑名单</strong>
            <span>统一屏蔽规则、风险标签和触发人工审核的条件。</span>
          </div>
          <button class="button danger small icon-label" type="button" data-settings-action="add-blacklist">${iconSvg("risk")}<span>加入黑名单</span></button>
        </div>
        <div class="settings-risk-grid">
          <div class="settings-rule-card"><strong>黑名单企业</strong><span>Santos Foods Import</span><small>多次压价后取消订舱，需主管复核。</small></div>
          <div class="settings-rule-card"><strong>敏感词触发</strong><span>锁价、账期延长、目的港杂费包干</span><small>命中后进入信息审核。</small></div>
          <div class="settings-rule-card"><strong>联系人异常</strong><span>身份置信度低于 85%</span><small>禁止自动确认联系人主档。</small></div>
          <div class="settings-rule-card"><strong>费用口径变化</strong><span>报价、附加费、币种变更</span><small>生成任务草稿并要求人工确认。</small></div>
        </div>
      </section>
    `;
  }

  function renderSettingsAudit() {
    return `
      <section class="settings-work-panel">
        <div class="settings-panel-head">
          <div>
            <strong>审计日志</strong>
            <span>记录 AI 草稿、员工确认、字段修改和任务完成，确保所有结论可追溯。</span>
          </div>
          <button class="button small icon-label" type="button" data-settings-action="export-audit">${iconSvg("export")}<span>导出日志</span></button>
        </div>
        <div class="settings-table audit">
          <div><strong>时间</strong><strong>对象</strong><strong>操作</strong><strong>人员</strong><strong>结果</strong></div>
          ${[
            ["06-18 10:32", "Aurora Home Living / Lucy Chen", "确认联系人画像", "Mia Zhang", "已确认"],
            ["06-18 10:18", "洛杉矶航线开发", "AI 生成任务草稿", "AI 助手", "待审核"],
            ["06-18 09:42", "Rhein Cargo GmbH", "官网分析", "Ethan Liu", "已更新"],
            ["06-17 17:20", "Santos Foods Import", "加入风险名单", "Grace Wang", "主管复核"]
          ].map((row) => `<div>${row.map((cell) => `<span>${cell}</span>`).join("")}</div>`).join("")}
        </div>
      </section>
    `;
  }

  function renderSettingsDictionaryV2() {
    const dictionary = businessDictionary();
    const groups = [
      ["合作方类型", "partnerTypes", "合作方信息配置、列表筛选、权限范围"],
      ["联系人身份", "contactIdentities", "联系人主档、AI 身份识别、合并确认"],
      ["Topic 类型", "topicTypes", "联系人 Topic 归档和时间线筛选"],
      ["任务类型", "taskTypes", "任务审核、跟进任务、已结束任务"],
      ["风险标签", "risks", "合作方背调、风险名单、审核触发"]
    ];
    return `
      <section class="settings-work-panel">
        <div class="settings-panel-head">
          <div>
            <strong>业务字典</strong>
            <span>这些选项会直接进入合作方、联系人、Topic 和任务表单；员工不能绕过字典随便输入口径。</span>
          </div>
          <button class="button small icon-label" type="button" data-settings-action="save-dictionary">${iconSvg("save")}<span>保存字典</span></button>
        </div>
        <div class="settings-dictionary-grid configurable">
          ${groups.map(([title, key, usage]) => `
            <div class="settings-dictionary-card">
              <div class="section-headline"><strong>${title}</strong><span>${usage}</span></div>
              <div class="dictionary-card-actions">
                <input class="dictionary-search-input" value="" placeholder="查询${title}" data-dictionary-search="${key}">
                <button class="button primary small icon-label" type="button" data-dictionary-add="${key}">${iconSvg("add")}<span>新增</span></button>
              </div>
              <div class="dictionary-edit-list">
                ${dictionary[key].map((item, index) => `
                  <label class="dictionary-edit-row">
                    <span>${String(index + 1).padStart(2, "0")}</span>
                    <input value="${item}">
                    ${tag(index < 3 ? "常用" : "可维护", index < 3 ? "green" : "blue")}
                    <button class="icon-button danger" type="button" data-dictionary-delete="${key}:${index}" aria-label="删除${item}">${iconSvg("delete")}</button>
                  </label>
                `).join("")}
              </div>
            </div>
          `).join("")}
        </div>
      </section>
    `;
  }
  function renderSettings() {
    const content = {
      users: renderSettingsUsers,
      dictionary: renderSettingsDictionaryV2,
      channels: renderSettingsChannels,
      ai: renderAiConfig,
      template: renderSettingsTemplate,
      risk: renderSettingsRisk,
      audit: renderSettingsAudit
    };
    return `
      <section class="settings-shell">
        <div class="settings-hero">
          <div>
            <span>CRM SETTINGS</span>
            <h2>\u7cfb\u7edf\u8bbe\u7f6e</h2>
            <p>\u6309\u529f\u80fd\u7ef4\u62a4\u540e\u53f0\u89c4\u5219\u3002\u8fd9\u91cc\u914d\u7f6e\u7684\u662f\u7cfb\u7edf\u53e3\u5f84\uff0c\u4e1a\u52a1\u9875\u9762\u53ea\u6d88\u8d39\u8fd9\u4e9b\u89c4\u5219\uff0c\u4e0d\u5728\u9875\u9762\u91cc\u79c1\u81ea\u521b\u9020\u5b57\u6bb5\u3002</p>
          </div>
          <button class="button small" type="button" data-settings-logout>\u9000\u51fa\u5f53\u524d\u6f14\u793a\u8d26\u53f7</button>
        </div>
        ${renderSettingsTabs()}
        ${(content[state.settingsTab] || content.users)()}
      </section>
    `;
  }
  function renderPageContent() {
    const renderers = {
      workbench: renderWorkbench,
      communication: renderCommunication,
      partners: renderPartners,
      contacts: renderContacts,
      tasks: renderTasks,
      dashboard: renderDashboard,
      settings: renderSettings
    };
    $("#pageContent").innerHTML = (renderers[state.page] || renderers.settings)();
  }

  function renderAll() {
    renderNav();
    renderBreadcrumb();
    renderPageTitle();
    renderRoleSwitch();
    renderToolbar();
    renderPageContent();
    document.querySelector("#aiSecretaryHost").innerHTML = renderAiAssistantWidget();
  }

  function renderAllKeepingScroll(selector) {
    const scroller = document.querySelector(selector);
    const scrollTop = scroller ? scroller.scrollTop : 0;
    renderAll();
    const nextScroller = document.querySelector(selector);
    if (nextScroller) nextScroller.scrollTop = scrollTop;
  }

  function goToStep(index) {
    state.step = Math.max(0, Math.min(data.workflow.length - 1, index));
    state.page = data.workflow[state.step].page;
    if (state.step >= 2) state.accepted.identity = true;
    if (state.step >= 3) state.accepted.topic = true;
    if (state.step >= 4) state.accepted.task = true;
    if (!canViewRaw()) state.rawVisible = false;
    renderAll();
  }

  function handleSuggestion(action) {
    const suggestion = selectedSuggestion();
    if (action === "edit") {
      const box = document.querySelector(".edit-box");
      box.classList.toggle("visible");
      box.focus();
      return;
    }
    if (action === "reject") {
      toast("已拒绝该 AI 建议。系统保留拒绝原因与审计记录。");
      return;
    }
    if (suggestion.type === "身份识别") {
      state.accepted.identity = true;
      state.step = Math.max(state.step, 2);
      state.page = "contacts";
      toast(action === "editAccept" ? "已修改后确认联系人身份。" : "已确认联系人身份。");
    } else if (suggestion.type === "Topic 建议") {
      state.accepted.topic = true;
      state.step = Math.max(state.step, 3);
      state.page = "contacts";
      toast(action === "editAccept" ? "已修改后确认 Topic。" : "已确认 Topic 更新。");
    } else if (suggestion.type === "任务与阶段建议") {
      state.accepted.task = true;
      state.step = Math.max(state.step, 4);
      state.page = "tasks";
      toast(action === "editAccept" ? "已修改后确认任务和阶段。" : "已确认任务和阶段。");
    }
    renderAll();
  }

  function bindEvents() {
    $("#nextStep").addEventListener("click", () => {
      goToStep(state.step + 1);
      toast(`下一步：${data.workflow[state.step].title}`);
    });

    $("#resetDemo").addEventListener("click", () => {
      state.page = "workbench";
      state.step = 0;
      state.selectedPartner = "blueharbor";
      state.selectedContact = "Lucy Chen";
      state.expandedPartnerTopic = "洛杉矶航线开发";
      state.selectedMessage = "mail-001";
      state.selectedTopic = "洛杉矶航线开发";
      state.selectedSuggestion = 0;
      state.customerAggregate = "month";
      state.progressAggregate = "month";
      state.selectedCustomerId = "blueharbor";
      state.rawVisible = false;
      state.statsOpen = false;
      state.accepted.identity = false;
      state.accepted.topic = false;
      state.accepted.task = false;
      state.accepted.profile = false;
      syncScopeSelection();
      renderAll();
      toast("演示已重置。");
    });

    document.body.addEventListener("click", (event) => {
      const pageTarget = event.target.closest("[data-page]");
      if (pageTarget) {
        state.page = pageTarget.dataset.page;
        if (pageTarget.dataset.settingsTab) state.settingsTab = pageTarget.dataset.settingsTab;
        renderAll();
      }

      const roleTarget = event.target.closest("[data-role]");
      if (roleTarget) {
        state.role = roleTarget.dataset.role;
        if (!canViewRaw()) state.rawVisible = false;
        resetPartnerFilters();
        syncScopeSelection();
        renderAll();
        toast(`已切换到${currentRole().label}视角。`);
      }

      const partnerDetailTarget = event.target.closest("[data-open-partner-detail]");
      if (partnerDetailTarget) {
        state.selectedPartner = partnerDetailTarget.dataset.openPartnerDetail;
        state.partnerView = "detail";
        state.partnerDetailTab = "contacts";
        const contacts = partnerContacts();
        state.selectedContact = contacts[0] ? contacts[0].name : state.selectedContact;
        state.expandedPartnerTopic = firstContactTopicName(currentContact());
        renderAll();
        return;
      }

      if (event.target.closest("[data-apply-partner-filters]")) {
        applyPartnerFilters();
        renderPageContent();
        return;
      }

      if (event.target.closest("[data-reset-partner-filters]")) {
        resetPartnerFilters();
        renderPageContent();
        return;
      }

      if (event.target.closest("[data-new-contact]")) {
        state.contactDetailMode = "create";
        state.contactDetailOpen = true;
        renderAll();
        return;
      }

      if (event.target.closest("[data-ai-assistant-toggle]")) {
        state.aiAssistantOpen = !state.aiAssistantOpen;
        if (!state.aiAssistantOpen) state.aiAssistantToolsOpen = false;
        renderAll();
        return;
      }

      if (event.target.closest("[data-ai-tool-toggle]")) {
        state.aiAssistantToolsOpen = !state.aiAssistantToolsOpen;
        renderAll();
        return;
      }

      const aiJumpTarget = event.target.closest("[data-ai-jump]");
      if (aiJumpTarget) {
        const target = aiJumpTarget.dataset.aiJump === "ai" ? "settings" : aiJumpTarget.dataset.aiJump;
        state.page = target;
        if (target === "tasks") state.taskView = "review";
        if (target === "partners") state.partnerView = "list";
        if (target === "settings") state.settingsTab = "ai";
        state.aiAssistantOpen = false;
        state.aiAssistantToolsOpen = false;
        renderAll();
        toast("已跳转到对应的 AI 内容处理入口。");
        return;
      }

      const settingsTabTarget = event.target.closest("[data-settings-tab]");
      if (settingsTabTarget) {
        state.settingsTab = settingsTabTarget.dataset.settingsTab;
        renderAll();
        return;
      }

      const dictionaryAddTarget = event.target.closest("[data-dictionary-add]");
      if (dictionaryAddTarget) {
        toast("已打开字典新增行，保存后生效。");
        return;
      }

      const dictionaryDeleteTarget = event.target.closest("[data-dictionary-delete]");
      if (dictionaryDeleteTarget) {
        toast("已标记删除，保存字典后生效。");
        return;
      }

      const substageAddTarget = event.target.closest("[data-substage-add]");
      if (substageAddTarget) {
        toast("已添加子流程空行，保存模板后生效。");
        return;
      }

      const substageDeleteTarget = event.target.closest("[data-substage-delete]");
      if (substageDeleteTarget) {
        toast("已标记删除子流程，保存模板后生效。");
        return;
      }

      const settingsActionTarget = event.target.closest("[data-settings-action]");
      if (settingsActionTarget) {
        const actionText = {
          "create-user": "用户已新增到演示列表。",
          "save-dictionary": "业务字典配置已保存。",
          "connect-channel": "已打开新增渠道配置入口。",
          "save-template": "开发模板和评分权重已保存。",
          "add-blacklist": "黑名单规则已提交审核。",
          "export-audit": "审计日志已导出。"
        };
        toast(actionText[settingsActionTarget.dataset.settingsAction] || "设置已保存。");
        return;
      }

      if (event.target.closest("[data-settings-logout]")) {
        toast("演示账号已退出。");
        return;
      }

      const aiConfigAction = event.target.closest("[data-ai-config-action]");
      if (aiConfigAction) {
        toast(aiConfigAction.dataset.aiConfigAction === "test" ? "AI API 连接测试已通过。" : "AI 配置已保存。");
        return;
      }

      if (event.target.closest("[data-ai-knowledge-flow]")) {
        toast("已进入知识库沉淀流程，可提交问题和解决方案。");
        return;
      }

      if (event.target.closest("[data-ai-chat-send]")) {
        toast("消息已发送，AI 正在生成可追溯回答。");
        return;
      }

      if (event.target.closest("[data-new-task]")) {
        state.taskView = "review";
        renderAll();
        toast("已打开任务草稿区，可从信息来源创建新任务。");
        return;
      }

      if (event.target.closest("[data-back-partner-list]")) {
        state.partnerView = "list";
        renderAll();
        return;
      }

      const partnerTabTarget = event.target.closest("[data-partner-tab]");
      if (partnerTabTarget) {
        state.partnerDetailTab = partnerTabTarget.dataset.partnerTab;
        renderAll();
        return;
      }

      if (event.target.closest("[data-start-website-analysis]")) {
        toast("已开始官网分析，分析完成后可确认或修改结果。");
        return;
      }

      if (event.target.closest("[data-start-due-report]")) {
        toast("已开始背调报告，报告完成后可确认或修改结论。");
        return;
      }

      const partnerConfirmTarget = event.target.closest("[data-confirm-partner-tab]");
      if (partnerConfirmTarget) {
        toast("当前子页内容已确认。");
        return;
      }

      const partnerEditTarget = event.target.closest("[data-edit-partner-tab]");
      if (partnerEditTarget) {
        toast("已进入修改状态，可调整当前子页内容。");
        return;
      }

      if (event.target.closest("[data-upload-order-result]")) {
        toast("订单谈判结果已上传，并归档到当前合作方。");
        return;
      }

      const contactTarget = event.target.closest("[data-contact-name]");
      if (contactTarget) {
        state.selectedContact = contactTarget.dataset.contactName;
        state.expandedPartnerTopic = firstContactTopicName(state.page === "contacts" ? currentStandaloneContact() : currentContact());
        renderAll();
        return;
      }

      const partnerTopicToggle = event.target.closest("[data-partner-topic-toggle]");
      if (partnerTopicToggle) {
        const topicName = partnerTopicToggle.dataset.partnerTopicToggle;
        state.expandedPartnerTopic = state.expandedPartnerTopic === topicName ? "" : topicName;
        renderAllKeepingScroll(state.page === "contacts" ? ".contact-topic-list" : ".partner-topic-list");
        return;
      }

      const taskToggleTarget = event.target.closest("[data-task-toggle]");
      if (taskToggleTarget) {
        const key = taskToggleTarget.dataset.taskToggle;
        state.taskExpanded[key] = !state.taskExpanded[key];
        renderAll();
        return;
      }
      const taskViewTarget = event.target.closest("[data-task-view]");
      if (taskViewTarget) {
        state.taskView = taskViewTarget.dataset.taskView;
        state.taskPartnerPickerOpen = false;
        renderAll();
        return;
      }

      if (event.target.closest("[data-task-partner-picker]")) {
        state.taskPartnerPickerOpen = !state.taskPartnerPickerOpen;
        renderAll();
        return;
      }

      const taskPartnerOption = event.target.closest("[data-task-partner-option]");
      if (taskPartnerOption) {
        const allowed = taskFilterCatalog().partnerOptions.some((item) => item.id === taskPartnerOption.dataset.taskPartnerOption);
        if (allowed) state.selectedTaskPartner = taskPartnerOption.dataset.taskPartnerOption;
        state.taskPartnerPickerOpen = false;
        renderAll();
        return;
      }

      const taskCompleteTarget = event.target.closest("[data-task-complete]");
      if (taskCompleteTarget) {
        state.taskCompletedOverrides[taskCompleteTarget.dataset.taskComplete] = true;
        renderAll();
        toast("任务已标记完成。");
        return;
      }

      if (event.target.closest("[data-contact-detail-open]")) {
        state.contactDetailMode = "edit";
        state.contactDetailOpen = true;
        renderAll();
        return;
      }

      if (event.target.closest("[data-close-contact-detail]")) {
        state.contactDetailOpen = false;
        state.contactDetailMode = "edit";
        renderAll();
        return;
      }

      const messageTarget = event.target.closest("[data-message-id]");
      if (messageTarget) {
        state.selectedMessage = messageTarget.dataset.messageId;
        state.rawVisible = false;
        state.summaryEditVisible = false;
        state.topicPickerOpen = false;
        renderAll();
      }

      const messageDetailTarget = event.target.closest("[data-message-detail]");
      if (messageDetailTarget) {
        state.selectedMessage = messageDetailTarget.dataset.messageDetail;
        state.messageDetailOpen = true;
        state.rawVisible = false;
        state.summaryEditVisible = false;
        state.topicPickerOpen = false;
        renderAll();
        return;
      }

      if (event.target.closest("[data-close-message-detail]")) {
        state.messageDetailOpen = false;
        state.topicPickerOpen = false;
        renderAll();
        return;
      }

      const companyChannelTarget = event.target.closest("[data-company-channel]");
      if (companyChannelTarget) {
        const company = companyChannelTarget.dataset.companyChannel;
        state.companyChannelTabs[company] = companyChannelTarget.dataset.channelName;
        state.companyPages[company] = 1;
        renderAllKeepingScroll(".content-shell");
        return;
      }

      const companyPageTarget = event.target.closest("[data-company-page]");
      if (companyPageTarget) {
        const company = companyPageTarget.dataset.companyPage;
        const current = Number(state.companyPages[company] || 1);
        state.companyPages[company] = Math.max(1, current + Number(companyPageTarget.dataset.pageDir));
        renderAllKeepingScroll(".content-shell");
        return;
      }

      const companyToggleTarget = event.target.closest("[data-company-toggle]");
      if (companyToggleTarget) {
        const company = companyToggleTarget.dataset.companyToggle;
        state.companyCardsOpen[company] = !state.companyCardsOpen[company];
        renderAllKeepingScroll(".content-shell");
        return;
      }

      const openCustomerDetail = event.target.closest("[data-open-customer-detail]");
      if (openCustomerDetail) {
        state.selectedCustomerId = openCustomerDetail.dataset.openCustomerDetail;
        state.customerDetailOpen = true;
        renderAll();
      }

      if (event.target.closest("[data-close-customer-detail]")) {
        state.customerDetailOpen = false;
        renderAll();
      }

      const suggestionIndex = event.target.closest("[data-suggestion-index]");
      if (suggestionIndex) {
        state.selectedSuggestion = Number(suggestionIndex.dataset.suggestionIndex);
        renderAll();
      }

      const aggregateTarget = event.target.closest("[data-aggregate]");
      if (aggregateTarget) {
        state.aggregate = aggregateTarget.dataset.aggregate;
        renderAll();
      }

      const workbenchAggregateTarget = event.target.closest("[data-workbench-aggregate]");
      if (workbenchAggregateTarget) {
        state.customerAggregate = workbenchAggregateTarget.dataset.workbenchAggregate;
        const group = currentWorkbenchCustomerGroup();
        state.selectedCustomerId = group.rows[0] ? group.rows[0].id : state.selectedCustomerId;
        renderAll();
      }

      const customerAggregateTarget = event.target.closest("[data-customer-aggregate]");
      if (customerAggregateTarget) {
        state.progressAggregate = customerAggregateTarget.dataset.customerAggregate;
        renderAll();
      }

      if (event.target.closest("[data-open-stats]")) {
        state.statsOpen = true;
        renderAll();
      }

      if (event.target.closest("[data-close-stats]")) {
        state.statsOpen = false;
        renderAll();
      }

      if (event.target.classList.contains("stats-overlay")) {
        state.statsOpen = false;
        renderAll();
      }

      if (event.target.classList.contains("detail-overlay")) {
        state.customerDetailOpen = false;
        state.channelFormOpen = false;
        state.messageDetailOpen = false;
        renderAll();
      }

      if (event.target.closest("[data-open-channel-config]")) {
        state.channelFormOpen = true;
        renderAll();
        return;
      }

      const channelTab = event.target.closest("[data-channel-tab]");
      if (channelTab) {
        state.selectedChannel = channelTab.dataset.channelTab;
        renderAll();
        return;
      }

      if (event.target.closest("[data-close-channel-form]")) {
        state.channelFormOpen = false;
        renderAll();
        return;
      }
      const mergeTopicTarget = event.target.closest("[data-merge-topic]");
      if (mergeTopicTarget) {
        const message = currentMessage();
        const topicName = mergeTopicTarget.dataset.mergeTopic;
        state.topicArchives[message.id] = {
          topicName,
          contactName: message.contact,
          mode: "人工确认"
        };
        state.topicPickerOpen = false;
        renderAll();
        toast(`已并入 ${message.contact} / ${topicName}。`);
        return;
      }
      if (event.target.closest("[data-topic-picker]")) {
        state.topicPickerOpen = !state.topicPickerOpen;
        renderAll();
        return;
      }
      if (event.target.closest("[data-create-contact-topic]")) {
        const message = currentMessage();
        const topicName = fallbackTopicName(message);
        state.topicArchives[message.id] = {
          topicName,
          contactName: message.contact,
          mode: "新建联系人 Topic"
        };
        state.topicPickerOpen = false;
        renderAll();
        toast(`已新建 ${message.contact} / ${topicName}。`);
        return;
      }
      const channelAction = event.target.closest("[data-channel-action]");
      if (channelAction) {
        toast(`${channelAction.dataset.channelAction}：演示环境已展示配置入口。`);
      }
      const summaryAction = event.target.closest("[data-summary-action]");
      if (summaryAction) {
        const action = summaryAction.dataset.summaryAction;
        if (action === "edit") {
          state.summaryEditVisible = !state.summaryEditVisible;
          renderAll();
          const box = document.querySelector(".summary-control .edit-box");
          if (box) box.focus();
          return;
        }
        state.summaryEditVisible = false;
        const text = {
          confirm: "已确定 AI 摘要，信息进入后续归档流程。",
          delete: "已删除 AI 摘要，保留原始信息等待重新处理。"
        }[action];
        renderAll();
        toast(text || "已处理 AI 摘要。");
        return;
      }
      const suggestionAction = event.target.closest("[data-suggestion-action]");
      if (suggestionAction) {
        handleSuggestion(suggestionAction.dataset.suggestionAction);
      }

      const confirmAction = event.target.closest("[data-confirm-action]");
      if (confirmAction) {
        const action = confirmAction.dataset.confirmAction;
        state.accepted[action] = true;
        const text = {
          identity: "已确认联系人身份。",
          profile: "已确认联系人画像。",
          topic: "已确认联系人关联 Topic。"
        }[action];
        renderAll();
        toast(text);
      }

      if (event.target.id === "toggleRaw") {
        if (!canViewRaw()) {
          state.rawVisible = false;
          toast("当前角色默认不能查看信息原文。只显示摘要。");
        } else {
          state.rawVisible = !state.rawVisible;
        }
        renderAll();
      }

      if (state.taskPartnerPickerOpen && !event.target.closest(".task-partner-picker")) {
        state.taskPartnerPickerOpen = false;
        renderAll();
      }
    });

    document.body.addEventListener("change", (event) => {
      if (event.target.matches("[data-task-owner-filter]")) {
        if (event.inputType === "insertCompositionText" || event.isComposing) return;
        applyTaskToolbarFilter(event.target);
      }
      if (event.target.matches("[data-task-time-filter]")) {
        state.taskTimeFilter = event.target.value;
        state.taskPartnerPickerOpen = false;
        renderAll();
      }
      if (event.target.matches("[data-task-priority]")) {
        state.taskPriorityOverrides[event.target.dataset.taskPriority] = event.target.value;
        renderAll();
      }
    });

    document.body.addEventListener("wheel", (event) => {
      const partnerTopicList = event.target.closest(".partner-topic-list");
      if (partnerTopicList) {
        const canScroll = partnerTopicList.scrollHeight > partnerTopicList.clientHeight + 1;
        if (!canScroll) return;
        const atTop = partnerTopicList.scrollTop <= 0;
        const atBottom = Math.ceil(partnerTopicList.scrollTop + partnerTopicList.clientHeight) >= partnerTopicList.scrollHeight;
        if ((event.deltaY < 0 && atTop) || (event.deltaY > 0 && atBottom)) {
          event.preventDefault();
          return;
        }
        event.stopPropagation();
        return;
      }

      const menu = event.target.closest(".task-partner-menu");
      if (!menu) return;
      const atTop = menu.scrollTop <= 0;
      const atBottom = Math.ceil(menu.scrollTop + menu.clientHeight) >= menu.scrollHeight;
      if ((event.deltaY < 0 && atTop) || (event.deltaY > 0 && atBottom)) {
        event.preventDefault();
        return;
      }
      event.stopPropagation();
    }, { passive: false });

    document.body.addEventListener("keydown", (event) => {
      if (event.key === "Enter" && event.target.matches("[data-task-owner-filter]")) {
        applyTaskToolbarFilter(event.target);
      }
    });

    document.body.addEventListener("submit", (event) => {
      if (event.target.matches("[data-contact-detail-form]")) {
        event.preventDefault();
        saveContactDetail(event.target);
      }
    });

    document.body.addEventListener("input", (event) => {
      if (event.target.matches("[data-company-query]")) {
        state.informationCompanyQuery = event.target.value;
        renderPageContent();
      }

      if (event.target.matches("[data-company-date]")) {
        const company = event.target.dataset.companyDate;
        state.companyDateFilters[company] = event.target.value;
        state.companyPages[company] = 1;
        renderPageContent();
      }

      if (event.target.matches("[data-contact-search]")) {
        if (state.contactSearchComposing || event.isComposing) return;
        applyContactSearch(event.target);
      }

      if (event.target.matches("[data-partner-name-filter]")) {
        state.partnerListFilterDrafts.name = event.target.value;
      }

      if (event.target.matches("[data-partner-owner-filter]")) {
        state.partnerListFilterDrafts.owner = event.target.value;
      }

      if (event.target.matches("[data-partner-date-filter]")) {
        state.partnerListFilterDrafts.date = event.target.value;
      }
    });

    document.body.addEventListener("compositionstart", (event) => {
      if (event.target.matches("[data-contact-search]")) {
        state.contactSearchComposing = true;
      }
    });

    document.body.addEventListener("compositionend", (event) => {
      if (event.target.matches("[data-contact-search]")) {
        state.contactSearchComposing = false;
        applyContactSearch(event.target);
      }
    });

    document.body.addEventListener("keydown", (event) => {
      if (event.key === "Enter" && event.target.matches("[data-partner-name-filter], [data-partner-owner-filter], [data-partner-date-filter]")) {
        applyPartnerFilters();
        renderPageContent();
      }
    });
  }

  bindEvents();
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && (state.statsOpen || state.channelFormOpen || state.customerDetailOpen || state.messageDetailOpen || state.contactDetailOpen)) {
      state.statsOpen = false;
      state.channelFormOpen = false;
      state.customerDetailOpen = false;
      state.messageDetailOpen = false;
      state.contactDetailOpen = false;
      state.contactDetailMode = "edit";
      renderAll();
    }
  });
  renderAll();
})();
