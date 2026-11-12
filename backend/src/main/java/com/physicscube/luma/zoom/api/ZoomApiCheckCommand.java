package com.physicscube.luma.zoom.api;

import com.physicscube.luma.zoom.ZoomWebhookProperties;
import java.io.PrintStream;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntConsumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Operator check of the Zoom app (scripts/zoom-api-check.sh): obtains a token and reads one
 * webinar on the test allow-list, printing only facts about the webinar and counts. Names
 * and emails of registrants and participants are never printed.
 */
@Component
@ConditionalOnProperty("luma.zoom-api-check.webinar-id")
class ZoomApiCheckCommand implements ApplicationRunner {
    private final ObjectProvider<ZoomApiClient> client;
    private final ZoomWebhookProperties webhook;
    private final String webinarId;
    private final PrintStream out;
    private final IntConsumer exit;

    @Autowired
    ZoomApiCheckCommand(ObjectProvider<ZoomApiClient> client, ZoomWebhookProperties webhook, ConfigurableApplicationContext context,
                        @Value("${luma.zoom-api-check.webinar-id}") String webinarId) {
        this(client, webhook, webinarId, System.out, code -> System.exit(SpringApplication.exit(context, () -> code)));
    }

    ZoomApiCheckCommand(ObjectProvider<ZoomApiClient> client, ZoomWebhookProperties webhook, String webinarId, PrintStream out,
                        IntConsumer exit) {
        this.client = client;
        this.webhook = webhook;
        this.webinarId = webinarId == null ? "" : webinarId.replaceAll("[\\s-]", "");
        this.out = out;
        this.exit = exit;
    }

    @Override
    public void run(ApplicationArguments args) {
        exit.accept(execute());
    }

    int execute() {
        ZoomApiClient zoom = client.getIfAvailable();
        if (zoom == null) {
            out.println("Zoom API check refused: LUMA_ZOOM_API_ACCOUNT_ID, _CLIENT_ID and _CLIENT_SECRET are not set.");
            return 2;
        }
        if (!webhook.webinarAllowList().contains(webinarId)) {
            out.println("Zoom API check refused: webinar " + webinarId + " is not in LUMA_ZOOM_WEBHOOK_WEBINAR_ALLOW_LIST. "
                    + "Only test webinars are read.");
            return 2;
        }
        try {
            out.println("Token: obtained. Scopes granted: " + zoom.grantedScopes());
            ZoomApiClient.Webinar webinar = zoom.webinar(webinarId);
            out.println("Webinar " + webinar.id() + ": \"" + webinar.topic() + "\", type " + webinar.type() + ", starts "
                    + webinar.startTime() + " (" + webinar.timezone() + "), " + webinar.duration() + " min, occurrences "
                    + webinar.occurrences().size() + ", approval type " + webinar.approvalType() + ", registration type "
                    + webinar.registrationType());
            out.println("Registrants: " + zoom.registrantCount(webinarId, "approved") + " approved, "
                    + zoom.registrantCount(webinarId, "pending") + " pending");
            List<ZoomApiClient.PastInstance> instances;
            try {
                instances = zoom.pastInstances(webinarId);
            } catch (ZoomApiException e) {
                if (!e.notFound()) {
                    throw e;
                }
                instances = List.of();
            }
            out.println("Past instances: " + instances.size());
            if (!instances.isEmpty()) {
                ZoomApiClient.PastInstance latest = instances.stream()
                        .max(Comparator.comparing(i -> i.startTime() == null ? java.time.Instant.EPOCH : i.startTime())).orElseThrow();
                List<ZoomApiClient.Participant> sessions = zoom.pastParticipants(latest.uuid());
                long people = sessions.stream().map(p -> p.userEmail() != null ? p.userEmail() : p.name()).distinct().count();
                out.println("Latest instance " + latest.startTime() + ": " + sessions.size() + " join sessions by " + people
                        + " people (identities not shown)");
            }
            out.println("Zoom API check passed.");
            return 0;
        } catch (ZoomApiException e) {
            out.println("Zoom API check failed: " + e.getMessage());
            return 1;
        }
    }
}
