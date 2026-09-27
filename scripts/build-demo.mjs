/**
 * Rebuild the BouncePay prototype into public/demo/ and apply the dark theme.
 *
 * The prototype lives in a sibling repo and ships a light Material You
 * palette. This script builds it with a relative base (so it can be served
 * from /demo/), then links scripts/demo-theme.css after its own stylesheet to
 * re-point its tokens at the site's dark palette.
 *
 * Run with: npm run build:demo
 */
import { execSync } from 'node:child_process';
import { copyFileSync, existsSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const site = resolve(here, '..');
const proto = resolve(site, '..', 'bouncepay');
const outDir = resolve(site, 'public', 'demo');
const themeSrc = resolve(here, 'demo-theme.css');
const THEME_FILE = 'theme-dark.css';

if (!existsSync(proto)) {
  console.error(`✗ Prototype not found at ${proto}`);
  console.error('  Expected it as a sibling of this repo. Nothing was changed.');
  process.exit(1);
}

console.log(`• Building prototype from ${proto}`);
execSync(
  `npx vite build --base=./ --outDir "${outDir}" --emptyOutDir`,
  { cwd: proto, stdio: 'inherit' }
);

// --emptyOutDir wipes the folder, so the theme is copied in after the build.
copyFileSync(themeSrc, resolve(outDir, THEME_FILE));
console.log(`• Copied ${THEME_FILE}`);

/* ---------------------------------------------------------------------------
   Post-build polish.

   Everything below was once applied by hand to the build output, which meant
   the next `npm run build:demo` silently undid it. It is encoded here so the
   embedded demo is reproducible from the prototype source.
   --------------------------------------------------------------------------- */

// The terminal's device id repeats across the merchant panel, and the handset
// model is a sponsor detail the page does not need to name.
const PHRASES = [
  [' (POS #BP-01)', ''],
  [' • POS #BP-01', ''],
  ['iQOO 12 PRO', 'iQOO PHONE'],   // markup
  ['iQOO 12 Pro', 'iQOO Phone'],   // strings the app builds at runtime
];
const replacePhrases = (file) => {
  let text = readFileSync(file, 'utf8');
  let hits = 0;
  for (const [from, to] of PHRASES) {
    while (text.includes(from)) { text = text.replace(from, to); hits++; }
  }
  if (hits) { writeFileSync(file, text, 'utf8'); }
  return hits;
};

// Colour emoji look like clip art next to the site's typography, so the route
// nodes become numerals and the status glyphs become typographic marks.
const GLYPHS = [
  ['<span>⚠️</span>', '<span style="font-weight: 700;">!</span>'],
  ['<span class="ble-signal-waves">📡</span>', '<span class="ble-signal-waves">●</span>'],
  ['<span class="dtc-lock">🔒</span>', '<span class="dtc-lock">SECURE</span>'],
  ['<span>🌐 SYNC WHEN ONLINE</span>', '<span>SYNC WHEN ONLINE</span>'],
];

// The prototype's own step ticker duplicates the routing column's step bar.
const TICKER_FROM =
  '<!-- Step-by-Step State Progress Indicator (Pill Card) -->\n        <div class="step-ticker-card">';
const TICKER_TO =
  '<!-- Step-by-Step State Progress Indicator (Hidden) -->\n        <div class="step-ticker-card" style="display: none !important;">';

// Lets the travelling packet land in the backend node rather than stopping
// short of it; pairs with .settled-absorbed in demo-theme.css.
const ABSORB_SCRIPT = `  <script>
    // Seamless packet absorption into Backend on Step 7 (Settlement)
    (function() {
      const backendNode = document.getElementById('nodeBackend');
      const packet = document.getElementById('travelingPacket');
      if (!backendNode || !packet) return;

      const observer = new MutationObserver(function() {
        if (backendNode.classList.contains('completed')) {
          packet.classList.add('settled-absorbed');
        } else {
          packet.classList.remove('settled-absorbed');
        }
      });
      observer.observe(backendNode, { attributes: true, attributeFilter: ['class'] });
    })();
  </script>
`;

const polishMarkup = (file) => {
  let text = readFileSync(file, 'utf8');

  // number the route nodes and the direct-BLE device cards in document order
  let node = 0;
  text = text.replace(/<div class="node-circle-bubble">[^<]*<\/div>/g,
    () => `<div class="node-circle-bubble">${++node}</div>`);
  let card = 0;
  text = text.replace(/<div class="ddc-icon">[^<]*<\/div>/g,
    () => `<div class="ddc-icon">${++card}</div>`);

  for (const [from, to] of GLYPHS) text = text.replace(from, to);
  text = text.replace(TICKER_FROM, TICKER_TO);

  if (!text.includes('settled-absorbed')) {
    text = text.replace('</body>', `${ABSORB_SCRIPT}</body>`);
  }

  writeFileSync(file, text, 'utf8');
  return { node, card };
};

const indexPath = resolve(outDir, 'index.html');
const assetsDir = resolve(outDir, 'assets');
const jsFiles = existsSync(assetsDir)
  ? readdirSync(assetsDir).filter(f => f.endsWith('.js')).map(f => resolve(assetsDir, f))
  : [];
const replaced = [indexPath, ...jsFiles].reduce((n, f) => n + replacePhrases(f), 0);
console.log(`• Rewrote ${replaced} device/branding reference(s)`);

const { node, card } = polishMarkup(indexPath);
console.log(`• Numbered ${node} route nodes and ${card} device cards, hid the step ticker`);

let html = readFileSync(indexPath, 'utf8');

if (html.includes(THEME_FILE)) {
  console.log('• Theme already linked');
} else {
  // Must load after the prototype's own stylesheet to win the cascade.
  const appCss = html.match(/<link[^>]+rel="stylesheet"[^>]+assets\/[^>]*>/);
  if (!appCss) {
    console.error('✗ Could not find the prototype stylesheet link to anchor to.');
    process.exit(1);
  }
  html = html.replace(
    appCss[0],
    `${appCss[0]}\n  <link rel="stylesheet" href="./${THEME_FILE}" />`
  );
  writeFileSync(indexPath, html, 'utf8');
  console.log('• Linked the dark theme into index.html');
}

console.log('✓ Demo ready at public/demo/');
