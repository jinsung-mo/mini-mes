// Mini MES 단말 화면
//
// 이 화면은 어느 WAS가 응답하는지 모른다. 요청 주소의 앞부분만 바뀐다.
//   C# 레거시 선택 → /legacy/api/... → nginx → localhost:5080
//   Java 선택     → /java/api/...   → nginx → localhost:8081
// 두 WAS가 같은 응답 계약(HTTP 200 + resultCode, snake_case 키)을 지키면 이 파일은 고칠 필요가 없다.

const BACKENDS = {
  legacy: { label: 'C# 레거시', port: 5080, base: '/legacy' },
  java: { label: 'Java', port: 8081, base: '/java' },
};

const STATUS_LABEL = { CREATED: '생성', RELEASED: '지시', IN_PROGRESS: '작업중', COMPLETED: '완료' };
const ACTION_LABEL = { release: '릴리즈', start: '작업 시작', complete: '완료' };
const NEXT_ACTION = { CREATED: 'release', RELEASED: 'start', IN_PROGRESS: 'complete' };

const state = {
  backend: loadPref('backend', 'legacy'),
  plantCd: loadPref('plantCd', 'KR01'),
  statusFilter: '',
  plants: [],
  items: [],
  equipments: [],
  defectCodes: [],
  workOrders: [],
  selectedId: null,
  selected: null,    // { wo, lots, results, routings }
  reportForm: null,  // 마지막 실적 보고 입력값. 화면을 다시 그려도 유지한다
  lastReport: null,
};

const $ = (selector) => document.querySelector(selector);

// ---------- 서버 호출 ----------

class ApiError extends Error {
  constructor(code, message) {
    super(message);
    this.code = code;
  }
}

async function send(backend, method, path, body) {
  const target = BACKENDS[backend];
  const out = { http: null, upstream: null, json: null, error: null };
  try {
    const res = await fetch(target.base + path, {
      method,
      headers: body ? { 'Content-Type': 'application/json' } : undefined,
      body: body ? JSON.stringify(body) : undefined,
    });
    out.http = res.status;
    out.upstream = res.headers.get('X-Upstream');
    const text = await res.text();
    try {
      out.json = JSON.parse(text);
    } catch {
      out.error = `${target.label} WAS(:${target.port})가 응답하지 않아요 (HTTP ${res.status})`;
    }
  } catch (e) {
    out.error = `웹 서버(nginx)에 연결할 수 없어요 (${e.message})`;
  }
  return out;
}

// 화면에서 쓰는 호출. 로그를 남기고, resultCode가 0000이 아니면 예외로 바꾼다.
async function api(method, path, body) {
  const backend = state.backend;
  const started = performance.now();
  const res = await send(backend, method, path, body);
  addLog({ at: new Date(), backend, method, path, request: body ?? null, ms: Math.round(performance.now() - started), ...res });

  if (res.error) throw new ApiError('NET', res.error);
  const { resultCode, message, data } = res.json ?? {};
  if (resultCode === undefined) throw new ApiError(`HTTP ${res.http}`, '응답에 resultCode가 없어요. 약속한 응답 형식과 달라요.');
  if (resultCode !== '0000') throw new ApiError(resultCode, message);
  return data;
}

async function checkHealth() {
  await Promise.all(Object.entries(BACKENDS).map(async ([key, { label, port }]) => {
    const res = await send(key, 'GET', '/api/plants');
    let status = 'down';
    let title = `${label} WAS(:${port}) 응답 없음`;
    if (res.json?.resultCode === '0000') {
      status = 'up';
      title = `${label} WAS(:${port}) 정상`;
    } else if (res.http && res.http !== 502 && res.http !== 504) {
      status = 'partial';
      title = `${label} WAS(:${port})는 떠 있지만 /api/plants가 아직 약속한 형식으로 응답하지 않아요`;
    }
    const dot = document.querySelector(`[data-health="${key}"]`);
    dot.className = `dot ${status}`;
    dot.parentElement.title = title;
  }));
}

// ---------- 데이터 불러오기 ----------

async function reloadAll() {
  try {
    await loadPlants();
    await loadPlantMasters();
    await loadWorkOrders();
    if (state.selectedId) await loadDetail();
  } catch (e) {
    if (e.code === 'E004') clearSelection();
    else showLoadFailed(e);
    showError(e);
  }
  setDefaultPlanDate();
  tick();
}

async function loadPlants() {
  state.plants = await api('GET', '/api/plants');
  if (!plantOf(state.plantCd)) state.plantCd = state.plants[0]?.plant_cd;
  $('#plant-select').innerHTML = state.plants
    .map((p) => `<option value="${esc(p.plant_cd)}">${esc(p.plant_nm)} (${esc(p.plant_cd)})</option>`)
    .join('');
  $('#plant-select').value = state.plantCd;
}

async function loadPlantMasters() {
  const plantCd = encodeURIComponent(state.plantCd);
  const [items, equipments, defectCodes] = await Promise.all([
    api('GET', `/api/items?plantCd=${plantCd}`),
    api('GET', `/api/equipments?plantCd=${plantCd}`),
    api('GET', '/api/defect-codes'),
  ]);
  Object.assign(state, { items, equipments, defectCodes });

  const select = $('#item-select');
  const prev = select.value;
  select.innerHTML = items.map((i) => `<option value="${esc(i.item_cd)}">${esc(i.item_cd)} · ${esc(i.item_nm)}</option>`).join('');
  if (items.some((i) => i.item_cd === prev)) select.value = prev;
}

async function loadWorkOrders() {
  const query = new URLSearchParams({ plantCd: state.plantCd });
  if (state.statusFilter) query.set('status', state.statusFilter);
  state.workOrders = await api('GET', `/api/work-orders?${query}`);
  renderWorkOrders();
}

async function loadDetail() {
  const id = state.selectedId;
  const [wo, lots, results] = await Promise.all([
    api('GET', `/api/work-orders/${id}`),
    api('GET', `/api/work-orders/${id}/lots`),
    api('GET', `/api/work-orders/${id}/results`),
  ]);

  // 주소(#wo=..)로 다른 공장의 작업지시를 열었으면 공장을 맞춘다
  if (wo.plant_cd !== state.plantCd) {
    state.plantCd = wo.plant_cd;
    savePref('plantCd', wo.plant_cd);
    $('#plant-select').value = wo.plant_cd;
    await loadPlantMasters();
    await loadWorkOrders();
  }

  const query = new URLSearchParams({ plantCd: wo.plant_cd, itemCd: wo.item_cd });
  const routings = await api('GET', `/api/routings?${query}`);
  state.selected = { wo, lots, results, routings };
  renderDetail();
}

async function refreshAfterChange() {
  await loadWorkOrders();
  await loadDetail();
}

// ---------- 사용자 동작 ----------

async function switchBackend(backend) {
  if (backend === state.backend) return;
  state.backend = backend;
  savePref('backend', backend);
  renderBackendSwitch();
  const { label, port } = BACKENDS[backend];
  toast(`이제 ${label} WAS(:${port})로 요청해요. 화면 코드는 그대로예요.`);
  await reloadAll();
}

async function switchPlant(plantCd) {
  state.plantCd = plantCd;
  savePref('plantCd', plantCd);
  clearSelection();
  $('#plan-date').value = '';
  try {
    await loadPlantMasters();
    await loadWorkOrders();
  } catch (e) {
    showError(e);
  }
  setDefaultPlanDate();
  tick();
}

async function selectWorkOrder(id) {
  state.selectedId = id;
  history.replaceState(null, '', `#wo=${id}`);
  renderWorkOrders();
  try {
    await loadDetail();
  } catch (e) {
    if (e.code === 'E004') clearSelection();
    showError(e);
  }
}

async function createWorkOrder(form) {
  const f = Object.fromEntries(new FormData(form));
  try {
    const data = await api('POST', '/api/work-orders', {
      plantCd: state.plantCd,
      itemCd: f.itemCd,
      planQty: toInt(f.planQty),
      planDate: f.planDate,
    });
    toast(`작업지시 ${data.wo_no} 생성`, 'ok');
    await loadWorkOrders();
    await selectWorkOrder(data.wo_id);
  } catch (e) {
    showError(e);
  }
}

async function changeStatus(action) {
  try {
    await api('POST', `/api/work-orders/${state.selectedId}/${action}`);
    toast(`${ACTION_LABEL[action]} 처리했어요`, 'ok');
    await refreshAfterChange();
  } catch (e) {
    showError(e);
  }
}

async function reportResult(form) {
  const f = Object.fromEntries(new FormData(form));
  state.reportForm = f;
  const { wo } = state.selected;
  const timeZone = plantOf(wo.plant_cd)?.time_zone;
  const body = {
    woId: wo.wo_id,
    procCd: f.procCd,
    eqpCd: f.eqpCd,
    goodQty: toInt(f.goodQty),
    defectQty: toInt(f.defectQty),
    defectCd: f.defectCd || null,
    reportedAt: f.reportedAt ? withPlantOffset(f.reportedAt, timeZone) : null,
  };
  try {
    const data = await api('POST', '/api/production-results', body);
    state.lastReport = {
      woId: wo.wo_id,
      backend: state.backend,
      data,
      expected: f.reportedAt ? expectedShift(f.reportedAt) : null,
    };
    toast(data.lot_no ? `실적 보고 완료 · LOT ${data.lot_no}` : '실적 보고 완료', 'ok');
    await refreshAfterChange();
  } catch (e) {
    showError(e);
  }
}

// ---------- 그리기 ----------

function renderBackendSwitch() {
  document.body.dataset.backend = state.backend;
  document.querySelectorAll('.backend-btn').forEach((btn) => {
    btn.classList.toggle('active', btn.dataset.backend === state.backend);
  });
}

function renderWorkOrders() {
  const rows = state.workOrders.map((wo) => `
    <tr data-id="${wo.wo_id}" class="${wo.wo_id === state.selectedId ? 'selected' : ''}">
      <td class="mono">${esc(wo.wo_no)}</td>
      <td>${esc(wo.item_cd)}</td>
      <td><div class="bar"><span style="width:${progress(wo)}%"></span></div><small>${wo.good_qty} / ${wo.plan_qty}</small></td>
      <td class="num">${wo.defect_qty}</td>
      <td>${statusBadge(wo.status)}</td>
    </tr>`);
  $('#wo-rows').innerHTML = rows.join('')
    || '<tr><td colspan="5" class="empty">작업지시가 없어요. 위에서 새로 만들어 보세요.</td></tr>';
}

function showLoadFailed(e) {
  const { label, port } = BACKENDS[state.backend];
  $('#wo-rows').innerHTML = `
    <tr><td colspan="5" class="empty">${esc(label)} WAS(:${port})에서 불러오지 못했어요.<br><small>${esc(e.message)}</small></td></tr>`;
  $('#detail').hidden = true;
  $('#detail-empty').hidden = false;
}

function clearSelection() {
  state.selectedId = null;
  state.selected = null;
  history.replaceState(null, '', location.pathname);
  $('#detail').hidden = true;
  $('#detail-empty').hidden = false;
  renderWorkOrders();
}

function renderDetail() {
  const { wo, lots, results, routings } = state.selected;
  const next = NEXT_ACTION[wo.status];
  const pct = progress(wo);

  $('#detail-empty').hidden = true;
  const el = $('#detail');
  el.hidden = false;
  el.innerHTML = `
    <div class="detail-head">
      <div>
        <div class="mono big">${esc(wo.wo_no)}</div>
        <div class="muted">${esc(wo.item_cd)} · 계획일 ${esc(wo.plan_date)}</div>
        <div class="muted">시작 ${esc(wo.started_at ?? '-')} · 완료 ${esc(wo.completed_at ?? '-')}</div>
      </div>
      ${statusBadge(wo.status)}
    </div>

    <div class="progress">
      <div class="bar big"><span style="width:${pct}%"></span></div>
      <div class="progress-nums">양품 <b>${wo.good_qty}</b> / 계획 ${wo.plan_qty} (${pct}%) · 불량 <b>${wo.defect_qty}</b></div>
    </div>

    <div class="actions">
      ${Object.entries(ACTION_LABEL).map(([action, label]) => `
        <button type="button" class="btn ${action === next ? 'primary' : 'ghost'}" data-action="${action}">${label}</button>`).join('')}
      <span class="hint">파란 버튼이 다음 단계예요. 다른 버튼을 누르면 서버가 거절(E005)하는 걸 볼 수 있어요.</span>
    </div>

    <h3>실적 보고</h3>
    <form id="report-form" class="report-form">
      <label class="c2">공정
        <select name="procCd">${routings.map((r) => `<option value="${esc(r.proc_cd)}">${r.seq} · ${esc(r.proc_nm)}</option>`).join('')}</select>
      </label>
      <label class="c2">설비 <select name="eqpCd"></select></label>
      <label>양품 <input name="goodQty" type="number" value="1"></label>
      <label>불량 <input name="defectQty" type="number" value="0"></label>
      <label class="c2">불량 코드
        <select name="defectCd">
          <option value="">없음</option>
          ${state.defectCodes.map((d) => `<option value="${esc(d.defect_cd)}">${esc(d.defect_cd)} · ${esc(d.defect_nm)}</option>`).join('')}
        </select>
      </label>
      <label class="c3">보고 시각 (공장 현지 시각)
        <span class="time-row">
          <input name="reportedAt" type="datetime-local">
          <button type="button" class="btn ghost small" data-now>지금</button>
        </span>
      </label>
      <div class="submit"><button class="btn primary">보고</button></div>
      <div class="hint c6" data-preview></div>
    </form>
    ${renderReportResult()}

    <h3>LOT (${lots.length})</h3>
    ${renderLots(lots)}

    <h3>실적 이력 (${results.length})</h3>
    ${renderResults(results)}`;

  restoreReportForm($('#report-form'));
}

function restoreReportForm(form) {
  const saved = state.reportForm ?? {};
  const timeZone = plantOf(state.selected.wo.plant_cd)?.time_zone;
  if ([...form.procCd.options].some((o) => o.value === saved.procCd)) form.procCd.value = saved.procCd;
  fillEquipments(form, saved.eqpCd);
  for (const name of ['goodQty', 'defectQty', 'defectCd']) {
    if (saved[name] !== undefined) form[name].value = saved[name];
  }
  form.reportedAt.value = saved.reportedAt ?? zonedNow(timeZone).minute;
  updateReportPreview(form);
}

function fillEquipments(form, selected) {
  const list = state.equipments.filter((e) => e.proc_cd === form.procCd.value);
  form.eqpCd.innerHTML = list.map((e) => `<option value="${esc(e.eqp_cd)}">${esc(e.eqp_cd)} · ${esc(e.eqp_nm)}</option>`).join('');
  if (list.some((e) => e.eqp_cd === selected)) form.eqpCd.value = selected;
}

function updateReportPreview(form) {
  const timeZone = plantOf(state.selected.wo.plant_cd)?.time_zone;
  form.querySelector('[data-preview]').textContent = form.reportedAt.value
    ? `서버로 보낼 값: "${withPlantOffset(form.reportedAt.value, timeZone)}"  (${timeZone} 현지 시각에 오프셋을 붙임)`
    : '보고 시각을 비우면 서버가 자기 현재 시각을 써요.';
}

function renderReportResult() {
  const r = state.lastReport;
  if (!r || r.woId !== state.selectedId) return '';
  const { data, expected } = r;
  const same = expected && expected.shift === data.shift_cd && expected.workDate === data.work_date;

  let verdict = '보고 시각을 비워서 보냈어요. 서버가 자기 현재 시각으로 근무조를 계산했어요.';
  if (expected && same) verdict = '✅ 공장 현지 시각으로 계산한 기대값과 같아요.';
  if (expected && !same) verdict = '⚠️ 기대값과 달라요. 서버가 공장 현지 시각이 아니라 서버 시각으로 근무조를 계산했어요. (레거시 문제점 2)';

  return `
    <div class="result-card ${!expected || same ? 'ok' : 'warn'}">
      <div><span class="muted">처리한 WAS</span> ${backendBadge(r.backend)}</div>
      <div><span class="muted">발행 LOT</span> <b class="mono">${esc(data.lot_no ?? '없음 (양품 0)')}</b></div>
      <div><span class="muted">서버 계산</span> <b>${esc(data.work_date)} · ${esc(data.shift_cd)}조</b></div>
      <div><span class="muted">기대값</span> ${expected
        ? `<b>${esc(expected.workDate)} · ${esc(expected.shift)}조</b> <span class="muted">(현지 ${esc(expected.local)})</span>`
        : '-'}</div>
      <div class="verdict">${verdict}</div>
      ${data.wo_status === 'COMPLETED' ? '<div class="verdict">계획 수량을 채워서 작업지시가 자동 완료됐어요.</div>' : ''}
    </div>`;
}

function renderLots(lots) {
  if (!lots.length) return '<div class="empty">아직 발행된 LOT이 없어요.</div>';
  return `
    <div class="table-wrap"><table class="grid compact">
      <thead><tr><th>LOT 번호</th><th class="num">수량</th><th>생성 시각</th></tr></thead>
      <tbody>${lots.map((l) => `
        <tr><td class="mono">${esc(l.lot_no)}</td><td class="num">${l.qty}</td><td>${esc(l.created_at)}</td></tr>`).join('')}
      </tbody>
    </table></div>`;
}

function renderResults(results) {
  if (!results.length) return '<div class="empty">아직 보고된 실적이 없어요.</div>';
  return `
    <div class="table-wrap"><table class="grid compact">
      <thead><tr>
        <th>#</th><th>공정</th><th>설비</th><th class="num">양품</th><th class="num">불량</th><th>불량 코드</th>
        <th>근무일자</th><th>조</th><th title="레거시 서버가 Asia/Seoul로 고정해서 보내요 (레거시 문제점 3)">보고 시각</th>
      </tr></thead>
      <tbody>${results.map((r) => `
        <tr>
          <td>${r.result_id}</td><td>${esc(r.proc_cd)}</td><td class="mono">${esc(r.eqp_cd)}</td>
          <td class="num">${r.good_qty}</td><td class="num">${r.defect_qty}</td><td>${esc(r.defect_cd ?? '')}</td>
          <td>${esc(r.work_date)}</td><td>${esc(r.shift_cd)}</td><td>${esc(r.reported_at)}</td>
        </tr>`).join('')}
      </tbody>
    </table></div>`;
}

// ---------- API 로그, 응답 비교 ----------

function addLog(entry) {
  const ok = entry.json?.resultCode === '0000';
  const code = entry.json?.resultCode ?? `HTTP ${entry.http ?? '-'}`;
  const li = document.createElement('li');
  li.innerHTML = `
    <details>
      <summary>
        <span class="mono muted">${fmtTime(entry.at)}</span>
        <span>${backendBadge(entry.backend)}</span>
        <span class="method ${entry.method}">${entry.method}</span>
        <span class="mono path" title="${esc(entry.path)}">${esc(entry.path)}</span>
        <span class="code ${ok ? 'ok' : 'err'}">${esc(code)}</span>
        <span class="muted num">${entry.ms}ms</span>
      </summary>
      <div class="log-body">
        <div class="muted mono">${esc(BACKENDS[entry.backend].base + entry.path)} → nginx → ${esc(entry.upstream ?? '(응답 없음)')}</div>
        ${entry.method === 'GET'
          ? '<button type="button" class="btn ghost small" data-compare>C# ↔ Java에 같은 요청을 보내서 비교</button><div class="compare"></div>'
          : ''}
        ${entry.request ? `<div class="label">요청 본문</div><pre>${esc(pretty(entry.request))}</pre>` : ''}
        <div class="label">응답 (HTTP ${entry.http ?? '-'})</div>
        <pre>${esc(entry.error ?? pretty(entry.json))}</pre>
      </div>
    </details>`;
  li.querySelector('[data-compare]')?.addEventListener('click', () => compareBackends(entry.path, li.querySelector('.compare')));

  const list = $('#log-list');
  list.prepend(li);
  while (list.children.length > 200) list.lastElementChild.remove();
}

// 조회(GET)만 비교한다. 등록·변경(POST)은 같은 DB에 두 번 반영되니까 화면에서 비교하지 않는다.
async function compareBackends(path, box) {
  box.innerHTML = '<span class="muted">두 WAS에 같은 요청을 보내는 중...</span>';
  const [cs, java] = await Promise.all([send('legacy', 'GET', path), send('java', 'GET', path)]);
  if (cs.error || java.error) {
    const down = [cs.error && 'C# 레거시', java.error && 'Java'].filter(Boolean).join(', ');
    box.innerHTML = `<div class="diff">비교할 수 없어요. 응답 없는 WAS: ${esc(down)}</div>`;
    return;
  }

  const diffs = diffJson(cs.json, java.json);
  const summary = diffs.length
    ? `<div class="diff">❌ ${diffs.length}곳이 달라요</div>
       <div class="table-wrap"><table class="grid compact">
         <thead><tr><th>위치</th><th>C# 레거시</th><th>Java</th></tr></thead>
         <tbody>${diffs.slice(0, 30).map((d) => `
           <tr><td class="mono">${esc(d.path)}</td><td class="mono">${esc(showValue(d.a))}</td><td class="mono">${esc(showValue(d.b))}</td></tr>`).join('')}
         </tbody>
       </table></div>`
    : '<div class="same">✅ 두 WAS의 응답이 같아요 (객체 키 순서는 비교하지 않아요)</div>';

  box.innerHTML = `${summary}
    <div class="side-by-side">
      <div>${backendBadge('legacy')}<pre>${esc(pretty(cs.json))}</pre></div>
      <div>${backendBadge('java')}<pre>${esc(pretty(java.json))}</pre></div>
    </div>`;
}

// 두 JSON에서 값이 다른 위치를 모은다
function diffJson(a, b, path = '') {
  if (a === b) return [];
  const bothObjects = a && b && typeof a === 'object' && typeof b === 'object' && Array.isArray(a) === Array.isArray(b);
  if (!bothObjects) return [{ path: path || '(전체)', a, b }];
  const keys = new Set([...Object.keys(a), ...Object.keys(b)]);
  return [...keys].flatMap((k) => {
    const child = Array.isArray(a) ? `${path}[${k}]` : path ? `${path}.${k}` : k;
    return diffJson(a[k], b[k], child);
  });
}

// ---------- 시간대 ----------

// 특정 시간대의 연·월·일·시·분·초
function zonedParts(epochMs, timeZone) {
  const fmt = new Intl.DateTimeFormat('en-US', {
    timeZone,
    hourCycle: 'h23',
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit',
  });
  return Object.fromEntries(fmt.formatToParts(new Date(epochMs)).map((p) => [p.type, p.value]));
}

function zonedNow(timeZone) {
  const p = zonedParts(Date.now(), timeZone);
  const date = `${p.year}-${p.month}-${p.day}`;
  return { date, minute: `${date}T${p.hour}:${p.minute}`, full: `${date} ${p.hour}:${p.minute}:${p.second}` };
}

// 그 순간 그 시간대의 UTC 오프셋(분). 예) 여름의 America/Chicago → -300
function offsetMinutes(epochMs, timeZone) {
  const p = zonedParts(epochMs, timeZone);
  const wallAsUtc = Date.UTC(+p.year, +p.month - 1, +p.day, +p.hour, +p.minute, +p.second);
  return Math.round((wallAsUtc - Math.floor(epochMs / 1000) * 1000) / 60000);
}

// 공장 현지 시각에 그 공장의 오프셋을 붙인다. "2026-10-29T08:00" → "2026-10-29T08:00:00-05:00"
function withPlantOffset(local, timeZone) {
  const [date, time] = local.split('T');
  const [y, mo, d] = date.split('-').map(Number);
  const [h, mi] = time.split(':').map(Number);
  const wallAsUtc = Date.UTC(y, mo - 1, d, h, mi);
  let offset = offsetMinutes(wallAsUtc, timeZone);
  offset = offsetMinutes(wallAsUtc - offset * 60000, timeZone);  // 서머타임 경계 보정
  const sign = offset < 0 ? '-' : '+';
  const abs = Math.abs(offset);
  return `${date}T${pad(h)}:${pad(mi)}:00${sign}${pad(Math.floor(abs / 60))}:${pad(abs % 60)}`;
}

// 레거시와 같은 근무조 규칙(A 06~14, B 14~22, C 22~06)을 공장 현지 시각에 적용한 기대값
function expectedShift(local) {
  const [date, time] = local.split('T');
  const h = Number(time.slice(0, 2));
  const shift = h >= 6 && h < 14 ? 'A' : h >= 14 && h < 22 ? 'B' : 'C';
  return { shift, workDate: h < 6 ? addDays(date, -1) : date, local: local.replace('T', ' ') };
}

function addDays(date, days) {
  const [y, m, d] = date.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d + days)).toISOString().slice(0, 10);
}

function tick() {
  const plant = plantOf(state.plantCd);
  $('#plant-clock').textContent = plant ? `현지 ${zonedNow(plant.time_zone).full} · ${plant.time_zone}` : '';
}

function setDefaultPlanDate() {
  const plant = plantOf(state.plantCd);
  const input = $('#plan-date');
  if (plant && !input.value) input.value = zonedNow(plant.time_zone).date;
}

// ---------- 작은 도우미 ----------

function plantOf(plantCd) {
  return state.plants.find((p) => p.plant_cd === plantCd);
}

function progress(wo) {
  return Math.min(100, Math.round((wo.good_qty / wo.plan_qty) * 100));
}

// C#의 int처럼, 비어 있으면 0으로 보낸다
function toInt(value) {
  const n = Number.parseInt(value, 10);
  return Number.isNaN(n) ? 0 : n;
}

function statusBadge(status) {
  return `<span class="badge s-${esc(status)}" title="${esc(status)}">${esc(STATUS_LABEL[status] ?? status)}</span>`;
}

function backendBadge(backend) {
  return `<span class="be be-${backend}">${esc(BACKENDS[backend].label)}</span>`;
}

function esc(value) {
  return String(value ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
}

function pretty(value) {
  return JSON.stringify(value, null, 2);
}

function showValue(value) {
  return value === undefined ? '(없음)' : JSON.stringify(value);
}

function pad(n) {
  return String(n).padStart(2, '0');
}

function fmtTime(date) {
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

let toastTimer;
function toast(message, kind = 'info') {
  const el = $('#toast');
  el.textContent = message;
  el.className = `toast ${kind}`;
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.hidden = true; }, kind === 'err' ? 6000 : 2500);
}

function showError(e) {
  toast(e instanceof ApiError ? `[${e.code}] ${e.message}` : String(e), 'err');
}

function loadPref(key, fallback) {
  try {
    return localStorage.getItem(`mini-mes.${key}`) ?? fallback;
  } catch {
    return fallback;
  }
}

function savePref(key, value) {
  try {
    localStorage.setItem(`mini-mes.${key}`, value);
  } catch {
    // 저장하지 못해도 화면 동작에는 문제없다
  }
}

// ---------- 시작 ----------

function bindEvents() {
  document.querySelectorAll('.backend-btn').forEach((btn) => {
    btn.addEventListener('click', () => switchBackend(btn.dataset.backend));
  });
  $('#plant-select').addEventListener('change', (e) => switchPlant(e.target.value));
  $('#refresh-btn').addEventListener('click', () => reloadAll());
  $('#clear-log').addEventListener('click', () => { $('#log-list').innerHTML = ''; });
  $('#create-form').addEventListener('submit', (e) => {
    e.preventDefault();
    createWorkOrder(e.target);
  });

  $('#status-filters').addEventListener('click', async (e) => {
    const chip = e.target.closest('[data-status]');
    if (!chip) return;
    state.statusFilter = chip.dataset.status;
    document.querySelectorAll('#status-filters .chip').forEach((c) => c.classList.toggle('active', c === chip));
    try {
      await loadWorkOrders();
    } catch (err) {
      showError(err);
    }
  });

  $('#wo-rows').addEventListener('click', (e) => {
    const row = e.target.closest('tr[data-id]');
    if (row) selectWorkOrder(Number(row.dataset.id));
  });

  // 상세 영역은 다시 그려지므로 이벤트를 바깥 컨테이너 한 곳에서 받는다
  const detail = $('#detail');
  detail.addEventListener('click', (e) => {
    const actionBtn = e.target.closest('[data-action]');
    if (actionBtn) changeStatus(actionBtn.dataset.action);
    if (e.target.closest('[data-now]')) {
      const form = $('#report-form');
      form.reportedAt.value = zonedNow(plantOf(state.selected.wo.plant_cd)?.time_zone).minute;
      updateReportPreview(form);
    }
  });
  detail.addEventListener('submit', (e) => {
    e.preventDefault();
    reportResult(e.target);
  });
  detail.addEventListener('change', (e) => {
    if (e.target.name === 'procCd') fillEquipments(e.target.form);
  });
  detail.addEventListener('input', (e) => {
    if (e.target.name === 'reportedAt') updateReportPreview(e.target.form);
  });
}

async function init() {
  const match = location.hash.match(/wo=(\d+)/);
  if (match) state.selectedId = Number(match[1]);

  bindEvents();
  renderBackendSwitch();
  checkHealth();
  setInterval(checkHealth, 5000);
  setInterval(tick, 1000);
  await reloadAll();
}

init();
