package com.github.forax.framework.mapper;

public final class JSONWriter {
  public String toJSON(Object o) {
    return switch (o){
      case String s -> '"' + s + '"';
      case null -> "null";
      case Boolean bool ->  bool.toString();
      case Integer integer -> integer.toString();
      case Double d -> d.toString();
      default -> throw new IllegalArgumentException("Unknown object :(" + o);
    };

  }
}
