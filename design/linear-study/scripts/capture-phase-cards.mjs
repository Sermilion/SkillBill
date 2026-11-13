import { createServer } from 'node:http';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { dirname, extname, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const { chromium } = await import(process.env.SONA_PLAYWRIGHT_MODULE || 'playwright');
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const output = resolve(process.argv[2] || resolve(root, 'build/phase-cards'));
const fonts = resolve(root, '../../desktop/core/designsystem/src/commonMain/composeResources/font');
const mime = { '.html': 'text/html', '.js': 'application/javascript', '.css': 'text/css', '.svg': 'image/svg+xml' };
const server = createServer(async (request, response) => {
  const font = new URL(request.url, 'http://localhost').pathname.match(/^\/snapshot-fonts\/(inter|jetbrains_mono)\.ttf$/)?.[1];
  if (font) { response.setHeader('Content-Type', 'font/ttf'); response.end(await readFile(resolve(fonts, `${font}.ttf`))); return; }
  const path = resolve(root, '.' + new URL(request.url, 'http://localhost').pathname);
  if (!path.startsWith(root + sep)) { response.writeHead(403).end(); return; }
  try { response.setHeader('Content-Type', mime[extname(path)] || 'application/octet-stream'); response.end(await readFile(path)); }
  catch { response.writeHead(404).end(); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const browser = await chromium.launch({ headless: true });
const records = [];
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1200 }, deviceScaleFactor: 1, reducedMotion: 'reduce' });
  await page.route('https://fonts.googleapis.com/**', route => route.fulfill({ contentType: 'text/css', body: `
    @font-face { font-family: Inter; src: url(http://127.0.0.1:${server.address().port}/snapshot-fonts/inter.ttf); font-weight: 100 900; }
    @font-face { font-family: 'JetBrains Mono'; src: url(http://127.0.0.1:${server.address().port}/snapshot-fonts/jetbrains_mono.ttf); font-weight: 100 800; }
  ` }));
  await page.goto(`http://127.0.0.1:${server.address().port}/index.html#detail/SKILL-416`);
  await page.evaluate(() => document.fonts.ready);
  await page.addStyleTag({ content: '*, *::before, *::after { animation: none !important; transition: none !important; caret-color: transparent !important; }' });
  const capture = async (card, path, width) => {
    await card.evaluate((element, width) => {
      const wrap = document.createElement('div');
      wrap.id = 'phase-reference-capture';
      wrap.style.cssText = `position:absolute;top:0;left:0;z-index:99999;width:${width - 40}px;background:var(--bg-panel)`;
      wrap.append(element.cloneNode(true));
      document.body.append(wrap);
    }, width);
    try { await page.locator('#phase-reference-capture').screenshot({ path, animations: 'disabled' }); }
    finally { await page.locator('#phase-reference-capture').evaluate(element => element.remove()); }
  };
  for (const subject of ['1', 'finish']) {
    await page.locator(`[data-act="sub-select"][data-arg="SKILL-416|${subject}"]`).first().click();
    const pills = await page.locator('[data-act="peek-pill"]').evaluateAll(nodes => nodes.map(node => ({ arg: node.dataset.arg, phase: node.dataset.arg.split('|').at(-1) })));
    for (const pill of pills) {
      console.log(`Capturing ${pill.arg}`);
      await page.locator(`[data-act="peek-pill"][data-arg="${pill.arg}"]`).click();
      const card = page.locator('.sb-pane .sb-pk');
      await card.waitFor({ state: 'visible' });
      for (const width of [760, 360]) {
        await page.locator('.sb-pane').evaluate((element, width) => { element.style.width = `${width}px`; element.style.flex = `0 0 ${width}px`; }, width);
        const directory = resolve(output, `${subject === '1' ? 'subtask' : 'finish'}-${pill.phase}`);
        await mkdir(directory, { recursive: true });
        await capture(card, resolve(directory, `${width}-collapsed.png`), width);
      }
      const clicked = new Set();
      while (true) {
        const disclosures = await card.locator(':is([data-act="audit-met"], [data-act="fold-toggle"], [data-act="qa-find-toggle"], [data-act="qa-report-toggle"], [data-act="qa-toggle"], [aria-expanded="false"][data-act]):visible').evaluateAll(nodes => nodes.map((node, index) => ({ key: node.dataset.arg || `${node.dataset.act}:${index}`, text: node.textContent.trim(), expanded: node.getAttribute('aria-expanded') })));
        const next = disclosures.find(node => !clicked.has(node.key) && node.expanded !== 'true' && !/Show fewer|Show less/.test(node.text));
        if (!next) break;
        clicked.add(next.key);
        const buttons = card.locator(':is([data-act="audit-met"], [data-act="fold-toggle"], [data-act="qa-find-toggle"], [data-act="qa-report-toggle"], [data-act="qa-toggle"], [aria-expanded="false"][data-act]):visible');
        await buttons.nth(disclosures.indexOf(next)).click();
      }
      for (const width of [760, 360]) {
        await page.locator('.sb-pane').evaluate((element, width) => { element.style.width = `${width}px`; element.style.flex = `0 0 ${width}px`; }, width);
        const directory = resolve(output, `${subject === '1' ? 'subtask' : 'finish'}-${pill.phase}`);
        await capture(card, resolve(directory, `${width}-expanded.png`), width);
      }
      records.push({ subject, phase: pill.phase, disclosures: [...clicked], text: await card.innerText() });
    }
  }
  await writeFile(resolve(output, 'manifest.json'), JSON.stringify(records, null, 2) + '\n');
  const gallery = records.map(record => {
    const name = `${record.subject === '1' ? 'subtask' : 'finish'}-${record.phase}`;
    return `<section><h2>${name}</h2>${[760, 360].map(width => `<div><h3>${width}px</h3>${['collapsed', 'expanded'].map(state => `<a href="${name}/${width}-${state}.png"><img alt="${name}, ${state}" src="${name}/${width}-${state}.png" style="width:${width - 40}px;max-width:100%;vertical-align:top;margin:8px"></a>`).join('')}</div>`).join('')}</section>`;
  }).join('');
  await writeFile(resolve(output, 'index.html'), `<!doctype html><meta charset="utf-8"><title>Phase card references</title><style>body{background:#111113;color:#ddd;font:14px system-ui;margin:24px}section{border-top:1px solid #444;padding:16px 0}img{border:1px solid #444}</style><h1>Phase card references</h1><p>Each pill clicked, then every available disclosure opened. Fonts come from the desktop bundle.</p>${gallery}`);
  console.log(`Captured ${records.length} phase cards in ${output}`);
} finally {
  await browser.close();
  await new Promise(resolve => server.close(resolve));
}
