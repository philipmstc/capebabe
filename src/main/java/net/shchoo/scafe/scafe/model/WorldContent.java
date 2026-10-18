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
    private final Map<Integer, ProductLine> productLinesById = new HashMap<>();
    private final Map<Integer, Recipe> recipesById = new HashMap<>();
    private final Map<Integer, Node> nodesById = new HashMap<>();
    public final List<MonsterType> monsters = new ArrayList<>();
    private final Map<Integer, MonsterType> monstersById = new HashMap<>();
    private final Map<Position, Node> nodesAt = new HashMap<>();
    private final List<ContentBatch> history = new ArrayList<>();
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
        for (ContentBatch.SkillDef def : batch.skills()) {
            Skill skill = new Skill(def.id(), def.name(), def.type(), def.colorIndex(), def.symbol());
            skills.add(skill);
            skillsById.put(def.id(), skill);
            seen(def.id());
        }
        for (ContentBatch.ProductDef def : batch.products()) {
            productsById.put(def.id(), new Product(def.id(), def.name()));
            seen(def.id());
        }
        for (ContentBatch.ProductLineDef def : batch.productLines()) {
            Skill skill = require(skillsById, def.skillId(), "skill");
            ProductLine productLine = new ProductLine(def.id(), skill, new ArrayList<>());
            skill.productLines.add(productLine);
            productLinesById.put(def.id(), productLine);
            seen(def.id());
        }
        for (ContentBatch.RecipeDef def : batch.recipes()) {
            ProductLine productLine = require(productLinesById, def.productLineId(), "product line");
            Map<Product, Integer> components = new LinkedHashMap<>();
            def.components().forEach((productId, count) -> components.put(require(productsById, productId, "product"), count));
            Recipe recipe = new Recipe(def.id(), productLine.skill, require(productsById, def.productId(), "product"),
                    def.levelNeeded(), def.expGained(), components);
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

    /** Generators add skills and nodes through these; product lines/recipes/products hang off the skills. */
    public void addSkill(Skill skill) {
        skills.add(skill);
        skillsById.put(skill.id, skill);
    }

    public void addMonster(MonsterType monster) {
        monsters.add(monster);
        monstersById.put(monster.id, monster);
    }

    public void addNode(Node node) {
        nodesById.put(node.id, node);
        nodesAt.put(node.position, node);
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
        List<ContentBatch.ProductDef> products = new ArrayList<>(newProducts.values());
        products.sort(Comparator.comparingInt(ContentBatch.ProductDef::id));
        return new ContentBatch(original.version + 1, newSkills, products, newProductLines, newRecipes, newNodes,
                newMonsters, newDrops);
    }

    private static void addIfNew(WorldContent original, Product product, Map<Integer, ContentBatch.ProductDef> into) {
        if (!original.productsById.containsKey(product.id)) {
            into.putIfAbsent(product.id, new ContentBatch.ProductDef(product.id, product.name));
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

    public Collection<Node> nodes() {
        return nodesById.values();
    }
}
