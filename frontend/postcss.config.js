import autoprefixer from 'autoprefixer';
import postcssImport from 'postcss-import';
import postcssPresetEnv from 'postcss-preset-env';
import tailwindcss from 'tailwindcss';

export default {
  plugins: [
    postcssPresetEnv({
      autoprefixer: {
        flexbox: 'no-2009',
      },
      stage: 3,
    }),
    postcssImport,
    tailwindcss,
    autoprefixer,
  ],
};
