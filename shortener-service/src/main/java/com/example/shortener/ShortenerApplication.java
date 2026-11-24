package com.example.shortener;

import com.example.shortener.api.HttpServerAdapter;
import com.example.shortener.api.ShortenerApi;
import com.example.shortener.api.TokenBucketRateLimiter;
import com.example.shortener.config.Settings;
import com.example.shortener.core.ShortenerService;
import com.example.shortener.store.InMemoryLinkRepository;
import com.example.shortener.store.JournalLinkRepository;
import com.example.shortener.store.LinkRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

/** Composition root: wires configuration, persistence, domain and transport. */
public final class ShortenerApplication {

    private static final Logger LOG = Logger.getLogger(ShortenerApplication.class.getName());

    private ShortenerApplication() {
    }

    public static HttpServerAdapter start(Settings settings, LinkRepository repository) throws IOException {
        ShortenerService service = new ShortenerService(repository, settings);
        ShortenerApi api = new ShortenerApi(service, new TokenBucketRateLimiter(settings.rateLimitPerSecond(), settings.rateLimitBurst()));
        HttpServerAdapter server = new HttpServerAdapter(api, settings.host(), settings.port(), settings.maxBodyBytes(),
                Math.max(4, Runtime.getRuntime().availableProcessors() * 2));
        server.start();
        return server;
    }

    public static void main(String[] args) throws Exception {
        Settings settings = Settings.fromEnv();
        LinkRepository repository = settings.dataFile() == null
                ? new InMemoryLinkRepository()
                : new JournalLinkRepository(Path.of(settings.dataFile()));
        HttpServerAdapter server = start(settings, repository);
        LOG.info(() -> "{\"event\":\"started\",\"listen\":\"" + settings.host() + ":" + server.port()
                + "\",\"dataFile\":\"" + settings.dataFile() + "\",\"links\":" + repository.linkCount() + "}");

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            repository.close();
            stopped.countDown();
        }, "shutdown"));
        stopped.await();
    }
}
