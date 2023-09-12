package com.github.forax.framework.mapper;

import java.util.Arrays;
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

  private String toJSONBean(Object o) {
    var beanInfo = Utils.beanInfo(o.getClass());
    return Arrays
            .stream(beanInfo.getPropertyDescriptors())
            .filter(prop -> !prop.getName().equals("class"))
            .map(prop -> toJSON(prop.getName()) + ": " + toJSON(Utils.invokeMethod(o, prop.getReadMethod())))
            .collect(Collectors.joining(", ", "{", "}" ));

  }
}
