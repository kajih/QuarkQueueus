package xyz.kajih.quarqueueus.execution.messaging;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;
import xyz.kajih.quarqueueus.execution.JobRequest;
import xyz.kajih.quarqueueus.execution.JobWorker;

@ApplicationScoped
public class JobRequestRoute extends RouteBuilder {
    @Override
    public void configure() {

        from(JobRequest.QUEUE)
                .routeId("job-request")
                .log("Job request ${header.JobId} (delivery ${header.JMSXDeliveryCount})")
                .unmarshal().json(JobRequest.class)
                .bean(JobWorker.class, "handle");
    }
}