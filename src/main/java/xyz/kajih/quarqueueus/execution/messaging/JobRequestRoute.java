package xyz.kajih.quarqueueus.execution.messaging;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;
import xyz.kajih.quarqueueus.execution.JobWorker;

@ApplicationScoped
public class JobRequestRoute extends RouteBuilder {

    @Inject
    JobWorker worker;

    @Override
    public void configure() {
        from("jms:queue:DEV.JOBS.REQUEST")
                .routeId("job-request")
                .log("Job request ${header.JMSMessageID} (delivery ${header.JMSXDeliveryCount}): ${body}")
                .bean(JobWorker.class, "handle");
    }
}
