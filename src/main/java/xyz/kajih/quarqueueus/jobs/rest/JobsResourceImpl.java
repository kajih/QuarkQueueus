package xyz.kajih.quarqueueus.jobs.rest;

import xyz.kajih.jexapi.model.CreateJobRequest;
import xyz.kajih.jexapi.model.Job;
import xyz.kajih.jexapi.model.JobResult;
import xyz.kajih.jexapi.model.JobStatus;
import xyz.kajih.jexapi.resources.JobsResource;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public class JobsResourceImpl implements JobsResource {
    @Override
    public Job createJob(CreateJobRequest createJobRequest) {
        return null;
    }

    @Override
    public Job getJob(UUID jobId) {
        return null;
    }

    @Override
    public JobResult getJobResult(UUID jobId) {
        return null;
    }

    @Override
    public List<Job> listJobs(JobStatus status) {
        var job = new Job()
                .id(UUID.fromString("2D2fB03E-9982-Cba3-38Bf-99B825CA04D1"))
                .type("string")
                .status(JobStatus.QUEUED)
                .createdAt(OffsetDateTime.parse("2022-03-10T12:15:50-04:00"))
                .finishedAt(OffsetDateTime.parse("2022-03-10T12:15:50-04:00"))
                .progress(0);
        return List.of(job);
    }
}
