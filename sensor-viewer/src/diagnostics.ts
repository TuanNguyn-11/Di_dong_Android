export interface AiSnapshot {
  schemaVersion: 1; source: 'native-litert'; running: boolean;
  modelName: string; modelSha256: string; sessionId: string;
  inferenceCount: number; lastInferenceMicros: number;
  probability: number; inferenceAgeMs: number; goldenPassed: number; updatedAt: number;
}

export function parseAiSnapshot(value: unknown): AiSnapshot | null {
  if (!value || typeof value !== 'object') return null;
  const v = value as Record<string, unknown>;
  if (v.schemaVersion !== 1 || v.source !== 'native-litert' || typeof v.running !== 'boolean') return null;
  for (const key of ['modelName', 'modelSha256', 'sessionId']) if (typeof v[key] !== 'string') return null;
  for (const key of ['inferenceCount','lastInferenceMicros','probability','inferenceAgeMs','goldenPassed','updatedAt'])
    if (typeof v[key] !== 'number' || !Number.isFinite(v[key])) return null;
  if ((v.inferenceCount as number) < 0 || (v.lastInferenceMicros as number) < 0
      || (v.probability as number) < -1 || (v.probability as number) > 1
      || (v.inferenceAgeMs as number) < -1 || (v.updatedAt as number) <= 0) return null;
  return v as unknown as AiSnapshot;
}

export function aiState(ai: AiSnapshot | null, streaming: boolean, now: number, error: string): { label: string; kind: string } {
  if (error) return { label: error, kind: 'error' };
  if (!ai) return { label: 'Chưa có dữ liệu AI — bật giám sát trên APK hỗ trợ chẩn đoán.', kind: 'empty' };
  if (!streaming || !ai.running) return { label: 'Đã dừng giám sát · số liệu lần cuối', kind: 'stopped' };
  const age = Math.max(0, now - ai.updatedAt);
  if (age > 5000) return { label: 'Dữ liệu cũ · mất kết nối hoặc app chưa gửi cập nhật', kind: 'stale' };
  if (ai.goldenPassed !== 10) return { label: 'Chưa xác nhận đủ 10 mẫu chuẩn', kind: 'error' };
  if (!ai.inferenceCount || ai.inferenceAgeMs < 0) return { label: 'Đang chờ cửa sổ cảm biến đầu tiên', kind: 'waiting' };
  if (ai.inferenceAgeMs + age > 3000) return { label: 'Chưa có lần suy luận mới · kiểm tra cảm biến', kind: 'stale' };
  return { label: 'AI đang suy luận trên điện thoại', kind: 'live' };
}

let snapshot: AiSnapshot | null = null;
let streaming = false;
let error = '';
let serverOffset = 0;
export function updateDiagnostics(value: AiSnapshot | null, isStreaming: boolean, message = '') {
  snapshot = value; streaming = isStreaming; error = message; renderDiagnostics();
}
export function setDiagnosticClockOffset(offset: number) { serverOffset = offset; }
const put = (id: string, value: string) => { const node = document.getElementById(id); if (node) node.textContent = value; };
export function renderDiagnostics() {
  const now = Date.now() + serverOffset;
  const state = aiState(snapshot, streaming, now, error);
  put('ai-status', state.label);
  document.getElementById('ai-status')?.setAttribute('data-state', state.kind);
  put('ai-count', snapshot ? snapshot.inferenceCount.toLocaleString('vi-VN') : '—');
  put('ai-latency', snapshot?.inferenceCount ? `${(snapshot.lastInferenceMicros / 1000).toFixed(2)} ms` : '—');
  const probability = snapshot && snapshot.probability >= 0 ? snapshot.probability : null;
  put('ai-probability', probability !== null ? `${(probability * 100).toFixed(1)}%` : '—');
  const meter = document.getElementById('ai-meter') as HTMLMeterElement | null;
  if (meter) { meter.value = probability ?? 0; meter.hidden = probability === null; }
  put('ai-golden', snapshot ? `${snapshot.goldenPassed}/10` : '—');
  put('ai-model', snapshot?.modelName || '—');
  put('ai-sha', snapshot?.modelSha256 || '—');
  put('ai-session', snapshot?.sessionId || '—');
  put('ai-updated', snapshot ? `${new Date(snapshot.updatedAt).toLocaleTimeString('vi-VN')} · ${Math.floor(Math.max(0, now - snapshot.updatedAt) / 1000)} giây trước` : 'Chưa nhận dữ liệu');
  const exportButton = document.getElementById('ai-export') as HTMLButtonElement | null;
  if (exportButton) exportButton.disabled = !snapshot;
}
export function exportDiagnostics(deviceId: string | null) {
  if (!snapshot) return;
  const evidence = { deviceId, exportedAt: new Date().toISOString(), state: aiState(snapshot, streaming, Date.now() + serverOffset, error), snapshot,
    note: 'Telemetry từ app; kiểm tra số học và trạng thái suy luận, không phải chứng nhận độ chính xác phát hiện té ngã.' };
  const url = URL.createObjectURL(new Blob([JSON.stringify(evidence, null, 2)], { type: 'application/json' }));
  const a = document.createElement('a'); a.href = url; a.download = `ai-diagnostics-${deviceId || 'device'}-${Date.now()}.json`; a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
