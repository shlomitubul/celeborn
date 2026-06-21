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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.HttpStorageOptions;
import com.google.cloud.storage.MultipartUploadClient;
import com.google.cloud.storage.MultipartUploadSettings;
import com.google.cloud.storage.RequestBody;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.cloud.storage.multipartupload.model.*;
import com.google.common.io.ByteStreams; // Guava; on classpath via google-cloud-storage
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.celeborn.server.common.service.mpu.MultipartUploadHandler;

public class GcsMultipartUploadHandler implements MultipartUploadHandler {

  private static final Logger logger = LoggerFactory.getLogger(GcsMultipartUploadHandler.class);
  private static final int MIN_PART_SIZE = 5 * 1024 * 1024; // GCS XML MPU non-final part minimum

  private final GcsMultipartUploadHandlerSharedState sharedState;
  private final String key;
  private final List<CompletedPart> completedParts = new ArrayList<>();
  // Internal accumulation buffer. GCS XML MPU requires every NON-final part to be >= 5 MiB, but the
  // worker can deliver sub-5MiB flushes under memory pressure. We buffer here until we cross the
  // threshold so we never emit an illegal small non-final part (which would crash the job). The
  // handler owns the real GCS part numbers; the partNumber passed to putPart is ignored.
  private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
  private int nextPartNumber = 1;
  private String uploadId;

  public static class GcsMultipartUploadHandlerSharedState implements AutoCloseable {
    final MultipartUploadClient client;
    final Storage storage;
    final String bucketName;

    public GcsMultipartUploadHandlerSharedState(
        MultipartUploadClient client, Storage storage, String bucketName) {
      this.client = client;
      this.storage = storage;
      this.bucketName = bucketName;
    }

    @Override
    public void close() {
      // MultipartUploadClient has no close(); only Storage is AutoCloseable (verified 2.67.0).
      try {
        if (storage != null) storage.close();
      } catch (Exception e) {
        logger.warn("Failed to close GCS Storage client", e);
      }
    }
  }

  public GcsMultipartUploadHandler(AutoCloseable sharedState, String key) {
    this.sharedState = (GcsMultipartUploadHandlerSharedState) sharedState;
    this.key = key;
  }

  /**
   * Single entry point used by the worker via reflection. Builds both clients from one
   * HttpStorageOptions, validates the bucket is standard (not zonal/Rapid), and returns the
   * shared state. Keeps all Google SDK types inside this optional module.
   */
  public static AutoCloseable newSharedState(
      String projectId, String credentialsPath, String bucketName, boolean skipBucketCheck)
      throws IOException {
    GoogleCredentials creds =
        credentialsPath != null
            ? ServiceAccountCredentials.fromStream(Files.newInputStream(Paths.get(credentialsPath)))
            : GoogleCredentials.getApplicationDefault();
    HttpStorageOptions.Builder ob = StorageOptions.http().setCredentials(creds);
    if (projectId != null) ob.setProjectId(projectId);
    HttpStorageOptions options = ob.build();
    Storage storage = options.getService();
    if (!skipBucketCheck) {
      validateStandardBucket(storage, bucketName);
    }
    MultipartUploadClient mpu = MultipartUploadClient.create(MultipartUploadSettings.of(options));
    return new GcsMultipartUploadHandlerSharedState(mpu, storage, bucketName);
  }

  // Fail-closed: reject zonal/Rapid buckets, where the XML multipart upload backend is unsupported.
  // locationType is the GCS JSON API field; standard buckets report region/dual-region/multi-region,
  // zonal buckets report "zone". The substring match tolerates any zonal variant; the
  // celeborn.storage.gcs.skipBucketCompatibilityCheck config is the escape hatch if the value differs.
  static void validateStandardBucket(Storage storage, String bucket) {
    Bucket b = storage.get(bucket);
    if (b == null) {
      throw new IllegalStateException("GCS bucket " + bucket + " not found or not accessible.");
    }
    String locType = b.getLocationType() == null ? "" : b.getLocationType().toLowerCase();
    if (locType.contains("zon")) {
      throw new IllegalStateException(
          "GCS bucket " + bucket + " is zonal/Rapid; the XML multipart upload backend is not "
              + "supported on zonal buckets. Use a regional/multi-region bucket, or set "
              + "celeborn.storage.gcs.skipBucketCompatibilityCheck=true if this is a false positive.");
    }
  }

  @Override
  public void startUpload() {
    CreateMultipartUploadResponse resp =
        sharedState.client.createMultipartUpload(
            CreateMultipartUploadRequest.builder()
                .bucket(sharedState.bucketName)
                .key(key)
                .build());
    this.uploadId = resp.uploadId();
  }

  @Override
  public void putPart(InputStream inputStream, Integer partNumber, Boolean finalFlush)
      throws IOException {
    try (InputStream in = inputStream) {
      byte[] bytes = ByteStreams.toByteArray(in); // Guava; Java 8-safe (no readAllBytes)
      // Zero-byte short-circuit: nothing to add and nothing pending to flush.
      if (bytes.length == 0 && !(finalFlush && buffer.size() > 0)) {
        logger.debug("key {} uploadId {} skip empty part (incoming {})", key, uploadId, partNumber);
        return;
      }
      buffer.write(bytes);

      if (finalFlush) {
        // Final part has no minimum size. If nothing was ever buffered and no parts uploaded,
        // upload nothing so complete() aborts (empty-file/abort parity with S3).
        if (buffer.size() > 0) {
          uploadBufferedPart();
        }
      } else {
        // Non-final: upload only after crossing the 5 MiB minimum; otherwise keep accumulating.
        if (buffer.size() >= MIN_PART_SIZE) {
          uploadBufferedPart();
        }
      }
    } catch (RuntimeException | IOException e) {
      logger.error("Failed to upload GCS part", e);
      throw e;
    }
  }

  private void uploadBufferedPart() {
    byte[] partBytes = buffer.toByteArray();
    UploadPartRequest req =
        UploadPartRequest.builder()
            .bucket(sharedState.bucketName)
            .key(key)
            .uploadId(uploadId)
            .partNumber(nextPartNumber)
            .build();
    UploadPartResponse resp =
        sharedState.client.uploadPart(req, RequestBody.of(ByteBuffer.wrap(partBytes)));
    completedParts.add(
        CompletedPart.builder().partNumber(nextPartNumber).eTag(resp.eTag()).build());
    nextPartNumber++;
    buffer.reset();
  }

  @Override
  public void complete() {
    if (completedParts.isEmpty()) {
      logger.debug("key {} uploadId {} has no parts, aborting", key, uploadId);
      abort();
      return;
    }
    completedParts.sort(Comparator.comparingInt(CompletedPart::partNumber));
    CompletedMultipartUpload completed =
        CompletedMultipartUpload.builder().parts(completedParts).build();
    sharedState.client.completeMultipartUpload(
        CompleteMultipartUploadRequest.builder()
            .bucket(sharedState.bucketName)
            .key(key)
            .uploadId(uploadId)
            .multipartUpload(completed)
            .build());
  }

  @Override
  public void abort() {
    sharedState.client.abortMultipartUpload(
        AbortMultipartUploadRequest.builder()
            .bucket(sharedState.bucketName)
            .key(key)
            .uploadId(uploadId)
            .build());
  }

  @Override
  public void close() {}
}
