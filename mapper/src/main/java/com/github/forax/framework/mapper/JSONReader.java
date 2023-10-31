package com.github.forax.framework.mapper;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class JSONReader {
    private record BeanData(Constructor<?> constructor, Map<String, PropertyDescriptor> propertyMap) {
        PropertyDescriptor findProperty(String key) {
            var property = propertyMap.get(key);
            if (property == null) {
                throw new IllegalStateException("unknown key " + key + " for bean " + constructor.getDeclaringClass().getName());
            }
            return property;
        }
    }

    private static final ClassValue<BeanData> BEAN_DATA_CLASS_VALUE = new ClassValue<>() {
        @Override
        protected BeanData computeValue(Class<?> type) {
            // throw new UnsupportedOperationException("TODO");
            var beanInfo = Utils.beanInfo(type);
            var map = Arrays.stream(beanInfo.getPropertyDescriptors())
                    .filter(property -> !property.getName().equals("class"))
                    .collect(Collectors.toMap(PropertyDescriptor::getName, Function.identity())); // Function.identity() is the same as property -> property
            var constructor = Utils.defaultConstructor(type);
            return new BeanData(constructor, map);
        }
    };

    public record ObjectBuilder<T>(Function<? super String, ? extends Type> typeProvider,
                                   Supplier<? extends T> supplier,
                                   Populater<? super T> populater,
                                   Function<? super T, ?> finisher) {
        public interface Populater<T> {
            void populate(T instance, String key, Object value);
        }

        public static ObjectBuilder<Object> bean(Class<?> beanClass) {
            Objects.requireNonNull(beanClass);
            var beanData = BEAN_DATA_CLASS_VALUE.get(beanClass);
            var constructor = beanData.constructor;
            return new ObjectBuilder<>(
                    key -> beanData.findProperty(key).getWriteMethod().getGenericParameterTypes()[0],
                    () -> Utils.newInstance(constructor),
                    (instance, key, value) -> {
                        var setter = beanData.findProperty(key).getWriteMethod();
                        Utils.invokeMethod(instance, setter, value);
                    },
                    Function.identity()
            );
        }
    }


    private record Context(ObjectBuilder<Object> objectBuilder, Object result) {
    }

    public <T> T parseJSON(String text, Class<T> beanClass) {
        return beanClass.cast(parseJSON(text, (Type) beanClass));
    }

    public Object parseJSON(String text, Type expectedType) {
        Objects.requireNonNull(text);
        Objects.requireNonNull(expectedType);

        var stack = new ArrayDeque<Context>(); // anonymous class below can capture this variable (all local variables by the way)
        var visitor = new ToyJSONParser.JSONVisitor() {
            private BeanData beanData;
            private Object result;

            @Override
            public void value(String key, Object value) {
                var currentContext = stack.peek();
//                var setter = Objects.requireNonNull(currentContext).beanData.findProperty(key).getWriteMethod();
//                Utils.invokeMethod(currentContext.result, setter, value);
                currentContext.objectBuilder.populater.populate(currentContext.result, key, value);
            }

            @Override
            public void startObject(String key) {
                var currentContext = stack.peek();
                var beanType = currentContext == null ?
                        expectedType : currentContext.objectBuilder.typeProvider.apply(key);
                var objectBuilder = ObjectBuilder.bean(Utils.erase(beanType));
                var instance = objectBuilder.supplier.get();

//                beanData = BEAN_DATA_CLASS_VALUE.get(beanType);
                //get the beanData and store it in the field
                //create an instance and store it in result
//                var instance = Utils.newInstance(beanData.constructor);
                stack.push(new Context(objectBuilder, instance));
            }

            @Override
            public void endObject(String key) {
                var previoustContext = stack.pop();
                var result = previoustContext.result;
                if (stack.isEmpty())
                    this.result = result;
                else {
                    var currentContext = stack.peek();
//                    var setter = currentContext.objectBuilder.finisher.apply(currentContext.result);
//                            .beanData.findProperty(key).getWriteMethod();
//                    Utils.invokeMethod(currentContext.result, setter, result);
                    currentContext.objectBuilder.populater.populate(currentContext.result, key, result);
                }
            }

            @Override
            public void startArray(String key) {
                throw new UnsupportedOperationException("Implemented later");
            }

            @Override
            public void endArray(String key) {
                throw new UnsupportedOperationException("Implemented later");
            }
        };
        ToyJSONParser.parse(text, visitor);
        return visitor.result;
    }
}
