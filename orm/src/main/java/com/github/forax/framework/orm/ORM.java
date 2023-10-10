package com.github.forax.framework.orm;

import javax.sql.DataSource;
import java.beans.PropertyDescriptor;
import java.io.Serial;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ORM {
    private ORM() {
        throw new AssertionError();
    }

    @FunctionalInterface
    public interface TransactionBlock {
        void run() throws SQLException;
    }

    private static final Map<Class<?>, String> TYPE_MAPPING = Map.of(
            int.class, "INTEGER",
            Integer.class, "INTEGER",
            long.class, "BIGINT",
            Long.class, "BIGINT",
            String.class, "VARCHAR(255)"
    );

    private static Class<?> findBeanTypeFromRepository(Class<?> repositoryType) {
        var repositorySupertype = Arrays.stream(repositoryType.getGenericInterfaces())
                .flatMap(superInterface -> {
                    if (superInterface instanceof ParameterizedType parameterizedType
                            && parameterizedType.getRawType() == Repository.class) {
                        return Stream.of(parameterizedType);
                    }
                    return null;
                })
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("invalid repository interface " + repositoryType.getName()));
        var typeArgument = repositorySupertype.getActualTypeArguments()[0];
        if (typeArgument instanceof Class<?> beanType) {
            return beanType;
        }
        throw new IllegalArgumentException("invalid type argument " + typeArgument + " for repository interface " + repositoryType.getName());
    }

    private static class UncheckedSQLException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 42L;

        private UncheckedSQLException(SQLException cause) {
            super(cause);
        }

        @Override
        public SQLException getCause() {
            return (SQLException) super.getCause();
        }
    }


    // --- do not change the code above

    /*To associate a thread for a connection*/
    public static final ThreadLocal<Connection> CONNECTION_THREAD_LOCAL = new ThreadLocal<>();

    public static void transaction(DataSource dataSource, TransactionBlock transactionBlock) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            CONNECTION_THREAD_LOCAL.set(connection);
            try {
                connection.setAutoCommit(false);
                transactionBlock.run();
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                CONNECTION_THREAD_LOCAL.remove();
            }
        }
    }

    public static Connection currentConnection() {
        var connection = CONNECTION_THREAD_LOCAL.get();
        if (connection == null) {
            throw new IllegalStateException("There is no connection available");
        }
        return connection;
    }

    static String findTableName(Class<?> beanClass) {
        var tableAnnotation = beanClass.getAnnotation(Table.class);
        var name = tableAnnotation == null
                ? beanClass.getSimpleName()
                : tableAnnotation.value();
        return name.toUpperCase(Locale.ROOT);
    }

    private static Method getGetterMethod(PropertyDescriptor property) {
        var getter = property.getReadMethod();
        if (getter == null) {
            throw new IllegalStateException("No getter for the given property: " + property.getName());
        }
        return getter;
    }

    static String findColumnName(PropertyDescriptor property) {
        var getter = getGetterMethod(property);
        var column = getter.getAnnotation(Column.class);
        var name = column == null
                ? property.getName()
                : column.value();
        return name.toUpperCase(Locale.ROOT);
    }

    private static boolean isAnnotatedGeneratedValue(PropertyDescriptor property) {
        var getter = getGetterMethod(property);
        return getter.isAnnotationPresent(GeneratedValue.class);
    }

    private static boolean isAnnotatedId(PropertyDescriptor property) {
        var getter = getGetterMethod(property);
        return getter.isAnnotationPresent(Id.class);
    }

    private static String getColumnNameForSQLStatement(PropertyDescriptor property) {
        var columnName = findColumnName(property);
        var columnPropertyType = property.getPropertyType();
        var typeName = TYPE_MAPPING.get(columnPropertyType);
        if (typeName == null) {
            throw new IllegalStateException("Column type name is unknown: " + columnPropertyType);
        }
        var notNull = columnPropertyType.isPrimitive() ? " NOT NULL" : "";
        var autoIncrement = isAnnotatedGeneratedValue(property)
                ? " AUTO_INCREMENT"
                : "";
        var primaryKey = isAnnotatedId(property)
                ? ", PRIMARY KEY (" + columnName + ")"
                : "";
        return columnName + " " + typeName + notNull + autoIncrement + primaryKey;
    }

    static void createTable(Class<?> beanClass) throws SQLException {
        Objects.requireNonNull(beanClass);
        var tableName = findTableName(beanClass);
        // get columns
        var beanInfo = Utils.beanInfo(beanClass);
        var update = "CREATE TABLE " + tableName + " " +
                Arrays
                        .stream(beanInfo.getPropertyDescriptors())
                        .filter(property -> !property.getName().equals("class"))
                        .map(ORM::getColumnNameForSQLStatement)
                        .collect(Collectors.joining(",", "(\n", "\n);"));
        var connection = ORM.currentConnection();
        try (var statement = connection.createStatement()) {
            statement.executeUpdate(update);
        }
    }

    public static <T extends Repository<?, ?>> T createRepository(Class<T> repositoryClass) {
        var beanType = findBeanTypeFromRepository(repositoryClass);
        return repositoryClass.cast(
                Proxy.newProxyInstance(
                        repositoryClass.getClassLoader(),
                        new Class<?>[]{repositoryClass},
                        (Object proxy, Method method, Object[] args) -> {
                            try {
                                return switch (method.getName()) {
                                    case "findAll" -> findAll(beanType);
                                    case "hashCode", "equals", "toString" -> throw new UnsupportedOperationException("Method: " + method + " not supported");
                                    default -> throw new IllegalStateException("Method: " + method + " not supported");
                                };
                            } catch (SQLException e) {
                                throw new UncheckedSQLException(e);
                            }
                        }
                )
        );
    }

    private static List<?> findAll(Class<?> beanType) throws SQLException {
        var constructor = Utils.defaultConstructor(beanType);
        var beanInfo = Utils.beanInfo(beanType);
        var properties = beanInfo.getPropertyDescriptors();

        var connection = currentConnection();
        var tableName = findTableName(beanType);
        var sqlQuery = "SELECT * FROM " + tableName;
        try (PreparedStatement statement = connection.prepareStatement(sqlQuery)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                var list = new ArrayList<>();
                while (resultSet.next()) {
                    // I suppose that my constructor has no parameters
                    var instance = Utils.newInstance(constructor);
                    var index = 1;
                    for (var property : properties) {
                        if (property.getName().equals("class")) {
                            continue;
                        }
                        var value = resultSet.getObject(index++);
                        var setter = property.getWriteMethod();
                        Utils.invokeMethod(instance, setter, value);
                    }
                    list.add(instance);
                    /*Long id = (Long) resultSet.getObject(1);
                    String name = (String) resultSet.getObject(2);*/
                }
                return list;
            }
        }
    }
}
