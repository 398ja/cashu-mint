package picocli.test;

@FunctionalInterface
public interface Supplier<T> {
    T get();
}
