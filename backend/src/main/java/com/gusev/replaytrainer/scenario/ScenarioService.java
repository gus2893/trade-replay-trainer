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
 * after the open and one hour before the close; context is the two prior
 * sessions plus the current session up to the cut; the future is the rest of
 * that session.
 * Crypto (15m continuous): 192 context bars (48h), 48 future bars (12h).
 */
@Service
public class ScenarioService {

	private static final ZoneId MARKET_TZ = ZoneId.of("America/New_York");
	private static final int STOCK_MIN_SESSION_BARS = 40;
	private static final int STOCK_MIN_CUT_OFFSET = 6;
	private static final int STOCK_MIN_FUTURE_BARS = 12;
	private static final int CRYPTO_CONTEXT_BARS = 192;
	private static final int CRYPTO_FUTURE_BARS = 48;
	private static final int MAX_STORED_SCENARIOS = 300;
	private static final int MAX_PICK_ATTEMPTS = 25;

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
		boolean masked = requestedSymbol == null || requestedSymbol.isBlank();
		List<SymbolInfo> pool = pool(requestedSymbol, includeCrypto);
		for (int attempt = 0; attempt < MAX_PICK_ATTEMPTS; attempt++) {
			SymbolInfo pick = pool.get(random.nextInt(pool.size()));
			Scenario scenario = tryBuild(provider.series(pick.symbol()), masked);
			if (scenario != null) {
				store.put(scenario.id, scenario);
				return scenario;
			}
		}
		throw new IllegalStateException("Could not find a usable scenario window; is the lab data present?");
	}

	private List<SymbolInfo> pool(String requestedSymbol, boolean includeCrypto) {
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
				.toList();
		if (pool.isEmpty()) {
			throw new IllegalStateException("No symbols available — check the configured data directories");
		}
		return pool;
	}

	private Scenario tryBuild(BarSeries series, boolean masked) {
		int[] window = series.assetClass() == AssetClass.STOCK
				? stockWindow(series.bars())
				: cryptoWindow(series.bars());
		if (window == null) {
			return null;
		}
		List<Bar> context = List.copyOf(series.bars().subList(window[0], window[1] + 1));
		List<Bar> future = List.copyOf(series.bars().subList(window[1] + 1, window[2] + 1));
		TradePlan modelPlan = modelTrader.proposeTrade(context);
		return new Scenario(UUID.randomUUID().toString(), series.symbol(), series.assetClass(),
				series.barMinutes(), masked, context, future, modelPlan);
	}

	/** Returns {contextStart, cutIndex, futureEndInclusive} or null if the pick is unusable. */
	private int[] stockWindow(List<Bar> bars) {
		List<int[]> sessions = sessions(bars);
		if (sessions.size() < 3) {
			return null;
		}
		int s = 2 + random.nextInt(sessions.size() - 2);
		int[] session = sessions.get(s);
		int len = session[1] - session[0] + 1;
		if (len < STOCK_MIN_SESSION_BARS) {
			return null;
		}
		int maxCutOffset = len - 1 - STOCK_MIN_FUTURE_BARS;
		if (maxCutOffset <= STOCK_MIN_CUT_OFFSET) {
			return null;
		}
		int cut = session[0] + STOCK_MIN_CUT_OFFSET
				+ random.nextInt(maxCutOffset - STOCK_MIN_CUT_OFFSET + 1);
		return new int[] { sessions.get(s - 2)[0], cut, session[1] };
	}

	private int[] cryptoWindow(List<Bar> bars) {
		int minCut = CRYPTO_CONTEXT_BARS - 1;
		int maxCut = bars.size() - 1 - CRYPTO_FUTURE_BARS;
		if (maxCut <= minCut) {
			return null;
		}
		int cut = minCut + random.nextInt(maxCut - minCut + 1);
		return new int[] { cut - CRYPTO_CONTEXT_BARS + 1, cut, cut + CRYPTO_FUTURE_BARS };
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
			TradeOutcome user = scenario.userTrade().direction() == TradeDirection.SKIP
					? null
					: TradeSimulator.simulate(scenario.userTrade(), scenario.futureBars);
			TradeOutcome model = scenario.modelPlan.direction() == TradeDirection.SKIP
					? null
					: TradeSimulator.simulate(
							new TradeSpec(scenario.modelPlan.direction(), scenario.modelPlan.stop(),
									scenario.modelPlan.target()),
							scenario.futureBars);
			scenario.reveal(user, model);
		}
		return scenario;
	}
}
