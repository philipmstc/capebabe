package net.shchoo.scafe.scafe.model;

public class Product {
    public Product(int id, String name) {
        this.id = id;
        this.name = name;
    }
    public final int id;
    public String name;
    public int recipesUsedIn = 0;

    @Override
    public boolean equals(Object o) {
        return o instanceof Product other && other.id == id;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(id);
    }
}
