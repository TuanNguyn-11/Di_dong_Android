import { writeFile, mkdir } from 'node:fs/promises';
const mode = process.argv[2] || 'status';
const pages = await (await fetch('http://127.0.0.1:9223/json')).json();
const page = pages.find(p => p.url.startsWith('https://localhost'));
if (!page) throw Error('App WebView not available');
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((resolve,reject) => { ws.onopen=resolve; ws.onerror=reject; });
let serial=0;
const pending=new Map();
ws.onmessage=e=>{const r=JSON.parse(e.data); if(pending.has(r.id)){pending.get(r.id)(r);pending.delete(r.id);}};
async function command(method,params){const id=++serial;const p=new Promise(r=>pending.set(id,r));ws.send(JSON.stringify({id,method,params}));return p;}
async function evaluate(expression){const r=await command('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});if(r.error||r.result.exceptionDetails)throw Error(JSON.stringify(r));return r.result.result.value;}
const timeout=setTimeout(()=>{console.error('Device check timed out');process.exit(1);},45000);
try {
 let result;
 if(mode==='status') result=await evaluate(`(async()=>({
   page:document.querySelector('.page--active')?.dataset.page,
   toggles:[...document.querySelectorAll('input[type=checkbox]')].map(e=>({id:e.id,checked:e.checked})),
   native:await Capacitor.Plugins.FallGuardSensor.isAvailable(),
   locationPermission:await Capacitor.Plugins.Geolocation.checkPermissions(),
   battery:await Capacitor.Plugins.Device.getBatteryInfo()
 }))()`);
 else if(mode==='prepare') result=await evaluate(`(async()=>{
   const toggle=document.querySelector('#toggle-monitoring');
   const wasEnabled=!!toggle?.checked;
   if(wasEnabled)toggle.click();
   await new Promise(r=>setTimeout(r,500));
   return {wasEnabled,monitoringEnabled:!!toggle?.checked,status:await Capacitor.Plugins.FallGuardSensor.isAvailable()};
 })()`);
 else if(mode==='resume') result=await evaluate(`(async()=>{
   const toggle=document.querySelector('#toggle-monitoring');
   if(!toggle)throw Error('Monitoring toggle not found');
   if(!toggle.checked)toggle.click();
   await new Promise(r=>setTimeout(r,2000));
   return {monitoringEnabled:toggle.checked,status:await Capacitor.Plugins.FallGuardSensor.isAvailable()};
 })()`);
 else if(mode==='gps') result=await evaluate(`(async()=>{
   const started=Date.now();
   const p=await Capacitor.Plugins.Geolocation.getCurrentPosition({enableHighAccuracy:true,timeout:15000,maximumAge:0});
   return {hasCoordinates:Number.isFinite(p.coords.latitude)&&Number.isFinite(p.coords.longitude),accuracyMeters:p.coords.accuracy,elapsedMs:Date.now()-started,ageMs:Date.now()-p.timestamp};
 })()`);
 else if(mode==='alarm') result=await evaluate(`(async()=>{
   if(document.querySelector('#toggle-monitoring')?.checked)throw Error('Monitoring must be off for local alarm test');
   const p=Capacitor.Plugins.FallGuardSensor;
   try{
     await p.start({alarmMuted:false});
     await p.startAlarm();
     await new Promise(r=>setTimeout(r,2000));
     await p.stopAlarm();
     return {startAndStopCommandsCompleted:true,durationMs:2000,cloudAlertRequested:false};
   }finally{await p.stop();}
 })()`);
 else if(mode==='start') result=await evaluate(`(async()=>{
   const toggle=document.querySelector('#toggle-monitoring');
   if(toggle?.checked) throw Error('Turn monitoring off before isolated sensor check');
   const p=Capacitor.Plugins.FallGuardSensor;
   window.__deviceCheck={batches:0,samples:0,errors:[],falls:0,first:0,last:0,handles:[]};
   const r=window.__deviceCheck;
   r.handles.push(await p.addListener('sensorBatch',b=>{r.batches++;r.samples+=b.ax.length;r.first ||= b.t0;r.last=b.t0;r.lastBatch={hz:b.hz,count:b.ax.length};}));
   r.handles.push(await p.addListener('monitorError',e=>r.errors.push(e.message)));
   r.handles.push(await p.addListener('fallDetected',()=>{r.falls++;}));
   await p.start({alarmMuted:true});
   return p.isAvailable();
 })()`);
 else if(mode==='sample') result=await evaluate(`(async()=>{
   const r=window.__deviceCheck;
   return {status:await Capacitor.Plugins.FallGuardSensor.isAvailable(),
     samples:r?.samples,batches:r?.batches,errors:r?.errors,fallCandidates:r?.falls,first:r?.first,last:r?.last,lastBatch:r?.lastBatch};
 })()`);
 else if(mode==='stop') result=await evaluate(`(async()=>{
   await Capacitor.Plugins.FallGuardSensor.stop();
   for(const h of window.__deviceCheck?.handles || [])await h.remove();
   delete window.__deviceCheck;
   await new Promise(r=>setTimeout(r,300));
   return Capacitor.Plugins.FallGuardSensor.isAvailable();
 })()`);
 else throw Error('Unknown check mode');
 await mkdir(new URL('../artifacts/phone-check/',import.meta.url),{recursive:true});
 await writeFile(new URL('../artifacts/phone-check/'+mode+'.json',import.meta.url),JSON.stringify(result,null,2));
 console.log(JSON.stringify(result,null,2));
}finally{clearTimeout(timeout);ws.close();}
