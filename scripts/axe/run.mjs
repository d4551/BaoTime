import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const adbPath = process.env.ADB ?? 'adb';
const [device, runId, mode = 'default'] = process.argv.slice(2);
if (!device || !runId) throw new Error('Expected a device serial and a run ID');
if (!['default', 'full-catalog'].includes(mode)) throw new Error('Expected default or full-catalog mode');
if (!/^[a-z0-9-]+$/.test(runId)) throw new Error('Run ID must use lowercase letters, digits, or hyphens');
const reports = new URL('../../build/reports/axe/', import.meta.url);
const outputDirectory = fileURLToPath(new URL(`${runId}/`, reports));
mkdirSync(reports, { recursive: true });
mkdirSync(outputDirectory);
const apkPath = fileURLToPath(new URL('../../app/build/outputs/apk/debug/app-debug.apk', import.meta.url));
const axePath = fileURLToPath(new URL('./node_modules/axe-core/axe.min.js', import.meta.url));
const packagePath = fileURLToPath(new URL('./node_modules/axe-core/package.json', import.meta.url));
const sha256 = path => createHash('sha256').update(readFileSync(path)).digest('hex');
const adb = (...args) => execFileSync(adbPath, ['-s', device, ...args], { encoding: 'utf8', timeout: 30000 }).trim();
const pid = adb('shell', 'pidof', 'com.baotime.app');
if (!/^\d+$/.test(pid)) throw new Error(`Expected one running BaoTime process: ${pid}`);
const socket = `webview_devtools_remote_${pid}`;
if (!adb('shell', 'cat', '/proc/net/unix').includes(`@${socket}`)) throw new Error(`Missing BaoTime WebView socket ${socket}`);
adb('forward', 'tcp:9334', `localabstract:${socket}`);
const pageResponse = await fetch('http://127.0.0.1:9334/json/list');
if (!pageResponse.ok) throw new Error(`DevTools discovery failed: ${pageResponse.status}`);
const pages = await pageResponse.json();
const rooms = pages.filter(page => new URL(page.url).origin === 'https://facetime.apple.com');
if (rooms.length !== 1) throw new Error(`Expected exactly one actual FaceTime room: ${JSON.stringify(pages)}`);
const page = rooms[0];
const connection = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((resolve, reject) => {
  connection.addEventListener('open', resolve, { once: true });
  connection.addEventListener('error', reject, { once: true });
});
let messageId = 0;
const pending = new Map();
connection.addEventListener('message', event => {
  const message = JSON.parse(event.data);
  const request = pending.get(message.id);
  if (!request) return;
  pending.delete(message.id);
  clearTimeout(request.timer);
  if (message.error) request.reject(new Error(JSON.stringify(message.error)));
  else request.resolve(message.result);
});
const send = (method, params) => new Promise((resolve, reject) => {
  const id = ++messageId;
  const timer = setTimeout(() => {
    pending.delete(id);
    reject(new Error(`CDP timeout: ${method}`));
  }, 110000);
  pending.set(id, { resolve, reject, timer });
  connection.send(JSON.stringify({ id, method, params }));
});
const evaluate = async expression => {
  const result = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true, timeout: 100000 });
  if (result.exceptionDetails) throw new Error(JSON.stringify(result.exceptionDetails));
  return result.result.value;
};
await send('Runtime.enable', {});
const surface = await evaluate('({url: location.href, title: document.title, text: document.body.innerText, readyState: document.readyState, userAgent: navigator.userAgent})');
writeFileSync(`${outputDirectory}surface-before.json`, JSON.stringify(surface, null, 2));
writeFileSync(`${outputDirectory}surface-before.png`, execFileSync(adbPath, ['-s', device, 'exec-out', 'screencap', '-p'], { timeout: 30000 }));
await evaluate(readFileSync(axePath, 'utf8'));
const engineVersion = await evaluate('axe.version');
const ruleCatalog = await evaluate('axe.getRules()');
writeFileSync(`${outputDirectory}rule-catalog.json`, JSON.stringify(ruleCatalog, null, 2));
console.log(`AXE ${engineVersion} PAGE ${surface.url}`);
const invocation = mode === 'full-catalog'
  ? "axe.run(document, {runOnly: {type: 'rule', values: axe.getRules().map(rule => rule.ruleId)}})"
  : 'axe.run(document)';
const results = await evaluate(invocation);
writeFileSync(`${outputDirectory}axe-full-results.json`, JSON.stringify(results, null, 2));
const counts = Object.fromEntries(['violations', 'passes', 'incomplete', 'inapplicable'].map(kind => [kind, results[kind].length]));
const metadata = {
  timestamp: new Date().toISOString(),
  invocation,
  mode,
  ruleIds: mode === 'full-catalog' ? ruleCatalog.map(rule => rule.ruleId) : ruleCatalog.filter(rule => rule.enabled).map(rule => rule.ruleId),
  device,
  avd: adb('shell', 'getprop', 'ro.boot.qemu.avd_name'),
  androidApi: adb('shell', 'getprop', 'ro.build.version.sdk'),
  webViewProvider: adb('shell', 'dumpsys', 'webviewupdate'),
  apkSha256: sha256(apkPath),
  installedApkSha256: adb('shell', 'sha256sum', adb('shell', 'pm', 'path', 'com.baotime.app').replace(/^package:/, '')).split(/\s+/, 1)[0],
  axeSha256: sha256(axePath),
  axePackageVersion: JSON.parse(readFileSync(packagePath, 'utf8')).version,
  injectedAxeVersion: engineVersion,
  page,
  counts,
  nodeVersion: process.version
};
writeFileSync(`${outputDirectory}run-metadata.json`, JSON.stringify(metadata, null, 2));
console.log(JSON.stringify(counts));
for (const kind of ['violations', 'incomplete']) {
  for (const finding of results[kind]) console.log(`${kind} ${finding.id} ${finding.impact} nodes=${finding.nodes.length}: ${finding.help}`);
}
connection.close();
adb('forward', '--remove', 'tcp:9334');
process.exitCode = results.violations.length ? 1 : 0;
