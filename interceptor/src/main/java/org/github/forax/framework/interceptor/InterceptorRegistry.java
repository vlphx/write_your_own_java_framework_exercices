package org.github.forax.framework.interceptor;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public final class InterceptorRegistry {
    private AroundAdvice advice;
    private final HashMap<Class<? extends Annotation>, List<AroundAdvice>> aroundAdvicesByAnnotationMap = new HashMap<>();

    List<AroundAdvice> findAdvices(Method method) {
        Objects.requireNonNull(method);
        return Arrays
                .stream(method.getAnnotations())
                .flatMap(annotation -> aroundAdvicesByAnnotationMap.getOrDefault(annotation.annotationType(), List.of()).stream())
                .toList();
    }
    public void addAroundAdvice(Class<? extends Annotation> annotationClass, AroundAdvice aroundAdvice) {
        Objects.requireNonNull(annotationClass);
        Objects.requireNonNull(aroundAdvice);
        aroundAdvicesByAnnotationMap.computeIfAbsent(annotationClass, __ -> new ArrayList<>()).add(aroundAdvice);
    }

    public <T> T createProxy(Class<T> interfaceType, T instance) {
        Objects.requireNonNull(interfaceType);
        Objects.requireNonNull(instance);
        return interfaceType.cast(Proxy.newProxyInstance(interfaceType.getClassLoader(), new Class[]{interfaceType},
                (Object __, Method method, Object[] args) -> {
                    var advices = findAdvices(method);
                    for (var advice : advices){
                        advice.before(instance, method, args);
                    }
                    Object result = null;
                    try {
                        result = Utils.invokeMethod(instance, method, args);
                        return result;
                    } finally {
                        for(var advice : advices.reversed()) {
                            advice.after(instance, method, args, result);
                        }
                    }
                })
        );
    }
}
