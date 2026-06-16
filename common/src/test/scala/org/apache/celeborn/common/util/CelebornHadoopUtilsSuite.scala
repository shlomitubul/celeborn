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

package org.apache.celeborn.common.util

import org.apache.celeborn.CelebornFunSuite
import org.apache.celeborn.common.CelebornConf

class CelebornHadoopUtilsSuite extends CelebornFunSuite {

  test("GCS hadoop conf sets fs.gs.impl and explicit ADC auth") {
    val conf = new CelebornConf()
    conf.set("celeborn.storage.availableTypes", "HDD,GCS")
    conf.set("celeborn.storage.gcs.dir", "gs://bucket/celeborn")
    val hadoopConf = CelebornHadoopUtils.newConfiguration(conf)
    assert(hadoopConf.get("fs.gs.impl") ==
      "com.google.cloud.hadoop.fs.gcs.GoogleHadoopFileSystem")
    assert(hadoopConf.get("fs.gs.auth.type") == "APPLICATION_DEFAULT")
  }

  test("GCS hadoop conf uses keyfile auth when credentials path is set") {
    val conf = new CelebornConf()
    conf.set("celeborn.storage.availableTypes", "HDD,GCS")
    conf.set("celeborn.storage.gcs.dir", "gs://bucket/celeborn")
    conf.set("celeborn.storage.gcs.credentials.path", "/etc/gcs/key.json")
    conf.set("celeborn.storage.gcs.project.id", "my-project")
    val hadoopConf = CelebornHadoopUtils.newConfiguration(conf)
    assert(hadoopConf.get("fs.gs.auth.type") == "SERVICE_ACCOUNT_JSON_KEYFILE")
    assert(hadoopConf.get("fs.gs.auth.service.account.json.keyfile") == "/etc/gcs/key.json")
    assert(hadoopConf.get("fs.gs.project.id") == "my-project")
  }
}
