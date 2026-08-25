const whatsappTemplateLanguages = [
  { value: 'zh_CN', label: '简体中文' },
  { value: 'zh_HK', label: '繁体中文（香港）' },
  { value: 'zh_TW', label: '繁体中文（台湾）' },
  { value: 'en_US', label: '英语（美国）' },
  { value: 'en_GB', label: '英语（英国）' },
  { value: 'en', label: '英语' },
  { value: 'es_ES', label: '西班牙语（西班牙）' },
  { value: 'es_MX', label: '西班牙语（墨西哥）' },
  { value: 'es_AR', label: '西班牙语（阿根廷）' },
  { value: 'es', label: '西班牙语' },
  { value: 'pt_BR', label: '葡萄牙语（巴西）' },
  { value: 'pt_PT', label: '葡萄牙语（葡萄牙）' },
  { value: 'fr', label: '法语' },
  { value: 'de', label: '德语' },
  { value: 'it', label: '意大利语' },
  { value: 'ja', label: '日语' },
  { value: 'ko', label: '韩语' },
  { value: 'af', label: '南非荷兰语' },
  { value: 'sq', label: '阿尔巴尼亚语' },
  { value: 'ar', label: '阿拉伯语' },
  { value: 'az', label: '阿塞拜疆语' },
  { value: 'bn', label: '孟加拉语' },
  { value: 'bg', label: '保加利亚语' },
  { value: 'ca', label: '加泰罗尼亚语' },
  { value: 'hr', label: '克罗地亚语' },
  { value: 'cs', label: '捷克语' },
  { value: 'da', label: '丹麦语' },
  { value: 'nl', label: '荷兰语' },
  { value: 'et', label: '爱沙尼亚语' },
  { value: 'fil', label: '菲律宾语' },
  { value: 'fi', label: '芬兰语' },
  { value: 'ka', label: '格鲁吉亚语' },
  { value: 'el', label: '希腊语' },
  { value: 'gu', label: '古吉拉特语' },
  { value: 'ha', label: '豪萨语' },
  { value: 'he', label: '希伯来语' },
  { value: 'hi', label: '印地语' },
  { value: 'hu', label: '匈牙利语' },
  { value: 'id', label: '印度尼西亚语' },
  { value: 'ga', label: '爱尔兰语' },
  { value: 'kn', label: '卡纳达语' },
  { value: 'kk', label: '哈萨克语' },
  { value: 'lo', label: '老挝语' },
  { value: 'lv', label: '拉脱维亚语' },
  { value: 'lt', label: '立陶宛语' },
  { value: 'mk', label: '马其顿语' },
  { value: 'ms', label: '马来语' },
  { value: 'ml', label: '马拉雅拉姆语' },
  { value: 'mr', label: '马拉地语' },
  { value: 'nb', label: '挪威语' },
  { value: 'fa', label: '波斯语' },
  { value: 'pl', label: '波兰语' },
  { value: 'pa', label: '旁遮普语' },
  { value: 'ro', label: '罗马尼亚语' },
  { value: 'ru', label: '俄语' },
  { value: 'sr', label: '塞尔维亚语' },
  { value: 'sk', label: '斯洛伐克语' },
  { value: 'sl', label: '斯洛文尼亚语' },
  { value: 'sw', label: '斯瓦希里语' },
  { value: 'sv', label: '瑞典语' },
  { value: 'ta', label: '泰米尔语' },
  { value: 'te', label: '泰卢固语' },
  { value: 'th', label: '泰语' },
  { value: 'tr', label: '土耳其语' },
  { value: 'uk', label: '乌克兰语' },
  { value: 'ur', label: '乌尔都语' },
  { value: 'uz', label: '乌兹别克语' },
  { value: 'vi', label: '越南语' },
  { value: 'zu', label: '祖鲁语' },
] as const;

export type WhatsAppTemplateLanguageCode = typeof whatsappTemplateLanguages[number]['value'];

export const WHATSAPP_TEMPLATE_LANGUAGE_OPTIONS: Array<{
  value: WhatsAppTemplateLanguageCode;
  label: string;
}> = whatsappTemplateLanguages.map((option) => ({ ...option }));

const languageCodes = new Set<string>(
  WHATSAPP_TEMPLATE_LANGUAGE_OPTIONS.map((option) => option.value),
);

export function isWhatsAppTemplateLanguage(
  value: string | null | undefined,
): value is WhatsAppTemplateLanguageCode {
  return typeof value === 'string' && languageCodes.has(value);
}
