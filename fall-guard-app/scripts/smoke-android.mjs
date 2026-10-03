// Debug APK only. Forward port 9222 to its WebView devtools socket using adb first.
// No sign-in, cloud writes or synthetic fall notifications are performed.
import { writeFile } from 'node:fs/promises';
const pages = await (await fetch('http://127.0.0.1:9222/json')).json();
const page = pages.find(p => p.url.startsWith('https://localhost'));
if (!page) throw new Error('Fall Guard WebView not found');
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
let id = 0;
const pending = new Map();
ws.onmessage = e => {
  const message = JSON.parse(e.data);
  if (pending.has(message.id)) {
    pending.get(message.id)(message);
    pending.delete(message.id);
  }
};
async function evaluate(expression) {
  const callId = ++id;
  const response = new Promise(resolve => pending.set(callId, resolve));
  ws.send(JSON.stringify({ id: callId, method: 'Runtime.evaluate', params: {
    expression, awaitPromise: true, returnByValue: true
  }}));
  const timer = setTimeout(() => { console.error('WebView timeout'); process.exit(1); }, 30000);
  const result = await response;
  clearTimeout(timer);
  if (result.error || result.result.exceptionDetails) throw new Error(JSON.stringify(result));
  return result.result.result.value;
}
try {
  const result = await evaluate(`(async () => {
    const plugin = window.Capacitor.Plugins.FallGuardSensor;
    const statusBefore = await plugin.isAvailable();
    const batches = [], errors = [];
    const batchHandle = await plugin.addListener('sensorBatch', b => batches.push({ hz: b.hz, count: b.ax.length, t0: b.t0 }));
    const errorHandle = await plugin.addListener('monitorError', e => errors.push(e.message));
    try {
      await plugin.start({ alarmMuted: true });
      await new Promise(resolve => setTimeout(resolve, 3500));
      const status = await plugin.isAvailable();
      return {
        title: document.title,
        page: document.querySelector('.page--active')?.dataset.page,
        hasLogin: !!document.querySelector('input[type="password"]'),
        statusBefore, status, batches, errors,
        pass: status.running && status.inferenceCount >= 10 && batches.length >= 4 && errors.length === 0
      };
    } finally {
      await plugin.stop(); await batchHandle.remove(); await errorHandle.remove();
    }
  })()`);
  result.statusAfterStop = await evaluate(`(async () => {
    const plugin = window.Capacitor.Plugins.FallGuardSensor;
    for (let i = 0; i < 20; i++) {
      const status = await plugin.isAvailable();
      if (!status.running) return status;
      await new Promise(resolve => setTimeout(resolve, 100));
    }
    return plugin.isAvailable();
  })()`);
  result.pass = result.pass && !result.statusAfterStop.running;
  await writeFile(new URL('../artifacts/android-smoke.json', import.meta.url), JSON.stringify(result, null, 2));
  console.log(JSON.stringify(result, null, 2));
  if (!result.pass) process.exitCode = 1;
} finally { ws.close(); }
