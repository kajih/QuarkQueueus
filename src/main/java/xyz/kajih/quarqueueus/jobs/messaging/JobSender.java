package xyz.kajih.quarqueueus.jobs.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import org.apache.camel.ProducerTemplate;
import xyz.kajih.jexapi.model.CreateJobRequest;
import xyz.kajih.jexapi.model.Job;
import xyz.kajih.jexapi.model.JobStatus;

import java.util.UUID;

public class JobSender {

    @Inject
    ProducerTemplate producer;

    @Inject
    ObjectMapper mapper;   // Quarkus' Jackson ObjectMapper (quarkus-rest-jackson)

    public Job createJob(CreateJobRequest request) {
        var job = new Job().id(UUID.randomUUID()).status(JobStatus.QUEUED) /* ... */;
        try {
            producer.sendBodyAndHeader("jms:queue:DEV.JOBS.REQUEST",
                    mapper.writeValueAsString(request),   // String body → JMS TextMessage
                    "JobId", job.getId().toString());     // becomes a JMS string property
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
        return job;
    }
}
