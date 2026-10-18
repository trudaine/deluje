package org.deluge.xml;

import java.util.function.BiConsumer;
import java.util.function.Function;
import org.deluge.model.SynthTrackModel;
import org.w3c.dom.Element;

/**
 * Describes how to extract a single value from an XML element and set it on a SynthTrackModel.
 *
 * @param <T> the Java type of the parsed value (String, Float, Integer, etc.)
 */
public class FieldBinding<T> {

  private final String container;
  private final String tag;
  private final BiConsumer<SynthTrackModel, T> setter;
  private final Function<String, T> converter;
  private final boolean unipolar;
  private final BindingStrategy strategy;

  @FunctionalInterface
  interface BindingStrategy {
    void apply(Element soundNode, FieldBinding<?> binding, SynthTrackModel synth);
  }

  private FieldBinding(
      String container,
      String tag,
      BiConsumer<SynthTrackModel, T> setter,
      Function<String, T> converter,
      boolean unipolar,
      BindingStrategy strategy) {
    this.container = container;
    this.tag = tag;
    this.setter = setter;
    this.converter = converter;
    this.unipolar = unipolar;
    this.strategy = strategy;
  }

  /** Apply this binding: extract value from {@code soundNode} and set on {@code synth}. */
  @SuppressWarnings("unchecked")
  public void apply(Element soundNode, SynthTrackModel synth) {
    if (strategy != null) {
      strategy.apply(soundNode, this, synth);
    } else {
      // C sound.cpp:4056-4128 & Deserializer — preset <defaultParams> writes params as attributes
      // in modern firmware and as direct child elements in older firmware; never search deeper
      // descendants.
      Element parent = soundNode;
      if (container != null && !container.isEmpty()) {
        parent = DelugeXmlUtil.directChild(soundNode, container);
        if (parent == null) return;
      }
      String raw = DelugeXmlUtil.attrOrDirectChildText(parent, tag);
      if (raw == null || raw.isBlank()) return;
      T value = converter.apply(raw.trim());
      if (value instanceof Float f && unipolar) {
        value = (T) Float.valueOf((f + 1.0f) / 2.0f);
      }
      setter.accept(synth, value);
    }
  }

  /**
   * Apply this binding directly against a given container element (e.g. a clip's {@code
   * <soundParams>}), reading the value from an ATTRIBUTE named {@code tag} first (the firmware's
   * modern attribute style), falling back to a direct child element's text (the old element style).
   */
  @SuppressWarnings("unchecked")
  public void applyTo(Element container, SynthTrackModel synth) {
    if (strategy != null) {
      return; // strategy bindings are instrument-structure bindings, not param-table entries
    }
    String raw = DelugeXmlUtil.attrOrDirectChildText(container, tag);
    if (raw == null || raw.isBlank()) return;
    T value = converter.apply(raw.trim());
    if (value instanceof Float f && unipolar) {
      value = (T) Float.valueOf((f + 1.0f) / 2.0f);
    }
    setter.accept(synth, value);
  }

  // ── Accessors needed by custom strategies ──

  String tag() {
    return tag;
  }

  BiConsumer<SynthTrackModel, T> setter() {
    return setter;
  }

  Function<String, T> converter() {
    return converter;
  }

  // ── Factory methods ──

  /** Direct child element, hex-encoded float, unipolar (abs). */
  public static FieldBinding<Float> hexFloat(
      String tag, BiConsumer<SynthTrackModel, Float> setter) {
    return hexFloat(null, tag, setter);
  }

  /** Scoped child element, hex-encoded float, unipolar (abs). */
  public static FieldBinding<Float> hexFloat(
      String container, String tag, BiConsumer<SynthTrackModel, Float> setter) {
    return new FieldBinding<>(
        container, tag, setter, s -> DelugeHexMapper.hexToFloat(s), true, null);
  }

  /** Scoped child element, hex-encoded float, bipolar. */
  public static FieldBinding<Float> hexFloatBipolar(
      String container, String tag, BiConsumer<SynthTrackModel, Float> setter) {
    return new FieldBinding<>(
        container, tag, setter, s -> DelugeHexMapper.hexToFloat(s), false, null);
  }

  /** Scoped child element, hex-encoded frequency value. */
  public static FieldBinding<Float> hexHz(
      String container, String tag, BiConsumer<SynthTrackModel, Float> setter) {
    return new FieldBinding<>(container, tag, setter, s -> DelugeHexMapper.hexToHz(s), false, null);
  }

  /** Direct child element, string value with {@code String::toUpperCase} transform. */
  public static FieldBinding<String> upper(String tag, BiConsumer<SynthTrackModel, String> setter) {
    return new FieldBinding<>(null, tag, setter, String::toUpperCase, false, null);
  }

  /** Attribute of a direct child element. */
  @SuppressWarnings("unchecked")
  public static <T> FieldBinding<T> attr(
      String childTag,
      String attrName,
      BiConsumer<SynthTrackModel, T> setter,
      Function<String, T> converter) {
    return new FieldBinding<>(
        null,
        childTag,
        setter,
        converter,
        false,
        (soundNode, binding, synth) -> {
          Element child = DelugeXmlUtil.directChild(soundNode, binding.tag());
          if (child == null || !child.hasAttribute(attrName)) return;
          String raw = child.getAttribute(attrName);
          if (raw == null || raw.isBlank()) return;
          ((BiConsumer<SynthTrackModel, T>) binding.setter())
              .accept(synth, (T) binding.converter().apply(raw.trim()));
        });
  }

  /** Attribute OR child element for a direct child of soundNode. Tries attribute first. */
  @SuppressWarnings("unchecked")
  public static FieldBinding<String> attrOrChild(
      String childTag,
      String attrName,
      BiConsumer<SynthTrackModel, String> setter,
      Function<String, String> transform) {
    return new FieldBinding<>(
        null,
        childTag,
        setter,
        transform,
        false,
        (soundNode, binding, synth) -> {
          Element child = DelugeXmlUtil.directChild(soundNode, binding.tag());
          if (child == null) return;
          String raw = DelugeXmlUtil.attrOrDirectChildText(child, attrName);
          if (raw == null || raw.isBlank()) return;
          ((BiConsumer<SynthTrackModel, String>) binding.setter())
              .accept(synth, transform.apply(raw.trim()));
        });
  }

  /**
   * Reads text content from a named direct child element of a direct container element. E.g. {@code
   * childText("osc2", "type", setter, toUpper)} reads {@code <osc2><type>TEXT</type></osc2>}.
   */
  @SuppressWarnings("unchecked")
  public static FieldBinding<String> childText(
      String containerTag,
      String childTag,
      BiConsumer<SynthTrackModel, String> setter,
      Function<String, String> transform) {
    return new FieldBinding<>(
        null,
        containerTag,
        setter,
        transform,
        false,
        (soundNode, binding, synth) -> {
          Element container = DelugeXmlUtil.directChild(soundNode, binding.tag());
          if (container == null) return;
          Element child = DelugeXmlUtil.directChild(container, childTag);
          if (child == null) return;
          String raw = child.getTextContent();
          if (raw == null || raw.isBlank()) return;
          ((BiConsumer<SynthTrackModel, String>) binding.setter())
              .accept(synth, transform.apply(raw.trim()));
        });
  }

  /** Direct child element, integer value from text content. */
  public static FieldBinding<Integer> integer(
      String tag, BiConsumer<SynthTrackModel, Integer> setter) {
    return new FieldBinding<>(
        null, tag, (s, v) -> setter.accept(s, v), Integer::parseInt, false, null);
  }
}
