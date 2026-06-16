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

package org.apache.celeborn.tests.spark

import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import org.apache.celeborn.client.ShuffleClient
import org.apache.celeborn.common.CelebornConf
import org.apache.celeborn.common.protocol.ShuffleMode

/**
 * Gated GCS tiered-storage integration test. Mirrors [[S3TieredStorageTest]] but offloads shuffle
 * data to a real Google Cloud Storage bucket.
 *
 * This test is SKIPPED unless a real bucket is provided via the system property
 * `celeborn.gcs.it.bucket` (e.g. `-Dceleborn.gcs.it.bucket=gs://my-bucket/it`). There is no GCS
 * emulator equivalent of MinIO in this suite, so without a real bucket (and Application Default
 * Credentials), the test aborts via `assume(...)` rather than failing. The mini cluster is only
 * started when the bucket is configured, so a default run incurs no setup cost.
 */
class GcsTieredStorageTest extends AnyFunSuite
  with SparkTestBase
  with BeforeAndAfterEach {

  private val gcsBucketDir = System.getProperty("celeborn.gcs.it.bucket")
  private def gcsConfigured: Boolean = gcsBucketDir != null && gcsBucketDir.startsWith("gs://")

  override def beforeAll(): Unit = {
    // Do not spin up the mini cluster unless a real GCS bucket is configured; the test itself
    // aborts via assume(...) below when it is not.
    if (!gcsConfigured)
      return

    val augmentedConfiguration = Map(
      // Keep local disk as a fast tier and offload to GCS under disk pressure.
      CelebornConf.ACTIVE_STORAGE_TYPES.key -> "MEMORY,HDD,GCS",
      // Force files to be created on and evicted to GCS so the test exercises the GCS path.
      CelebornConf.WORKER_STORAGE_CREATE_FILE_POLICY.key -> "GCS",
      CelebornConf.WORKER_STORAGE_EVICT_POLICY.key -> "MEMORY,HDD|HDD,GCS",
      CelebornConf.GCS_DIR.key -> gcsBucketDir)

    setupMiniClusterWithRandomPorts(
      masterConf = augmentedConfiguration,
      workerConf = augmentedConfiguration,
      workerNum = 1)
  }

  override def beforeEach(): Unit = {
    ShuffleClient.reset()
  }

  override def afterAll(): Unit = {
    if (gcsConfigured) {
      super.afterAll()
    }
  }

  override def updateSparkConf(sparkConf: SparkConf, mode: ShuffleMode): SparkConf = {
    val newConf = sparkConf
      .set("spark." + CelebornConf.ACTIVE_STORAGE_TYPES.key, "MEMORY,HDD,GCS")
      .set("spark." + CelebornConf.GCS_DIR.key, gcsBucketDir)
    super.updateSparkConf(newConf, mode)
  }

  test("celeborn spark integration test - gcs") {
    assume(
      gcsConfigured,
      "Skipping test because no GCS bucket is configured; " +
        "set -Dceleborn.gcs.it.bucket=gs://<bucket>/<path> (with ADC available) to run this test")

    val sparkConf = new SparkConf().setAppName("celeborn-demo").setMaster("local[2]")
    val celebornSparkSession = SparkSession.builder()
      .config(updateSparkConf(sparkConf, ShuffleMode.HASH))
      .getOrCreate()

    // execute multiple operations that reserve slots and force eviction to GCS
    repartition(celebornSparkSession)
    groupBy(celebornSparkSession)

    celebornSparkSession.stop()
  }

}
