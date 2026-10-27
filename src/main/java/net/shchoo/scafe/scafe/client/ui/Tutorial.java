package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.model.Node;
import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.model.Recipe;
import net.shchoo.scafe.scafe.model.Skill;
import net.shchoo.scafe.scafe.model.SkillType;
import net.shchoo.scafe.scafe.model.Stat;

import java.util.List;
import java.util.Optional;

/**
 * The tutorial's guide: what to do next, worked out from what you can see (the tutorial world's server brings
 * things on in order, {@code TutorialDiscovery}). Each {@link Step} says what to do and moves on by itself once
 * you've done it ({@link #update}); the explanations wait for "Got it" ({@link #dismiss}). Finding a monster drop
 * gets its own explanation, whenever it happens.
 */
public class Tutorial {
    public enum Step {
        EXPLORE, CRAFT, ITEM, KEEP_CRAFTING, SECONDARY, RECIPE, LINKS, WAIT_MONSTER, FIGHT, WAIT_LOCKED, GOAL,
        WAIT_TERTIARY, WAYPOINT, DONE, FINISHED
    }

    /** What the guide can't see on the player: the Skills tab's state. */
    public interface Screen {
        /** A skill's page is open in the Skills tab. */
        boolean skillPageOpen();

        /** On a page reached through a link (a cost or "Used by"). */
        boolean followedLink();
    }

    private Step step = Step.EXPLORE;
    private boolean dropShowing;
    private boolean dropExplained;

    public Step step() {
        return step;
    }

    /** Showing the monster drop explanation (over whatever the step is). */
    public boolean dropShowing() {
        return dropShowing;
    }

    /** Moves on if what the step asked for is done. */
    public void update(Player player, Screen screen) {
        if (!dropExplained && player.trophies() > 0) {
            dropExplained = true;
            dropShowing = true;
        }
        switch (step) {
            case EXPLORE -> {
                if (!player.content.nodes().isEmpty()) step = Step.CRAFT;
            }
            case CRAFT -> {
                if (firstItem(player, SkillType.PRIMARY).isPresent()) step = Step.ITEM;
            }
            case KEEP_CRAFTING -> {
                if (skill(player, SkillType.SECONDARY).isPresent()) step = Step.SECONDARY;
            }
            case SECONDARY -> {
                if (firstItem(player, SkillType.SECONDARY).isPresent()) step = Step.RECIPE;
            }
            case RECIPE -> {
                if (screen.skillPageOpen()) step = Step.LINKS;
            }
            case LINKS -> {
                if (screen.followedLink()) step = Step.WAIT_MONSTER;
            }
            case WAIT_MONSTER -> {
                if (!player.content.monsters.isEmpty()) step = Step.FIGHT;
            }
            case FIGHT -> {
                if (player.stats.getOrDefault(Stat.MONSTERS_KILLED, 0L) > 0) step = Step.WAIT_LOCKED;
            }
            case WAIT_LOCKED -> {
                if (lockedNode(player).isPresent()) step = Step.GOAL;
            }
            case GOAL -> {
                if (!player.goals.isEmpty()) step = Step.WAIT_TERTIARY;
            }
            case WAIT_TERTIARY -> {
                if (skill(player, SkillType.TERTIARY).isPresent()) step = Step.WAYPOINT;
            }
            case WAYPOINT -> {
                if (player.waypoints.nodes().stream().anyMatch(n -> n.skill().type == SkillType.SECONDARY)) step = Step.DONE;
            }
            default -> {}
        }
    }

    /** Whether what's showing waits for "Got it" (an explanation, not something to do). */
    public boolean dismissable() {
        return dropShowing || step == Step.ITEM || step == Step.LINKS || step == Step.DONE;
    }

    /** "Got it": the drop explanation goes, or the step's explanation moves on. */
    public void dismiss() {
        if (dropShowing) {
            dropShowing = false;
            return;
        }
        step = switch (step) {
            case ITEM -> Step.KEEP_CRAFTING;
            case LINKS -> Step.WAIT_MONSTER;
            case DONE -> Step.FINISHED;
            default -> step;
        };
    }

    /** The title and lines to show now (empty once it's over). */
    public List<String> text(Player player) {
        if (dropShowing) {
            return List.of("A monster drop!",
                    "Monster drops are uniqueish: each kind of monster drops each of its items only a few times in "
                            + "the whole world (the first just once, the next twice...), to whoever's lucky.",
                    "Every one you find is a trophy, and each trophy adds a slot to your inventory.");
        }
        String primary = skill(player, SkillType.PRIMARY).map(s -> s.name).orElse("a skill");
        String secondary = skill(player, SkillType.SECONDARY).map(s -> s.name).orElse("a secondary skill");
        return switch (step) {
            case EXPLORE -> List.of("Welcome to capebabe!",
                    "The world starts empty and grows as you play. Walk around (w a s d, or click the map) "
                            + "to explore until you discover something.");
            case CRAFT -> List.of("You found a node!",
                    "Walk up to it, face it and press q -- or right-click it and pick Craft -- to make an item.");
            case ITEM -> firstItem(player, SkillType.PRIMARY).map(item -> List.of("Your first item! In your inventory (left):",
                    " " + symbol(player, item) + "     <-- the skill that makes it (" + primary + ").",
                    "." + symbol(player, item) + ":    <-- dots either side are the item's tier: none at tier 0, up to :"
                            + symbol(player, item) + ": at tier 4. Higher tiers need a higher level to make, but are more useful.",
                    "x" + player.inventory.getOrDefault(item, 0) + "    <-- how many in this stack. A stack holds as many as your "
                            + "level in that skill, so it grows as you level up.")).orElse(List.of());
            case KEEP_CRAFTING -> List.of("Keep crafting.",
                    "Making things levels you up -- and levelling up turns up new skills and nodes.");
            case SECONDARY -> List.of(secondary + ": a secondary skill",
                    "Its node uses your " + primary + " items: each craft takes some and makes " + secondary
                            + "'s item. Find its node and craft at it.");
            case RECIPE -> List.of("Recipes",
                    "Open the Skills tab (press 1, or click it) and click " + secondary + " to see its recipe: what a "
                            + "craft costs, and the level each tier unlocks at.");
            case LINKS -> List.of("Getting around",
                    "On a skill's page its costs and the skills it's \"Used by\" are links: click one (or Tab to it "
                            + "and press Enter) to jump to that skill's page. Esc goes back the way you came.");
            case WAIT_MONSTER, WAIT_LOCKED, WAIT_TERTIARY -> List.of("Keep going.",
                    "Explore and craft: there's more to find.");
            case FIGHT -> List.of("A monster!",
                    "Right-click it and pick Fight, or stand next to it and press f. Fighting trains combat; "
                            + "weapons and armour (equip from the inventory) help.");
            case GOAL -> lockedNode(player).map(node -> List.of("Too high level, for now",
                    "That " + node.recipe.product.name + " node needs " + node.skill().name + " Lv. " + node.recipe.levelNeeded
                            + ". Walk up to it and press q to try it, then g to make it a goal -- or open " + node.skill().name
                            + "'s page (Skills tab) and press g. Your goal shows in the xp tracker at the top."))
                    .orElse(List.of());
            case WAYPOINT -> waypointText(player);
            case DONE -> List.of("That's the tutorial!",
                    "This world is yours to keep exploring. When you're ready, log out (top right) to go back to "
                            + "the login and join a real world.");
            case FINISHED -> List.of();
        };
    }

    private List<String> waypointText(Player player) {
        Skill tertiary = skill(player, SkillType.TERTIARY).orElse(null);
        if (tertiary == null || tertiary.productLines.isEmpty()) {
            return List.of();
        }
        Recipe first = tertiary.productLines.get(0).recipes.get(0);
        String component = first.components.keySet().stream()
                .map(player.content::madeBy).filter(r -> r != null && r.skill.type == SkillType.SECONDARY)
                .map(r -> r.product.name).findFirst().orElse("its secondary item");
        return List.of(tertiary.name + ": a tertiary skill",
                "Its recipe takes a secondary skill's item too: " + component + ". Waypoints point you at the nearest "
                        + "node for something: open " + tertiary.name + "'s page, click the " + component + " cost to open "
                        + "its skill, then click its tier (or press Enter on it) to set a waypoint to " + component + ".");
    }

    /** The first (in the list) crafting skill of {@code type}. */
    private static Optional<Skill> skill(Player player, SkillType type) {
        return player.content.craftingSkills().stream().filter(s -> s.type == type).findFirst();
    }

    /** An item you hold made by a skill of {@code type}. */
    private static Optional<Product> firstItem(Player player, SkillType type) {
        return player.inventory.entrySet().stream().filter(e -> e.getValue() > 0).map(java.util.Map.Entry::getKey)
                .filter(p -> {
                    Recipe recipe = player.content.madeBy(p.root());
                    return recipe != null && recipe.skill.type == type;
                }).findFirst();
    }

    /** A node you're too low level for. */
    private static Optional<Node> lockedNode(Player player) {
        return player.content.nodes().stream().filter(player::isLockedFor).findFirst();
    }

    private static String symbol(Player player, Product item) {
        Recipe recipe = player.content.madeBy(item.root());
        return recipe == null ? "?" : String.valueOf(recipe.skill.symbol);
    }
}
