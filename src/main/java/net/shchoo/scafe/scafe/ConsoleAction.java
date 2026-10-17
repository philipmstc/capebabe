package net.shchoo.scafe.scafe;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

public enum ConsoleAction {
    RIGHT('d', "Move right", (player, board) -> player.moveBy(1, 0)),
    LEFT('a', "Move left", (player, board) -> player.moveBy(-1, 0)),
    UP('w', "Move up", (player, board) -> player.moveBy(0, -1)),
    DOWN('s', "Move down", (player, board) -> player.moveBy(0, 1)),

    PERFORM('q', "Perform action", (player, board) -> {
        if (!board.availableActions(player).isEmpty())
            player.startAction(board.availableActions(player).get(0));
        else GameLog.say("Nothing to do here...");
    }),

    GOAL('g', "Add a goal for the locked action you're standing on", (player, board) -> {
        List<Board.Action> here = board.availableActions(player);
        if (here.isEmpty()) {
            GameLog.say("Nothing here to aim for.");
            return;
        }
        Optional<Board.Action> tried = here.stream().filter(a -> a.wasAttempted() && a.isLockedFor(player)).findFirst();
        if (tried.isEmpty()) {
            GameLog.say(here.stream().anyMatch(a -> a.isLockedFor(player))
                    ? "Try it first (q)."
                    : "You can already do that!");
            return;
        }
        tried.get().goal().ifPresent(goal -> GameLog.say(player.addGoal(goal)
                ? "New goal: " + goal.describe()
                : "Already a goal."));
    }),

    LIST('l', "List skills", (player, board, additionalInputs) -> {
        SkillType filter = getSkillType(additionalInputs);
        for (Skill s : board.skills.stream().filter(s -> s.getType().equals(filter) || filter == null).toList()) {
            GameLog.say("[" + s.type + "] Skill: " + s.name);
            for (ProductLine pl : s.productLines) {
                for (Recipe r : pl.recipes) {
                    String components = "[" + r.components.keySet().stream().map(p -> p.name).collect(Collectors.joining(",")) + "]";
                    GameLog.say("\t" + r.levelNeeded + ":" + r.product.name + components);
                }
            }
        }
    }),
    HELP('k', "Help", (player, board) -> {
        for (int i = 0; i < ConsoleAction.values().length; i++) {
            ConsoleAction ca = ConsoleAction.values()[i];
            GameLog.say(ca.input + " - " + ca.description);
        }
    }),

    DEBUG_CREATE_NEW_RANDOM_SKILL('1', "Create new random skill(s) ", (player, board, additionalInput) -> {
        Integer count = additionalInput.isEmpty() ? 1 : Integer.parseInt(additionalInput.get(0));
        for (int i = 0; i < count; i++) {
            Skill skill = SkillFactory.createRandomSkill(board.skills, "skill" + Main.skillCount++);
            board.skills.add(skill);
            SkillStyle.getColorForSkill(skill);
            SkillStyle.getSymbolForSkill(skill);
        }
    }),

    DEBUG_CREATE_RANDOM_PRODUCT_LINE('2', "Create random product line", (player, board, additionalInput) -> {
        Integer count = additionalInput.isEmpty() ? 1 : Integer.parseInt(additionalInput.get(0));
        if (board.skills.isEmpty()) {
            GameLog.say("No skills yet -- create one with 1 first.");
            return;
        }
        for (int i = 0; i < count; i++) {
            Skill target = board.skills.stream().skip((int) (board.skills.size() * Math.random())).findFirst().orElseThrow();
            SkillFactory.addNewProductLineToSkill(target, board.skills);
        }
    }),

    ECHO('e', "Echo input", (player, board, additionalInputs) -> {
        GameLog.say(additionalInputs.toString());
    }),

    DEBUG_CREATE_ACTION_NODE('3', "Create random actionable node in indicated direction [wasd]",
            (player, board, additionalInputs) -> {
                if (board.skills.isEmpty()) {
                    GameLog.say("No skills yet -- create one with 1 first.");
                    return;
                }
                int skillChoice = (int) (Math.random() * board.skills.size());
                Skill skill = board.skills.get(skillChoice);
                int productLineChoice = (int) (Math.random() * skill.productLines.size());
                ProductLine productLine = skill.productLines.get(productLineChoice);
                int recipeChoice = (int) (Math.random() * productLine.recipes.size());
                Board.Action o = new RecipeAction(productLine.recipes.get(recipeChoice));

                board.setActions(player.x, player.y, Collections.singletonList(o));
            });

    private static SkillType getSkillType(List<String> additionalInputs) {
        SkillType filter = null;
        if (!additionalInputs.isEmpty()) {
            if (SkillType.TERTIARY.name().equalsIgnoreCase(additionalInputs.get(0))) {
                filter = SkillType.TERTIARY;
            }
            if (SkillType.SECONDARY.name().equalsIgnoreCase(additionalInputs.get(0))) {
                filter = SkillType.SECONDARY;
            }
            if (SkillType.PRIMARY.name().equalsIgnoreCase(additionalInputs.get(0))) {
                filter = SkillType.PRIMARY;
            }
        }
        return filter;
    }

    private static <T> Optional<T> suchThat(Collection<T> ts, Function<T, String> func, String equals) {
        return ts.stream().filter(t -> func.apply(t).equals(equals)).findFirst();
    }

    final char input;
    final OnSelect onSelect;
    final String description;

    interface OnSelect {
        void onSelect(Player player, Board board, List<String> additionalInputs);
    }

    ConsoleAction(char input, String description, BiConsumer<Player, Board> onSelect) {
        this.input = input;
        this.description = description;
        this.onSelect = (player, board, additionalInputs) -> onSelect.accept(player, board);
    }

    ConsoleAction(char input, String description, OnSelect onSelect) {
        this.input = input;
        this.description = description;
        this.onSelect = onSelect;
    }

    public void onSelect(Player player, Board board, List<String> additionalInputs) {
        onSelect.onSelect(player, board, additionalInputs);
    }

}
