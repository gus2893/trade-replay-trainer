/* TAPE DOJO — price action replay trainer
 * Static frontend (GitHub Pages). Talks to the local Spring Boot backend,
 * which slices random windows out of the paper-trade labs' cached bars.
 *
 * The backend always delivers BASE bars (5m stocks, 15m crypto). Playback
 * steps base bars; the view timeframe is a client-side aggregation, so on
 * 15m/1h/4h you watch the higher-TF candle form bar by bar. Indicators are
 * computed on the view-timeframe buckets.
 */
"use strict";

const LS = {
  backend: "tapedojo.backend",
  stats: "tapedojo.stats",
  crypto: "tapedojo.crypto",
  ind: "tapedojo.ind",
  phase: "tapedojo.phase",
};

const TF_OPTIONS = { STOCK: [5, 15, 30, 60], CRYPTO: [15, 60, 240] };

const INDICATORS = [
  { key: "vwap", label: "VWAP", color: "#f5a524cc" },
  { key: "vwapBands", label: "VWAP ±1σ ±2σ", color: "#f5a52466" },
  { key: "ema9", label: "EMA 9", color: "#7dd3fccc" },
  { key: "ema20", label: "EMA 20", color: "#38bdf8aa" },
  { key: "ema50", label: "EMA 50", color: "#9d7bd8aa" },
  { key: "sma200", label: "SMA 200", color: "#e2e8f0aa" },
  { key: "bb", label: "Bollinger 20·2σ", color: "#64748baa" },
  { key: "orb", label: "Opening range 30m", color: "#f5a524", stockOnly: true },
  { key: "pdlvl", label: "Prior day H/L/C", color: "#94a3b8" },
  { key: "rsi", label: "RSI 14 (pane)", color: "#f5a524" },
];

const state = {
  backend: localStorage.getItem(LS.backend) || "http://localhost:8080",
  symbols: [],
  scenario: null,
  reveal: null,
  phase: "idle", // idle -> loaded -> armed -> playing -> revealed -> rated
  direction: null,
  userSpec: null,
  speed: 4, // bars per second, 0 = instant
  timer: null,
  paused: false,
  playIdx: 0,
  priceLines: [],
  markers: [], // marker times are BASE bar times; remapped to buckets on render
  baseTf: 5,
  viewTf: 5,
  delivered: [], // base bars shown so far (context + played future)
  buckets: [], // delivered aggregated to viewTf
  timeMap: new Map(), // base t -> bucket t
  agg: null, // incremental aggregation cursor for playback
  ind: JSON.parse(localStorage.getItem(LS.ind) || "{}"),
  managed: null, // client-side managed-position engine (partials/BE/trail)
  stats: { trades: 0, youR: 0, modelR: 0, gbSum: 0, ...JSON.parse(localStorage.getItem(LS.stats) || "{}") },
};

const $ = (id) => document.getElementById(id);

/* ── chart ─────────────────────────────────────────────── */

let chart, candles, volume;
const indSeries = {}; // key -> [lineSeries...]
let rsiGuidesAdded = false;

function tzForScenario() {
  return state.scenario && state.scenario.assetClass === "STOCK" ? "America/New_York" : "UTC";
}

function fmtTime(epochSec, withDate = false) {
  const opts = { hour: "2-digit", minute: "2-digit", hour12: false, timeZone: tzForScenario() };
  if (withDate) Object.assign(opts, { weekday: "short", year: "numeric", month: "short", day: "numeric" });
  return new Intl.DateTimeFormat("en-US", opts).format(new Date(epochSec * 1000));
}

const dayKeyCache = new Map();
function etDayKey(epochSec) {
  let v = dayKeyCache.get(epochSec);
  if (!v) {
    v = new Intl.DateTimeFormat("en-CA", { timeZone: "America/New_York", year: "numeric", month: "2-digit", day: "2-digit" })
      .format(new Date(epochSec * 1000));
    dayKeyCache.set(epochSec, v);
  }
  return v;
}

function periodKey(epochSec) {
  return state.scenario.assetClass === "STOCK" ? etDayKey(epochSec) : String(Math.floor(epochSec / 86400));
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
  applyPaneLayout();
  new ResizeObserver(() => chart.applyOptions({ width: el.clientWidth, height: el.clientHeight })).observe(el);
}

function applyPaneLayout() {
  const rsiOn = !!state.ind.rsi;
  chart.priceScale("right").applyOptions({ scaleMargins: rsiOn ? { top: 0.05, bottom: 0.34 } : { top: 0.08, bottom: 0.2 } });
  chart.priceScale("vol").applyOptions({ scaleMargins: rsiOn ? { top: 0.92, bottom: 0 } : { top: 0.86, bottom: 0 } });
  if (indSeries.rsi) chart.priceScale("rsi").applyOptions({ scaleMargins: { top: 0.7, bottom: 0.1 } });
}

function toCandle(b) {
  return { time: b.t, open: b.o, high: b.h, low: b.l, close: b.c };
}

function volBar(b) {
  return { time: b.t, value: b.v, color: b.c >= b.o ? "#2dd48f33" : "#f5484d33" };
}

function clearOverlayLines() {
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
  renderMarkers();
}

function renderMarkers() {
  const mapped = state.markers
    .map((m) => ({ ...m, time: state.timeMap.get(m.time) ?? m.time }))
    .sort((a, b) => a.time - b.time);
  candles.setMarkers(mapped);
}

/* ── timeframe aggregation ─────────────────────────────── */

function tfLabel(tf) {
  return tf < 60 ? `${tf}m` : `${tf / 60}h`;
}

/**
 * Merge one base bar into the aggregation cursor; returns the (possibly new)
 * current bucket. Stocks bucket by index-since-session-open so 1h bars align
 * to 9:30 ET; crypto buckets by epoch floor.
 */
function aggStep(agg, b) {
  const tf = state.viewTf;
  let bt;
  if (tf === state.baseTf) {
    bt = b.t;
  } else if (state.scenario.assetClass === "CRYPTO") {
    bt = b.t - (b.t % (tf * 60));
  } else {
    const dk = etDayKey(b.t);
    if (dk !== agg.day) {
      agg.day = dk;
      agg.idx = -1;
    }
    agg.idx++;
    if (agg.idx % (tf / state.baseTf) === 0) agg.bucketStart = b.t;
    bt = agg.bucketStart;
  }
  state.timeMap.set(b.t, bt);
  const last = state.buckets[state.buckets.length - 1];
  if (last && last.t === bt) {
    last.h = Math.max(last.h, b.h);
    last.l = Math.min(last.l, b.l);
    last.c = b.c;
    last.v += b.v;
    return last;
  }
  const fresh = { t: bt, o: b.o, h: b.h, l: b.l, c: b.c, v: b.v };
  state.buckets.push(fresh);
  return fresh;
}

function rebuildBuckets() {
  state.buckets = [];
  state.timeMap = new Map();
  state.agg = { day: null, idx: -1, bucketStart: 0 };
  for (const b of state.delivered) aggStep(state.agg, b);
}

function renderAll() {
  rebuildBuckets();
  candles.setData(state.buckets.map(toCandle));
  volume.setData(state.buckets.map(volBar));
  renderIndicators();
  renderMarkers();
  applyVisibleRange();
}

function applyVisibleRange() {
  const n = state.buckets.length;
  chart.timeScale().setVisibleLogicalRange({ from: Math.max(-2, n - 150), to: n + 4 });
}

/* ── indicators ────────────────────────────────────────── */

function anyIndOn() {
  return INDICATORS.some((i) => state.ind[i.key]);
}

function getIndSeries(key, defs) {
  if (!indSeries[key]) {
    indSeries[key] = defs.map((o) => chart.addLineSeries({
      lineWidth: 1, priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false, ...o,
    }));
  }
  return indSeries[key];
}

function setInd(key, defs, dataArrays) {
  const arr = getIndSeries(key, defs);
  arr.forEach((s, i) => s.setData(dataArrays ? dataArrays[i] || [] : []));
}

function renderIndicators() {
  const on = state.ind;
  const B = state.buckets;
  const isStock = state.scenario?.assetClass === "STOCK";
  const dot = LightweightCharts.LineStyle.Dotted;

  const vw = (on.vwap || on.vwapBands) ? computeVwap(B) : null;
  setInd("vwap", [{ color: "#f5a524cc" }], on.vwap && vw ? [vw.mid] : null);
  setInd("vwapBands",
    [{ color: "#f5a52466" }, { color: "#f5a52466" }, { color: "#f5a52433" }, { color: "#f5a52433" }],
    on.vwapBands && vw ? [vw.up1, vw.dn1, vw.up2, vw.dn2] : null);

  setInd("ema9", [{ color: "#7dd3fccc" }], on.ema9 ? [emaSeries(B, 9)] : null);
  setInd("ema20", [{ color: "#38bdf8aa" }], on.ema20 ? [emaSeries(B, 20)] : null);
  setInd("ema50", [{ color: "#9d7bd8aa" }], on.ema50 ? [emaSeries(B, 50)] : null);
  setInd("sma200", [{ color: "#e2e8f0aa", lineWidth: 2 }], on.sma200 ? [smaSeries(B, 200)] : null);

  const bb = on.bb ? computeBB(B, 20, 2) : null;
  setInd("bb",
    [{ color: "#64748baa", lineStyle: dot }, { color: "#64748b77" }, { color: "#64748b77" }],
    bb ? [bb.mid, bb.up, bb.dn] : null);

  const or = on.orb && isStock ? computeOpeningRange() : null;
  setInd("orb",
    [{ color: "#f5a524dd", lineStyle: dot, lineWidth: 2 }, { color: "#f5a524dd", lineStyle: dot, lineWidth: 2 }],
    or ? [or.high, or.low] : null);

  const pd = on.pdlvl ? computePriorDayLevels() : null;
  setInd("pdlvl",
    [{ color: "#2dd48f88", lineStyle: dot }, { color: "#f5484d88", lineStyle: dot }, { color: "#94a3b888", lineStyle: dot }],
    pd ? [pd.high, pd.low, pd.close] : null);

  const rsiArr = on.rsi ? [rsiSeriesData(B, 14)] : null;
  setInd("rsi", [{ color: "#f5a524cc", priceScaleId: "rsi" }], rsiArr);
  if (on.rsi && !rsiGuidesAdded && indSeries.rsi) {
    for (const level of [30, 70]) {
      indSeries.rsi[0].createPriceLine({
        price: level, color: "#46536488", lineWidth: 1,
        lineStyle: LightweightCharts.LineStyle.Dotted, axisLabelVisible: false, title: "",
      });
    }
    rsiGuidesAdded = true;
  }
  applyPaneLayout();
}

/** Session-anchored VWAP with ±1σ/±2σ bands (volume-weighted variance). */
function computeVwap(B) {
  const mid = [], up1 = [], dn1 = [], up2 = [], dn2 = [];
  let period = null, cumV = 0, cumPV = 0, cumP2V = 0;
  for (const b of B) {
    const pk = periodKey(b.t);
    if (pk !== period) {
      period = pk;
      cumV = 0; cumPV = 0; cumP2V = 0;
    }
    const tp = (b.h + b.l + b.c) / 3;
    cumV += b.v;
    cumPV += tp * b.v;
    cumP2V += tp * tp * b.v;
    if (cumV <= 0) continue;
    const vwap = cumPV / cumV;
    const sigma = Math.sqrt(Math.max(0, cumP2V / cumV - vwap * vwap));
    mid.push({ time: b.t, value: vwap });
    up1.push({ time: b.t, value: vwap + sigma });
    dn1.push({ time: b.t, value: vwap - sigma });
    up2.push({ time: b.t, value: vwap + 2 * sigma });
    dn2.push({ time: b.t, value: vwap - 2 * sigma });
  }
  return { mid, up1, dn1, up2, dn2 };
}

function emaSeries(B, period) {
  const out = [];
  const k = 2 / (period + 1);
  let ema = null;
  for (const b of B) {
    ema = ema === null ? b.c : b.c * k + ema * (1 - k);
    out.push({ time: b.t, value: ema });
  }
  return out.slice(period);
}

function smaSeries(B, period) {
  const out = [];
  let sum = 0;
  for (let i = 0; i < B.length; i++) {
    sum += B[i].c;
    if (i >= period) sum -= B[i - period].c;
    if (i >= period - 1) out.push({ time: B[i].t, value: sum / period });
  }
  return out;
}

function computeBB(B, period, mult) {
  const mid = [], up = [], dn = [];
  for (let i = period - 1; i < B.length; i++) {
    let sum = 0, sum2 = 0;
    for (let j = i - period + 1; j <= i; j++) {
      sum += B[j].c;
      sum2 += B[j].c * B[j].c;
    }
    const mean = sum / period;
    const sd = Math.sqrt(Math.max(0, sum2 / period - mean * mean));
    mid.push({ time: B[i].t, value: mean });
    up.push({ time: B[i].t, value: mean + mult * sd });
    dn.push({ time: B[i].t, value: mean - mult * sd });
  }
  return { mid, up, dn };
}

/** First-30-minutes high/low per session, drawn from OR completion onward. */
function computeOpeningRange() {
  const orBars = Math.max(1, Math.round(30 / state.baseTf));
  const byDay = new Map(); // day -> {high, low, endT}
  let day = null, count = 0, hi = 0, lo = 0;
  for (const b of state.delivered) {
    const dk = etDayKey(b.t);
    if (dk !== day) {
      day = dk;
      count = 0;
      hi = -Infinity;
      lo = Infinity;
    }
    if (count < orBars) {
      hi = Math.max(hi, b.h);
      lo = Math.min(lo, b.l);
      count++;
      if (count === orBars) byDay.set(dk, { high: hi, low: lo, endT: b.t });
    }
  }
  const high = [], low = [];
  for (const b of state.buckets) {
    const or = byDay.get(etDayKey(b.t));
    if (or && b.t >= or.endT) {
      high.push({ time: b.t, value: or.high });
      low.push({ time: b.t, value: or.low });
    }
  }
  return { high, low };
}

/** Previous session's high/low/close drawn across the current session. */
function computePriorDayLevels() {
  const days = []; // ordered {key, high, low, close}
  const idx = new Map();
  for (const b of state.delivered) {
    const pk = periodKey(b.t);
    if (!idx.has(pk)) {
      idx.set(pk, days.length);
      days.push({ key: pk, high: b.h, low: b.l, close: b.c });
    } else {
      const d = days[idx.get(pk)];
      d.high = Math.max(d.high, b.h);
      d.low = Math.min(d.low, b.l);
      d.close = b.c;
    }
  }
  const high = [], low = [], close = [];
  for (const b of state.buckets) {
    const i = idx.get(periodKey(b.t));
    if (i === undefined || i === 0) continue;
    const prev = days[i - 1];
    high.push({ time: b.t, value: prev.high });
    low.push({ time: b.t, value: prev.low });
    close.push({ time: b.t, value: prev.close });
  }
  return { high, low, close };
}

/** Wilder RSI on view-TF closes. */
function rsiSeriesData(B, period) {
  if (B.length <= period) return [];
  let gain = 0, loss = 0;
  for (let i = 1; i <= period; i++) {
    const d = B[i].c - B[i - 1].c;
    if (d >= 0) gain += d;
    else loss -= d;
  }
  let avgGain = gain / period, avgLoss = loss / period;
  const out = [{ time: B[period].t, value: rsiVal(avgGain, avgLoss) }];
  for (let i = period + 1; i < B.length; i++) {
    const d = B[i].c - B[i - 1].c;
    avgGain = (avgGain * (period - 1) + Math.max(0, d)) / period;
    avgLoss = (avgLoss * (period - 1) + Math.max(0, -d)) / period;
    out.push({ time: B[i].t, value: rsiVal(avgGain, avgLoss) });
  }
  return out;
}

function rsiVal(avgGain, avgLoss) {
  if (avgLoss === 0) return 100;
  return 100 - 100 / (1 + avgGain / avgLoss);
}

function buildIndPanel() {
  const panel = $("indPanel");
  panel.innerHTML = "";
  for (const ind of INDICATORS) {
    if (ind.stockOnly && state.scenario && state.scenario.assetClass !== "STOCK") continue;
    const label = document.createElement("label");
    label.className = "ind-item" + (state.ind[ind.key] ? " on" : "");
    const cb = document.createElement("input");
    cb.type = "checkbox";
    cb.checked = !!state.ind[ind.key];
    cb.onchange = () => {
      state.ind[ind.key] = cb.checked;
      label.classList.toggle("on", cb.checked);
      localStorage.setItem(LS.ind, JSON.stringify(state.ind));
      if (state.scenario) renderIndicators();
    };
    const sw = document.createElement("span");
    sw.className = "swatch";
    sw.style.background = ind.color;
    label.append(cb, sw, document.createTextNode(ind.label));
    panel.appendChild(label);
  }
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

function buildTfButtons() {
  const group = $("tfGroup");
  group.innerHTML = "";
  for (const tf of TF_OPTIONS[state.scenario.assetClass]) {
    const b = document.createElement("button");
    b.className = "tf-btn" + (tf === state.viewTf ? " active" : "");
    b.textContent = tfLabel(tf);
    b.onclick = () => {
      state.viewTf = tf;
      group.querySelectorAll(".tf-btn").forEach((x) => x.classList.remove("active"));
      b.classList.add("active");
      renderAll();
    };
    group.appendChild(b);
  }
}

async function newScenario() {
  stopTimer();
  state.paused = false;
  state.playIdx = 0;
  const symbol = $("symbolSelect").value || null;
  const includeCrypto = $("cryptoToggle").checked;
  const phase = $("phaseSelect").value;
  let sc;
  try {
    sc = await api("/api/scenarios", { symbol, includeCrypto, phase });
  } catch (e) {
    toast(e.message, true);
    return;
  }
  state.scenario = sc;
  state.reveal = null;
  state.direction = null;
  state.userSpec = null;
  state.managed = null;
  $("manageBlock").classList.add("hidden");
  document.querySelectorAll(".mg-btn").forEach((b) => { b.classList.remove("used", "on"); b.disabled = false; });
  $("mReadout").textContent = "—";
  state.baseTf = sc.barMinutes;
  state.viewTf = sc.barMinutes;
  state.delivered = sc.bars.slice();

  clearOverlayLines();
  buildTfButtons();
  buildIndPanel();
  renderAll();

  const chip = $("chipSymbol");
  chip.textContent = sc.masked ? "?????" : sc.displaySymbol;
  chip.classList.toggle("masked", sc.masked);
  $("chipTf").textContent =
    `${sc.assetClass} · ${sc.barMinutes}m base · last ${fmtPrice(sc.lastClose)} · ATR ${fmtPrice(visibleAtr())}`;

  document.querySelectorAll(".dir-btn").forEach((b) => b.classList.remove("selected"));
  $("stopInput").value = "";
  $("targetInput").value = "";
  $("ticketFields").style.opacity = 1;
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

/* ATR of the last visible base bars, for stop/target prefills. */
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
  $("ticketFields").style.opacity = dir === "SKIP" ? 0.35 : 1;
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
  toast("Trade locked. The model has committed too — hit PLAY (space).");
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
  state.paused = false;
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

  if (state.userSpec && reveal.user.outcome) {
    initManaged(reveal.user.outcome.entryFill);
  }

  state.playIdx = 0;
  if (state.speed === 0) {
    while (state.playIdx < reveal.futureBars.length) stepPlayback();
    return;
  }
  startTimer();
}

/* ── managed-position drills (partials / break-even / trail) ── */

function initManaged(entryFill) {
  state.managed = {
    entry: entryFill,
    isLong: state.userSpec.isLong,
    stop: state.userSpec.stop,
    target: state.userSpec.target,
    risk: Math.abs(entryFill - state.userSpec.stop),
    remaining: 1,
    realized: 0,
    peak: 0,
    trailing: false,
    halfUsed: false,
    beUsed: false,
    exited: false,
    exitReason: null,
    final: null,
    actions: [],
  };
  $("manageBlock").classList.remove("hidden");
  updateManageUI();
}

function rAt(m, price) {
  return (m.isLong ? price - m.entry : m.entry - price) / m.risk;
}

function managedR(price) {
  const m = state.managed;
  return m.realized + (m.exited ? 0 : m.remaining * rAt(m, price));
}

function lastDeliveredClose() {
  return state.delivered[state.delivered.length - 1].c;
}

function manageBar(b) {
  const m = state.managed;
  const L = m.isLong;
  const stopHit = L ? b.l <= m.stop : b.h >= m.stop;
  const targetHit = L ? b.h >= m.target : b.l <= m.target;
  if (stopHit) {
    const fill = L ? Math.min(m.stop, b.o) : Math.max(m.stop, b.o);
    m.realized += m.remaining * rAt(m, fill);
    m.remaining = 0;
    m.exited = true;
    m.exitReason = Math.abs(m.stop - m.entry) < 1e-9 ? "BE" : m.trailing ? "TRAIL" : "STOP";
  } else if (targetHit) {
    const fill = L ? Math.max(m.target, b.o) : Math.min(m.target, b.o);
    m.realized += m.remaining * rAt(m, fill);
    m.remaining = 0;
    m.exited = true;
    m.exitReason = "TARGET";
  } else if (m.trailing) {
    const trail = L ? b.c - m.risk : b.c + m.risk;
    if (L ? trail > m.stop : trail < m.stop) m.stop = trail;
  }
  if (m.exited && m.actions.length) {
    addMarker({
      time: b.t, position: L ? "aboveBar" : "belowBar", color: "#f5a524", shape: "circle",
      text: `MGD ${m.exitReason} ${m.realized > 0 ? "+" : ""}${m.realized.toFixed(2)}R`,
    });
  }
  m.peak = Math.max(m.peak, managedR(b.c));
  updateManageUI();
}

function updateManageUI() {
  const m = state.managed;
  if (!m) return;
  const cur = managedR(lastDeliveredClose());
  $("mReadout").textContent =
    `${cur > 0 ? "+" : ""}${cur.toFixed(2)}R · ${Math.round(m.remaining * 100)}% on` +
    (m.exited ? ` · out (${m.exitReason})` : "");
  $("mHalf").disabled = m.exited || m.halfUsed;
  $("mBe").disabled = m.exited || m.beUsed;
  $("mTrail").disabled = m.exited;
}

async function postManaged() {
  const m = state.managed;
  if (!m) return;
  try {
    await api(`/api/scenarios/${state.scenario.id}/managed`,
      { r: Math.round(m.final * 100) / 100, actions: m.actions });
  } catch {
    // best-effort: the plan outcome is already in the training log
  }
}

function startTimer() {
  stopTimer();
  state.paused = false;
  state.timer = setInterval(() => {
    stepPlayback();
    if (!state.reveal || state.playIdx >= state.reveal.futureBars.length) stopTimer();
  }, 1000 / state.speed);
}

function stopTimer() {
  if (state.timer) clearInterval(state.timer);
  state.timer = null;
}

function togglePause() {
  if (state.phase !== "playing") return;
  if (state.timer) {
    stopTimer();
    state.paused = true;
    toast("Paused — space to resume, or read the tape on another timeframe.");
  } else {
    startTimer();
  }
}

function stepPlayback() {
  const reveal = state.reveal;
  if (!reveal || state.playIdx >= reveal.futureBars.length) return;
  const b = reveal.futureBars[state.playIdx];
  state.delivered.push(b);
  const bucket = aggStep(state.agg, b);
  candles.update(toCandle(bucket));
  volume.update(volBar(bucket));
  // At MAX speed indicators redraw once at the end, not per bar.
  if (state.speed !== 0 && anyIndOn()) renderIndicators();
  if (state.managed && !state.managed.exited) manageBar(b);
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
  if (!state.reveal.user.outcome) { el.textContent = "flat"; el.className = "live-pnl"; return; }
  const r = state.managed ? managedR(bar.c) : 0;
  el.textContent = `${r > 0 ? "+" : ""}${r.toFixed(2)}R`;
  el.className = "live-pnl " + (r > 0.005 ? "pos" : r < -0.005 ? "neg" : "");
}

function finishPlayback() {
  stopTimer();
  if (state.managed) {
    const m = state.managed;
    if (!m.exited) m.exitReason = "END";
    m.final = m.exited ? m.realized : managedR(lastDeliveredClose());
    postManaged();
  }
  if (anyIndOn()) renderIndicators();
  applyVisibleRange();
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
    return null;
  }
  const o = report.outcome;
  $(elDir).textContent = report.direction;
  $(elR).textContent = `${o.r > 0 ? "+" : ""}${o.r}R`;
  $(elR).className = "v-r " + (o.r > 0 ? "pos" : o.r < 0 ? "neg" : "flat");
  const gaveBack = Math.max(0, o.mfeR - o.r);
  $(elDetail).textContent =
    `in ${fmtPrice(o.entryFill)} → out ${fmtPrice(o.exitPrice)} (${o.exitReason})` +
    ` · peak ${o.mfeR > 0 ? "+" : ""}${o.mfeR}R` +
    (gaveBack > 0.05 ? ` · gave back ${gaveBack.toFixed(2)}R` : "");
  return o;
}

function showResult() {
  const rv = state.reveal;
  const sc = state.scenario;

  // Unmask.
  $("chipSymbol").textContent = rv.symbol;
  $("chipSymbol").classList.remove("masked");
  $("revealIdentity").textContent =
    `${rv.symbol} · ${rv.assetClass} · cut at ${fmtTime(rv.cutTime, true)} ${sc.assetClass === "STOCK" ? "ET" : "UTC"}`;

  const userOutcome = verdict("youDir", "youR", "youDetail", rv.user);
  const modelOutcome = verdict("modelDir", "modelR", "modelDetail", rv.model);
  $("modelRationale").textContent = rv.model.rationale || "";
  renderSetupReveal(rv.setup);

  const m = state.managed;
  if (m && userOutcome) {
    $("youDetail").textContent +=
      ` · managed ${m.final > 0 ? "+" : ""}${m.final.toFixed(2)}R (${m.actions.join(", ") || "no actions"})`;
    $("youR").textContent = `${m.final > 0 ? "+" : ""}${m.final.toFixed(2)}R`;
    $("youR").className = "v-r " + (m.final > 0 ? "pos" : m.final < 0 ? "neg" : "flat");
  }

  if (userOutcome) {
    const finalR = m ? m.final : userOutcome.r;
    const peakR = m ? Math.max(m.peak, 0) : userOutcome.mfeR;
    state.stats.trades += 1;
    state.stats.youR += finalR;
    state.stats.gbSum += Math.max(0, peakR - finalR);
  }
  if (modelOutcome) state.stats.modelR += modelOutcome.r;
  localStorage.setItem(LS.stats, JSON.stringify(state.stats));
  renderStats();
}

const SETUP_LABELS = {
  LIQUIDITY_SWEEP: "LIQUIDITY SWEEP",
  BREAKOUT_CONTINUATION: "BREAKOUT CONTINUATION",
  FAILED_BREAKOUT: "FAILED BREAKOUT · FADE",
  VWAP_REVERSION: "VWAP REVERSION",
  TREND_PULLBACK: "TREND PULLBACK",
  MOMENTUM_RUN: "MOMENTUM RUN",
  CHOP: "CHOP — NO TRADE",
  MIXED: "TRAP TAPE — NO CLEAN TRADE",
};

function renderSetupReveal(setup) {
  if (!setup) {
    $("setupReveal").classList.add("hidden");
    return;
  }
  $("setupReveal").classList.remove("hidden");
  const chip = $("setupChip");
  const tradeable = setup.direction !== "SKIP";
  chip.textContent = (SETUP_LABELS[setup.type] || setup.type)
    + (tradeable ? ` → ${setup.direction} · ${setup.moveAtr} ATR` : "");
  chip.className = "setup-chip " + (tradeable ? setup.direction.toLowerCase() : "pass");
  $("setupDesc").textContent = setup.description || "";
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
  const gbAvg = s.trades ? s.gbSum / s.trades : 0;
  const gbEl = $("statGiveback");
  gbEl.textContent = `${gbAvg.toFixed(2)}R`;
  gbEl.className = "stat-v " + (gbAvg > 0.5 ? "neg" : "");
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
      if (state.phase === "playing" && !state.paused) {
        if (state.speed === 0) {
          while (state.reveal && state.playIdx < state.reveal.futureBars.length) stepPlayback();
        } else {
          startTimer();
        }
      }
    };
  });

  document.querySelectorAll(".rate-btn").forEach((b) => (b.onclick = () => rate(b.dataset.rating, b)));

  $("indBtn").onclick = (e) => {
    e.stopPropagation();
    $("indPanel").classList.toggle("hidden");
  };
  document.addEventListener("click", (e) => {
    if (!e.target.closest(".ind-wrap")) $("indPanel").classList.add("hidden");
  });

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

  $("phaseSelect").value = localStorage.getItem(LS.phase) || "ANY";
  $("phaseSelect").onchange = () => localStorage.setItem(LS.phase, $("phaseSelect").value);

  $("mHalf").onclick = () => {
    const m = state.managed;
    if (!m || m.exited || m.halfUsed || state.phase !== "playing") return;
    const price = lastDeliveredClose();
    m.realized += (m.remaining / 2) * rAt(m, price);
    m.remaining /= 2;
    m.halfUsed = true;
    m.actions.push(`HALF@${rAt(m, price) > 0 ? "+" : ""}${rAt(m, price).toFixed(2)}R`);
    $("mHalf").classList.add("used");
    updateManageUI();
  };
  $("mBe").onclick = () => {
    const m = state.managed;
    if (!m || m.exited || m.beUsed || state.phase !== "playing") return;
    if (m.isLong ? m.entry > m.stop : m.entry < m.stop) m.stop = m.entry;
    m.beUsed = true;
    m.actions.push("BE");
    $("mBe").classList.add("used");
    updateManageUI();
  };
  $("mTrail").onclick = () => {
    const m = state.managed;
    if (!m || m.exited || state.phase !== "playing") return;
    m.trailing = !m.trailing;
    m.actions.push(m.trailing ? "TRAIL_ON" : "TRAIL_OFF");
    $("mTrail").classList.toggle("on", m.trailing);
    updateManageUI();
  };

  document.addEventListener("keydown", (e) => {
    if (e.target.matches("input, select, textarea") || e.ctrlKey || e.metaKey || e.altKey) return;
    const k = e.key.toLowerCase();
    if (k === "n") {
      newScenario();
    } else if (state.phase === "loaded") {
      if (k === "l") selectDirection("LONG");
      else if (k === "s") selectDirection("SHORT");
      else if (k === "p") selectDirection("SKIP");
      else if (e.key === "Enter" && state.direction) commitTrade();
    } else if (state.phase === "armed" && e.key === " ") {
      e.preventDefault();
      play();
    } else if (state.phase === "playing" && e.key === " ") {
      e.preventDefault();
      togglePause();
    } else if (state.phase === "revealed") {
      const map = { "1": "GOOD", "2": "NEUTRAL", "3": "BAD" };
      if (map[k]) {
        const btn = document.querySelector(`.rate-btn[data-rating="${map[k]}"]`);
        if (btn) rate(map[k], btn);
      }
    }
  });
}

/** Hosted backend (Render free tier — may take ~1 min to wake from idle). */
const HOSTED_BACKEND = "https://tape-dojo.onrender.com";

async function pickBackend() {
  const candidates = [...new Set([state.backend, "http://localhost:8080", HOSTED_BACKEND])];
  for (const base of candidates) {
    try {
      const res = await fetch(base + "/api/symbols", { signal: AbortSignal.timeout(base === HOSTED_BACKEND ? 70000 : 4000) });
      if (res.ok) {
        state.backend = base;
        localStorage.setItem(LS.backend, base);
        $("backendUrl").value = base;
        return true;
      }
    } catch {
      // try the next candidate
    }
  }
  return false;
}

async function boot() {
  initChart();
  wire();
  buildIndPanel();
  renderStats();
  setPhase("idle");
  $("emptyHint").textContent = "Looking for a backend (local, then hosted — the hosted one may take ~1 min to wake)…";
  if (await pickBackend()) {
    try {
      await loadSymbols();
      $("emptyHint").textContent =
        `${state.symbols.length} instruments via ${state.backend.includes("localhost") ? "local backend" : "hosted backend"} — hit NEW SCENARIO (or press N)`;
      return;
    } catch {
      // fall through to the offline hint
    }
  }
  $("emptyHint").textContent =
    "No backend found. Local: double-click Tape Dojo.bat. Hosted: check the Render service, then set its URL in ⚙.";
  $("settingsPanel").classList.remove("hidden");
}

boot();
