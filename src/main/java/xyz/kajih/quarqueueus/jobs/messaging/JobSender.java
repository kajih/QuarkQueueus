package xyz.kajih.quarqueueus.jobs.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.ProducerTemplate;
import xyz.kajih.quarqueueus.execution.JobRequest;

@ApplicationScoped
public class JobSender {

    @Inject
    ProducerTemplate producer;

    @Inject
    ObjectMapper mapper;   // Quarkus' Jackson ObjectMapper (quarkus-rest-jackson)

    public void send(JobRequest request) {
        try {
            producer.sendBodyAndHeader(JobRequest.QUEUE,
                    mapper.writeValueAsString(request),
                    "JobId", request.jobId().toString());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
