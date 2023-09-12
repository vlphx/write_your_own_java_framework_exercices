package com.github.forax.framework.mapper;

import java.beans.PropertyDescriptor;
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

  private static final ClassValue<PropertyDescriptor[]> CACHE = new ClassValue<PropertyDescriptor[]>() {
    @Override
    protected PropertyDescriptor[] computeValue(Class<?> type) {
      var beanInfo = Utils.beanInfo(type);
      return beanInfo.getPropertyDescriptors();
    }
  };

  private String toJSONBean(Object o) {
    return Arrays
            .stream(CACHE.get((o.getClass())))
            .filter(prop -> !prop.getName().equals("class"))
            .map(prop -> toJSON(prop.getName()) + ": " + toJSON(Utils.invokeMethod(o, prop.getReadMethod())))
            .collect(Collectors.joining(", ", "{", "}" ));

  }
}
