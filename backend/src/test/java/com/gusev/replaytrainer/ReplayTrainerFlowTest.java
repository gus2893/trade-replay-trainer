package com.gusev.replaytrainer;

import static org.hamcrest.Matchers.hasItems;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.gusev.replaytrainer.testutil.TestBars;

@SpringBootTest
@AutoConfigureMockMvc
class ReplayTrainerFlowTest {

	private static Path trainingLog;

	@DynamicPropertySource
	static void testData(DynamicPropertyRegistry registry) throws IOException {
		Path stocks = Files.createTempDirectory("trainer-stocks");
		Path crypto = Files.createTempDirectory("trainer-crypto");
		Files.writeString(stocks.resolve("hist_TSLA_5Min_alpaca_400d.csv"),
				TestBars.toCsv(TestBars.stockSeries(6, Instant.parse("2026-01-05T14:30:00Z"), 100, 0.05)));
		Files.writeString(crypto.resolve("hist_BTC_15m_365d.csv"),
				TestBars.toCsv(TestBars.cryptoSeries(400, Instant.parse("2026-01-01T00:00:00Z"), 60000, 5)));
		trainingLog = Files.createTempDirectory("trainer-log").resolve("training_log.jsonl");
		registry.add("trainer.stock-data-dirs[0]", stocks::toString);
		registry.add("trainer.crypto-data-dirs[0]", crypto::toString);
		registry.add("trainer.crypto-data-dirs[1]", crypto::toString);
		registry.add("trainer.training-log", trainingLog::toString);
		registry.add("trainer.learned-model", () -> trainingLog.getParent().resolve("learned_model.json").toString());
		registry.add("trainer.self-play", () -> "false");
	}

	@Autowired
	MockMvc mvc;
	@Autowired
	ObjectMapper mapper;

	@Test
	void fullPracticeLoop() throws Exception {
		mvc.perform(get("/api/symbols"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$..symbol", hasItems("BTC", "TSLA")));

		JsonNode scenario = readJson(mvc.perform(post("/api/scenarios")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"symbol\":\"TSLA\",\"includeCrypto\":false}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.displaySymbol").value("TSLA"))
				.andExpect(jsonPath("$.masked").value(false))
				.andReturn().getResponse().getContentAsString());

		String id = scenario.get("id").asText();
		double lastClose = scenario.get("lastClose").asDouble();
		assertTrue(scenario.get("bars").size() >= 60, "context bars served");

		mvc.perform(post("/api/scenarios/" + id + "/trade")
				.contentType(MediaType.APPLICATION_JSON)
				.content(String.format(java.util.Locale.ROOT,
						"{\"direction\":\"LONG\",\"stop\":%.4f,\"target\":%.4f}",
						lastClose * 0.98, lastClose * 1.03)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accepted").value(true))
				.andExpect(jsonPath("$.modelReady").value(true));

		JsonNode reveal = readJson(mvc.perform(post("/api/scenarios/" + id + "/play"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.symbol").value("TSLA"))
				.andExpect(jsonPath("$.user.direction").value("LONG"))
				.andExpect(jsonPath("$.user.outcome.r").exists())
				.andExpect(jsonPath("$.model.direction").exists())
				.andReturn().getResponse().getContentAsString());
		assertTrue(reveal.get("futureBars").size() >= 12, "hidden bars revealed on play");

		mvc.perform(post("/api/scenarios/" + id + "/feedback")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"rating\":\"GOOD\",\"note\":\"clean breakout read\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.recorded").value(true));

		List<String> lines = Files.readAllLines(trainingLog);
		assertEquals(2, lines.size(), "one outcome record (from play) + one rating record");
		assertTrue(lines.get(0).contains("\"type\":\"outcome\""));
		assertTrue(lines.get(0).contains("\"symbol\":\"TSLA\""));
		assertTrue(lines.get(0).contains("\"modelFeatures\""));
		assertTrue(lines.get(1).contains("\"type\":\"rating\""));
		assertTrue(lines.get(1).contains("\"rating\":\"GOOD\""));

		mvc.perform(post("/api/scenarios/" + id + "/managed")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"r\":0.55,\"actions\":[\"HALF@+0.8R\",\"BE\"]}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.recorded").value(true));

		mvc.perform(get("/api/training/stats"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcomes").value(1))
				.andExpect(jsonPath("$.ratings").value(1))
				.andExpect(jsonPath("$.managedRecords").value(1))
				.andExpect(jsonPath("$.ratingCounts.GOOD").value(1))
				.andExpect(jsonPath("$.learnedFilter.ready").value(false));
	}

	@Test
	void errorPathsReturnUsefulStatuses() throws Exception {
		mvc.perform(post("/api/scenarios/does-not-exist/play"))
				.andExpect(status().isNotFound());

		JsonNode scenario = readJson(mvc.perform(post("/api/scenarios")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"symbol\":\"TSLA\"}"))
				.andReturn().getResponse().getContentAsString());
		String id = scenario.get("id").asText();

		mvc.perform(post("/api/scenarios/" + id + "/play"))
				.andExpect(status().isConflict());

		mvc.perform(post("/api/scenarios/" + id + "/feedback")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"rating\":\"GOOD\"}"))
				.andExpect(status().isConflict());

		double lastClose = scenario.get("lastClose").asDouble();
		mvc.perform(post("/api/scenarios/" + id + "/trade")
				.contentType(MediaType.APPLICATION_JSON)
				.content(String.format(java.util.Locale.ROOT,
						"{\"direction\":\"LONG\",\"stop\":%.4f,\"target\":%.4f}",
						lastClose * 1.02, lastClose * 1.05)))
				.andExpect(status().isBadRequest());
	}

	private JsonNode readJson(String body) throws IOException {
		return mapper.readTree(body);
	}
}
