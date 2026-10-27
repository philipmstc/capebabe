package net.shchoo.scafe.scafe.model;

public class Product {
    /** The tier of products that aren't part of a product line (one-of-a-kind drops, ...). */
    public static final int NO_TIER = -1;

    public Product(int id, String name) {
        this(id, name, NO_TIER);
    }

    public Product(int id, String name, int tier) {
        this(id, name, tier, null, 0);
    }

    /** A prestige version of {@code base}: made by someone {@code prestige} times prestiged in its skill. */
    public Product(int id, String name, int tier, Product base, int prestige) {
        this.id = id;
        this.name = name;
        this.tier = tier;
        this.base = base;
        this.prestige = prestige;
    }

    /** A church blessing for this recipe ({@link Prayer}): used from the inventory, it blesses you. Else null. */
    public Recipe blesses;

    /** A blessing's name: "blessing of skill3-2". */
    public static String blessingName(Recipe recipe) {
        return "blessing of " + recipe.product.name;
    }

    /** How much more a prestige level adds to a weapon's base damage than the tiers do: one line's worth. */
    public static final int PRESTIGE_DAMAGE = 5;

    /** The plain item this is a prestige version of, or null if it's plain. */
    public final Product base;
    /** 0 for a plain item. */
    public final int prestige;

    /** The plain item (itself, if it is one): what recipes ask for. */
    public Product root() {
        return base == null ? this : base;
    }

    /** A prestige version's name: "skill0{1}-2+1". */
    public static String prestigeName(Product base, int prestige) {
        return base.name + "+" + prestige;
    }

    public final int id;
    public String name;
    /** Which tier of its product line this is (0 = the first), or {@link #NO_TIER}. */
    public final int tier;
    public int recipesUsedIn = 0;

    public boolean hasTier() {
        return tier != NO_TIER;
    }

    /** The name without its "-tier" ending: the product line's (and so the skill's) name ("skill1-3" -> "skill1"). */
    public String lineName() {
        if (base != null) {
            return base.lineName();
        }
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
