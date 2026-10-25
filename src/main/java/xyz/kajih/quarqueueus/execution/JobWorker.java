package xyz.kajih.quarqueueus.execution;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.Body;
import org.apache.camel.Handler;
import org.apache.camel.Headers;

import java.util.Map;

@ApplicationScoped
public class JobWorker {
    @Handler //Optional
    public void handle(@Body JobRequest request, @Headers Map<String, Object> headers) {          // Camel converts the TextMessage body to String

        String type = request.type();

        if (type.contains("boom")) {
            throw new IllegalStateException("poison: " + type);
        }
        // real work goes here
        Log.infov("Headers: {0}", headers);
        Log.infov("Message: {0}", request);
    }
}
