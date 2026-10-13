// Kebbi 舞蹈工具網頁：呼叫 webui/app.py 的 API。
"use strict";

const $ = (id) => document.getElementById(id);

// 馬達 id 與 RobotMotor.java 一致；範圍來自 RobotMapper.java
const MOTORS = [
  { id: 1, name: "頭 上下", min: -20, max: 20, color: "#2563eb" },
  { id: 2, name: "頭 左右", min: -31, max: 31, color: "#0891b2" },
  { id: 4, name: "右肩 舉", min: -200, max: 70, color: "#dc2626" },
  { id: 5, name: "右肩 外展", min: -3, max: 100, color: "#ea580c" },
  { id: 3, name: "右肩 前後", min: -85, max: 5, color: "#ca8a04" },
  { id: 6, name: "右肘", min: -80, max: 0, color: "#b45309" },
  { id: 8, name: "左肩 舉", min: -200, max: 70, color: "#16a34a" },
  { id: 9, name: "左肩 外展", min: -3, max: 100, color: "#059669" },
  { id: 7, name: "左肩 前後", min: -85, max: 5, color: "#65a30d" },
  { id: 10, name: "左肘", min: -80, max: 0, color: "#7c3aed" },
];
const FACE_LABEL = { NEUTRAL: "平靜", HAPPY: "開心", SURPRISED: "驚訝", ANGRY: "生氣", SAD: "難過" };

const store = {
  get(k, d) { try { return localStorage.getItem(k) ?? d; } catch { return d; } },
  set(k, v) { try { localStorage.setItem(k, v); } catch { /* 無痕模式 */ } },
};

async function api(path, opts = {}) {
  const init = { method: opts.method || (opts.body !== undefined ? "POST" : "GET"), headers: {} };
  if (opts.body instanceof FormData) init.body = opts.body;
  else if (opts.body !== undefined) { init.body = JSON.stringify(opts.body); init.headers["Content-Type"] = "application/json"; }
  const res = await fetch(path, init);
  const data = await res.json().catch(() => ({ ok: false, message: `HTTP ${res.status}` }));
  if (!res.ok) throw new Error(data.message || `HTTP ${res.status}`);
  return data;
}

function fmtSec(ms) { return (ms / 1000).toFixed(2) + " s"; }
function fmtSize(b) { return b > 1e6 ? (b / 1e6).toFixed(1) + " MB" : (b / 1e3).toFixed(0) + " KB"; }

// ---------------------------------------------------------------------------
// 影片清單與上傳
// ---------------------------------------------------------------------------

let videos = [];

async function loadVideos(select) {
  videos = await api("/api/videos");
  for (const sel of [$("video-select"), $("assign-video")]) {
    const keep = sel.value;
    sel.innerHTML = videos.length ? "" : "<option value=''>（沒有影片，請先上傳）</option>";
    for (const v of videos) {
      const o = document.createElement("option");
      o.value = v.path; o.textContent = `${v.name}　(${v.folder}, ${fmtSize(v.size)})`;
      sel.appendChild(o);
    }
    if (keep && videos.some((v) => v.path === keep)) sel.value = keep;
  }
  if (select) $("video-select").value = select;
}

$("video-upload").addEventListener("change", () => {
  const file = $("video-upload").files[0];
  if (!file) return;
  const form = new FormData();
  form.append("file", file);
  const xhr = new XMLHttpRequest();
  const p = $("upload-progress");
  p.classList.remove("hidden");
  xhr.upload.onprogress = (e) => { p.textContent = `上傳中 ${Math.round((e.loaded / e.total) * 100)}%`; };
  xhr.onload = async () => {
    const data = JSON.parse(xhr.responseText || "{}");
    if (xhr.status !== 200) { p.textContent = "上傳失敗：" + (data.message || xhr.status); return; }
    p.textContent = `已上傳 ${data.name}`;
    await loadVideos(data.path);
  };
  xhr.onerror = () => { p.textContent = "上傳失敗"; };
  xhr.open("POST", "/api/videos");
  xhr.send(form);
  $("video-upload").value = "";
});

// ---------------------------------------------------------------------------
// 工作與紀錄
// ---------------------------------------------------------------------------

let currentJob = null;

function setBusy(job) {
  const running = job && job.state === "running";
  $("busy").textContent = running ? `執行中：${job.title}` : "";
  $("busy").classList.toggle("hidden", !running);
  $("btn-analyze").disabled = running;
  $("btn-sim").disabled = running;
  $("btn-cancel").classList.toggle("hidden", !running);
  document.querySelectorAll(".tool button").forEach((b) => { b.disabled = running || b.dataset.available !== "1"; });
}

async function followJob(job) {
  currentJob = job;
  const log = $("log");
  log.textContent = "";
  $("job-title").textContent = job.title;
  let since = 0;
  setBusy(job);
  while (true) {
    const j = await api(`/api/jobs/${job.id}?since=${since}`);
    if (j.lines.length) {
      const atBottom = log.scrollTop + log.clientHeight >= log.scrollHeight - 20;
      log.textContent += j.lines.join("\n") + "\n";
      if (atBottom) log.scrollTop = log.scrollHeight;
    }
    since = j.lineCount;
    if (j.state !== "running") {
      const label = { done: "完成", failed: "失敗", cancelled: "已取消" }[j.state];
      $("job-title").innerHTML = `${j.title}　<span class="${j.state === "done" ? "ok" : "bad"}">${label}</span>`;
      setBusy(null);
      return j;
    }
    await new Promise((r) => setTimeout(r, 500));
  }
}

$("btn-analyze").addEventListener("click", async () => {
  const body = {
    video: $("video-select").value,
    engine: $("engine").value,
    maxSeconds: Number($("max-seconds").value) || null,
    interval: Number($("interval").value) || 200,
    outName: $("out-name").value.trim(),
    noFace: !$("face").checked,
    skipExtract: $("skip-extract").checked,
  };
  if (!body.video) return alert("請先選影片或上傳影片");
  try {
    const job = await api("/api/analyze", { body });
    const done = await followJob(job);
    if (done.state === "done") { await loadResults(done.result); }
  } catch (e) { alert(e.message); }
});

$("btn-cancel").addEventListener("click", () => { if (currentJob) api(`/api/jobs/${currentJob.id}/cancel`, { body: {} }); });

// ---------------------------------------------------------------------------
// 結果預覽
// ---------------------------------------------------------------------------

let result = null;   // 目前選的結果（含 script）
let timeline = null; // { states: [{t, deg:{id:deg}}], faces, events }

async function loadResults(select) {
  const list = await api("/api/results");
  const sel = $("result-select");
  const keep = select || sel.value || store.get("result", "");
  sel.innerHTML = list.length ? "" : "<option value=''>（還沒有結果，請先分析影片）</option>";
  for (const r of list) {
    const o = document.createElement("option");
    o.value = r.name;
    o.textContent = `${r.name}　(${fmtSec(r.durationMs || 0)}, ${r.steps} 步, ${r.faces} 次換臉)`;
    sel.appendChild(o);
  }
  if (keep && list.some((r) => r.name === keep)) sel.value = keep;
  await showResult(sel.value);
}

$("result-select").addEventListener("change", () => showResult($("result-select").value));
$("btn-refresh").addEventListener("click", () => { loadVideos(); loadResults(); });

async function showResult(name) {
  result = null;
  $("btn-download").removeAttribute("href");
  if (!name) { drawTimeline(); renderState(0); return; }
  store.set("result", name);
  result = await api(`/api/results/${encodeURIComponent(name)}`);
  const s = result.script;
  $("btn-download").href = `/api/results/${encodeURIComponent(name)}/file/dance_script.json`;
  const m = result.meta || {};
  const engine = { mediapipe: "MediaPipe", rtmw3d: "RTMW3D" }[m.engine] || "未知";
  $("result-summary").textContent =
    `舞蹈「${s.name}」長 ${fmtSec(s.durationMs)}，${s.steps.length} 個馬達步驟、${(s.faces || []).length} 次換臉；` +
    `取樣 ${s.frameIntervalMs} ms，${s.sourceFrameCount} 幀中 ${s.skippedFrameCount} 幀沒偵測到人；姿態模型：${engine}。`;

  const video = $("video");
  if (result.video) {
    video.src = `/media/video?path=${encodeURIComponent(result.video)}`;
    $("no-video").classList.add("hidden");
  } else {
    video.removeAttribute("src"); video.load();
    $("no-video").classList.remove("hidden");
  }
  buildTimeline(s);
  drawTimeline();
  renderState(0);
  renderSim(result.sim);
}

$("btn-assign").addEventListener("click", async () => {
  if (!result || !$("assign-video").value) return;
  await api(`/api/results/${encodeURIComponent(result.name)}/video`, { body: { video: $("assign-video").value } });
  showResult(result.name);
});

function buildTimeline(s) {
  const deg = {};
  const states = [{ t: 0, deg: {} }];
  for (const step of s.steps) {
    for (const c of step.cmds) deg[c.motor] = c.deg;
    states.push({ t: step.t, deg: { ...deg }, event: step.event });
  }
  timeline = { states, faces: s.faces || [], duration: Math.max(s.durationMs, 1) };
}

// 時間 t（毫秒）時每顆馬達「被要求」的角度＝最後一個 t ≤ 現在的步驟累積結果
function stateAt(t) {
  if (!timeline) return { deg: {}, event: "", face: null };
  const st = timeline.states;
  let lo = 0, hi = st.length - 1;
  while (lo < hi) { const mid = (lo + hi + 1) >> 1; if (st[mid].t <= t) lo = mid; else hi = mid - 1; }
  let face = null;
  for (const f of timeline.faces) { if (f.t <= t) face = f; else break; }
  return { deg: st[lo].deg, event: st[lo].event || "", face };
}

function renderState(t) {
  const s = stateAt(t);
  $("now-t").textContent = fmtSec(t);
  $("now-event").textContent = s.event;
  $("now-face").textContent = s.face ? `${FACE_LABEL[s.face.expr] || s.face.expr}${s.face.face ? "（" + s.face.face + "）" : ""}` : "";
  const box = $("motors");
  if (!box.childElementCount) {
    box.innerHTML = MOTORS.map((m) => `<div class="motor" id="m${m.id}"><span>${m.name}</span>` +
      `<div class="bar"><div class="zero" style="left:${(-m.min / (m.max - m.min)) * 100}%"></div><div class="fill" style="background:${m.color}"></div></div>` +
      `<span class="val"></span></div>`).join("");
  }
  for (const m of MOTORS) {
    const row = $("m" + m.id);
    const v = s.deg[m.id];
    row.classList.toggle("idle", v === undefined);
    const z = (-m.min / (m.max - m.min)) * 100;
    const p = v === undefined ? z : ((v - m.min) / (m.max - m.min)) * 100;
    const fill = row.querySelector(".fill");
    fill.style.left = Math.min(z, p) + "%";
    fill.style.width = Math.abs(p - z) + "%";
    row.querySelector(".val").textContent = v === undefined ? "—" : v.toFixed(1) + "°";
  }
  drawPlayhead(t);
}

function drawTimeline() {
  const svg = $("timeline");
  const W = 1000, H = 190, top = 14;
  svg.setAttribute("viewBox", `0 0 ${W} ${H}`);
  if (!timeline) { svg.innerHTML = ""; $("legend").innerHTML = ""; return; }
  const x = (t) => (t / timeline.duration) * W;
  let html = "";
  // 每顆馬達一條線，各自依自己的範圍正規化
  const used = MOTORS.filter((m) => timeline.states.some((s) => s.deg[m.id] !== undefined));
  for (const m of used) {
    let d = "", prev = null;
    for (const s of timeline.states) {
      const v = s.deg[m.id];
      if (v === undefined) continue;
      const y = top + (1 - (v - m.min) / (m.max - m.min)) * (H - top - 6);
      d += prev === null ? `M${x(s.t)},${y}` : `H${x(s.t)}V${y}`;
      prev = y;
    }
    if (d) html += `<path d="${d}H${W}" fill="none" stroke="${m.color}" stroke-width="1.6" vector-effect="non-scaling-stroke"/>`;
  }
  // 換臉事件
  for (const f of timeline.faces) {
    html += `<line x1="${x(f.t)}" x2="${x(f.t)}" y1="0" y2="${H}" stroke="var(--face)" stroke-dasharray="3 3" vector-effect="non-scaling-stroke"/>`;
  }
  html += `<line id="playhead" x1="0" x2="0" y1="0" y2="${H}" stroke="var(--text)" stroke-width="1.5" vector-effect="non-scaling-stroke"/>`;
  svg.innerHTML = html;
  $("legend").innerHTML = used.map((m) => `<span><i style="background:${m.color}"></i>${m.name}</span>`).join("") +
    (timeline.faces.length ? `<span><i style="background:var(--face)"></i>換臉</span>` : "");
}

function drawPlayhead(t) {
  const ph = document.getElementById("playhead");
  if (!ph || !timeline) return;
  const px = (t / timeline.duration) * 1000;
  ph.setAttribute("x1", px); ph.setAttribute("x2", px);
}

$("timeline").addEventListener("click", (e) => {
  if (!timeline) return;
  const r = e.currentTarget.getBoundingClientRect();
  const t = ((e.clientX - r.left) / r.width) * timeline.duration;
  const video = $("video");
  if (video.getAttribute("src")) video.currentTime = t / 1000;
  renderState(t);
});

// 影片播放時，同步顯示該時間點的馬達角度與表情
function tick() {
  const video = $("video");
  if (video.getAttribute("src") && !video.paused) renderState(video.currentTime * 1000);
  requestAnimationFrame(tick);
}
$("video").addEventListener("seeked", () => renderState($("video").currentTime * 1000));

function renderSim(sim) {
  const box = $("sim-summary");
  if (!sim) { box.textContent = "還沒跑過 PyBullet 模擬（按上方按鈕，需安裝 pybullet）。"; return; }
  const rows = Object.entries(sim.tracking || {})
    .map(([k, v]) => `${k} 平均誤差 ${v.mean_abs_err_deg.toFixed(1)}°、最大 ${v.max_err_deg.toFixed(0)}°`);
  const over = sim.overVelocity && sim.overVelocity.length ? `超過速度上限：${JSON.stringify(sim.overVelocity)}` : "沒有超過速度上限";
  const coll = sim.collisions.length ? `碰撞 ${sim.collisions.length} 組（例：${sim.collisions[0].pair.join(" × ")}）` : "沒有新的碰撞";
  box.innerHTML = `<b>模擬結果：</b>${over}；${coll}<br>${rows.join("；")}`;
}

$("btn-sim").addEventListener("click", async () => {
  if (!result) return;
  try {
    const done = await followJob(await api(`/api/results/${encodeURIComponent(result.name)}/simulate`, { body: {} }));
    if (done.state === "done") showResult(result.name);
  } catch (e) { alert(e.message); }
});

// ---------------------------------------------------------------------------
// Kebbi
// ---------------------------------------------------------------------------

$("kebbi-host").value = store.get("kebbiHost", "");
$("kebbi-port").value = store.get("kebbiPort", "8765");
$("sync-offset").value = store.get("syncOffset", "1500");
for (const id of ["kebbi-host", "kebbi-port", "sync-offset"]) {
  $(id).addEventListener("change", () => store.set({ "kebbi-host": "kebbiHost", "kebbi-port": "kebbiPort", "sync-offset": "syncOffset" }[id], $(id).value.trim()));
}

function target() {
  return { host: $("kebbi-host").value.trim(), port: Number($("kebbi-port").value) || 8765 };
}

async function kebbi(action, extra = {}) {
  const box = $("kebbi-state");
  box.textContent = "連線中…";
  try {
    const r = await api(`/api/kebbi/${action}`, { body: { ...target(), ...extra } });
    const ok = r.ok !== false;
    let text = r.message || "";
    if (action === "status" && ok) {
      text = `已連線　${r.playing ? "播放中" : "待機"}${r.robotReady === false ? "（機器人尚未就緒）" : ""}` +
        (r.script ? `　已載入：${r.script}` : "　尚未載入腳本");
    }
    box.innerHTML = `<span class="${ok ? "ok" : "bad"}">${ok ? "✓" : "✗"}</span> ${text}　<span class="muted">往返 ${r.roundTripMs} ms</span>`;
    return r;
  } catch (e) {
    box.innerHTML = `<span class="bad">✗</span> ${e.message}`;
    return { ok: false };
  }
}

function startSyncedVideo() {
  const video = $("video");
  if (!$("sync-video").checked || !video.getAttribute("src")) return;
  video.pause();
  video.currentTime = 0;
  setTimeout(() => video.play(), Number($("sync-offset").value) || 0);
}

$("btn-status").addEventListener("click", () => kebbi("status"));
$("btn-upload").addEventListener("click", () => result ? kebbi("upload", { result: result.name }) : alert("請先選結果"));
$("btn-upload-play").addEventListener("click", async () => {
  if (!result) return alert("請先選結果");
  const r = await kebbi("upload", { result: result.name, play: true });
  if (r.ok) startSyncedVideo();
});
$("btn-play").addEventListener("click", async () => { const r = await kebbi("play"); if (r.ok) startSyncedVideo(); });
$("btn-stop").addEventListener("click", () => { $("video").pause(); kebbi("stop"); });
$("btn-home").addEventListener("click", () => kebbi("home"));

// FakeKebbi
let fakeSince = 0, fakeTimer = null;
async function pollFake() {
  const j = await api(`/api/fakekebbi?since=${fakeSince}`);
  if (!j) { $("fake-state").textContent = "未啟動"; return; }
  const log = $("fake-log");
  if (j.lines.length) { log.textContent += j.lines.join("\n") + "\n"; log.scrollTop = log.scrollHeight; }
  fakeSince = j.lineCount;
  $("fake-state").textContent = j.state === "running" ? "執行中（IP 填 127.0.0.1）" : "已關閉";
  clearTimeout(fakeTimer);
  if (j.state === "running") fakeTimer = setTimeout(pollFake, 600);
}
$("btn-fake-start").addEventListener("click", async () => {
  $("fake-log").textContent = ""; fakeSince = 0;
  await api("/api/fakekebbi/start", { body: { port: target().port } });
  if (!$("kebbi-host").value.trim()) { $("kebbi-host").value = "127.0.0.1"; }
  pollFake();
});
$("btn-fake-stop").addEventListener("click", async () => { await api("/api/fakekebbi/stop", { body: {} }); setTimeout(pollFake, 500); });

// ---------------------------------------------------------------------------
// 其他工具
// ---------------------------------------------------------------------------

async function loadTools() {
  const tools = await api("/api/tools");
  $("tools").innerHTML = tools.map((t) => `<div class="tool"><div><b>${t.label}</b>　<span class="muted">${t.desc}</span><br>` +
    `<span class="muted">${t.available ? `<code>pc/${t.file}</code>` : `尚未加入（缺 <code>pc/${t.file}</code>）`}</span></div>` +
    `<div class="row" style="margin:0"><input type="text" placeholder="其他參數" data-args="${t.id}" ${t.available ? "" : "disabled"}>` +
    `<button class="secondary" data-tool="${t.id}" data-available="${t.available ? 1 : 0}" ${t.available ? "" : "disabled"}>執行</button></div></div>`).join("");
  document.querySelectorAll("[data-tool]").forEach((b) => b.addEventListener("click", async () => {
    try {
      const extraArgs = document.querySelector(`[data-args="${b.dataset.tool}"]`).value;
      await followJob(await api(`/api/tools/${b.dataset.tool}`, { body: { ...target(), result: result && result.name, extraArgs } }));
    } catch (e) { alert(e.message); }
  }));
}

// ---------------------------------------------------------------------------

(async function init() {
  await Promise.all([loadVideos(), loadResults(), loadTools()]);
  const jobs = await api("/api/jobs");
  const running = jobs.find((j) => j.state === "running" && j.kind !== "fakekebbi");
  if (running) followJob(running).then((j) => j.state === "done" && j.result && loadResults(j.result));
  pollFake();
  requestAnimationFrame(tick);
})();
