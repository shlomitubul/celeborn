/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.celeborn;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.util.List;

import com.google.cloud.storage.MultipartUploadClient;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.multipartupload.model.CompleteMultipartUploadRequest;
import com.google.cloud.storage.multipartupload.model.CompletedPart;
import com.google.cloud.storage.multipartupload.model.UploadPartResponse;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class GcsMultipartUploadHandlerSuiteJ {

  private GcsMultipartUploadHandler.GcsMultipartUploadHandlerSharedState state(
      MultipartUploadClient mpu) {
    Storage storage = mock(Storage.class);
    return new GcsMultipartUploadHandler.GcsMultipartUploadHandlerSharedState(
        mpu, storage, "bucket");
  }

  @Test
  public void putPartAndAbort() throws Exception {
    MultipartUploadClient client = mock(MultipartUploadClient.class);
    UploadPartResponse resp = mock(UploadPartResponse.class);
    when(resp.eTag()).thenReturn("etag-1");
    when(client.uploadPart(any(), any())).thenReturn(resp);
    when(client.createMultipartUpload(any()))
        .thenReturn(
            mock(
                com.google.cloud.storage.multipartupload.model.CreateMultipartUploadResponse.class,
                RETURNS_DEEP_STUBS));

    GcsMultipartUploadHandler handler =
        new GcsMultipartUploadHandler(state(client), "app/0/file");
    handler.startUpload();
    verify(client).createMultipartUpload(any());

    handler.putPart(new ByteArrayInputStream(new byte[6 * 1024 * 1024]), 1, false);
    verify(client).uploadPart(any(), any()); // two-arg: request + RequestBody

    handler.abort();
    verify(client).abortMultipartUpload(any());
  }

  @Test
  public void zeroBytePartIsSkipped() throws Exception {
    MultipartUploadClient client = mock(MultipartUploadClient.class, RETURNS_DEEP_STUBS);
    GcsMultipartUploadHandler handler = new GcsMultipartUploadHandler(state(client), "k");
    handler.startUpload();
    handler.putPart(new ByteArrayInputStream(new byte[0]), 1, true);
    verify(client, never()).uploadPart(any(), any());
  }

  @Test
  public void completeAccumulatesETagsInAscendingPartOrder() throws Exception {
    MultipartUploadClient client = mock(MultipartUploadClient.class, RETURNS_DEEP_STUBS);
    UploadPartResponse resp1 = mock(UploadPartResponse.class);
    UploadPartResponse resp2 = mock(UploadPartResponse.class);
    when(resp1.eTag()).thenReturn("etag-part-1");
    when(resp2.eTag()).thenReturn("etag-part-2");
    // Returned in call order: handler assigns part 1 then part 2.
    when(client.uploadPart(any(), any())).thenReturn(resp1, resp2);

    GcsMultipartUploadHandler handler =
        new GcsMultipartUploadHandler(state(client), "app/0/file");
    handler.startUpload();

    // Two LARGE non-final flushes (each >= 5 MiB) each become their own part. The handler owns
    // the part numbers, so the incoming partNumbers are ignored.
    handler.putPart(new ByteArrayInputStream(new byte[6 * 1024 * 1024]), 99, false);
    handler.putPart(new ByteArrayInputStream(new byte[6 * 1024 * 1024]), 7, false);
    verify(client, times(2)).uploadPart(any(), any());

    handler.complete();

    ArgumentCaptor<CompleteMultipartUploadRequest> captor =
        ArgumentCaptor.forClass(CompleteMultipartUploadRequest.class);
    verify(client, times(1)).completeMultipartUpload(captor.capture());
    verify(client, never()).abortMultipartUpload(any());

    List<CompletedPart> parts = captor.getValue().multipartUpload().parts();
    assertEquals(2, parts.size());
    assertEquals(1, parts.get(0).partNumber()); // ascending: handler-assigned 1, 2
    assertEquals(2, parts.get(1).partNumber());
    assertEquals("etag-part-1", parts.get(0).eTag());
    assertEquals("etag-part-2", parts.get(1).eTag());
  }

  @Test
  public void smallNonFinalFlushesAccumulateIntoOnePart() throws Exception {
    MultipartUploadClient client = mock(MultipartUploadClient.class, RETURNS_DEEP_STUBS);
    UploadPartResponse resp = mock(UploadPartResponse.class);
    when(resp.eTag()).thenReturn("etag-1");
    when(client.uploadPart(any(), any())).thenReturn(resp);

    GcsMultipartUploadHandler handler =
        new GcsMultipartUploadHandler(state(client), "app/0/file");
    handler.startUpload();

    // Three 1 MiB non-final flushes total 3 MiB (< 5 MiB) -> nothing uploaded yet.
    handler.putPart(new ByteArrayInputStream(new byte[1 * 1024 * 1024]), 1, false);
    handler.putPart(new ByteArrayInputStream(new byte[1 * 1024 * 1024]), 2, false);
    handler.putPart(new ByteArrayInputStream(new byte[1 * 1024 * 1024]), 3, false);
    verify(client, never()).uploadPart(any(), any());

    // Final flush uploads everything as a single (final) part.
    handler.putPart(new ByteArrayInputStream(new byte[0]), 4, true);
    verify(client, times(1)).uploadPart(any(), any());

    handler.complete();
    verify(client, times(1)).completeMultipartUpload(any());
    verify(client, never()).abortMultipartUpload(any());
  }

  @Test
  public void crossesThresholdUploadsThenFinalRemainder() throws Exception {
    MultipartUploadClient client = mock(MultipartUploadClient.class, RETURNS_DEEP_STUBS);
    UploadPartResponse resp1 = mock(UploadPartResponse.class);
    UploadPartResponse resp2 = mock(UploadPartResponse.class);
    when(resp1.eTag()).thenReturn("etag-part-1");
    when(resp2.eTag()).thenReturn("etag-part-2");
    when(client.uploadPart(any(), any())).thenReturn(resp1, resp2);

    GcsMultipartUploadHandler handler =
        new GcsMultipartUploadHandler(state(client), "app/0/file");
    handler.startUpload();

    // 3 MiB + 3 MiB = 6 MiB crosses 5 MiB -> exactly one non-final part uploaded.
    handler.putPart(new ByteArrayInputStream(new byte[3 * 1024 * 1024]), 1, false);
    verify(client, never()).uploadPart(any(), any());
    handler.putPart(new ByteArrayInputStream(new byte[3 * 1024 * 1024]), 2, false);
    verify(client, times(1)).uploadPart(any(), any());

    // A small final remainder becomes the second (final) part.
    handler.putPart(new ByteArrayInputStream(new byte[1024]), 3, true);
    verify(client, times(2)).uploadPart(any(), any());

    handler.complete();

    ArgumentCaptor<CompleteMultipartUploadRequest> captor =
        ArgumentCaptor.forClass(CompleteMultipartUploadRequest.class);
    verify(client, times(1)).completeMultipartUpload(captor.capture());
    List<CompletedPart> parts = captor.getValue().multipartUpload().parts();
    assertEquals(2, parts.size());
    assertEquals(1, parts.get(0).partNumber());
    assertEquals(2, parts.get(1).partNumber());
  }

  @Test
  public void completeWithNoPartsAbortsAndDoesNotComplete() throws Exception {
    MultipartUploadClient client = mock(MultipartUploadClient.class, RETURNS_DEEP_STUBS);
    GcsMultipartUploadHandler handler = new GcsMultipartUploadHandler(state(client), "k");
    handler.startUpload();

    handler.complete(); // no putPart was called

    verify(client).abortMultipartUpload(any());
    verify(client, never()).completeMultipartUpload(any());
  }
}
