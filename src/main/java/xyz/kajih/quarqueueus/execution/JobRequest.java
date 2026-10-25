package xyz.kajih.quarqueueus.execution;

import java.util.Map;
import java.util.UUID;

/**
 * Message on the job request queue. This is execution's inbound contract.
 */
public record JobRequest(UUID jobId, String type, Map<String, String> parameters) {

    public static final String QUEUE = "jms:queue:DEV.JOBS.REQUEST";
}