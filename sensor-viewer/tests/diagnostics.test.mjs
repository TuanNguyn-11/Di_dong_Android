import test from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
const result = await build({ entryPoints: ['src/diagnostics.ts'], bundle: true, format: 'esm', write: false });
const { parseAiSnapshot, aiState } = await import(`data:text/javascript;base64,${Buffer.from(result.outputFiles[0].text).toString('base64')}`);
const now = 1_000_000;
const valid = { schemaVersion:1, source:'native-litert', running:true, modelName:'model.tflite', modelSha256:'a'.repeat(64), sessionId:'session', inferenceCount:25,lastInferenceMicros:550,probability:0.3,inferenceAgeMs:40,goldenPassed:10,updatedAt:now };
test('accept native telemetry; reject legacy/malformed/nonfinite data',()=>{
 assert.deepEqual(parseAiSnapshot(valid),valid);
 for(const value of [null,{}, {...valid,source:'mock'},{...valid,probability:1.5},{...valid,inferenceCount:NaN},{...valid,updatedAt:0}])assert.equal(parseAiSnapshot(value),null);
});
test('fresh native inference is live',()=>assert.equal(aiState(valid,true,now+500,'').kind,'live'));
test('stopped meta wins over a cached running snapshot',()=>assert.equal(aiState(valid,false,now,'').kind,'stopped'));
test('cached cloud data becomes stale without further snapshots',()=>assert.equal(aiState(valid,true,now+6000,'').kind,'stale'));
test('fresh cloud writes cannot hide stalled native inference',()=>assert.equal(aiState({...valid,inferenceAgeMs:4000},true,now,'').kind,'stale'));
test('zero inference count means waiting, not proof of AI running',()=>assert.equal(aiState({...valid,inferenceCount:0,inferenceAgeMs:-1},true,now,'').kind,'waiting'));
test('missing data, failed golden and permission errors never become live',()=>{
 assert.equal(aiState(null,true,now,'').kind,'empty');
 assert.equal(aiState({...valid,goldenPassed:9},true,now,'').kind,'error');
 assert.equal(aiState(valid,true,now,'Permission denied').kind,'error');
});
