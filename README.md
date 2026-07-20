# Tape Dojo — trade replay trainer

Practice reading price action against real historical data, blind. The app pulls a random
slice of history from my paper-trading labs' cached bars (67 US stocks × 400 days of 5-minute
bars, BTC × 365 days of 15-minute bars), hides the symbol and the future, and asks one
question: **what's your trade?** Commit long/short/pass with a stop and target, hit play, and
watch bar-by-bar how it actually resolved.

A baseline model (momentum breakout policy) commits its own hidden trade on every scenario at
the same moment you do. After the replay you rate the model's call — good / bad / neutral — and
the full record (both trades, both outcomes in R-multiples, your judgement) is appended to a
JSONL training log, building the dataset to train a learned policy that will replace the
baseline behind the same interface.

**Reading the tape:** scenarios come with deep context (up to 20 prior sessions for stocks,
14 days for crypto) and a view-timeframe switcher — stocks 5m/15m/30m/1h, crypto 15m/1h/4h.
Replay always steps in base bars, so on a higher timeframe you watch the candle form live.
Indicator menu: session-anchored VWAP with ±1σ/±2σ bands, EMA 9/20/50 (the 20/50 pair is
exactly what the model looks at), SMA 200, Bollinger 20·2σ, 30-minute opening range,
prior-day high/low/close levels, and an RSI-14 pane. ATR readout in the header for stop
sizing. Keyboard-first: **N** new scenario, **L/S/P** direction, **Enter** commit,
**Space** play/pause, **1/2/3** rate. The header tracks session R for you and the model plus
average **give-back** (peak R surrendered per trade — the metric the live account leaks).

## Architecture

```
docs/       Static frontend (vanilla JS + lightweight-charts) — hosted on GitHub Pages
backend/    Java 21 · Spring Boot 4.1 API — runs locally, reads the labs' bar CSVs from disk
```

The frontend is served at **https://gus2893.github.io/trade-replay-trainer/** and talks to the
backend on `http://localhost:8080` (configurable via the ⚙ panel). No API keys anywhere: all
market data comes from CSVs the labs have already cached.

## Run it

Double-click **`Tape Dojo.bat`** (starts the backend and opens the app), or:

```bash
cd backend
./mvnw spring-boot:run        # Windows: mvnw.cmd spring-boot:run
```

Then open the GitHub Pages URL — or just `http://localhost:8080/index.html`, since the backend
serves the frontend too.

Expected sibling repos (configurable in `backend/src/main/resources/application.yml`):

```
../US_Stocks_Paper_Trade_Lab/data/hist_<SYM>_5Min_alpaca_<days>d.csv
../BTC_Paper_Trade_Lab/data/hist_BTC_15m_<days>d.csv
```

## Host it (no PC required)

The backend is cloud-ready: `Dockerfile` + `render.yaml` deploy it to Render's free tier.
In cloud mode (`SPRING_PROFILES_ACTIVE=cloud`) it **fetches its own bars at startup — no keys
required**: forex majors (EURUSD/GBPUSD/USDJPY/AUDUSD/USDCAD, 1h × 2 years) and stocks from
Yahoo's public endpoint (5m × ~60 days), BTC/ETH from Coinbase. If `APCA_API_KEY_ID` /
`APCA_API_SECRET_KEY` env vars are present, stocks upgrade to Alpaca's deeper history
(5m × 150 days). It also **persists its learning** (training log + learned model) to a
private GitHub repo every 10 minutes, restoring on boot, so redeploys never lose progress.
Self-play keeps training 24/7 while the service is awake.

One-time setup (~5 min):
1. render.com → New → Blueprint → connect `gus2893/trade-replay-trainer` (it reads `render.yaml`).
2. Optional env vars: `APCA_API_KEY_ID` / `APCA_API_SECRET_KEY` (deeper stock history);
   `TRAINER_SYNC_REPO=gus2893/tapedojo-data` + `TRAINER_SYNC_TOKEN` (fine-grained PAT with
   Contents read/write on that private repo) for durable learning.
3. Deploy. First boot backfills data (~3–5 min); after that the GitHub Pages frontend finds
   the hosted backend automatically (it tries local first, then `tape-dojo.onrender.com`).

Free-tier note: the instance naps after ~15 idle minutes (first request takes ~1 min to
wake). A free UptimeRobot monitor pinging `/api/training/stats` every 5 minutes keeps it (and
the self-play learning loop) always on.

## API

| Endpoint | What it does |
|---|---|
| `GET /api/symbols` | Available instruments (stocks + crypto) |
| `POST /api/scenarios` | New random scenario `{symbol?, includeCrypto}` — returns context bars only |
| `POST /api/scenarios/{id}/trade` | Commit your trade `{direction, stop, target}` (direction `SKIP` to pass) |
| `POST /api/scenarios/{id}/play` | Reveal: future bars + model's trade + both outcomes (R, MFE, MAE) |
| `POST /api/scenarios/{id}/feedback` | Rate the model's call `{rating: GOOD\|BAD\|NEUTRAL, note?}` → training log |

Scenario mechanics: stock cuts land between 30 minutes after the open and an hour before the
close (first 90 minutes only in the "At the open" ORB mode). **The future plays out for as
many bars as the shown context** — up to 20 sessions forward for stocks (crossing overnight
gaps) and 14 days for crypto — so multi-day holds are viable. Fills are next-bar-open; when a
bar touches both stop and target, the stop is assumed first (conservative). Same R-multiple
conventions as the labs' ledgers.

**Manage the position while it plays**: take half off, move the stop to break-even, or toggle
a 1R trail — the managed result is scored next to the set-and-forget plan and logged as a
`managed` record, so exit management (the live account's give-back leak) is a trainable rep.

**The learning loop runs itself**: a self-play trainer generates a scenario every ~15 s, lets
the model trade it, and appends the outcome to the log; a retrainer refits a logistic
win-probability filter from the log every 60 s (human GOOD/BAD ratings count double). Once
it has 30+ samples the live model starts gating its breakouts with the learned P(win) —
vetoed setups show up as SKIPs with the probability in the rationale. Weights persist in
`backend/data/learned_model.json`.

## Training data

`backend/data/training_log.jsonl` (gitignored) gets one line per event, two record types
joined by `scenarioId`:

- **`outcome`** — written for EVERY played scenario, rated or not: both trades, both results
  (R/MFE/MAE), and the model's exact feature snapshot at decision time (close, EMA20/50,
  ATR14, range high/low, trend flags). This is what a learned policy trains on.
- **`rating`** — the human GOOD/BAD/NEUTRAL judgement plus optional note, when given.

`GET /api/training/stats` summarizes the dataset (record counts, model/user avg R, rating
breakdown) so you can watch it grow. Bars are reproducible from symbol + cutTime against the
labs' CSVs, so records stay small.

## Tests

```bash
cd backend && ./mvnw verify
```

Covers the trade simulator (stop/target/same-bar/gap fills, R math, MFE/MAE), the breakout
model, scenario windowing (no future leak, session boundaries, crypto toggle), CSV discovery
and parsing, and the full HTTP practice loop via MockMvc.

## Roadmap

- Deeper learned policy (features beyond the breakout snapshot; setup-type aware)
- Forex locally (cloud mode has it via Yahoo; local mode still reads only the labs' CSVs)
- Richer exit drills: scale-outs at targets, time stops, ATR-multiple trails
