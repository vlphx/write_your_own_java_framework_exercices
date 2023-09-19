package com.github.forax.framework.injector;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class InjectorRegistry {
    private final HashMap<Class<?>, Object> instanceByClassMap = new HashMap<>();
  public <T> void registerInstance(Class<T> type, T instance){
      Objects.requireNonNull(type);
      Objects.requireNonNull(instance);
      var doesExist = instanceByClassMap.putIfAbsent(type, instance);
      if (doesExist != null){
          throw new IllegalStateException("already registered for " + type.getName());
      }
  }

  public <T> T lookupInstance(Class<T> type){
      Objects.requireNonNull(type);
      var instance =  instanceByClassMap.getOrDefault(type, new IllegalStateException("instance of " + type +" does not exist: "));
      return type.cast(instance);
      /*
      var instance = instanceByClassMap.get(type);
      if (instance == null){
          throw new IllegalStateException("instance of " + type +" does not exist");
      }
      return instance;
      */
  }


}