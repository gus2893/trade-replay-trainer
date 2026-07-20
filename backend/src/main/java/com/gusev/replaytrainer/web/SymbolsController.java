package com.gusev.replaytrainer.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gusev.replaytrainer.market.MarketDataProvider;
import com.gusev.replaytrainer.market.SymbolInfo;

@RestController
@RequestMapping("/api/symbols")
public class SymbolsController {

	private final MarketDataProvider provider;

	public SymbolsController(MarketDataProvider provider) {
		this.provider = provider;
	}

	@GetMapping
	public List<SymbolInfo> symbols() {
		return provider.symbols();
	}
}
