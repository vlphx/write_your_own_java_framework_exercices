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

    public void addAroundAdvice(Class<? extends Annotation> annotationClass, AroundAdvice aroundAdvice) {
        Objects.requireNonNull(annotationClass);
        Objects.requireNonNull(aroundAdvice);
        advice = aroundAdvice;
    }

    public <T> T createProxy(Class<T> interfaceType, T instance) {
        Objects.requireNonNull(interfaceType);
        Objects.requireNonNull(instance);
        return interfaceType.cast(Proxy.newProxyInstance(interfaceType.getClassLoader(), new Class[]{interfaceType},
                (Object proxy, Method method, Object[] args) -> {
                    Object result = null;
                    advice.before(instance, method, args);
                    try {
                        result = Utils.invokeMethod(instance, method, args);
                        return result;
                    } finally {
                        advice.after(instance, method, args, result);
                    }
                })
        );
    }
}
