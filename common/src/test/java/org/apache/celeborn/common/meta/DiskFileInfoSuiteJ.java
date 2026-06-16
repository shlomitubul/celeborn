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

package org.apache.celeborn.common.meta;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import org.apache.celeborn.common.protocol.StorageInfo;

public class DiskFileInfoSuiteJ {

  @Test
  public void testIsGcs() {
    DiskFileInfo info =
        new DiskFileInfo(
            new org.apache.celeborn.common.identity.UserIdentifier("a", "b"),
            true,
            new org.apache.celeborn.common.meta.ReduceFileMeta(8 * 1024 * 1024),
            "gs://bucket/p/app/0/file",
            StorageInfo.Type.GCS);
    assertTrue(info.isGCS());
    assertTrue(info.isDFS());
    assertFalse(info.isS3());
  }
}
