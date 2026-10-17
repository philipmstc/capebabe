package net.shchoo.scafe.scafe;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class RecipeAction extends Board.Action {
    private final Recipe recipe;
    private boolean attempted = false;

    public RecipeAction(Recipe recipe) {
        this.recipe = recipe;
    }

    @Override
    public Skill perform(Player player) {
        if (isLockedFor(player)) {
            attempted = true;
        }
        if (recipe.create(player)) {
            player.lastActionAt = player.position();
            GameLog.say("Made " + recipe.product.name);
        } else if (attempted && isLockedFor(player)) {
            GameLog.say("(press g to make it a goal)");
        }
        return recipe.skill;
    }

    @Override
    public boolean canPerform(Player player) {
        return recipe.levelMet(player) && player.has(recipe.components);
    }

    @Override
    public String progressLabel() {
        return recipe.product.name;
    }

    @Override
    public Skill forWhich() {
        return recipe.skill;
    }

    @Override
    public List<String> describe() {
        String components = recipe.components.keySet().stream().map(p -> p.name).collect(Collectors.joining(", "));
        return List.of(
                recipe.skill.name + " [Lv. " + recipe.levelNeeded + "]",
                "  makes " + recipe.product.name + " (+" + recipe.expGained + "xp)",
                "  needs " + (components.isEmpty() ? "nothing" : components));
    }

    @Override
    public boolean isLockedFor(Player player) {
        return !recipe.levelMet(player);
    }

    @Override
    public boolean wasAttempted() {
        return attempted;
    }

    @Override
    public Optional<Goal> goal() {
        return Optional.of(new Goal(recipe.skill, recipe.levelNeeded, recipe.product));
    }
}
