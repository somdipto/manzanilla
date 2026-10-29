/* Keep the shipped WebView scripts runnable on Android 8's Chrome 61 engine. */
const { transformSync } = require('esbuild');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
// This is the offered-language compatibility contract, not editable locale data.
const offeredCodes = 'es en ca fr de it pt nl ar zh ru uk pl ro sv el tr ja ko hi cs da fi hu bg hr sk sr no id th vi ms tl he fa ur bn ta te kn mr gu pa ne kk ka hy et lv lt af'.split(' ');
const unavailable = 'This service is unavailable. Please ask hotel staff.';

function placeholders(text) {
  const names = [];
  const pattern = /\{([A-Za-z_][A-Za-z0-9_]*)\}/g;
  let match;
  while ((match = pattern.exec(text))) names.push(match[1]);
  if (/[{}]/.test(text.replace(pattern, ''))) throw new Error('Invalid named placeholder: ' + text);
  return names.sort().join(',');
}

function parseCatalogue(source, requiredMessages = []) {
  const catalogue = JSON.parse(source);
  // JSON.parse silently accepts duplicate keys. Inspect complete JSON string tokens
  // first, so braces and escaped quotes inside translations cannot hide duplicates.
  const objects = [];
  const tokens = /"(?:\\[\s\S]|[^"\\])*"|[{}]/g;
  let token;
  while ((token = tokens.exec(source))) {
    if (token[0] === '{') objects.push(new Set());
    else if (token[0] === '}') objects.pop();
    else if (/^\s*:/.test(source.slice(tokens.lastIndex))) {
      const key = JSON.parse(token[0]);
      const keys = objects[objects.length - 1];
      if (keys.has(key)) throw new Error('Duplicate JSON key: ' + key);
      keys.add(key);
    }
  }
  if (!catalogue || catalogue.schema !== 1 || !Array.isArray(catalogue.languages)) throw new Error('Invalid locale schema');
  const codes = new Set();
  for (const language of catalogue.languages) {
    if (!language || typeof language.code !== 'string' || codes.has(language.code)) throw new Error('Duplicate or invalid language code');
    codes.add(language.code);
    if (typeof language.name !== 'string' || !language.name.trim() || typeof language.native !== 'string' || !language.native.trim()) throw new Error('Missing language name: ' + language.code);
    if (language.direction !== 'ltr' && language.direction !== 'rtl') throw new Error('Invalid direction: ' + language.code);
  }
  if (catalogue.languages.map(language => language.code).join(' ') !== offeredCodes.join(' ')) throw new Error('Missing or reordered offered language');
  if (!catalogue.messages || typeof catalogue.messages !== 'object' || Array.isArray(catalogue.messages) || !Object.prototype.hasOwnProperty.call(catalogue.messages, unavailable)) throw new Error('Missing interface messages');
  for (const message of requiredMessages) {
    if (!Object.prototype.hasOwnProperty.call(catalogue.messages, message)) throw new Error('Missing interface message: ' + message);
  }
  for (const [message, translations] of Object.entries(catalogue.messages)) {
    if (!message.trim() || !translations || typeof translations !== 'object' || Array.isArray(translations) || translations.en !== message) throw new Error('Invalid English message ID: ' + message);
    const expected = placeholders(message);
    if (Object.keys(translations).length !== offeredCodes.length) throw new Error('Missing or extra translation: ' + message);
    for (const code of offeredCodes) {
      const text = translations[code];
      if (typeof text !== 'string' || !text.trim()) throw new Error('Blank or missing translation: ' + code + ': ' + message);
      if (placeholders(text) !== expected) throw new Error('Mismatched placeholders: ' + code + ': ' + message);
    }
  }
  return catalogue;
}

function asciiJson(value) {
  return JSON.stringify(value).replace(/[\u007f-\uffff]/g, character => '\\u' + character.charCodeAt(0).toString(16).padStart(4, '0'));
}

function deskLanguages(catalogue) {
  const quote = value => JSON.stringify(value).replace(/\$/g, '\\$');
  const list = catalogue.languages;
  return 'package com.agentdeck.hotel\n\n' +
    '// Generated from shared/reception-locales.json; run npm run build:reception.\n' +
    '// Interface choices do not establish provider or device speech qualification.\n' +
    'object DeskLanguages {\n' +
    '    val codes: List<String> = listOf(\n' + list.map(language => '        ' + quote(language.code)).join(',\n') + '\n    )\n' +
    '    private val english = mapOf(\n' + list.map(language => '        ' + quote(language.code) + ' to ' + quote(language.name)).join(',\n') + '\n    )\n' +
    '    val native = mapOf(\n' + list.map(language => '        ' + quote(language.code) + ' to ' + quote(language.native)).join(',\n') + '\n    )\n\n' +
    '    fun has(code: String) = code in codes\n' +
    '    fun english(code: String) = english[code] ?: code\n' +
    '}\n';
}

function sourceMessages() {
  // English IDs remain at their call sites. Check static lookup calls as well as
  // each catalogue row, so deleting an entire referenced message is an error.
  const literal = String.raw`("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')`;
  const native = 'android/hotel/src/main/java/com/agentdeck/hotel/';
  const consumers = [
    ['ui/reception/reception.js', String.raw`\buiText\(\s*`],
    ['admin/index.html', String.raw`\buiText\(\s*`],
    ['cloud/operations.py', String.raw`\bui_text\(\s*[^,\n]+,\s*`],
    ['cloud/app.py', String.raw`\bui_text\(\s*[^,\n]+,\s*`],
    ...['HotelActivity.kt', 'HotelGateway.kt', 'FishVoice.kt', 'FishStartupFailure.kt', 'AudioPolicy.kt', 'KeyEntryActivity.kt', 'Updater.kt', 'UpdateFailure.kt', 'UpdateIdentity.kt'].map(file =>
      [native + file, String.raw`\btext\(\s*`]),
  ];
  const messages = new Set([unavailable]);
  const addLiterals = (source, prefix) => {
    const pattern = new RegExp(prefix + literal, 'g');
    let match;
    while ((match = pattern.exec(source))) {
      const quoted = match[1];
      const json = quoted[0] === '"' ? quoted :
        '"' + quoted.slice(1, -1).replace(/\\'/g, "'").replace(/"/g, '\\"') + '"';
      messages.add(JSON.parse(json));
    }
  };
  for (const [file, prefix] of consumers) {
    const source = fs.readFileSync(path.join(root, file), 'utf8');
    addLiterals(source, prefix);
    if (file === 'cloud/operations.py') {
      // operation_fields and receipt also select IDs from these producer-owned
      // dictionaries. Read their literals, never maintain a second message list.
      const fields = source.match(/\bFIELDS\s*=\s*\{[\s\S]*?\nMODELS\s*=/);
      if (fields) addLiterals(fields[0], String.raw`\(\s*['"][a-z_]+['"]\s*,\s*`);
      const statuses = source.match(/\bmessage\s*=\s*\{[\s\S]*?\}\.get\(\s*status\s*,[^\n]*\)/);
      if (statuses) {
        addLiterals(statuses[0], String.raw`['"][a-z_]+['"]\s*:\s*`);
        addLiterals(statuses[0], String.raw`\.get\(\s*status\s*,\s*`);
      }
    }
  }
  return messages;
}

function build() {
  const check = process.argv.includes('--check');
  let stale = false;
  const source = fs.readFileSync(path.join(root, 'shared/reception-locales.json'), 'utf8');
  const catalogue = parseCatalogue(source, sourceMessages());
  const localeScript = '// Generated from shared/reception-locales.json; run npm run build:reception.\nwindow.MANZANILLA_UI = ' + asciiJson(catalogue) + ';\n';
  const outputs = new Map([
    ['ui/reception/locales.js', localeScript],
    ['android/hotel/src/main/assets/reception-locales.json', source],
    ['android/hotel/src/main/java/com/agentdeck/hotel/DeskLanguages.kt', deskLanguages(catalogue)],
  ]);
  for (const file of ['locales.js', 'greetings.js', 'language-turns.js', 'reception.js']) {
    const script = file === 'locales.js' ? localeScript : fs.readFileSync(path.join(root, 'ui/reception', file), 'utf8');
    outputs.set('android/hotel/src/main/assets/reception/' + file, transformSync(script, {
      target: ['chrome61'], charset: file === 'locales.js' ? 'ascii' : 'utf8', legalComments: 'inline',
      banner: '// Generated from ' + (file === 'locales.js' ? 'shared/reception-locales.json' : 'ui/reception/' + file) + '; run npm run build:reception.',
    }).code);
  }
  for (const [file, output] of outputs) {
    const destination = path.join(root, file);
    if (check) {
      if (!fs.existsSync(destination) || fs.readFileSync(destination, 'utf8') !== output) {
        console.error('Stale generated reception output: ' + file + '. Run npm run build:reception.');
        stale = true;
      }
    } else fs.writeFileSync(destination, output);
  }
  if (stale) process.exitCode = 1;
}

module.exports = { parseCatalogue, placeholders };
if (require.main === module) build();
