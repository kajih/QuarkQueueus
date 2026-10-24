package xyz.kajih.quarqueueus.dev;

import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.spi.CamelEvent;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@IfBuildProfile("dev")
@ApplicationScoped
public class SeedJobSender {

    @Inject
    ProducerTemplate producer;

    void onCamelStarted(@Observes CamelEvent.CamelContextStartedEvent event) {
        producer.sendBodyAndHeader("jms:queue:DEV.JOBS.REQUEST",
                "{\"type\":\"report-generation\",\"parameters\":{}}",
                "JobId", java.util.UUID.randomUUID().toString());
        Log.info("Seed job sent");
    }
}