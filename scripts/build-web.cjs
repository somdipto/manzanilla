const { buildSync } = require('esbuild');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const check = process.argv.includes('--check');
const destination = path.join(root, 'ui/web/dist/app.js');
const result = buildSync({
  absWorkingDir: root,
  entryPoints: ['ui/web/app.js'],
  outfile: destination,
  bundle: true,
  platform: 'browser',
  format: 'iife',
  target: ['chrome100', 'firefox100', 'safari15.4'],
  charset: 'ascii',
  legalComments: 'inline',
  minify: true,
  sourcemap: false,
  write: false,
  logLevel: 'silent',
});
const output = result.outputFiles[0].contents;
if (check) {
  if (!fs.existsSync(destination) || !fs.readFileSync(destination).equals(Buffer.from(output))) {
    console.error('Browser translator bundle is missing or stale. Run npm run build:web.');
    process.exitCode = 1;
  }
} else {
  fs.mkdirSync(path.dirname(destination), { recursive: true });
  fs.writeFileSync(destination, output);
}
