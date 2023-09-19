package com.github.forax.framework.injector;

// import javax.swing.text.html.Option;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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

    private Optional<Constructor<?>> findInjectableConstructor(Class<?> type){
        var constructors = Arrays
                .stream(type.getConstructors())
                .filter(constructor -> constructor.isAnnotationPresent(Inject.class))
                .toList();
        return switch (constructors.size()) {
            case 0 -> Optional.empty();
            case 1 -> Optional.of(constructors.get(0));
            default -> throw new IllegalStateException("Too many injectable constructors !!");
        };
    }

    public <T> void registerProviderClass(Class<T> type, Class<? extends T> providerClass){
        Objects.requireNonNull(type);
        Objects.requireNonNull(providerClass);
        var constructor = findInjectableConstructor(providerClass).orElseGet( () ->
                Utils.defaultConstructor(providerClass));
        var injectableProperties = findInjectableProperties(providerClass);
        registerProvider(type, () -> {
            var arguments = Arrays
                    .stream(constructor.getParameterTypes())
                    .map(this::lookupInstance)
                    .toArray();
            var instance = Utils.newInstance(constructor, arguments);
            for (var injectableProperty : injectableProperties) {
                var value = lookupInstance(injectableProperty.getPropertyType());
                var setter = injectableProperty.getWriteMethod();
                Utils.invokeMethod(instance, setter, value);
            }
            return providerClass.cast(instance);
        });
    }

    public void registerProviderClass(Class<?> providerClass){
        Objects.requireNonNull(providerClass);
        registerProviderClassImplement(providerClass);
    }

    private <T> void registerProviderClassImplement(Class<T> providerClass){
        registerProviderClass(providerClass, providerClass);
    }
}