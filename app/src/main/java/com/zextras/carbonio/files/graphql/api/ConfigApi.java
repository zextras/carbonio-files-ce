// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.api;

import com.zextras.carbonio.files.Constants.ServiceDiscover.Config;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.graphql.model.ConfigModel;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Query;

@GraphQLApi
@Authenticated
public class ConfigApi {

  @Inject FilesConfig filesConfig;

  @Query("getConfigs")
  public @NonNull List<ConfigModel> getConfigs() {
    String maxVersionsRaw = filesConfig.getMaxNumberOfVersionsRaw();
    int maxVersions = Integer.parseInt(maxVersionsRaw);

    String maxKeepVersions =
        maxVersions <= Config.DIFF_MAX_VERSION_AND_MAX_KEEP_VERSION
            ? "0"
            : String.valueOf(maxVersions - Config.DIFF_MAX_VERSION_AND_MAX_KEEP_VERSION);

    return List.of(
        new ConfigModel(Config.MAX_VERSIONS, maxVersionsRaw),
        new ConfigModel(
            Config.MAX_DOWNLOADABLE_SIZE_IN_MB,
            filesConfig.getMaxDownloadableFileSizeInMb().map(String::valueOf).orElse(null)),
        new ConfigModel(
            Config.MAX_UPLOADABLE_SIZE_IN_MB,
            filesConfig.getMaxUploadableFileSizeInMb().map(String::valueOf).orElse(null)),
        new ConfigModel(Config.MAX_KEEP_VERSIONS, maxKeepVersions));
  }
}
