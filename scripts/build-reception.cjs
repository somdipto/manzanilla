/* Keep the shipped WebView scripts runnable on Android 8's Chrome 61 engine. */
const { transformSync } = require('esbuild');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const check = process.argv.includes('--check');
let stale = false;
for (const file of ['greetings.js', 'language-turns.js', 'reception.js']) {
  const source = fs.readFileSync(path.join(root, 'ui/reception', file), 'utf8');
  const output = transformSync(source, {
    target: ['chrome61'], charset: 'utf8', legalComments: 'inline',
    banner: '// Generated from ui/reception/' + file + '; run npm run build:reception.',
  }).code;
  const destination = path.join(root, 'android/hotel/src/main/assets/reception', file);
  if (check) {
    if (!fs.existsSync(destination) || fs.readFileSync(destination, 'utf8') !== output) {
      console.error('Stale reception asset: ' + file + '. Run npm run build:reception.');
      stale = true;
    }
  } else fs.writeFileSync(destination, output);
}
if (stale) process.exitCode = 1;
