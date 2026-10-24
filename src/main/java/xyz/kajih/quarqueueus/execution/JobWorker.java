package xyz.kajih.quarqueueus.execution;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class JobWorker {
    public void handle(String body) {          // Camel converts the TextMessage body to String
        if (body.contains("boom")) {
            throw new IllegalStateException("poison: " + body);
        }
        // real work goes here
        Log.info(body);
    }
}
