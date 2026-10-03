import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { build } from 'esbuild';
await mkdir('artifacts/preview', { recursive:true });
await build({entryPoints:['src/diagnostics.ts'],bundle:true,format:'iife',globalName:'Diagnostics',outfile:'artifacts/preview/diagnostics.js'});
let html=await readFile('www/index.html','utf8');
html=html.replace('href="styles.css"','href="../../www/styles.css"')
 .replace('class="login-screen"','class="login-screen" hidden')
 .replace('class="viewer" hidden','class="viewer"')
 .replace('class="ai-panel" aria-labelledby="ai-heading" hidden','class="ai-panel" aria-labelledby="ai-heading"')
 .replace('class="charts" id="wave-panel"','class="charts" id="wave-panel" hidden')
 .replace('<script src="app.js"></script>',`<script src="diagnostics.js"></script><script>
document.querySelector('.topbar__title').textContent='Sensor Viewer — DỮ LIỆU KIỂM THỬ GIAO DIỆN';
document.querySelectorAll('.toolbar__group:not(:first-child)').forEach(e=>e.hidden=true);
document.querySelectorAll('[data-view]').forEach(b=>b.classList.toggle('chip--active',b.dataset.view==='ai'));
Diagnostics.updateDiagnostics({schemaVersion:1,source:'native-litert',running:true,modelName:'dilated_aug_s0_int8.tflite',modelSha256:'436a3463ee4802aa960c777775b680d3f9fc50a1c5798b0204a5c8c91dff0f11',sessionId:'preview-session',inferenceCount:1234,lastInferenceMicros:850,probability:0.126,inferenceAgeMs:20,goldenPassed:10,updatedAt:Date.now()},true);
</script>`);
await writeFile('artifacts/preview/index.html',html);
