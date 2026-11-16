import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import axe from 'axe-core';

const runner = fileURLToPath(new URL('./run.mjs', import.meta.url));
const manifest = fileURLToPath(new URL('./package.json', import.meta.url));
const axePackage = fileURLToPath(new URL('./node_modules/axe-core/', import.meta.url));
const apk = 'test fixture APK bytes';
const apkSha256 = createHash('sha256').update(apk).digest('hex');
const engineVersion = JSON.parse(readFileSync(join(axePackage, 'package.json'), 'utf8')).version;
const roomUrl = 'https://facetime.apple.com/join?fixture';
const ruleCatalog = axe.getRules().map(({ ruleId, enabled }) => ({ ruleId, enabled }));
// A complete one-pixel RGB PNG containing only IHDR, IDAT and IEND.
const validScreenshot = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGNQSFgAAAHEASFiX4r9AAAAAElFTkSuQmCC',
  'base64',
);
const badScreenshots = {
  'empty-screenshot': Buffer.alloc(0),
  'non-png-screenshot': Buffer.from('screen capture returned text'),
  'zero-width-screenshot': Buffer.from(validScreenshot).fill(0, 16, 20),
  'missing-image-data': Buffer.concat([validScreenshot.subarray(0, 33), validScreenshot.subarray(-12)]),
  'truncated-screenshot': validScreenshot.subarray(0, -4),
};

// Install the boundary fixture in the CLI subprocess only. The runner and its process exit are real.
async function installFixture({ commands, scenario, apkSha256, engineVersion, roomUrl, ruleCatalog, screenshot }) {
  const { default: childProcess } = await import('node:child_process');
  const { syncBuiltinESMExports } = await import('node:module');
  const { appendFileSync } = await import('node:fs');
  const log = line => appendFileSync(commands, `${line}\n`);
  childProcess.execFileSync = (_command, [selection, device, ...args]) => {
    if (selection !== '-s' || device !== 'fixture-device') throw new Error('Unexpected fixture device');
    const command = args.join(' ');
    log(command);
    switch (command) {
      case 'shell pidof com.baotime.app': return '42';
      case 'shell pm path com.baotime.app': return 'package:/data/app/fixture/base.apk';
      case 'shell sha256sum /data/app/fixture/base.apk':
        return `${scenario === 'wrong-apk' ? '0'.repeat(64) : apkSha256}  /data/app/fixture/base.apk`;
      case 'forward tcp:0 localabstract:webview_devtools_remote_42': return '42899';
      case 'forward --remove tcp:42899': return '';
      case 'exec-out screencap -p': return Buffer.from(screenshot, 'base64');
      case 'shell getprop ro.boot.qemu.avd_name': return 'fixture-avd';
      case 'shell getprop ro.build.version.sdk': return '35';
      case 'shell dumpsys webviewupdate': return 'fixture WebView provider';
      default: throw new Error(`Unexpected adb command ${command}`);
    }
  };
  syncBuiltinESMExports();
  globalThis.fetch = async address => new Response(JSON.stringify([{
    id: 'fixture-page',
    type: 'page',
    url: roomUrl,
    webSocketDebuggerUrl: `ws://${new URL(address).host}/devtools/page/fixture-page`,
  }]));

  let surfaceReadCount = 0;
  globalThis.WebSocket = class extends EventTarget {
    constructor() {
      super();
      queueMicrotask(() => this.dispatchEvent(new Event('open')));
    }

    send(payload) {
      const { id, method, params } = JSON.parse(payload);
      const expression = params.expression;
      let value;
      let error;
      if (method === 'Runtime.enable') {
        if (scenario === 'cdp-failure') error = { message: 'CDP fixture failure' };
      } else if (expression.includes('location.href')) {
        value = {
          url: scenario === 'wrong-surface' ? roomUrl.replace('fixture', 'different') : roomUrl,
          readyState: 'complete',
          title: 'FaceTime',
          text: scenario === 'empty-surface' ? '' : 'Name',
          userAgent: 'fixture-webview',
          timeOrigin: scenario === 'changed-document' ? ++surfaceReadCount : 1,
          bodyRendered: true,
          unavailable: true,
          unavailablePatched: true,
        };
      } else if (expression === 'axe.version') {
        value = scenario === 'wrong-engine' ? '0.0.0' : engineVersion;
      } else if (expression === 'axe.getRules()') {
        value = ruleCatalog;
      } else if (expression.startsWith('axe.run(')) {
        const rule = { id: 'label', impact: 'serious', nodes: [{}], help: 'Label controls' };
        const fullCatalog = expression.includes('runOnly');
        const otherRules = ruleCatalog.filter(({ ruleId, enabled }) => ruleId !== rule.id && (fullCatalog || enabled));
        value = {
          url: roomUrl,
          testEngine: { name: 'axe-core', version: engineVersion },
          violations: scenario === 'violation' ? [rule] : [],
          passes: ['all-inapplicable', 'missing-rule', 'violation'].includes(scenario) ? [] : [rule],
          incomplete: [],
          inapplicable: [
            ...otherRules.map(({ ruleId }) => ({ id: ruleId })),
            ...(scenario === 'all-inapplicable' ? [rule] : []),
          ],
        };
      }
      const data = JSON.stringify({ id, error, result: { result: { value } } });
      queueMicrotask(() => this.dispatchEvent(new MessageEvent('message', { data })));
    }

    close() {
      log('websocket closed');
      this.dispatchEvent(new Event('close'));
    }
  };
}

function run(t, scenario, mode = 'default') {
  const build = fileURLToPath(new URL('../../build/', import.meta.url));
  mkdirSync(build, { recursive: true });
  const root = mkdtempSync(join(build, 'axe-runner-test-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));

  const targetRunner = join(root, 'scripts/axe/run.mjs');
  const dependencies = join(dirname(targetRunner), 'node_modules');
  const builtApk = join(root, 'app/build/outputs/apk/debug/app-debug.apk');
  mkdirSync(dependencies, { recursive: true });
  mkdirSync(dirname(builtApk), { recursive: true });
  cpSync(runner, targetRunner);
  cpSync(new URL('./routes/', import.meta.url), join(dirname(targetRunner), 'routes'), { recursive: true });
  cpSync(manifest, join(dirname(targetRunner), 'package.json'));
  symlinkSync(axePackage, join(dependencies, 'axe-core'), 'dir');
  writeFileSync(builtApk, apk);

  const commands = join(root, 'adb-commands');
  writeFileSync(commands, '');
  const screenshot = (badScreenshots[scenario] ?? validScreenshot).toString('base64');
  const configuration = { commands, scenario, apkSha256, engineVersion, roomUrl, ruleCatalog, screenshot };
  const fixture = `await (${installFixture})(${JSON.stringify(configuration)})`;
  const result = spawnSync(process.execPath, [
    '--import', `data:text/javascript,${encodeURIComponent(fixture)}`,
    targetRunner, 'fixture-device', 'test-run', mode,
  ], { encoding: 'utf8', timeout: 10000 });
  assert.ifError(result.error);
  const artifact = file => readFileSync(join(root, 'build/reports/axe/test-run', file));
  return {
    ...result,
    commands: readFileSync(commands, 'utf8'),
    artifact,
    report: file => JSON.parse(artifact(file).toString('utf8')),
  };
}

for (const [scenario, error] of [
  ['wrong-apk', /APK SHA-256 mismatch/],
  ['wrong-engine', /axe engine version mismatch/],
  ['wrong-surface', /document URL does not match/],
  ['changed-document', /document changed during the scan/],
  ['missing-rule', /axe result rule inventory mismatch/],
  ['empty-surface', /FaceTime document has no rendered text/],
  ['all-inapplicable', /axe rules applied to no FaceTime content/],
  ['cdp-failure', /CDP fixture failure/],
]) {
  test(`rejects ${scenario} and cleans up acquired resources`, t => {
    const result = run(t, scenario);
    assert.equal(result.status, 1, result.stderr);
    assert.match(result.stderr, error);
    if (scenario === 'wrong-apk') {
      assert.doesNotMatch(result.commands, /^forward /m);
    } else {
      assert.match(result.commands, /^websocket closed\nforward --remove tcp:42899$/m);
    }
  });
}

for (const scenario of Object.keys(badScreenshots)) {
  test(`rejects ${scenario} without recording invalid evidence and cleans up`, t => {
    const result = run(t, scenario);
    assert.equal(result.status, 1, result.stderr);
    assert.match(result.stderr, /ADB did not return a complete PNG screenshot/);
    assert.throws(() => result.artifact('surface-before.png'), { code: 'ENOENT' });
    assert.throws(() => result.artifact('run-metadata.json'), { code: 'ENOENT' });
    assert.match(result.commands, /^websocket closed\nforward --remove tcp:42899$/m);
  });
}

test('retains a reported violation and exits nonzero', t => {
  const result = run(t, 'violation');
  assert.equal(result.status, 1, result.stderr);
  assert.equal(result.report('axe-full-results.json').violations[0].id, 'label');
  assert.match(result.commands, /^websocket closed\nforward --remove tcp:42899$/m);
});

for (const mode of ['default', 'full-catalog']) {
  test(`records a bound ${mode} receipt for the patched unavailable surface`, t => {
    const result = run(t, 'clean', mode);
    assert.equal(result.status, 0, result.stderr);
    const receipt = result.report('run-metadata.json');
    assert.equal(receipt.installedApkSha256, apkSha256);
    assert.equal(receipt.apkSha256, apkSha256);
    assert.equal(receipt.injectedAxeVersion, engineVersion);
    assert.equal(receipt.surface, 'unavailable-patched');
    assert.deepEqual(result.artifact('surface-before.png'), validScreenshot);
    assert.deepEqual(receipt.screenshot, {
      width: 1,
      height: 1,
      sha256: createHash('sha256').update(validScreenshot).digest('hex'),
    });
    assert.deepEqual(receipt.ruleIds, ruleCatalog.filter(rule => mode === 'full-catalog' || rule.enabled).map(rule => rule.ruleId));
    assert.match(result.commands, /^websocket closed\nforward --remove tcp:42899$/m);
  });
}
