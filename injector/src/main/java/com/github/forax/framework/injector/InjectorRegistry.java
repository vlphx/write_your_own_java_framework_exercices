package com.github.forax.framework.injector;

import java.beans.PropertyDescriptor;
import java.util.*;
import java.util.function.Supplier;

public final class InjectorRegistry {
    private final HashMap<Class<?>, Supplier<?>> registry = new HashMap<>();

    public <T> void registerInstance(Class<T> type, T instance){
      Objects.requireNonNull(type);
      Objects.requireNonNull(instance);
      registerProvider(type, () -> instance);
    }

  public <T> T lookupInstance(Class<T> type){
      Objects.requireNonNull(type);
      var supplier =  registry
              .getOrDefault(type, () -> new IllegalStateException("instance of " + type +" does not exist: "));
      return type.cast(supplier.get());
      /*
      var instance = instanceByClassMap.get(type);
      if (instance == null){
          throw new IllegalStateException("instance of " + type +" does not exist");
      }
      return instance;
      */
  }

  public <T> void registerProvider(Class<T> type, Supplier<T> supplier){
    Objects.requireNonNull(type);
    Objects.requireNonNull(supplier);
      var doesExist = registry.putIfAbsent(type, supplier);
      if (doesExist != null){
          throw new IllegalStateException("already registered for " + type.getName());
      }
  }

  static List<PropertyDescriptor> findInjectableProperties(Class<?> type) {
        var beanInfoOfType = Utils.beanInfo(type);
      return  Arrays
              .stream(beanInfoOfType.getPropertyDescriptors())
              .filter(prop -> {
                  var setter = prop.getWriteMethod();
                  return setter != null && setter.isAnnotationPresent(Inject.class);
              })
              .toList();
    }
  
}