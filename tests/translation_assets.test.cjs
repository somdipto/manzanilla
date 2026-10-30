const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { parseSpeechCatalogue } = require('../scripts/build-reception.cjs');
const catalogue = JSON.parse(fs.readFileSync('shared/reception-locales.json', 'utf8'));
const source = fs.readFileSync('shared/translation-languages.json', 'utf8');
const greetings = JSON.parse(fs.readFileSync('cloud/fish_greetings.json', 'utf8'));


test('speech configuration refuses unsupported recognition languages and absent localized welcomes', () => {
  const changed = JSON.parse(source);
  changed.languages.push(catalogue.languages.find(language => language.code === 'ca'));
  assert.throws(() => parseSpeechCatalogue(JSON.stringify(changed), catalogue, greetings));
  assert.throws(() => parseSpeechCatalogue(source, catalogue, { ...greetings, ar: '' }));
});
