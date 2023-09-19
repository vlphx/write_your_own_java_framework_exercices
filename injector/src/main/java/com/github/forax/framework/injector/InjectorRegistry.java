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
              .getOrDefault(type, () -> {
                  throw new IllegalStateException("instance of " + type + " does not exist: ");
              });
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

    public <T> void registerProviderClass(Class<T> type, Class<? extends T> providerClass){
        Objects.requireNonNull(type);
        Objects.requireNonNull(providerClass);
        var constructor = Utils.defaultConstructor(providerClass);
        var injectableProperties = findInjectableProperties(providerClass);
        registerProvider(type, () -> {
            var instance = Utils.newInstance(constructor);
            for (var injectableProperty : injectableProperties) {
                var value = lookupInstance(injectableProperty.getPropertyType());
                var setter = injectableProperty.getWriteMethod();
                Utils.invokeMethod(instance, setter, value);
            }
            return instance;
        });
    }
}