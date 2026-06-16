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

  @Test(expected = java.io.IOException.class)
  public void nonFinalPartBelow5MiBIsRejected() throws Exception {
    MultipartUploadClient client = mock(MultipartUploadClient.class, RETURNS_DEEP_STUBS);
    GcsMultipartUploadHandler handler = new GcsMultipartUploadHandler(state(client), "k");
    handler.startUpload();
    handler.putPart(new ByteArrayInputStream(new byte[1024]), 1, false); // < 5 MiB, not final
  }

  @Test
  public void completeAccumulatesETagsInAscendingPartOrder() throws Exception {
    MultipartUploadClient client = mock(MultipartUploadClient.class, RETURNS_DEEP_STUBS);
    UploadPartResponse resp1 = mock(UploadPartResponse.class);
    UploadPartResponse resp2 = mock(UploadPartResponse.class);
    when(resp1.eTag()).thenReturn("etag-part-2");
    when(resp2.eTag()).thenReturn("etag-part-1");
    // Returned in call order: first putPart (part 2) gets resp1, second (part 1) gets resp2.
    when(client.uploadPart(any(), any())).thenReturn(resp1, resp2);

    GcsMultipartUploadHandler handler =
        new GcsMultipartUploadHandler(state(client), "app/0/file");
    handler.startUpload();

    // Put parts OUT OF ORDER; both non-final 6 MiB buffers pass the 5 MiB guard.
    handler.putPart(new ByteArrayInputStream(new byte[6 * 1024 * 1024]), 2, false);
    handler.putPart(new ByteArrayInputStream(new byte[6 * 1024 * 1024]), 1, false);

    handler.complete();

    ArgumentCaptor<CompleteMultipartUploadRequest> captor =
        ArgumentCaptor.forClass(CompleteMultipartUploadRequest.class);
    verify(client, times(1)).completeMultipartUpload(captor.capture());
    verify(client, never()).abortMultipartUpload(any());

    List<CompletedPart> parts = captor.getValue().multipartUpload().parts();
    assertEquals(2, parts.size());
    assertEquals(1, parts.get(0).partNumber()); // part 1 before part 2
    assertEquals(2, parts.get(1).partNumber());
    assertEquals("etag-part-1", parts.get(0).eTag());
    assertEquals("etag-part-2", parts.get(1).eTag());
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
