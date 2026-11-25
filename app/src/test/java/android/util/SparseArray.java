package android.util;

import java.util.TreeMap;

/**
 * The few parts of Android's SparseArray that Media3's MKV reader uses (get, put, size, valueAt), working: the unit
 * tests' android.jar only has stubs, which keep nothing, and the reader then finds no tracks. Only in the unit tests,
 * where classes of their own come before android.jar; the app uses Android's own.
 */
public class SparseArray<E> implements Cloneable {
    private final TreeMap<Integer, E> map = new TreeMap<>();

    public SparseArray() {}

    public SparseArray(int initialCapacity) {}

    public E get(int key) {
        return map.get(key);
    }

    public E get(int key, E valueIfKeyNotFound) {
        E value = map.get(key);
        return value == null ? valueIfKeyNotFound : value;
    }

    public void put(int key, E value) {
        map.put(key, value);
    }

    public void append(int key, E value) {
        map.put(key, value);
    }

    public void remove(int key) {
        map.remove(key);
    }

    public void delete(int key) {
        map.remove(key);
    }

    public void clear() {
        map.clear();
    }

    public int size() {
        return map.size();
    }

    public int keyAt(int index) {
        int i = 0;
        for (Integer key : map.keySet()) {
            if (i++ == index) return key;
        }
        throw new ArrayIndexOutOfBoundsException(index);
    }

    public E valueAt(int index) {
        return map.get(keyAt(index));
    }

    public int indexOfKey(int key) {
        int i = 0;
        for (Integer k : map.keySet()) {
            if (k == key) return i;
            i++;
        }
        return -1;
    }
}
