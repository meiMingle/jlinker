package dev.xdark.jlinker;

/**
 * Success result.
 *
 * @author xDark
 */
final class Success<V> implements Result<V> {
    private final V value;

    /**
     * @param value Value.
     */
    Success(V value) {
        this.value = value;
    }

    @Override
    public V value() {
        return value;
    }

    @Override
    public ResolutionError error() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isSuccess() {
        return true;
    }
}