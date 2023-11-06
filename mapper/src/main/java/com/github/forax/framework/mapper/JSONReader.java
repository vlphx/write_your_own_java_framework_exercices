package com.github.forax.framework.mapper;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class JSONReader {
    public interface TypeReference<T> {
    }

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

        public static ObjectBuilder<List<Object>> list(Type componentType) {
            Objects.requireNonNull(componentType);
            return new ObjectBuilder<List<Object>>(
                    key -> componentType,
                    ArrayList::new,
                    (instance, key, value) -> instance.add(value),
                    List::copyOf
            );
        }

        // Idée: Créer un tableau d'objet, remplir le tableau d'objet avec les valeurs représentant les champs du record, et à la fin créer le record
        public static ObjectBuilder<Object[]> record(Class<?> recordClass) {
            var components = recordClass.getRecordComponents();
            var map =
                    IntStream.range(0, components.length)
                            .boxed() // to get a int stream
                            .collect(Collectors.toMap(i -> components[i].getName(), Function.identity()));
            var recordConstructor = Utils.canonicalConstructor(recordClass, components);
            return new ObjectBuilder<Object[]>(
                    key -> components[map.get(key)].getGenericType(),
                    () -> new Object[components.length],
                    (array, key, value) -> array[map.get(key)] = value,
                    array -> Utils.newInstance(recordConstructor, array)
            );
        }
    }


    @FunctionalInterface
    public interface TypeMatcher {
        Optional<ObjectBuilder<?>> match(Type type);
    }

    private final ArrayList<TypeMatcher> typeMatchers = new ArrayList<>();

    public void addTypeMatcher(TypeMatcher typeMatcher) {
        Objects.requireNonNull(typeMatcher);
        typeMatchers.add(typeMatcher);
    }

    private ObjectBuilder<?> findObjectBuilder(Type type) {
        return typeMatchers.reversed()
                .stream()
                .flatMap(typeMatcher -> typeMatcher.match(type).stream())
//             .filter( typeMatcher -> typeMatcher.match(type).isPresent())
//             .map( typeMatcher -> typeMatcher.match(type).orElseThrow())
                .findFirst()
                .orElseGet(() -> ObjectBuilder.bean(Utils.erase(type)));
    }

    private record Context<T>(ObjectBuilder<T> objectBuilder, T result) {

        static <T> Context<T> createContext(ObjectBuilder<T> objectBuilder) {
            var instance = objectBuilder.supplier.get();
            return new Context<>(objectBuilder, instance);
        }

        void populate(String key, Object value) {
            objectBuilder.populater.populate(result, key, value);
        }

        Object finish() {
            return objectBuilder.finisher.apply(result);
        }
    }

    public <T> T parseJSON(String text, Class<T> expectedClass) {
        return expectedClass.cast(parseJSON(text, (Type) expectedClass));
    }

    public <T> T parseJSON(String text, TypeReference<T> typeReference) {
        var type = giveMeTheTypeRef(typeReference);
        @SuppressWarnings("unchecked")
        var parsedObject = (T) parseJSON(text, type);
        return parsedObject;
    }

    private <T> Type giveMeTheTypeRef(TypeReference<T> typeReference) {
        var typeReferenceType = Arrays.stream(typeReference.getClass().getGenericInterfaces())
                .flatMap(t -> t instanceof ParameterizedType parameterizedType ? Stream.of(parameterizedType) : null)
                .filter(t -> t.getRawType() == TypeReference.class)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("TypeReference is " + typeReference));
        return typeReferenceType.getActualTypeArguments()[0];
    }

    public Object parseJSON(String text, Type expectedType) {
        Objects.requireNonNull(text);
        Objects.requireNonNull(expectedType);

        var stack = new ArrayDeque<Context<?>>(); // anonymous class below can capture this variable (all local variables by the way)
        var visitor = new ToyJSONParser.JSONVisitor() {
            private BeanData beanData;
            private Object result;

            @Override
            public void value(String key, Object value) {
                var currentContext = stack.peek();
//                var setter = Objects.requireNonNull(currentContext).beanData.findProperty(key).getWriteMethod();
//                Utils.invokeMethod(currentContext.result, setter, value);
                assert currentContext != null;
                currentContext.populate(key, value);
            }

            @Override
            public void startObject(String key) {
                var currentContext = stack.peek();
                var type = currentContext == null ?
                        expectedType : currentContext.objectBuilder.typeProvider.apply(key);
                var objectBuilder = findObjectBuilder(type);
                stack.push(Context.createContext(objectBuilder));
            }

            @Override
            public void endObject(String key) {
                var previoustContext = stack.pop();
                var result = previoustContext.finish();
                if (stack.isEmpty())
                    this.result = result;
                else {
                    var currentContext = stack.peek();
                    currentContext.populate(key, result);
                }
            }

            @Override
            public void startArray(String key) {
                startObject(key);
            }

            @Override
            public void endArray(String key) {
                endObject(key);
            }
        };
        ToyJSONParser.parse(text, visitor);
        return visitor.result;
    }
}
