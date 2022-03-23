// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Optional;

public class PreviewQueryParameters {

  @JsonProperty("quality")
  private Quality quality;

  @JsonProperty("output_format")
  private Format outputFormat;

  @JsonProperty("crop")
  private Boolean crop;

  @JsonProperty("shape")
  private Shape shape;

  @JsonProperty("first_page")
  private Integer firstPage;

  @JsonProperty("last_page")
  private Integer lastPage;

  public Optional<String> getQuality() {
    return Optional.ofNullable(
      quality == null
        ? null
        : quality.name()
    );
  }

  public Optional<String> getOutputFormat() {
    return Optional.ofNullable(
      outputFormat == null
        ? null
        : outputFormat.name()
    );
  }

  public Optional<Boolean> getCrop() {
    return Optional.ofNullable(crop);
  }

  public Optional<String> getShape() {
    return Optional.ofNullable(
      shape == null
        ? null
        : shape.name()
    );
  }

  public Optional<Integer> getFirstPage() {
    return Optional.ofNullable(firstPage);
  }

  public Optional<Integer> getLastPage() {
    return Optional.ofNullable(lastPage);
  }

  private enum Quality {
    @JsonProperty("lowest")
    LOWEST,

    @JsonProperty("low")
    LOW,

    @JsonProperty("medium")
    MEDIUM,

    @JsonProperty("high")
    HIGH,

    @JsonProperty("highest")
    HIGHEST
  }

  private enum Format {
    @JsonProperty("jpeg")
    JPEG,

    @JsonProperty("png")
    PNG
  }

  private enum Shape {
    @JsonProperty("rounded")
    ROUNDED,

    @JsonProperty("rectangular")
    RECTANGULAR
  }
}
