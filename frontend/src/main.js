import { createApp } from 'vue';
import { createI18n } from 'vue-i18n';
import axios from 'axios';

import hljsVuePlugin from '@highlightjs/vue-plugin';
import { plugin, defaultConfig } from '@formkit/vue';
import WootWizard from 'components/ui/Wizard.vue';
import FloatingVue from 'floating-vue';
import WootUiKit from 'dashboard/components';
import App from 'dashboard/App.vue';
import i18nMessages from 'dashboard/i18n';
import createAxios from 'dashboard/helper/APIHelper';

import commonHelpers, { isJSONValid } from 'dashboard/helper/commons';
import { sync } from 'vuex-router-sync';
import { createPinia } from 'pinia';
import router, { initalizeRouter } from 'dashboard/routes';
import store from 'dashboard/store';
import constants from 'dashboard/constants/globals';

import FluentIcon from 'shared/components/FluentIcon/DashboardIcon.vue';
import VueDOMPurifyHTML from 'vue-dompurify-html';
import { domPurifyConfig } from 'shared/helpers/HTMLSanitizer.js';

import { vResizeObserver } from '@vueuse/components';
import { directive as onClickaway } from 'vue3-click-away';

import 'floating-vue/dist/style.css';

// ── Shims for Rails-provided globals ──────────────────────────
// The original Chatwoot code expects window.chatwootConfig to be
// injected by the Rails backend. Fill it with sensible defaults
// for a standalone frontend.
window.chatwootConfig = {
  apiHost: '',
  hostURL: '',
  websocketURL: '',
  vapidPublicKey: '',
  enabledLanguages: ['en', 'zh'],
  isEnterprise: 'false',
  enterprisePlanName: 'community',
  inboxEventsEnabled: 'false',
};

// Auth cookie helpers — stubbed for standalone mode.
// Replace with your own auth when you connect a real backend.
window.authCookie = null;

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages: i18nMessages,
});

sync(store, router);

const pinia = createPinia();

const app = createApp(App);
app.use(i18n);
app.use(store);
app.use(pinia);
app.use(router);

app.use(VueDOMPurifyHTML, domPurifyConfig);
app.use(WootUiKit);
app.use(
  plugin,
  defaultConfig({
    rules: { JSON: ({ value }) => isJSONValid(value) },
  })
);
app.use(FloatingVue, {
  instantMove: true,
  arrowOverflow: false,
  disposeTimeout: 5000000,
});
app.use(hljsVuePlugin);

app.component('woot-wizard', WootWizard);
app.component('fluent-icon', FluentIcon);

app.directive('resize', vResizeObserver);
app.directive('on-clickaway', onClickaway);

commonHelpers();
window.WootConstants = constants;
window.axios = createAxios(axios);

initalizeRouter();

window.onload = () => {
  app.mount('#app');
};
