package com.github.forax.framework.mapper;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public final class JSONWriter {
  public String toJSON(Object o) {
    return switch (o){
      case String s -> '"' + s + '"';
      case null -> "null";
      case Boolean bool ->  bool.toString();
      case Integer integer -> integer.toString();
      case Double d -> d.toString();
      default -> toJSONBean(o);
      // default -> throw new IllegalArgumentException("Unknown object :(" + o);
    };

  }

  @FunctionalInterface
  private interface Generator {
    String generate(JSONWriter writer, Object bean);
  }

  private static final ClassValue<List<Generator>> CACHE = new ClassValue<>() {
      @Override
      protected List<Generator> computeValue(Class<?> type) {
        var beanInfo = Utils.beanInfo(type);
        return Arrays
                .stream(beanInfo.getPropertyDescriptors())
                .filter(prop -> !prop.getName().equals("class"))
                .<Generator>map(prop -> {
                  var readMethod = prop.getReadMethod();
                  var annotation = readMethod.getAnnotation(JSONProperty.class);
                  var keyPrefix = "\"";
                  var keySuffix = "\": ";
                  var propertyName = annotation == null
                          ? prop.getName()
                          : annotation.value();
                  var key = keyPrefix + propertyName + keySuffix;
                  return (writer, o) -> key + writer.toJSON(Utils.invokeMethod(o, readMethod));
                })
                .toList();
      }
  };

  private String toJSONBean(Object o) {
    return CACHE
            .get((o.getClass()))
            .stream()
            .map(generator -> generator.generate(this, o))
            .collect(Collectors.joining(", ", "{", "}" ));
  }
}
