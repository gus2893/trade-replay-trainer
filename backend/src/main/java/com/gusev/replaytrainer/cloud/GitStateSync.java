package com.gusev.replaytrainer.cloud;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.gusev.replaytrainer.config.AppProperties;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.ObjectMapper;

/**
 * Durable learning on ephemeral disks: mirrors the training log and the
 * learned model into a PRIVATE GitHub repo (trainer.sync-repo = "owner/name",
 * token via TRAINER_SYNC_TOKEN env var). Pulls on boot when the local file is
 * missing; pushes every 10 minutes when something changed, and once more on
 * shutdown. Inactive unless both repo and token are configured.
 */
@Component
public class GitStateSync {

	private static final Logger log = LoggerFactory.getLogger(GitStateSync.class);

	private final AppProperties props;
	private final ObjectMapper mapper;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
	private final Map<Path, Long> lastPushed = new LinkedHashMap<>();
	private final String token = System.getenv("TRAINER_SYNC_TOKEN");

	public GitStateSync(AppProperties props, ObjectMapper mapper) {
		this.props = props;
		this.mapper = mapper;
	}

	private boolean active() {
		return props.syncRepoOrNull() != null && token != null && !token.isBlank();
	}

	private List<Path> files() {
		return List.of(Path.of(props.trainingLog()), Path.of(props.learnedModelPath()));
	}

	@PostConstruct
	void restore() {
		if (!active()) {
			return;
		}
		for (Path file : files()) {
			if (Files.exists(file)) {
				continue;
			}
			try {
				HttpResponse<byte[]> res = send(get(file).header("Accept", "application/vnd.github.raw"),
						HttpResponse.BodyHandlers.ofByteArray());
				if (res.statusCode() == 200) {
					Files.createDirectories(file.toAbsolutePath().getParent());
					Files.write(file, res.body());
					lastPushed.put(file, Files.getLastModifiedTime(file).toMillis());
					log.info("Restored {} from {}", file.getFileName(), props.syncRepoOrNull());
				}
			} catch (IOException | InterruptedException e) {
				log.warn("Could not restore {}: {}", file.getFileName(), e.getMessage());
			}
		}
	}

	@Scheduled(initialDelayString = "${trainer.sync-interval-ms:600000}", fixedDelayString = "${trainer.sync-interval-ms:600000}")
	public void push() {
		if (!active()) {
			return;
		}
		for (Path file : files()) {
			try {
				if (!Files.exists(file)) {
					continue;
				}
				long mtime = Files.getLastModifiedTime(file).toMillis();
				if (lastPushed.getOrDefault(file, -1L) == mtime) {
					continue;
				}
				String sha = currentSha(file);
				Map<String, Object> body = new LinkedHashMap<>();
				body.put("message", "sync " + file.getFileName());
				body.put("content", Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
				if (sha != null) {
					body.put("sha", sha);
				}
				HttpResponse<String> res = send(get(file)
						.method("PUT", HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))),
						HttpResponse.BodyHandlers.ofString());
				if (res.statusCode() < 300) {
					lastPushed.put(file, mtime);
				} else {
					log.warn("Sync push of {} got HTTP {}", file.getFileName(), res.statusCode());
				}
			} catch (IOException | InterruptedException e) {
				log.warn("Sync push failed for {}: {}", file.getFileName(), e.getMessage());
			}
		}
	}

	@PreDestroy
	void flush() {
		push();
	}

	private String currentSha(Path file) throws IOException, InterruptedException {
		HttpResponse<String> res = send(get(file), HttpResponse.BodyHandlers.ofString());
		if (res.statusCode() != 200) {
			return null;
		}
		return mapper.readTree(res.body()).path("sha").asString(null);
	}

	private HttpRequest.Builder get(Path file) {
		return HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + props.syncRepoOrNull()
				+ "/contents/" + file.getFileName()))
				.timeout(Duration.ofSeconds(30))
				.header("Authorization", "Bearer " + token)
				.header("X-GitHub-Api-Version", "2022-11-28");
	}

	private <T> HttpResponse<T> send(HttpRequest.Builder req, HttpResponse.BodyHandler<T> handler)
			throws IOException, InterruptedException {
		return http.send(req.build(), handler);
	}
}
