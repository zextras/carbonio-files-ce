// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class QueryCacheSizeTest {

  private static final String QUERY_CACHE_SIZE =
      "-Dio.smallrye.graphql.execution.queryCacheMaxSize=64";

  @Test
  void everyLauncherBoundsTheSmallRyeQueryCache() throws Exception {
    assertThat(Files.readString(Path.of("..", "package", "carbonio-files")))
        .contains(QUERY_CACHE_SIZE);
    assertThat(Files.readString(Path.of("..", "docker", "Dockerfile"))).contains(QUERY_CACHE_SIZE);
  }
}
