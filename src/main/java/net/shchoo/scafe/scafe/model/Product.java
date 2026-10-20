package net.shchoo.scafe.scafe.model;

public class Product {
    /** The tier of products that aren't part of a product line (one-of-a-kind drops, ...). */
    public static final int NO_TIER = -1;

    public Product(int id, String name) {
        this(id, name, NO_TIER);
    }

    public Product(int id, String name, int tier) {
        this.id = id;
        this.name = name;
        this.tier = tier;
    }

    public final int id;
    public String name;
    /** Which tier of its product line this is (0 = the first), or {@link #NO_TIER}. */
    public final int tier;
    public int recipesUsedIn = 0;

    public boolean hasTier() {
        return tier != NO_TIER;
    }

    /** The name without its "-tier" ending: the product line's name ("skill1{0}-3" -> "skill1{0}"). */
    public String lineName() {
        String suffix = "-" + tier;
        return hasTier() && name.endsWith(suffix) ? name.substring(0, name.length() - suffix.length()) : name;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Product other && other.id == id;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(id);
    }
}
