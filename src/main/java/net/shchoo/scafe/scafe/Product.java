package net.shchoo.scafe.scafe;

public class Product {
    public Product(int id, String name) {
        this.id = id;
        this.name = name;
    }
    final int id;
    public String name;
    int recipesUsedIn = 0;

    @Override
    public boolean equals(Object o) {
        return o instanceof Product other && other.id == id;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(id);
    }
}
