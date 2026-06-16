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

package org.apache.celeborn.common.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StorageInfoSuiteJ {

  @Test
  public void testGcsStorageType() {
    assertEquals(6, StorageInfo.Type.GCS.getValue());
    assertEquals(0b100000, StorageInfo.GCS_MASK);
    assertNotEquals(StorageInfo.S3_MASK, StorageInfo.GCS_MASK); // guard: distinct bits
    assertTrue(StorageInfo.GCSAvailable(StorageInfo.GCS_MASK));
    // ALL_TYPES_AVAILABLE_MASK is the sentinel 0; helpers short-circuit on `== 0`, so this
    // already returns true with NO change to ALL_TYPES_AVAILABLE_MASK (it is not an OR of masks).
    assertTrue(StorageInfo.GCSAvailable(StorageInfo.ALL_TYPES_AVAILABLE_MASK));
    assertTrue(StorageInfo.GCSOnly(StorageInfo.GCS_MASK));
    assertFalse(StorageInfo.GCSOnly(StorageInfo.S3_MASK));
    assertEquals(
        StorageInfo.GCS_MASK,
        StorageInfo.getAvailableTypes(java.util.Collections.singletonList(StorageInfo.Type.GCS)));
  }
}
