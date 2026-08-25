import { describe, expect, it } from 'vitest';
import { WHATSAPP_TEMPLATE_LANGUAGE_OPTIONS, isWhatsAppTemplateLanguage } from './whatsappLanguages';

describe('WhatsApp template language catalog', () => {
  it('keeps the default language first and includes common CAMS language codes', () => {
    expect(WHATSAPP_TEMPLATE_LANGUAGE_OPTIONS[0]).toEqual({ value: 'zh_CN', label: '简体中文' });
    expect(WHATSAPP_TEMPLATE_LANGUAGE_OPTIONS.map((option) => option.value)).toEqual([
      ...new Set(WHATSAPP_TEMPLATE_LANGUAGE_OPTIONS.map((option) => option.value)),
    ]);
    expect(WHATSAPP_TEMPLATE_LANGUAGE_OPTIONS.map((option) => option.value)).toEqual(
      expect.arrayContaining(['en_US', 'es_ES', 'fr', 'de', 'ja', 'pt_BR']),
    );
  });

  it('recognizes catalog languages without accepting unsupported regional guesses', () => {
    expect(isWhatsAppTemplateLanguage('es_ES')).toBe(true);
    expect(isWhatsAppTemplateLanguage('fr')).toBe(true);
    expect(isWhatsAppTemplateLanguage('fr_FR')).toBe(false);
    expect(isWhatsAppTemplateLanguage(null)).toBe(false);
  });
});
