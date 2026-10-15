package net.shchoo.scafe.scafe;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

public enum ConsoleAction {
    RIGHT('d', "Move right", (player, board) -> player.x += 1),
    LEFT('a', "Move left", (player, board) -> player.x -= 1),
    UP('w', "Move up", (player, board) -> player.y -= 1),
    DOWN('s', "Move down", (player, board) -> player.y += 1),

    PERFORM('q', "Perform action", (player, board) -> {
        if (!board.availableActions(player).isEmpty())
            board.availableActions(player).get(0).perform();
        else System.out.println("Nothing to do here...");
    }),

    LIST('l', "List skills", (player, board, additionalInputs) -> {
        SkillType filter = getSkillType(additionalInputs);
        for (Skill s : board.skills.stream().filter(s -> s.getType().equals(filter) || filter == null).toList()) {
            System.out.println("[" + s.type + "] Skill: " + s.name);
            for (ProductLine pl : s.productLines) {
                for (Recipe r : pl.recipes) {
                    String components = "[" + r.components.keySet().stream().map(p -> p.name).collect(Collectors.joining(",")) + "]";
                    System.out.println("\t" + r.levelNeeded + ":" + r.product.name + components);
                }
            }
        }
    }),
    HELP('k', "Help", (player, board) -> {
        for (int i = 0; i < ConsoleAction.values().length; i++) {
            ConsoleAction ca = ConsoleAction.values()[i];
            System.out.println(ca.input + " - " + ca.description);
        }
    }),

    DEBUG_CREATE_NEW_RANDOM_SKILL('1', "Create new random skill(s) ", (player, board, additionalInput) -> {
        Integer count = additionalInput.isEmpty() ? 1 : Integer.parseInt(additionalInput.get(0));
        for (int i = 0; i < count; i++) {
            board.skills.add(SkillFactory.createRandomSkill(board.skills, "skill" + Main.skillCount++));
        }
    }),

    DEBUG_CREATE_RANDOM_PRODUCT_LINE('2', "Create random product line", (player, board, additionalInput) -> {
        Integer count = additionalInput.isEmpty() ? 1 : Integer.parseInt(additionalInput.get(0));
        for (int i = 0; i < count; i++) {
            Skill target = board.skills.stream().skip((int) (board.skills.size() * Math.random())).findFirst().orElseThrow();
            SkillFactory.addNewProductLineToSkill(target, board.skills);
        }
    }),

    ECHO('e', "Echo input", (player, board, additionalInputs) -> {
        System.out.println(additionalInputs);
    }),

    DEBUG_CREATE_ACTION_NODE('3', "Create random actionable node in indicated direction [wasd]",
            (player, board, additionalInputs) -> {
                board.actions.get(player.y + 2).set(player.x + 2,
                        Collections.singletonList(new Board.Action() {
                            @Override
                            public void perform() {
                                Recipe recipe = board.skills.stream().filter(s -> s.type == SkillType.PRIMARY).findFirst().get()
                                        .productLines.stream().iterator().next()
                                        .recipes.get(0);
                                System.out.println("Making: " + recipe.product.name);
                                recipe.create(player);
                                System.out.println("Done!");
                            }
                        })
                );
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
