package com.github.forax.framework.orm;

import javax.sql.DataSource;
import java.beans.BeanInfo;
import java.beans.PropertyDescriptor;
import java.io.Serial;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.sql.*;
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
            } catch (UncheckedSQLException error) {
                connection.rollback();
                throw error.getCause();
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

    public static String createSaveQuery(String tableName, BeanInfo beanInfo) {
        var properties = beanInfo.getPropertyDescriptors();
        return """
                MERGE INTO %s %s VALUES (%s);"""
                .formatted(
                        tableName,
                        Arrays
                                .stream(properties)
                                .filter(property -> !property.getName().equals("class"))
                                .map(ORM::findColumnName)
                                .map(columnName -> columnName.toLowerCase(Locale.ROOT))
                                .collect(Collectors.joining(", ", "(", ")")),
                        String.join(", ",
                                Collections.nCopies(properties.length - 1, "?"))

                );
    }

    static PropertyDescriptor findId(BeanInfo beanInfo) {
        Objects.requireNonNull(beanInfo);
        var propertyIds = Arrays.stream(beanInfo.getPropertyDescriptors())
                .filter(ORM::isAnnotatedId)
                .toList();
        return switch (propertyIds.size()) {
            case 0 -> throw new IllegalStateException(" no @Id defined on any getters");
            case 1 -> propertyIds.getFirst();
            default -> throw new IllegalStateException(" too many @Id defined on getters");
        };
    }

    public static <T> T save(Connection connection, String tableName, BeanInfo beanInfo, T bean, PropertyDescriptor idProperty) throws SQLException {
        Objects.requireNonNull(connection);
        Objects.requireNonNull(tableName);
        Objects.requireNonNull(beanInfo);
//        Objects.requireNonNull(idProperty);

        var saveQuery = createSaveQuery(tableName, beanInfo);
        int columnIndex = 1;
        try (var statement = connection.prepareStatement(saveQuery, Statement.RETURN_GENERATED_KEYS)) {
            var properties = beanInfo.getPropertyDescriptors();
            for (var property : properties) {
                var name = property.getName();
                if (name.equals("class")) {
                    continue;
                }
                var getter = property.getReadMethod();
                var argument = Utils.invokeMethod(bean, getter);
                statement.setObject(columnIndex++, argument); // columnIndex value is used as argument for setObject method AND THEN it is incremented
            }
            statement.executeUpdate();
            if (idProperty != null) {
                try (var resultSet = statement.getGeneratedKeys()) {
                    if (resultSet.next()) {
                        var idValue = resultSet.getObject(1);
                        var idSetter = idProperty.getWriteMethod();
                        Utils.invokeMethod(bean, idSetter, idValue);
                    }
                }
            }
        }
        connection.commit();
        return bean;
    }

    public static <T extends Repository<?, ?>> T createRepository(Class<T> repositoryClass) {
        var beanType = findBeanTypeFromRepository(repositoryClass);
        var tableName = findTableName(beanType);

        var beanInfo = Utils.beanInfo(beanType);
        var sqlQuery = "SELECT * FROM " + tableName;
        var constructor = Utils.defaultConstructor(beanType);
        var idProperty = findId(beanInfo);
        return repositoryClass.cast(
                Proxy.newProxyInstance(
                        repositoryClass.getClassLoader(),
                        new Class<?>[]{repositoryClass},
                        (Object proxy, Method method, Object[] args) -> {
                            try {
                                return switch (method.getName()) {
                                    case "save" -> save(currentConnection(), tableName, beanInfo, args[0], idProperty);
                                    case "findAll" -> findAll(currentConnection(), sqlQuery, beanInfo, constructor);
                                    case "hashCode", "equals", "toString" ->
                                            throw new UnsupportedOperationException("Method: " + method + " not supported");
                                    default -> throw new IllegalStateException("Method: " + method + " not supported");
                                };
                            } catch (SQLException e) {
                                throw new UncheckedSQLException(e);
                            }
                        }
                )
        );
    }

    static <T> T toEntityClass(ResultSet resultSet, BeanInfo beanInfo, Constructor<T> constructor) throws SQLException {
        Objects.requireNonNull(resultSet);
        Objects.requireNonNull(beanInfo);
        Objects.requireNonNull(constructor);

        var newInstance = Utils.newInstance(constructor);
        var properties = beanInfo.getPropertyDescriptors();

        for (var property : properties) {
            var name = property.getName();
            if (name.equals("class")) {
                continue;
            }
            var setter = property.getWriteMethod();
            var argument = resultSet.getObject(name); // One line in result set corresponds to one object representing a row in the table
            Utils.invokeMethod(newInstance, setter, argument);
        }
        return newInstance;
    }

    public static <T> List<T> findAll(Connection connection, String sqlQuery, BeanInfo beanInfo, Constructor<? extends T> constructor) throws SQLException {
        Objects.requireNonNull(connection);
        Objects.requireNonNull(sqlQuery);
        Objects.requireNonNull(beanInfo);
        Objects.requireNonNull(constructor);

        var list = new ArrayList<T>();
        try (PreparedStatement statement = connection.prepareStatement(sqlQuery)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    var instance = toEntityClass(resultSet, beanInfo, constructor);
                    list.add(instance);
                }
            }
        }
        return list;
    }
}
