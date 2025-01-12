package org.github.forax.framework.interceptor;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public final class InterceptorRegistry {
    // private final HashMap<Class<? extends Annotation>, List<AroundAdvice>> aroundAdvicesByAnnotationMap = new HashMap<>();
    private final HashMap<Class<? extends Annotation>, List<Interceptor>> interceptorsByAnnotationMap = new HashMap<>();

    private final HashMap<Method, Invocation> invocationByMethodMap = new HashMap<>();

//    List<AroundAdvice> findAdvices(Method method) {
//        Objects.requireNonNull(method);
//        return Arrays
//                .stream(method.getAnnotations())
//                .flatMap(annotation -> aroundAdvicesByAnnotationMap.getOrDefault(annotation.annotationType(), List.of()).stream())
//                .toList();
//    }
    public void addAroundAdvice(Class<? extends Annotation> annotationClass, AroundAdvice aroundAdvice) {
        Objects.requireNonNull(annotationClass);
        Objects.requireNonNull(aroundAdvice);
//        aroundAdvicesByAnnotationMap.computeIfAbsent(annotationClass, __ -> new ArrayList<>()).add(aroundAdvice);
        addInterceptor(annotationClass,
                ((instance, method, args, invocation) -> {
                    aroundAdvice.before(instance, method, args);
                    Object result = null;
                    // var invocation = new Interceptor().intercept(instance, method, args, aroundAdvice.after);
                    try{
                        return result = invocation.proceed(instance, method, args);
                    }
                    finally {
                        aroundAdvice.after(instance, method, args, result);
                    }
                })
                );

    }

    public <T> T createProxy(Class<T> interfaceType, T instance) {
        Objects.requireNonNull(interfaceType);
        Objects.requireNonNull(instance);
        return interfaceType.cast(Proxy.newProxyInstance(interfaceType.getClassLoader(), new Class[]{interfaceType},
                (Object __, Method method, Object[] args) -> {
//                    var advices = findAdvices(method);
//                    for (var advice : advices){
//                        advice.before(instance, method, args);
//                    }
//                    Object result = null;
//                    try {
//                        result = Utils.invokeMethod(instance, method, args);
//                        return result;
//                    } finally {
//                        for(var advice : advices.reversed()) {
//                            advice.after(instance, method, args, result);
//                        }
//                    }
                    var invocation = computeInvocation(method);
                    return invocation.proceed(instance, method, args);
                })
        );
    }


    public void addInterceptor(Class<? extends Annotation> annotationClass, Interceptor interceptor){
        Objects.requireNonNull(annotationClass);
        Objects.requireNonNull(interceptor);
        interceptorsByAnnotationMap.computeIfAbsent(annotationClass, __ -> new ArrayList<>()).add(interceptor);
        invocationByMethodMap.clear(); // invalidate cache so that computeInvocation recalculates and takes into account the new interceptor where needed
        // we see that caching is not that effective as it recalculates everything after this...
    }

    List<Interceptor> findInterceptors(Method method){
        Objects.requireNonNull(method);
        return Stream.of(
                        Arrays.stream(method.getDeclaringClass().getAnnotations()),
                        Arrays.stream(method.getAnnotations()),
                        Arrays.stream(method.getParameterAnnotations()).flatMap(Arrays::stream))
                .flatMap(s -> s)
                .distinct()
                .flatMap(annotation -> interceptorsByAnnotationMap.getOrDefault(annotation.annotationType(), List.of()).stream())
                .toList();
    }

    static Invocation getInvocation(List<Interceptor> interceptors){
        Invocation invocation = Utils::invokeMethod;
        for (var interceptor: interceptors.reversed()){
            Invocation previousInvocation = invocation;
            invocation = (instance, method, args) -> interceptor.intercept(instance, method, args, previousInvocation);
        }
        return invocation;
    }
    Invocation computeInvocation(Method method){
        Objects.requireNonNull(method);
        return invocationByMethodMap.computeIfAbsent(method, __ -> getInvocation(findInterceptors(method)));
    }
}
