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

## Architecture

```
docs/       Static frontend (vanilla JS + lightweight-charts) — hosted on GitHub Pages
backend/    Java 21 · Spring Boot 4.1 API — runs locally, reads the labs' bar CSVs from disk
```

The frontend is served at **https://gus2893.github.io/trade-replay-trainer/** and talks to the
backend on `http://localhost:8080` (configurable via the ⚙ panel). No API keys anywhere: all
market data comes from CSVs the labs have already cached.

## Run it

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

## API

| Endpoint | What it does |
|---|---|
| `GET /api/symbols` | Available instruments (stocks + crypto) |
| `POST /api/scenarios` | New random scenario `{symbol?, includeCrypto}` — returns context bars only |
| `POST /api/scenarios/{id}/trade` | Commit your trade `{direction, stop, target}` (direction `SKIP` to pass) |
| `POST /api/scenarios/{id}/play` | Reveal: future bars + model's trade + both outcomes (R, MFE, MAE) |
| `POST /api/scenarios/{id}/feedback` | Rate the model's call `{rating: GOOD\|BAD\|NEUTRAL, note?}` → training log |

Scenario mechanics: stock cuts land between 30 minutes after the open and an hour before the
close, with two prior sessions of context; the future is the rest of that session. Crypto gets
48 hours of context and a 12-hour future. Fills are next-bar-open; when a bar touches both stop
and target, the stop is assumed first (conservative). Same R-multiple conventions as the labs'
ledgers.

## Training data

Every rated scenario appends one line to `backend/data/training_log.jsonl` (gitignored):

```json
{"ts":"…","symbol":"TSLA","cutTime":"…","userTrade":{…,"outcome":{"r":1.4,…}},
 "modelTrade":{…,"outcome":{"r":-1.0,…}},"rating":"BAD"}
```

Bars are reproducible from symbol + cutTime against the labs' CSVs, so records stay small.

## Tests

```bash
cd backend && ./mvnw verify
```

Covers the trade simulator (stop/target/same-bar/gap fills, R math, MFE/MAE), the breakout
model, scenario windowing (no future leak, session boundaries, crypto toggle), CSV discovery
and parsing, and the full HTTP practice loop via MockMvc.

## Roadmap

- Learned policy (train on the JSONL log, swap in behind `ModelTrader`)
- Forex provider (interface is ready; the labs don't cache FX bars yet)
- Entry-management drills: partials, break-even moves, trailing exits — the live account's
  known leak is give-back, so exit management is the next practice muscle
