package net.shchoo.scafe.scafe.model;

import java.util.*;

/**
 * A world's skills, products, recipes and nodes, built up only by applying {@link ContentBatch}es in order.
 * The server and every client hold one and stay in sync by applying the same batches.
 */
public class WorldContent {
    public final List<Skill> skills = new ArrayList<>();
    private final Map<Integer, Skill> skillsById = new HashMap<>();
    private final Map<Integer, Product> productsById = new HashMap<>();
    /** Prestige versions, by base product and prestige. */
    private final Map<Long, Product> variants = new HashMap<>();
    private final Map<Integer, ProductLine> productLinesById = new HashMap<>();
    private final Map<Integer, Recipe> recipesById = new HashMap<>();
    private final Map<Integer, Node> nodesById = new HashMap<>();
    public final List<MonsterType> monsters = new ArrayList<>();
    private final Map<Integer, MonsterType> monstersById = new HashMap<>();
    private final Map<Position, Node> nodesAt = new HashMap<>();
    private final List<Building> buildings = new ArrayList<>();
    /** Every tile of every building. */
    private final Map<Position, Building> buildingsAt = new HashMap<>();
    private final List<ContentBatch> history = new ArrayList<>();
    /** Whose action made each skill, node and monster kind, and when (absent if unknown). */
    private final Map<Integer, ContentBatch.Origin> origins = new HashMap<>();
    private int version = 0;
    private int nextId = 1;

    public int version() {
        return version;
    }

    public List<ContentBatch> history() {
        return Collections.unmodifiableList(history);
    }

    public void apply(ContentBatch batch) {
        if (batch.version() != version + 1) {
            throw new IllegalStateException("Expected content version " + (version + 1) + " but got " + batch.version());
        }
        if (batch.origin() != null) {
            batch.skills().forEach(def -> origins.put(def.id(), batch.origin()));
            batch.nodes().forEach(def -> origins.put(def.id(), batch.origin()));
            batch.monsters().forEach(def -> origins.put(def.id(), batch.origin()));
        }
        for (ContentBatch.SkillDef def : batch.skills()) {
            // an old world: a skill split off earlier (below) may have taken this name first -- it moves on
            Skill clash = skills.stream().filter(s -> splitFrom.containsKey(s) && s.name.equals(def.name())).findFirst().orElse(null);
            Skill skill = new Skill(def.id(), def.name(), def.type(), def.colorIndex(), def.symbol());
            skills.add(skill);
            skillsById.put(def.id(), skill);
            seen(def.id());
            if (clash != null) {
                clash.name = nextSkillName();
            }
        }
        for (ContentBatch.ProductDef def : batch.products()) {
            Product base = def.prestige() > 0 ? require(productsById, def.baseId(), "product") : null;
            addProduct(new Product(def.id(), def.name(), def.tier(), base, def.prestige()));
            seen(def.id());
        }
        for (ContentBatch.ProductLineDef def : batch.productLines()) {
            Skill skill = require(skillsById, def.skillId(), "skill");
            if (!skill.productLines.isEmpty()) {
                // one product line a skill: an old world's second (third...) line becomes a skill of its own
                skill = splitOff(skill, def.id());
            }
            ProductLine productLine = new ProductLine(def.id(), skill, new ArrayList<>());
            skill.productLines.add(productLine);
            productLinesById.put(def.id(), productLine);
            seen(def.id());
        }
        for (ContentBatch.RecipeDef def : batch.recipes()) {
            ProductLine productLine = require(productLinesById, def.productLineId(), "product line");
            Map<Product, Integer> components = new LinkedHashMap<>();
            def.components().forEach((productId, count) -> components.put(require(productsById, productId, "product"), count));
            // a world made before the tiers were spread out: its default levels move to the new ones (Tiers)
            Recipe recipe = new Recipe(def.id(), productLine.skill, require(productsById, def.productId(), "product"),
                    Tiers.levelNeeded(productLine.recipes.size(), def.levelNeeded()), def.expGained(), components);
            productLine.recipes.add(recipe);
            recipesById.put(def.id(), recipe);
            seen(def.id());
        }
        for (ContentBatch.MonsterDef def : batch.monsters()) {
            MonsterType monster = new MonsterType(def.id(), def.name(), def.level(), def.maxHp(), def.maxHit(),
                    def.colorIndex(), def.symbol(), def.roamRadius(), def.moveTicks());
            monsters.add(monster);
            monstersById.put(def.id(), monster);
            seen(def.id());
        }
        for (ContentBatch.DropDef def : batch.drops()) {
            require(monstersById, def.monsterId(), "monster").drops
                    .add(new MonsterType.Drop(require(productsById, def.productId(), "product"), def.oneIn(), def.foundBy()));
        }
        for (ContentBatch.NodeDef def : batch.nodes()) {
            Node node = new Node(def.id(), new Position(def.x(), def.y()), require(recipesById, def.recipeId(), "recipe"),
                    def.capacity(), def.maxCharges(), def.respawnTicks());
            nodesById.put(def.id(), node);
            nodesAt.put(node.position, node);
            seen(def.id());
        }
        for (ContentBatch.BuildingDef def : batch.buildings()) {
            addBuilding(new Building(def.id(), def.kind(), new Position(def.x(), def.y())));
            seen(def.id());
        }
        version = batch.version();
        history.add(batch);
    }

    private void seen(int id) {
        nextId = Math.max(nextId, id + 1);
    }

    private static <T> T require(Map<Integer, T> map, int id, String what) {
        T t = map.get(id);
        if (t == null) {
            throw new IllegalStateException("Content references unknown " + what + " " + id);
        }
        return t;
    }

    /** Ids are unique across all kinds of content in a world. Only used while generating, on a copy. */
    public int allocateId() {
        return nextId++;
    }

    /** A detached copy to generate into; see {@link WorldGenerator}. */
    public WorldContent copy() {
        WorldContent copy = new WorldContent();
        history.forEach(copy::apply);
        return copy;
    }

    /**
     * Skills that were another skill's extra product line in an old world (a skill has one now), and the skill they
     * came from. Made the same way on the server and every client as the history is applied, so nothing saved changes.
     */
    private final Map<Skill, Skill> splitFrom = new HashMap<>();

    /**
     * A new skill for an old world's extra product line {@code lineId} of {@code from}: same type and symbol, the
     * line's id (unique: every id comes from the one counter), the next free name, no xp for anyone yet.
     */
    private Skill splitOff(Skill from, int lineId) {
        Skill split = new Skill(lineId, nextSkillName(), from.type, skills.size(), from.symbol);
        skills.add(split);
        skillsById.put(lineId, split);
        splitFrom.put(split, from);
        return split;
    }

    /** The skill {@code skill} was split off from (see {@link #splitFrom}), or null. */
    public Skill splitFrom(Skill skill) {
        return splitFrom.get(skill);
    }

    private static final java.util.regex.Pattern NUMBERED = java.util.regex.Pattern.compile("skill(\\d+)");

    /** "skillN" with N one past the highest any skill has. */
    public String nextSkillName() {
        int next = 0;
        for (Skill skill : skills) {
            java.util.regex.Matcher m = NUMBERED.matcher(skill.name);
            if (m.matches()) {
                next = Math.max(next, Integer.parseInt(m.group(1)) + 1);
            }
        }
        return "skill" + next;
    }

    /** Generators add skills and nodes through these; product lines/recipes/products hang off the skills. */
    public void addSkill(Skill skill) {
        skills.add(skill);
        skillsById.put(skill.id, skill);
    }

    public void addMonster(MonsterType monster) {
        monsters.add(monster);
        monstersById.put(monster.id, monster);
    }

    /** A product on its own (a prestige version; others come with their recipes or drops). */
    public void addProduct(Product product) {
        productsById.put(product.id, product);
        if (product.base != null) {
            variants.put(variantKey(product.base, product.prestige), product);
        }
    }

    private static long variantKey(Product base, int prestige) {
        return ((long) base.id << 16) | prestige;
    }

    /** The prestige version of {@code base}, or null if nobody's made one yet. */
    public Product variant(Product base, int prestige) {
        return prestige <= 0 ? base : variants.get(variantKey(base, prestige));
    }

    public void addNode(Node node) {
        nodesById.put(node.id, node);
        nodesAt.put(node.position, node);
    }

    public void addBuilding(Building building) {
        buildings.add(building);
        building.tiles().forEach(tile -> buildingsAt.put(tile, building));
    }

    /** Everything in this (generated-into) copy that {@code original} doesn't have yet, as the next batch. */
    public ContentBatch diff(WorldContent original) {
        List<ContentBatch.SkillDef> newSkills = new ArrayList<>();
        Map<Integer, ContentBatch.ProductDef> newProducts = new LinkedHashMap<>();
        List<ContentBatch.ProductLineDef> newProductLines = new ArrayList<>();
        List<ContentBatch.RecipeDef> newRecipes = new ArrayList<>();
        List<ContentBatch.NodeDef> newNodes = new ArrayList<>();
        for (Skill skill : skills) {
            if (!original.skillsById.containsKey(skill.id)) {
                newSkills.add(new ContentBatch.SkillDef(skill.id, skill.name, skill.type, skill.colorIndex, skill.symbol));
            }
            for (ProductLine productLine : skill.productLines) {
                if (!original.productLinesById.containsKey(productLine.id)) {
                    newProductLines.add(new ContentBatch.ProductLineDef(productLine.id, skill.id));
                }
                for (Recipe recipe : productLine.recipes) {
                    if (original.recipesById.containsKey(recipe.id)) {
                        continue;
                    }
                    Map<Integer, Integer> components = new LinkedHashMap<>();
                    recipe.components.forEach((product, count) -> components.put(product.id, count));
                    newRecipes.add(new ContentBatch.RecipeDef(recipe.id, productLine.id, recipe.product.id,
                            recipe.levelNeeded, recipe.expGained, components));
                    for (Product product : recipe.components.keySet()) {
                        addIfNew(original, product, newProducts);
                    }
                    addIfNew(original, recipe.product, newProducts);
                }
            }
        }
        for (Node node : nodesById.values()) {
            if (!original.nodesById.containsKey(node.id)) {
                newNodes.add(new ContentBatch.NodeDef(node.id, node.position.x(), node.position.y(), node.recipe.id,
                        node.capacity, node.maxCharges, node.respawnTicks));
            }
        }
        newNodes.sort(Comparator.comparingInt(ContentBatch.NodeDef::id));
        List<ContentBatch.MonsterDef> newMonsters = new ArrayList<>();
        List<ContentBatch.DropDef> newDrops = new ArrayList<>();
        for (MonsterType monster : monsters) {
            MonsterType before = original.monstersById.get(monster.id);
            if (before == null) {
                newMonsters.add(new ContentBatch.MonsterDef(monster.id, monster.name, monster.level, monster.maxHp,
                        monster.maxHit, monster.colorIndex, monster.symbol, monster.roamRadius, monster.moveTicks));
            }
            for (int i = before == null ? 0 : before.drops.size(); i < monster.drops.size(); i++) {
                MonsterType.Drop drop = monster.drops.get(i);
                newDrops.add(new ContentBatch.DropDef(monster.id, drop.product().id, drop.oneIn(), drop.foundBy()));
                addIfNew(original, drop.product(), newProducts);
            }
        }
        for (Product product : productsById.values()) {
            // products not reached through recipes or drops: prestige versions
            if (product.base != null) {
                addIfNew(original, product, newProducts);
            }
        }
        List<ContentBatch.ProductDef> products = new ArrayList<>(newProducts.values());
        products.sort(Comparator.comparingInt(ContentBatch.ProductDef::id));
        Set<Integer> had = new HashSet<>();
        original.buildings.forEach(b -> had.add(b.id()));
        List<ContentBatch.BuildingDef> newBuildings = buildings.stream().filter(b -> !had.contains(b.id()))
                .map(b -> new ContentBatch.BuildingDef(b.id(), b.kind(), b.corner().x(), b.corner().y())).toList();
        return new ContentBatch(original.version + 1, newSkills, products, newProductLines, newRecipes, newNodes,
                newMonsters, newDrops, newBuildings, null);
    }

    private static void addIfNew(WorldContent original, Product product, Map<Integer, ContentBatch.ProductDef> into) {
        if (!original.productsById.containsKey(product.id)) {
            into.putIfAbsent(product.id, new ContentBatch.ProductDef(product.id, product.name, product.tier,
                    product.base == null ? 0 : product.base.id, product.prestige));
        }
    }

    /** Every skill except combat: the ones with product lines, which world generation balances. */
    public List<Skill> craftingSkills() {
        return skills.stream().filter(s -> s.type != SkillType.COMBAT).toList();
    }

    /** The combat skill, or null until the first monster is discovered. */
    public Skill combatSkill() {
        return skills.stream().filter(s -> s.type == SkillType.COMBAT).findFirst().orElse(null);
    }

    /** The recipe that makes this product (a prestige version: its plain one's), or null (monster drops). */
    public Recipe madeBy(Product product) {
        Product root = product.root();
        for (Recipe recipe : recipesById.values()) {
            if (recipe.product.equals(root)) {
                return recipe;
            }
        }
        return null;
    }

    /** The monster that dropped this one-of-a-kind product, or null if it's an ordinary product. */
    public MonsterType droppedBy(Product product) {
        for (MonsterType monster : monsters) {
            for (MonsterType.Drop drop : monster.drops) {
                if (drop.product().equals(product)) {
                    return monster;
                }
            }
        }
        return null;
    }

    public MonsterType monster(int id) {
        return monstersById.get(id);
    }

    public Skill skill(int id) {
        return skillsById.get(id);
    }

    public Product product(int id) {
        return productsById.get(id);
    }

    public Recipe recipe(int id) {
        return recipesById.get(id);
    }

    public Node node(int id) {
        return nodesById.get(id);
    }

    public Node nodeAt(Position position) {
        return nodesAt.get(position);
    }

    /** Whose action made this skill / node / monster kind and when, or null if that wasn't recorded. */
    public ContentBatch.Origin origin(int id) {
        return origins.get(id);
    }

    public Collection<Node> nodes() {
        return nodesById.values();
    }

    public List<Building> buildings() {
        return Collections.unmodifiableList(buildings);
    }

    public Building buildingAt(Position position) {
        return buildingsAt.get(position);
    }

    /** Nodes and buildings (but not a station's track): what you can't walk onto. */
    public boolean blocked(Position position) {
        Building building = buildingsAt.get(position);
        return nodesAt.containsKey(position) || building != null && !building.walkable(position);
    }

    /** A node or any part of a building, track included: where nothing new may go. */
    public boolean builtOn(Position position) {
        return nodesAt.containsKey(position) || buildingsAt.containsKey(position);
    }

    /** The building whose solid part (not track) is here, or null: what q acts on. */
    public Building solidBuildingAt(Position position) {
        Building building = buildingsAt.get(position);
        return building == null || building.walkable(position) ? null : building;
    }
}
