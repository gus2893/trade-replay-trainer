package com.gusev.replaytrainer.scenario;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.gusev.replaytrainer.market.AssetClass;
import com.gusev.replaytrainer.market.Bar;
import com.gusev.replaytrainer.market.BarSeries;
import com.gusev.replaytrainer.market.MarketDataProvider;
import com.gusev.replaytrainer.market.SymbolInfo;
import com.gusev.replaytrainer.model.ModelTrader;
import com.gusev.replaytrainer.model.TradePlan;
import com.gusev.replaytrainer.sim.TradeDirection;
import com.gusev.replaytrainer.sim.TradeOutcome;
import com.gusev.replaytrainer.sim.TradeSimulator;
import com.gusev.replaytrainer.sim.TradeSpec;

/**
 * Builds random practice windows out of the labs' cached series and holds the
 * per-scenario state. Windowing rules:
 * Stocks (5m RTH bars): pick a random session, cut somewhere between 30 min
 * after the open and one hour before the close (first 90 min only for
 * CutPhase.OPEN); context is up to twenty prior sessions plus the current
 * session up to the cut. The future runs for AS MANY BARS AS THE CONTEXT
 * (data permitting, min 12) — it crosses session boundaries and overnight
 * gaps, so multi-day holds are viable.
 * Continuous markets (crypto 15m, forex 60m): 7-30 days of context; the
 * future mirrors the context length (min 12 hours).
 */
@Service
public class ScenarioService {

	private static final ZoneId MARKET_TZ = ZoneId.of("America/New_York");
	private static final int STOCK_MIN_SESSION_BARS = 40;
	private static final int STOCK_MIN_CUT_OFFSET = 6;
	private static final int STOCK_OPEN_MAX_CUT_OFFSET = 18;
	private static final int STOCK_MIN_FUTURE_BARS = 12;
	private static final int STOCK_MAX_CONTEXT_SESSIONS = 20;
	/** Every scenario should carry enough tape to actually read: prefer >= 10 prior sessions. */
	private static final int STOCK_MIN_PRIOR_SESSIONS = 10;
	private static final int MAX_STORED_SCENARIOS = 300;
	private static final int MAX_PICK_ATTEMPTS = 40;
	/** Share of scenarios curated to be real setups; the rest are chop, so passing stays a skill. */
	private static final double SETUP_SHARE = 0.75;

	private final MarketDataProvider provider;
	private final ModelTrader modelTrader;
	private final Random random;

	private final Map<String, Scenario> store = new LinkedHashMap<>() {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Scenario> eldest) {
			return size() > MAX_STORED_SCENARIOS;
		}
	};

	@org.springframework.beans.factory.annotation.Autowired
	public ScenarioService(MarketDataProvider provider, ModelTrader modelTrader) {
		this(provider, modelTrader, new Random());
	}

	ScenarioService(MarketDataProvider provider, ModelTrader modelTrader, Random random) {
		this.provider = provider;
		this.modelTrader = modelTrader;
		this.random = random;
	}

	public synchronized Scenario create(String requestedSymbol, boolean includeCrypto) {
		return create(requestedSymbol, includeCrypto, true, CutPhase.ANY);
	}

	public synchronized Scenario create(String requestedSymbol, boolean includeCrypto, CutPhase phase) {
		return create(requestedSymbol, includeCrypto, true, phase);
	}

	public synchronized Scenario create(String requestedSymbol, boolean includeCrypto, boolean includeForex,
			CutPhase phase) {
		boolean masked = requestedSymbol == null || requestedSymbol.isBlank();
		Scenario scenario = attempt(pool(requestedSymbol, includeCrypto, includeForex), masked, phase);
		store.put(scenario.id, scenario);
		return scenario;
	}

	/** A scenario that is NOT stored — used by the self-play trainer. */
	public synchronized Scenario buildRandom(boolean includeCrypto, CutPhase phase) {
		return attempt(pool(null, includeCrypto, true), true, phase);
	}

	/**
	 * Curated sampling: decide up front whether this scenario should be a real
	 * setup or deliberate chop, then rejection-sample candidate windows until
	 * one matches. Falls back to the first buildable window so it never fails.
	 */
	private Scenario attempt(List<SymbolInfo> pool, boolean masked, CutPhase phase) {
		boolean wantSetup = random.nextDouble() < SETUP_SHARE;
		Scenario fallback = null;
		for (int i = 0; i < MAX_PICK_ATTEMPTS; i++) {
			SymbolInfo pick = pool.get(random.nextInt(pool.size()));
			Scenario candidate = tryBuild(provider.series(pick.symbol()), masked, phase);
			if (candidate == null) {
				continue;
			}
			if (fallback == null) {
				fallback = candidate;
			}
			if (wantSetup ? candidate.setup.isRealSetup() : candidate.setup.isChop()) {
				return candidate;
			}
		}
		if (fallback != null) {
			return fallback;
		}
		throw new IllegalStateException("Could not find a usable scenario window; is the lab data present?");
	}

	private List<SymbolInfo> pool(String requestedSymbol, boolean includeCrypto, boolean includeForex) {
		List<SymbolInfo> all = provider.symbols();
		if (requestedSymbol != null && !requestedSymbol.isBlank()) {
			return all.stream()
					.filter(s -> s.symbol().equalsIgnoreCase(requestedSymbol))
					.map(List::of)
					.findFirst()
					.orElseThrow(() -> new NoSuchElementException("Unknown symbol: " + requestedSymbol));
		}
		List<SymbolInfo> pool = all.stream()
				.filter(s -> includeCrypto || s.assetClass() != AssetClass.CRYPTO)
				.filter(s -> includeForex || s.assetClass() != AssetClass.FOREX)
				.toList();
		if (pool.isEmpty()) {
			throw new IllegalStateException("No symbols available — check the configured data directories");
		}
		return pool;
	}

	private Scenario tryBuild(BarSeries series, boolean masked, CutPhase phase) {
		int[] window = series.assetClass() == AssetClass.STOCK
				? stockWindow(series.bars(), phase)
				: continuousWindow(series.bars(), series.barMinutes());
		if (window == null) {
			return null;
		}
		List<Bar> context = List.copyOf(series.bars().subList(window[0], window[1] + 1));
		List<Bar> future = List.copyOf(series.bars().subList(window[1] + 1, window[2] + 1));
		TradePlan modelPlan = modelTrader.proposeTrade(context);
		SetupInfo setup = SetupDetector.classify(context, future, series.barMinutes());
		return new Scenario(UUID.randomUUID().toString(), series.symbol(), series.assetClass(),
				series.barMinutes(), masked, context, future, modelPlan, setup);
	}

	/** Returns {contextStart, cutIndex, futureEndInclusive} or null if the pick is unusable. */
	private int[] stockWindow(List<Bar> bars, CutPhase phase) {
		List<int[]> sessions = sessions(bars);
		if (sessions.size() < 3) {
			return null;
		}
		// Prefer 10+ prior sessions of context; degrade gracefully on short series.
		int minPrior = Math.min(STOCK_MIN_PRIOR_SESSIONS, Math.max(2, sessions.size() - 3));
		if (sessions.size() <= minPrior) {
			return null;
		}
		int s = minPrior + random.nextInt(sessions.size() - minPrior);
		int[] session = sessions.get(s);
		int len = session[1] - session[0] + 1;
		if (len < STOCK_MIN_SESSION_BARS) {
			return null;
		}
		int maxCutOffset = len - 1 - STOCK_MIN_FUTURE_BARS;
		if (phase == CutPhase.OPEN) {
			maxCutOffset = Math.min(maxCutOffset, STOCK_OPEN_MAX_CUT_OFFSET);
		}
		if (maxCutOffset <= STOCK_MIN_CUT_OFFSET) {
			return null;
		}
		int cut = session[0] + STOCK_MIN_CUT_OFFSET
				+ random.nextInt(maxCutOffset - STOCK_MIN_CUT_OFFSET + 1);
		int ctxStart = sessions.get(Math.max(0, s - STOCK_MAX_CONTEXT_SESSIONS))[0];
		int contextLen = cut - ctxStart + 1;
		int futureEnd = Math.min(bars.size() - 1, cut + contextLen);
		if (futureEnd - cut < STOCK_MIN_FUTURE_BARS) {
			return null;
		}
		return new int[] { ctxStart, cut, futureEnd };
	}

	/** Continuous markets (crypto 15m, forex 60m): windows scale with bar size. */
	private int[] continuousWindow(List<Bar> bars, int barMinutes) {
		// Prefer 7 days of context (up to 30); degrade gracefully on short series.
		int minContext = Math.min(7 * 1440 / barMinutes, Math.max(2 * 1440 / barMinutes, bars.size() / 3));
		int maxContext = 30 * 1440 / barMinutes;
		int minFuture = 12 * 60 / barMinutes; // 12 hours
		int minCut = minContext - 1;
		int maxCut = bars.size() - 1 - minFuture;
		if (maxCut <= minCut) {
			return null;
		}
		int cut = minCut + random.nextInt(maxCut - minCut + 1);
		int ctxStart = Math.max(0, cut - maxContext + 1);
		int contextLen = cut - ctxStart + 1;
		int futureEnd = Math.min(bars.size() - 1, cut + contextLen);
		if (futureEnd - cut < minFuture) {
			return null;
		}
		return new int[] { ctxStart, cut, futureEnd };
	}

	/** Groups bars into RTH sessions by their New-York-time calendar date. */
	static List<int[]> sessions(List<Bar> bars) {
		List<int[]> sessions = new ArrayList<>();
		int start = 0;
		for (int i = 1; i <= bars.size(); i++) {
			boolean boundary = i == bars.size()
					|| !bars.get(i).time().atZone(MARKET_TZ).toLocalDate()
							.equals(bars.get(start).time().atZone(MARKET_TZ).toLocalDate());
			if (boundary) {
				sessions.add(new int[] { start, i - 1 });
				start = i;
			}
		}
		return sessions;
	}

	public synchronized Scenario get(String id) {
		Scenario scenario = store.get(id);
		if (scenario == null) {
			throw new NoSuchElementException("Unknown or expired scenario: " + id);
		}
		return scenario;
	}

	public synchronized void placeTrade(String id, TradeSpec trade) {
		Scenario scenario = get(id);
		if (scenario.revealed()) {
			throw new IllegalStateException("Scenario already revealed");
		}
		trade.validateAgainst(scenario.lastVisibleClose());
		scenario.commitTrade(trade);
	}

	public synchronized Scenario play(String id) {
		Scenario scenario = get(id);
		if (scenario.userTrade() == null) {
			throw new IllegalStateException("Place a trade (or skip) before playing the scenario forward");
		}
		if (!scenario.revealed()) {
			resolve(scenario);
		}
		return scenario;
	}

	/** Self-play path: the "user" passes, outcomes resolve immediately. */
	public void resolveSelfPlay(Scenario scenario) {
		scenario.commitTrade(new TradeSpec(TradeDirection.SKIP, 0, 0));
		resolve(scenario);
	}

	private static void resolve(Scenario scenario) {
		TradeOutcome user = scenario.userTrade().direction() == TradeDirection.SKIP
				? null
				: TradeSimulator.simulate(scenario.userTrade(), scenario.futureBars);
		TradeOutcome model = scenario.modelPlan.direction() == TradeDirection.SKIP
				? null
				: TradeSimulator.simulate(
						new TradeSpec(scenario.modelPlan.direction(), scenario.modelPlan.entryType(),
								scenario.modelPlan.limit(), scenario.modelPlan.stop(), scenario.modelPlan.target()),
						scenario.futureBars);
		scenario.reveal(user, model);
	}
}
