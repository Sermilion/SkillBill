#!/usr/bin/env node
/* Reuse check for the design prototype. Screens (app/*.js) compose components from ds/components.js and the layout
 * primitives only. It fails when a screen file writes a component class (`sb-…`) or an inline style attribute, or when
 * a component is defined outside ds/components.js. Allowed in screens: layout primitives (l-row, l-col, l-grow, l-wrap,
 * l-top, g-N), text utilities (t-muted, t-faint, t-2, mono, tnum, trunc) and, in library.js, the documentation page
 * chrome (doc-…). Run: node scripts/check-reuse.mjs [--list] */
import { readFileSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const ALLOWED = /^(l-row|l-col|l-grow|l-wrap|l-top|g-\d|hide-sm|t-(fg|2|muted|faint|accent|blocked|failed|done|planning|xs|sm|md|strong)|l-fill|mono|tnum|trunc)$/;
const DOC = /^doc-/;
const files = readdirSync(join(root, 'app')).filter((f) => f.endsWith('.js')).map((f) => join('app', f));
const problems = [];
for (const file of files) {
  const lines = readFileSync(join(root, file), 'utf8').split('\n');
  lines.forEach((text, i) => {
    const at = `${file}:${i + 1}`;
    for (const m of text.matchAll(/class=\\?["']([^"'$]*)/g)) {
      for (const cls of m[1].split(/\s+/).filter(Boolean)) {
        if (ALLOWED.test(cls) || (/(library|states|main)\.js$/.test(file) && DOC.test(cls))) continue;
        problems.push(`${at}  class "${cls}"`);
      }
    }
    /* The Foundations page shows token values (a swatch's color, a space's width, a radius, a type size): allowed there. */
    const tokenDoc = file.endsWith('library.js') && /class="doc-(swatch|space|radius|type-s|elev|grid3)/.test(text);
    if (/\sstyle=\\?["']/.test(text) && !tokenDoc) problems.push(`${at}  inline style`);
    if (/^\s*function [A-Z][A-Za-z]+\(o\)/.test(text)) problems.push(`${at}  component defined outside ds/components.js`);
  });
}
const byKind = {};
problems.forEach((p) => { const k = p.split('  ')[1].replace(/".*"/, '"…"'); byKind[k] = (byKind[k] || 0) + 1; });
if (process.argv.includes('--list')) problems.forEach((p) => console.log(p));
console.log(problems.length ? `${problems.length} reuse problems: ${Object.entries(byKind).map(([k, n]) => `${n} × ${k}`).join(', ')}` : 'No reuse problems.');
process.exit(problems.length ? 1 : 0);
