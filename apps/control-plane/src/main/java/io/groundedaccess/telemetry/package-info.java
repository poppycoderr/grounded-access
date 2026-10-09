/**
 * Request correlation and tracing: the trace id of a request, the spans of the query pipeline, and the allow-list of span attributes that is
 * enforced before a span is exported. Query text, chunk text and titles never enter logs, traces or audit rows.
 */
@NullMarked
package io.groundedaccess.telemetry;

import org.jspecify.annotations.NullMarked;
