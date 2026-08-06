// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.resources;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.ws.rs.core.Response;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

/**
 * Plain JUnit (NOT {@code @QuarkusTest}) unit test for {@link BlobExceptionMapper}'s {@code
 * RejectedExecutionException} -&gt; 503 branch: the dedicated transfer pool (see {@code
 * com.zextras.carbonio.files.config.TransferPool}) surfaces saturation (both its threads AND its
 * bounded queue full) as a {@link RejectedExecutionException} — see {@code TransferStreaming}'s
 * catch blocks and {@code BlobResource#upload}'s {@code runSubscriptionOn} — and this mapper must
 * turn that into a 503 Service Unavailable rather than the generic 500 the {@code else} branch
 * would otherwise produce, so a client sees a transient/retryable overload signal.
 *
 * <p>{@link com.zextras.carbonio.files.rest.TransferPoolSaturationIT} additionally proves this
 * end-to-end (a real download request against a saturated pool actually receives HTTP 503); this
 * test isolates just the exception -&gt; status mapping.
 */
class BlobExceptionMapperTest {

  @Test
  void rejectedExecutionExceptionMapsTo503() {
    BlobExceptionMapper mapper = new BlobExceptionMapper();

    Response response = mapper.toResponse(new RejectedExecutionException("pool saturated"));

    assertThat(response.getStatus()).isEqualTo(Response.Status.SERVICE_UNAVAILABLE.getStatusCode());
    assertThat(response.getEntity()).isEqualTo("503 Service Unavailable");
  }

  @Test
  void rejectedExecutionExceptionWrappedAsACauseAlsoMapsTo503() {
    // Some call paths (e.g. Mutiny's runSubscriptionOn on upload) may surface the rejection wrapped
    // in another throwable; the mapper unwraps exactly one getCause() level (see its class
    // javadoc).
    BlobExceptionMapper mapper = new BlobExceptionMapper();
    RuntimeException wrapper =
        new RuntimeException("subscription failed", new RejectedExecutionException("saturated"));

    Response response = mapper.toResponse(wrapper);

    assertThat(response.getStatus()).isEqualTo(Response.Status.SERVICE_UNAVAILABLE.getStatusCode());
  }
}
