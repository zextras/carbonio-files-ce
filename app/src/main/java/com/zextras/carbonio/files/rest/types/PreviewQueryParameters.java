// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Locale;
import java.util.Optional;

/**
 * Quarkus port of the legacy (core) {@code PreviewQueryParameters}. The legacy Netty {@code
 * PreviewController} built this via a hand-rolled query-string parser + {@code
 * ObjectMapper#convertValue}; {@link com.zextras.carbonio.files.rest.resources.PreviewResource}
 * instead binds each field directly from a JAX-RS {@code @QueryParam}, so this class gains public
 * string/boolean/integer setters (the legacy class only exposed {@code setLangTag}/{@code
 * setNodeVersion}, relying on Jackson for the rest). Field semantics and the downstream consumer
 * ({@code PreviewService#generateQuery}) are unchanged.
 */
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

  @JsonProperty("lang_tag")
  private String langTag;

  @JsonProperty("version")
  private Integer nodeVersion;

  public Optional<String> getQuality() {
    return Optional.ofNullable(quality == null ? null : quality.name());
  }

  public void setQuality(String quality) {
    this.quality = quality == null ? null : Quality.valueOf(quality.toUpperCase(Locale.ROOT));
  }

  public Optional<String> getOutputFormat() {
    return Optional.ofNullable(outputFormat == null ? null : outputFormat.name());
  }

  public void setOutputFormat(String outputFormat) {
    this.outputFormat =
        outputFormat == null ? null : Format.valueOf(outputFormat.toUpperCase(Locale.ROOT));
  }

  public Optional<Boolean> getCrop() {
    return Optional.ofNullable(crop);
  }

  public void setCrop(Boolean crop) {
    this.crop = crop;
  }

  public Optional<String> getShape() {
    return Optional.ofNullable(shape == null ? null : shape.name());
  }

  public void setShape(String shape) {
    this.shape = shape == null ? null : Shape.valueOf(shape.toUpperCase(Locale.ROOT));
  }

  public Optional<Integer> getFirstPage() {
    return Optional.ofNullable(firstPage);
  }

  public void setFirstPage(Integer firstPage) {
    this.firstPage = firstPage;
  }

  public Optional<Integer> getLastPage() {
    return Optional.ofNullable(lastPage);
  }

  public void setLastPage(Integer lastPage) {
    this.lastPage = lastPage;
  }

  public Optional<String> getLangTag() {
    return Optional.ofNullable(langTag);
  }

  public void setLangTag(String langTag) {
    this.langTag = langTag;
  }

  public Optional<Integer> getNodeVersion() {
    return Optional.ofNullable(nodeVersion);
  }

  public void setNodeVersion(Integer nodeVersion) {
    this.nodeVersion = nodeVersion;
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
    @JsonProperty("gif")
    GIF,

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
