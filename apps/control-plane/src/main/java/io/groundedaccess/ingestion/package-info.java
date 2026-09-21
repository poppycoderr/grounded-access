/**
 * The ingestion job queue: requests are stored, answered with a job id, and run by a worker that claims jobs with {@code SKIP LOCKED}.
 */
@NullMarked
package io.groundedaccess.ingestion;

import org.jspecify.annotations.NullMarked;
