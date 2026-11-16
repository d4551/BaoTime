import { Buffer } from 'node:buffer';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import axe from 'axe-core';
import { faceTimeOrigin, findRoom } from './routes/devtools.mjs';

const adbPath = process.env.ADB ?? 'adb';
const [device, runId, mode = 'default'] = process.argv.slice(2);
if (!device || !runId) throw new Error('Expected a device serial and a run ID');
if (!['default', 'full-catalog'].includes(mode)) throw new Error('Expected default or full-catalog mode');
if (!/^[a-z0-9-]+$/.test(runId)) throw new Error('Run ID must use lowercase letters, digits, or hyphens');

const appPackage = 'com.baotime.app';
const discoveryTimeoutMs = 10000;
const evaluationTimeoutMs = 100000;
const commandTimeoutMs = 30000;
const pngSignature = Buffer.from('89504e470d0a1a0a', 'hex');
const pngChunkHeaderSize = 8;
const pngChunkOverhead = 12;
const pngHeaderDataSize = 13;
const pngMaximumDimension = 0x7fffffff;
const apkPath = fileURLToPath(new URL('../../app/build/outputs/apk/debug/app-debug.apk', import.meta.url));
const axePath = fileURLToPath(new URL('./node_modules/axe-core/axe.min.js', import.meta.url));
const axePackageVersion = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8')).devDependencies['axe-core'];
const expectedRuleIds = axe.getRules()
  .filter(rule => mode === 'full-catalog' || rule.enabled)
  .map(rule => rule.ruleId);
const resultGroups = ['violations', 'passes', 'incomplete', 'inapplicable'];
const invocation = mode === 'full-catalog'
  ? "axe.run(document, {runOnly: {type: 'rule', values: axe.getRules().map(rule => rule.ruleId)}})"
  : 'axe.run(document)';
const inspectDocument = `({
  url: location.href,
  title: document.title,
  text: document.body?.innerText ?? '',
  readyState: document.readyState,
  userAgent: navigator.userAgent,
  timeOrigin: performance.timeOrigin,
  bodyRendered: Boolean(document.body?.getClientRects().length),
  unavailable: Boolean(document.querySelector('.error-screen')),
  unavailablePatched: Boolean(document.querySelector('[data-baotime-error-screen]')),
})`;

const reports = new URL('../../build/reports/axe/', import.meta.url);
const outputDirectory = fileURLToPath(new URL(`${runId}/`, reports));
mkdirSync(reports, { recursive: true });
mkdirSync(outputDirectory, { mode: 0o700 });
const writeReport = (filename, value) => writeFileSync(join(outputDirectory, filename), JSON.stringify(value, null, 2));
const adb = (...args) => execFileSync(adbPath, ['-s', device, ...args], {
  encoding: 'utf8',
  timeout: commandTimeoutMs,
}).trim();
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const runningPid = () => adb('shell', 'pidof', appPackage);

function installedHash(expectedHash) {
  const packagePath = adb('shell', 'pm', 'path', appPackage).match(/^package:(\/[A-Za-z0-9_./~=-]+\.apk)$/);
  if (!packagePath) throw new Error('Expected a single installed BaoTime APK');
  const hash = adb('shell', 'sha256sum', packagePath[1]).split(/\s+/, 1)[0];
  if (hash !== expectedHash) throw new Error(`Installed BaoTime APK SHA-256 mismatch: expected ${expectedHash}, got ${hash}`);
  return hash;
}

function waitForOpen(connection) {
  const { promise, resolve, reject } = Promise.withResolvers();
  const listeners = new AbortController();
  const options = { signal: listeners.signal };
  const failed = () => reject(new Error('Could not connect to BaoTime DevTools'));
  const timer = setTimeout(failed, discoveryTimeoutMs);
  connection.addEventListener('open', resolve, options);
  connection.addEventListener('error', failed, options);
  connection.addEventListener('close', failed, options);
  return promise.finally(() => {
    clearTimeout(timer);
    listeners.abort();
  });
}

function cdpClient(connection) {
  let messageId = 0;
  let activeRequest;
  const connectionLost = () => activeRequest?.reject(new Error('BaoTime DevTools connection closed'));
  connection.addEventListener('error', connectionLost);
  connection.addEventListener('close', connectionLost);
  connection.addEventListener('message', event => {
    const request = activeRequest;
    if (!request) return;
    Promise.resolve(event.data).then(data => {
      const message = JSON.parse(data);
      if (!message || typeof message !== 'object' || Array.isArray(message)) {
        throw new Error('Expected a DevTools message object');
      }
      return message;
    }).then(message => {
      if (message.id !== request.id) return;
      if (message.error) request.reject(new Error(JSON.stringify(message.error)));
      else request.resolve(message.result);
    }, request.reject);
  });

  return (method, params) => {
    const id = ++messageId;
    const { promise, resolve, reject } = Promise.withResolvers();
    const timer = setTimeout(() => reject(new Error(`CDP timeout: ${method}`)), evaluationTimeoutMs + discoveryTimeoutMs);
    activeRequest = { id, resolve, reject };
    return Promise.resolve().then(() => {
      connection.send(JSON.stringify({ id, method, params }));
      return promise;
    }).finally(() => {
      clearTimeout(timer);
      activeRequest = undefined;
    });
  };
}

async function evaluate(send, expression) {
  const result = await send('Runtime.evaluate', {
    expression,
    returnByValue: true,
    awaitPromise: true,
    timeout: evaluationTimeoutMs,
  });
  if (result.exceptionDetails) throw new Error(JSON.stringify(result.exceptionDetails));
  return result.result.value;
}

function surfaceKind(surface) {
  if (surface.unavailablePatched) return 'unavailable-patched';
  return surface.unavailable ? 'unavailable-unpatched' : 'room-page';
}

function checkDocumentContent(surface) {
  if (surface.readyState !== 'complete' || !Number.isFinite(surface.timeOrigin)) {
    throw new Error('The FaceTime document is not ready to audit');
  }
  if (!surface.bodyRendered || !surface.text.trim()) throw new Error('The FaceTime document has no rendered text');
}

function checkRuleIds(observed, description) {
  const actual = new Set(observed);
  if (actual.size !== expectedRuleIds.length || expectedRuleIds.some(id => !actual.has(id))) {
    throw new Error(`${description} rule inventory mismatch`);
  }
}

function pngDimensions(png) {
  if (!png.subarray(0, pngSignature.length).equals(pngSignature)) return null;
  let dimensions;
  let hasImageData = false;
  let offset = pngSignature.length;
  while (offset + pngChunkOverhead <= png.length) {
    const length = png.readUInt32BE(offset);
    const dataOffset = offset + pngChunkHeaderSize;
    const next = offset + pngChunkOverhead + length;
    if (next > png.length) return null;
    const type = png.toString('latin1', dataOffset - 4, dataOffset);
    if (!dimensions) {
      if (type !== 'IHDR' || length !== pngHeaderDataSize) return null;
      const width = png.readUInt32BE(dataOffset);
      const height = png.readUInt32BE(dataOffset + 4);
      if (width === 0 || height === 0 || width > pngMaximumDimension || height > pngMaximumDimension) return null;
      dimensions = { width, height };
    } else if (type === 'IHDR') {
      return null;
    } else if (type === 'IDAT') {
      hasImageData ||= length > 0;
    } else if (type === 'IEND') {
      return hasImageData && length === 0 && next === png.length ? dimensions : null;
    }
    offset = next;
  }
  return null;
}

function captureScreenshot() {
  const png = execFileSync(adbPath, ['-s', device, 'exec-out', 'screencap', '-p'], { timeout: commandTimeoutMs });
  const dimensions = pngDimensions(png);
  if (!dimensions) throw new Error('ADB did not return a complete PNG screenshot');
  writeFileSync(join(outputDirectory, 'surface-before.png'), png);
  return { ...dimensions, sha256: sha256(png) };
}

async function scan(connection, page) {
  await waitForOpen(connection);
  const send = cdpClient(connection);
  await send('Runtime.enable', {});
  const before = await evaluate(send, inspectDocument);
  if (before.url !== page.url) throw new Error('DevTools document URL does not match the discovered FaceTime room');
  checkDocumentContent(before);
  writeReport('surface-before.json', before);
  const screenshot = captureScreenshot();

  const engine = readFileSync(axePath);
  await evaluate(send, engine.toString('utf8'));
  const engineVersion = await evaluate(send, 'axe.version');
  if (engineVersion !== axePackageVersion) throw new Error('Injected axe engine version mismatch');
  const ruleCatalog = await evaluate(send, 'axe.getRules()');
  writeReport('rule-catalog.json', ruleCatalog);
  checkRuleIds(ruleCatalog.filter(rule => mode === 'full-catalog' || rule.enabled).map(rule => rule.ruleId), 'Injected axe catalog');

  const results = await evaluate(send, invocation);
  if (results.url !== before.url) throw new Error('axe results refer to a different FaceTime page');
  writeReport('axe-full-results.json', results);
  const after = await evaluate(send, inspectDocument);
  if (before.url !== after.url || before.timeOrigin !== after.timeOrigin || surfaceKind(before) !== surfaceKind(after)) {
    throw new Error('The FaceTime document changed during the scan');
  }
  checkDocumentContent(after);
  writeReport('surface-after.json', after);
  if (results.testEngine?.name !== 'axe-core' || results.testEngine.version !== engineVersion) {
    throw new Error('axe result engine version mismatch');
  }
  checkRuleIds(resultGroups.flatMap(kind => results[kind].map(rule => rule.id)), 'axe result');
  if (results.passes.length + results.violations.length + results.incomplete.length === 0) {
    throw new Error('The axe rules applied to no FaceTime content');
  }
  return { results, surface: surfaceKind(before), engineVersion, axeSha256: sha256(engine), screenshot };
}

function forwardRoom(pid) {
  const socket = `webview_devtools_remote_${pid}`;
  const port = adb('forward', 'tcp:0', `localabstract:${socket}`);
  if (!/^\d{1,5}$/.test(port) || Number(port) < 1 || Number(port) > 65535) {
    throw new Error('ADB did not return a valid DevTools port');
  }
  return port;
}

function scanForward(port) {
  let connection;
  const cleanup = () => Promise.resolve()
    .then(() => connection?.close())
    .finally(() => adb('forward', '--remove', `tcp:${port}`));

  const observation = findRoom(port, discoveryTimeoutMs).then(async page => {
    connection = new WebSocket(page.webSocketDebuggerUrl);
    return { page, ...await scan(connection, page) };
  });
  return observation.then(async result => {
    await cleanup();
    return result;
  }, async error => {
    const [cleanupResult] = await Promise.allSettled([cleanup()]);
    if (cleanupResult.status === 'rejected') {
      throw new AggregateError([error, cleanupResult.reason], 'axe scan and ADB cleanup failed');
    }
    throw error;
  });
}

function recordScan({ pid, apkSha256, installedApkSha256 }, { page, results, surface, engineVersion, axeSha256, screenshot }) {
  const counts = Object.fromEntries(resultGroups.map(kind => [kind, results[kind].length]));
  writeReport('run-metadata.json', {
    timestamp: new Date().toISOString(),
    invocation,
    mode,
    ruleIds: expectedRuleIds,
    device,
    pid,
    avd: adb('shell', 'getprop', 'ro.boot.qemu.avd_name'),
    androidApi: adb('shell', 'getprop', 'ro.build.version.sdk'),
    webViewProvider: adb('shell', 'dumpsys', 'webviewupdate'),
    apkSha256,
    installedApkSha256,
    axeSha256,
    axePackageVersion,
    injectedAxeVersion: engineVersion,
    page,
    surface,
    screenshot,
    counts,
    nodeVersion: process.version,
  });
  console.log(`AXE ${engineVersion} ${surface} ${faceTimeOrigin}`);
  console.log(JSON.stringify(counts));
  for (const kind of ['violations', 'incomplete']) {
    for (const finding of results[kind]) {
      console.log(`${kind} ${finding.id} ${finding.impact} nodes=${finding.nodes.length}: ${finding.help}`);
    }
  }
  process.exitCode = counts.violations ? 1 : 0;
}

async function run() {
  if (axe.version !== axePackageVersion) throw new Error('Installed axe engine version mismatch');
  const apkSha256 = sha256(readFileSync(apkPath));
  const pid = runningPid();
  if (!/^\d+$/.test(pid)) throw new Error('Expected one running BaoTime process');
  const installedApkSha256 = installedHash(apkSha256);
  const observation = await scanForward(forwardRoom(pid));
  if (runningPid() !== pid || sha256(readFileSync(apkPath)) !== installedApkSha256) {
    throw new Error('The BaoTime process or built APK changed during the scan');
  }
  installedHash(apkSha256);
  recordScan({ pid, apkSha256, installedApkSha256 }, observation);
}

await run();
