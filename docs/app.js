/* TAPE DOJO — price action replay trainer
 * Static frontend (GitHub Pages). Talks to the local Spring Boot backend,
 * which slices random windows out of the paper-trade labs' cached bars.
 */
"use strict";

const LS = {
  backend: "tapedojo.backend",
  stats: "tapedojo.stats",
  crypto: "tapedojo.crypto",
};

const state = {
  backend: localStorage.getItem(LS.backend) || "http://localhost:8080",
  symbols: [],
  scenario: null,
  reveal: null,
  phase: "idle", // idle -> loaded -> armed -> playing -> revealed -> rated
  direction: null,
  speed: 4, // bars per second, 0 = instant
  timer: null,
  playIdx: 0,
  priceLines: [],
  markers: [],
  stats: JSON.parse(localStorage.getItem(LS.stats) || '{"trades":0,"youR":0,"modelR":0}'),
};

const $ = (id) => document.getElementById(id);

/* ── chart ─────────────────────────────────────────────── */

let chart, candles, volume;

function tzForScenario() {
  return state.scenario && state.scenario.assetClass === "STOCK" ? "America/New_York" : "UTC";
}

function fmtTime(epochSec, withDate = false) {
  const opts = { hour: "2-digit", minute: "2-digit", hour12: false, timeZone: tzForScenario() };
  if (withDate) Object.assign(opts, { weekday: "short", year: "numeric", month: "short", day: "numeric" });
  return new Intl.DateTimeFormat("en-US", opts).format(new Date(epochSec * 1000));
}

function initChart() {
  const el = $("chart");
  chart = LightweightCharts.createChart(el, {
    layout: { background: { color: "transparent" }, textColor: "#6d7a8c", fontFamily: "'IBM Plex Mono', monospace", fontSize: 11 },
    grid: { vertLines: { color: "#141c27" }, horzLines: { color: "#141c27" } },
    crosshair: { mode: LightweightCharts.CrosshairMode.Normal },
    rightPriceScale: { borderColor: "#1e2836" },
    timeScale: {
      borderColor: "#1e2836",
      timeVisible: true,
      secondsVisible: false,
      tickMarkFormatter: (t) => fmtTime(t),
    },
    localization: { timeFormatter: (t) => fmtTime(t, !state.scenario?.masked || state.phase === "revealed") },
  });
  candles = chart.addCandlestickSeries({
    upColor: "#2dd48f", downColor: "#f5484d",
    wickUpColor: "#2dd48f", wickDownColor: "#f5484d",
    borderVisible: false,
  });
  volume = chart.addHistogramSeries({ priceFormat: { type: "volume" }, priceScaleId: "vol" });
  chart.priceScale("vol").applyOptions({ scaleMargins: { top: 0.85, bottom: 0 } });
  new ResizeObserver(() => chart.applyOptions({ width: el.clientWidth, height: el.clientHeight })).observe(el);
}

function volBar(b) {
  return { time: b.t, value: b.v, color: b.c >= b.o ? "#2dd48f33" : "#f5484d33" };
}

function clearOverlays() {
  state.priceLines.forEach((l) => candles.removePriceLine(l));
  state.priceLines = [];
  state.markers = [];
  candles.setMarkers([]);
}

function addPriceLine(price, color, title, dashed = true) {
  const line = candles.createPriceLine({
    price, color, title,
    lineWidth: 1,
    lineStyle: dashed ? LightweightCharts.LineStyle.Dashed : LightweightCharts.LineStyle.Solid,
    axisLabelVisible: true,
  });
  state.priceLines.push(line);
}

function addMarker(m) {
  state.markers.push(m);
  state.markers.sort((a, b) => a.time - b.time);
  candles.setMarkers(state.markers);
}

/* ── backend ───────────────────────────────────────────── */

async function api(path, body) {
  let res;
  try {
    res = await fetch(state.backend + path, body === undefined ? {} : {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
  } catch {
    throw new Error("Backend unreachable — is it running? (see ⚙)");
  }
  const json = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(json.error || `${res.status} ${res.statusText}`);
  return json;
}

async function loadSymbols() {
  state.symbols = await api("/api/symbols");
  const sel = $("symbolSelect");
  sel.querySelectorAll("optgroup").forEach((g) => g.remove());
  const groups = { STOCK: "Stocks", CRYPTO: "Crypto" };
  for (const [cls, label] of Object.entries(groups)) {
    const items = state.symbols.filter((s) => s.assetClass === cls);
    if (!items.length) continue;
    const og = document.createElement("optgroup");
    og.label = label;
    items.forEach((s) => {
      const o = document.createElement("option");
      o.value = s.symbol;
      o.textContent = s.symbol;
      og.appendChild(o);
    });
    sel.appendChild(og);
  }
}

/* ── scenario lifecycle ────────────────────────────────── */

function setPhase(phase) {
  state.phase = phase;
  const armedOrLater = ["armed", "playing", "revealed", "rated"].includes(phase);
  $("commitBtn").classList.toggle("hidden", armedOrLater);
  $("playBtn").classList.toggle("hidden", phase !== "armed");
  $("resultPanel").classList.toggle("hidden", !["revealed", "rated"].includes(phase));
  $("ticketPanel").classList.toggle("hidden", ["revealed", "rated"].includes(phase));
  $("chartEmpty").classList.toggle("hidden", phase !== "idle");
  document.querySelectorAll("#ticketFields input, .dir-btn").forEach((el) => (el.disabled = armedOrLater));
}

async function newScenario() {
  stopPlayback();
  const symbol = $("symbolSelect").value || null;
  const includeCrypto = $("cryptoToggle").checked;
  let sc;
  try {
    sc = await api("/api/scenarios", { symbol, includeCrypto });
  } catch (e) {
    toast(e.message, true);
    return;
  }
  state.scenario = sc;
  state.reveal = null;
  state.direction = null;

  clearOverlays();
  candles.setData(sc.bars.map((b) => ({ time: b.t, open: b.o, high: b.h, low: b.l, close: b.c })));
  volume.setData(sc.bars.map(volBar));
  chart.timeScale().fitContent();

  const chip = $("chipSymbol");
  chip.textContent = sc.masked ? "?????" : sc.displaySymbol;
  chip.classList.toggle("masked", sc.masked);
  $("chipTf").textContent = `${sc.assetClass} · ${sc.barMinutes}m · last ${fmtPrice(sc.lastClose)}`;

  document.querySelectorAll(".dir-btn").forEach((b) => b.classList.remove("selected"));
  $("stopInput").value = "";
  $("targetInput").value = "";
  $("rrValue").textContent = "—";
  $("livePnl").textContent = "";
  $("livePnl").className = "live-pnl";
  $("commitBtn").disabled = true;
  document.querySelectorAll(".rate-btn").forEach((b) => { b.classList.remove("selected"); b.disabled = false; });
  $("ratingNote").value = "";
  $("ratedMsg").classList.add("hidden");
  $("ratingBlock").classList.remove("hidden");
  setPhase("loaded");
}

function fmtPrice(p) {
  return p >= 1000 ? p.toFixed(0) : p >= 100 ? p.toFixed(2) : p.toFixed(3);
}

/* ATR of the last visible bars, for stop/target prefills. */
function visibleAtr(n = 14) {
  const bars = state.scenario.bars;
  const from = Math.max(1, bars.length - n);
  let sum = 0, count = 0;
  for (let i = from; i < bars.length; i++) {
    const tr = Math.max(
      bars[i].h - bars[i].l,
      Math.abs(bars[i].h - bars[i - 1].c),
      Math.abs(bars[i].l - bars[i - 1].c),
    );
    sum += tr; count++;
  }
  return count ? sum / count : 0;
}

function selectDirection(dir) {
  if (!state.scenario || state.phase !== "loaded") return;
  state.direction = dir;
  document.querySelectorAll(".dir-btn").forEach((b) => b.classList.remove("selected"));
  $(dir === "LONG" ? "dirLong" : dir === "SHORT" ? "dirShort" : "dirSkip").classList.add("selected");
  const fields = $("ticketFields");
  fields.style.opacity = dir === "SKIP" ? 0.35 : 1;
  if (dir !== "SKIP") {
    const close = state.scenario.lastClose;
    const atr = visibleAtr();
    const stop = dir === "LONG" ? close - 1.2 * atr : close + 1.2 * atr;
    const target = dir === "LONG" ? close + 2 * (close - stop) : close - 2 * (stop - close);
    $("stopInput").value = stop.toFixed(4);
    $("targetInput").value = target.toFixed(4);
  }
  updateRR();
  $("commitBtn").disabled = false;
}

function updateRR() {
  const el = $("rrValue");
  if (state.direction === "SKIP" || !state.direction) { el.textContent = "—"; return; }
  const close = state.scenario.lastClose;
  const stop = parseFloat($("stopInput").value);
  const target = parseFloat($("targetInput").value);
  const risk = state.direction === "LONG" ? close - stop : stop - close;
  const reward = state.direction === "LONG" ? target - close : close - target;
  el.textContent = risk > 0 && reward > 0 ? `1 : ${(reward / risk).toFixed(2)}` : "invalid";
}

async function commitTrade() {
  if (!state.direction) return;
  const body = state.direction === "SKIP"
    ? { direction: "SKIP" }
    : { direction: state.direction, stop: parseFloat($("stopInput").value), target: parseFloat($("targetInput").value) };
  try {
    await api(`/api/scenarios/${state.scenario.id}/trade`, body);
  } catch (e) {
    toast(e.message, true);
    return;
  }
  if (state.direction !== "SKIP") {
    const isLong = state.direction === "LONG";
    addPriceLine(state.scenario.lastClose, "#f5a524", "you", false);
    addPriceLine(body.stop, "#f5484d", "stop");
    addPriceLine(body.target, "#2dd48f", "target");
    state.userSpec = { isLong, stop: body.stop, target: body.target };
  } else {
    state.userSpec = null;
  }
  setPhase("armed");
  toast("Trade locked. The model has committed too — hit PLAY.");
}

/* ── playback ──────────────────────────────────────────── */

async function play() {
  let reveal;
  try {
    reveal = await api(`/api/scenarios/${state.scenario.id}/play`, {});
  } catch (e) {
    toast(e.message, true);
    return;
  }
  state.reveal = reveal;
  setPhase("playing");

  // Model's hand is revealed the moment you press play.
  if (reveal.model.direction !== "SKIP") {
    addPriceLine(reveal.model.stop, "#38bdf8", "model stop");
    addPriceLine(reveal.model.target, "#38bdf8", "model tgt");
  }
  const firstFuture = reveal.futureBars[0];
  if (state.userSpec) {
    addMarker({
      time: firstFuture.t, position: state.userSpec.isLong ? "belowBar" : "aboveBar",
      color: "#f5a524", shape: state.userSpec.isLong ? "arrowUp" : "arrowDown", text: "YOU",
    });
  }
  if (reveal.model.direction !== "SKIP") {
    addMarker({
      time: firstFuture.t, position: reveal.model.direction === "LONG" ? "belowBar" : "aboveBar",
      color: "#38bdf8", shape: reveal.model.direction === "LONG" ? "arrowUp" : "arrowDown", text: "MODEL",
    });
  }

  state.playIdx = 0;
  if (state.speed === 0) {
    while (state.playIdx < reveal.futureBars.length) stepPlayback();
    return;
  }
  startTimer();
}

function startTimer() {
  stopTimer();
  state.timer = setInterval(() => {
    stepPlayback();
    if (!state.reveal || state.playIdx >= state.reveal.futureBars.length) stopTimer();
  }, 1000 / state.speed);
}

function stopTimer() {
  if (state.timer) clearInterval(state.timer);
  state.timer = null;
}

function stopPlayback() {
  stopTimer();
  state.playIdx = 0;
}

function stepPlayback() {
  const reveal = state.reveal;
  if (!reveal || state.playIdx >= reveal.futureBars.length) return;
  const b = reveal.futureBars[state.playIdx];
  candles.update({ time: b.t, open: b.o, high: b.h, low: b.l, close: b.c });
  volume.update(volBar(b));
  updateLivePnl(b);
  const u = reveal.user.outcome;
  if (u && state.playIdx === u.exitBarIndex) {
    addMarker({
      time: b.t, position: "aboveBar", color: "#f5a524", shape: "circle",
      text: `${u.exitReason} ${u.r > 0 ? "+" : ""}${u.r}R`,
    });
  }
  const m = reveal.model.outcome;
  if (m && state.playIdx === m.exitBarIndex) {
    addMarker({ time: b.t, position: "belowBar", color: "#38bdf8", shape: "circle", text: `M:${m.r > 0 ? "+" : ""}${m.r}R` });
  }
  state.playIdx++;
  if (state.playIdx >= reveal.futureBars.length) finishPlayback();
}

function updateLivePnl(bar) {
  const el = $("livePnl");
  const u = state.reveal.user.outcome;
  if (!u) { el.textContent = "flat"; el.className = "live-pnl"; return; }
  let r;
  if (state.playIdx >= u.exitBarIndex) {
    r = u.r;
  } else {
    const risk = Math.abs(u.entryFill - state.userSpec.stop);
    r = (state.userSpec.isLong ? bar.c - u.entryFill : u.entryFill - bar.c) / risk;
  }
  el.textContent = `${r > 0 ? "+" : ""}${r.toFixed(2)}R`;
  el.className = "live-pnl " + (r > 0.005 ? "pos" : r < -0.005 ? "neg" : "");
}

function finishPlayback() {
  stopTimer();
  chart.timeScale().fitContent();
  showResult();
  setPhase("revealed");
}

/* ── result & rating ───────────────────────────────────── */

function verdict(elDir, elR, elDetail, report) {
  if (report.direction === "SKIP") {
    $(elDir).textContent = "PASSED";
    $(elR).textContent = "0R";
    $(elR).className = "v-r flat";
    $(elDetail).textContent = "no trade taken";
    return 0;
  }
  const o = report.outcome;
  $(elDir).textContent = report.direction;
  $(elR).textContent = `${o.r > 0 ? "+" : ""}${o.r}R`;
  $(elR).className = "v-r " + (o.r > 0 ? "pos" : o.r < 0 ? "neg" : "flat");
  $(elDetail).textContent =
    `in ${fmtPrice(o.entryFill)} → out ${fmtPrice(o.exitPrice)} (${o.exitReason})` +
    ` · peak ${o.mfeR > 0 ? "+" : ""}${o.mfeR}R`;
  return o.r;
}

function showResult() {
  const rv = state.reveal;
  const sc = state.scenario;

  // Unmask.
  $("chipSymbol").textContent = rv.symbol;
  $("chipSymbol").classList.remove("masked");
  $("revealIdentity").textContent =
    `${rv.symbol} · ${rv.assetClass} · cut at ${fmtTime(rv.cutTime, true)} ${sc.assetClass === "STOCK" ? "ET" : "UTC"}`;

  const youR = verdict("youDir", "youR", "youDetail", rv.user);
  const modelR = verdict("modelDir", "modelR", "modelDetail", rv.model);
  $("modelRationale").textContent = rv.model.rationale || "";

  if (rv.user.direction !== "SKIP") {
    state.stats.trades += 1;
    state.stats.youR += youR;
  }
  state.stats.modelR += modelR;
  localStorage.setItem(LS.stats, JSON.stringify(state.stats));
  renderStats();
}

function renderStats() {
  const s = state.stats;
  $("statTrades").textContent = s.trades;
  const set = (id, v) => {
    const el = $(id);
    el.textContent = `${v >= 0 ? "+" : ""}${v.toFixed(1)}R`;
    el.className = "stat-v " + (v > 0.05 ? "pos" : v < -0.05 ? "neg" : "");
  };
  set("statYouR", s.youR);
  set("statModelR", s.modelR);
}

async function rate(rating, btn) {
  try {
    await api(`/api/scenarios/${state.scenario.id}/feedback`, { rating, note: $("ratingNote").value || null });
  } catch (e) {
    toast(e.message, true);
    return;
  }
  document.querySelectorAll(".rate-btn").forEach((b) => (b.disabled = true));
  btn.classList.add("selected");
  $("ratedMsg").classList.remove("hidden");
  setPhase("rated");
}

/* ── misc UI ───────────────────────────────────────────── */

let toastTimer;
function toast(msg, isError = false) {
  const el = $("toast");
  el.textContent = msg;
  el.className = "toast" + (isError ? " error" : "");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.add("hidden"), 4000);
}

function wire() {
  $("newScenarioBtn").onclick = newScenario;
  $("nextBtn").onclick = newScenario;
  $("dirLong").onclick = () => selectDirection("LONG");
  $("dirShort").onclick = () => selectDirection("SHORT");
  $("dirSkip").onclick = () => selectDirection("SKIP");
  $("stopInput").oninput = updateRR;
  $("targetInput").oninput = updateRR;
  $("commitBtn").onclick = commitTrade;
  $("playBtn").onclick = play;

  document.querySelectorAll(".speed-btn").forEach((b) => {
    b.onclick = () => {
      document.querySelectorAll(".speed-btn").forEach((x) => x.classList.remove("active"));
      b.classList.add("active");
      state.speed = Number(b.dataset.speed);
      if (state.phase === "playing") {
        if (state.speed === 0) {
          while (state.reveal && state.playIdx < state.reveal.futureBars.length) stepPlayback();
        } else {
          startTimer();
        }
      }
    };
  });

  document.querySelectorAll(".rate-btn").forEach((b) => (b.onclick = () => rate(b.dataset.rating, b)));

  $("settingsBtn").onclick = () => $("settingsPanel").classList.toggle("hidden");
  $("backendUrl").value = state.backend;
  $("backendUrl").onchange = () => {
    state.backend = $("backendUrl").value.replace(/\/+$/, "");
    localStorage.setItem(LS.backend, state.backend);
  };
  $("testBackendBtn").onclick = async () => {
    const st = $("backendStatus");
    try {
      await api("/api/symbols");
      st.textContent = "connected ✓";
      st.className = "backend-status ok";
      loadSymbols();
    } catch (e) {
      st.textContent = "unreachable ✗";
      st.className = "backend-status fail";
    }
  };

  $("cryptoToggle").checked = localStorage.getItem(LS.crypto) !== "false";
  $("cryptoToggle").onchange = () => localStorage.setItem(LS.crypto, $("cryptoToggle").checked);
}

async function boot() {
  initChart();
  wire();
  renderStats();
  setPhase("idle");
  try {
    await loadSymbols();
    $("emptyHint").textContent = `${state.symbols.length} instruments loaded — hit NEW SCENARIO`;
  } catch {
    $("emptyHint").textContent = "Backend offline. Start it: cd Trade_Replay_Trainer/backend && mvnw spring-boot:run";
    $("settingsPanel").classList.remove("hidden");
  }
}

boot();
