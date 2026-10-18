package net.shchoo.scafe.scafe.model;

public record Position(int x, int y) {

    public Position step(Direction direction) {
        return new Position(x + direction.dx, y + direction.dy);
    }
}
